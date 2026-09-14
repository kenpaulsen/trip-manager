package org.paulsens.trip.push;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;

/**
 * Per-recipient delivery: the gates ({@code push.enabled} → the person's master switch → devices), the
 * dedupe claim, quiet hours, the badge, then one gateway send per device with DROP_DEVICE pruning
 * ({@code docs/push-notifications.md} "Sending"). Callers run this off-request
 * ({@code TripThreads.startAs(AuditActor.system(), ...)}); nothing here throws.
 */
@Slf4j
public class PushSender {

    /** What happened on one device, for the test endpoint and the log. */
    public record DeviceOutcome(String kind, String label, PushOutcome outcome) {
    }

    /** The whole fan-out for one recipient; {@code skipped} names the gate that stopped it, else null. */
    public record Report(int sent, List<DeviceOutcome> outcomes, String skipped) {
        public static Report skipped(final String why) {
            return new Report(0, List.of(), why);
        }
    }

    public static final String SKIPPED_DISABLED = "disabled";
    public static final String SKIPPED_OFF = "off";
    public static final String SKIPPED_NO_DEVICES = "no-devices";
    public static final String SKIPPED_DEDUPED = "deduped";

    private static final PushSender INSTANCE = new PushSender();

    private final PushDevices devices;
    private final ConfigCommands config;
    private final Supplier<CacheClient> cache;
    private final Function<PushDevice.Kind, PushGateway> gateways;
    private final ToIntFunction<Person.Id> badges;
    private final Supplier<Instant> clock;

    public static PushSender getInstance() {
        return INSTANCE;
    }

    public PushSender() {
        this(PushDevices.getInstance(), new ConfigCommands(), () -> DAO.getInstance().getCacheClient(),
                PushRuntime::gatewayFor, PushSender::unreadChats, Instant::now);
    }

    /** Test seam. */
    public PushSender(final PushDevices devices, final ConfigCommands config, final Supplier<CacheClient> cache,
            final Function<PushDevice.Kind, PushGateway> gateways, final ToIntFunction<Person.Id> badges,
            final Supplier<Instant> clock) {
        this.devices = devices;
        this.config = config;
        this.cache = cache;
        this.gateways = gateways;
        this.badges = badges;
        this.clock = clock;
    }

    /** The master switch. */
    public boolean isEnabled() {
        return config.getBoolean(KnownSettings.PUSH_ENABLED);
    }

    /**
     * One alert to every device of one person.
     *
     * @param dedupeKey the {@code "{eventKey}|{personId}|PUSH"} claim key, or null to always send (the test
     *        endpoint). The claim is taken BEFORE the sends, so a retry or a second task cannot alert the
     *        same person twice for one event.
     */
    public Report sendAlert(final Person.Id person, final PushPayload payload, final String dedupeKey) {
        if (person == null || payload == null || payload.isSilent()) {
            return Report.skipped(SKIPPED_DISABLED);
        }
        if (!isEnabled()) {
            return Report.skipped(SKIPPED_DISABLED);
        }
        final PushPrefs prefs = devices.prefsOf(person);
        if (!prefs.isEnabled()) {
            return Report.skipped(SKIPPED_OFF);
        }
        final List<PushDevice> targets = alertTargets(prefs);
        if (targets.isEmpty()) {
            return Report.skipped(SKIPPED_NO_DEVICES);
        }
        if (dedupeKey != null && !cache.get().tryAcquireLock(CacheKeys.chatNotifySentKey(dedupeKey),
                CacheKeys.CHAT_NOTIFY_SENT_TTL)) {
            return Report.skipped(SKIPPED_DEDUPED);
        }
        final PushPayload shaped = payload.withPassive(prefs.isQuietAt(clock.get()));
        final List<DeviceOutcome> outcomes = new ArrayList<>();
        int sent = 0;
        Integer badge = null;
        for (final PushDevice device : targets) {
            if (device.getKind() == PushDevice.Kind.IOS && badge == null) {
                badge = badgeFor(person);
            }
            final PushOutcome outcome = deliver(person, device,
                    device.getKind() == PushDevice.Kind.IOS ? shaped.withBadge(badge) : shaped);
            outcomes.add(new DeviceOutcome(device.getKind().wire(), device.getLabel(), outcome));
            sent += outcome == PushOutcome.DELIVERED ? 1 : 0;
        }
        if (sent > 0) {
            // The alert already woke the app; a background refresh inside the interval would be a second
            // wake-up for nothing. Best effort: the marker is coalescing, not correctness.
            cache.get().tryAcquireLock(CacheKeys.pushSilentKey(person.getValue()), silentInterval());
        }
        return new Report(sent, outcomes, null);
    }

    /**
     * One background ({@code content-available}) push per iOS device, at most once per
     * {@code push.silent.intervalMinutes} per person across every task -- the Valkey marker's TTL is the
     * schedule. Web devices never get one: a service worker that shows nothing loses its permission.
     */
    public boolean sendSilent(final Person.Id person) {
        if (person == null || !isEnabled()) {
            return false;
        }
        final Duration interval = silentInterval();
        if (interval.isZero()) {
            return false;
        }
        final PushPrefs prefs = devices.prefsOf(person);
        final List<PushDevice> phones = prefs.devicesOf(PushDevice.Kind.IOS);
        if (!prefs.isEnabled() || phones.isEmpty()) {
            return false;
        }
        if (!cache.get().tryAcquireLock(CacheKeys.pushSilentKey(person.getValue()), interval)) {
            return false;
        }
        boolean any = false;
        for (final PushDevice phone : phones) {
            any |= deliver(person, phone, PushPayload.silent()) == PushOutcome.DELIVERED;
        }
        return any;
    }

    private PushOutcome deliver(final Person.Id person, final PushDevice device, final PushPayload payload) {
        PushOutcome outcome;
        try {
            outcome = gateways.apply(device.getKind()).send(device, payload);
        } catch (final RuntimeException ex) {
            // A gateway must not throw, but a bug in one must still not stop the other devices.
            log.warn("Push gateway {} threw for {}", device.getKind(), person, ex);
            outcome = PushOutcome.FAILED;
        }
        if (outcome == null) {
            outcome = PushOutcome.FAILED;
        }
        if (outcome == PushOutcome.DROP_DEVICE) {
            devices.prune(person, device);
        }
        return outcome;
    }

    private List<PushDevice> alertTargets(final PushPrefs prefs) {
        if (config.getBoolean(KnownSettings.PUSH_WEB_ENABLED)) {
            return prefs.getDevices();
        }
        return prefs.devicesOf(PushDevice.Kind.IOS);
    }

    private Duration silentInterval() {
        return Duration.ofMinutes(config.getInt(KnownSettings.PUSH_SILENT_INTERVAL_MINUTES, 0, 240));
    }

    /** A badge is decoration: a failing count must not cost the alert. */
    private Integer badgeFor(final Person.Id person) {
        try {
            return Math.max(0, badges.applyAsInt(person));
        } catch (final RuntimeException ex) {
            log.warn("Unread count for {} failed; sending without a badge", person, ex);
            return null;
        }
    }

    /** The app badge: unread chats, the same answer the chats list draws its dots from. */
    static int unreadChats(final Person.Id person) {
        return (int) ChatCommands.getChatCommands().myChats(person).stream()
                .filter(ChatCommands.ChatSummary::unread)
                .count();
    }
}
