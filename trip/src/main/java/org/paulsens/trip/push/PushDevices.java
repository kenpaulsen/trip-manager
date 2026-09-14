package org.paulsens.trip.push;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.AuthToken;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;

/**
 * The push-device registry and person-level push settings: ONE {@link PersonDataValue} per person under the
 * reserved {@link DataId} {@code push-devices} (the {@code BlockListCommands} idiom -- no new table, backed
 * up and cache-invalidated with everything else, deleted with the account). Content is a {@link PushPrefs}.
 *
 * <p>A plain singleton, not CDI: {@code TokenService} (itself a plain singleton) prunes through it on every
 * revocation. Every write is a read-merge-write under {@code pushDeviceLockKey}, so a registration racing a
 * prune on another task cannot resurrect a token the other writer just removed. {@code NoopCacheClient}
 * grants every lock; production has Valkey and local mode {@code InMemoryCacheClient}, the two modes that
 * matter.
 */
@Slf4j
public class PushDevices {

    /** The reserved person-data id. Fixed on purpose: it must be the same for every person. */
    public static final DataId PUSH_DEVICES_ID = DataId.from("push-devices");
    public static final String PUSH_DEVICES_TYPE = "PushPrefs";

    public static final String REFUSED_BAD_TOKEN = "bad_token";
    public static final String REFUSED_BAD_ENVIRONMENT = "bad_environment";
    public static final String REFUSED_SELECTOR = "selector";
    public static final String REFUSED_BAD_ENDPOINT = "bad_endpoint";
    public static final String REFUSED_BAD_KEYS = "bad_keys";
    public static final String REFUSED_BAD_TIME = "bad_time";
    public static final String REFUSED_BAD_ZONE = "bad_zone";
    public static final String REFUSED_STORE = "store";

    /** Hex, lower-cased by {@link PushDevice}; 32 bytes today, but Apple says the length may change. */
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{32,512}");
    private static final int LOCK_ATTEMPTS = 5;
    private static final long LOCK_WAIT_MILLIS = 50L;

    private static final ObjectMapper MAPPER =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final PushDevices INSTANCE = new PushDevices();

    /** Outcome of a write; {@code code} is one of the {@code REFUSED_*} constants on failure. */
    public record Outcome(boolean ok, String code, String message, PushPrefs prefs) {
        static Outcome ok(final PushPrefs prefs) {
            return new Outcome(true, null, null, prefs);
        }

        static Outcome refused(final String code, final String message, final PushPrefs prefs) {
            return new Outcome(false, code, message, prefs);
        }
    }

    private final LongSupplier clock;

    public static PushDevices getInstance() {
        return INSTANCE;
    }

    public PushDevices() {
        this(() -> System.currentTimeMillis() / 1000L);
    }

    /** Test seam: a controllable clock (epoch seconds). */
    public PushDevices(final LongSupplier clock) {
        this.clock = clock;
    }

    /** The stored settings and devices; the defaults (on, no quiet hours, no devices) when there is no row. */
    public PushPrefs prefsOf(final Person.Id me) {
        if (me == null) {
            return PushPrefs.defaults();
        }
        return dao().getPersonDataValue(me, PUSH_DEVICES_ID, Cached.NO)
                .map(PushDevices::prefsOf)
                .orElseGet(PushPrefs::defaults);
    }

    /**
     * Registers (or re-registers) an APNs token. Upsert by token: a re-registration keeps {@code registeredAt}
     * and refreshes everything else. {@code selector} must be a listable credential of the caller's, so the
     * device dies with the sign-in that registered it.
     */
    public Outcome registerIos(final Person.Id me, final String token, final String environment,
            final String selector, final String label, final String appVersion, final AuditActor actor) {
        final String normalised = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        if (me == null || !TOKEN.matcher(normalised).matches()) {
            return Outcome.refused(REFUSED_BAD_TOKEN, "The device token must be hex.", prefsOf(me));
        }
        if (!PushDevice.ENV_PRODUCTION.equals(environment) && !PushDevice.ENV_SANDBOX.equals(environment)) {
            return Outcome.refused(REFUSED_BAD_ENVIRONMENT, "environment must be production or sandbox.",
                    prefsOf(me));
        }
        if (!ownsSelector(me, selector)) {
            return Outcome.refused(REFUSED_SELECTOR, "That sign-in is not yours.", prefsOf(me));
        }
        final PushDevice fresh = PushDevice.ios(normalised, environment, selector.trim(), clean(label),
                clean(appVersion), clock.getAsLong());
        return underLock(me, () -> upsert(me, fresh, actor));
    }

    /** Registers (or re-registers) a Web Push subscription. Upsert by endpoint. */
    public Outcome registerWeb(final Person.Id me, final String endpoint, final String p256dh, final String auth,
            final String label, final String origin, final AuditActor actor) {
        if (me == null || !validEndpoint(endpoint)) {
            return Outcome.refused(REFUSED_BAD_ENDPOINT, "endpoint must be an https URL.", prefsOf(me));
        }
        if (!validKeys(p256dh, auth)) {
            return Outcome.refused(REFUSED_BAD_KEYS, "keys.p256dh must be a P-256 point and keys.auth at least "
                    + "16 bytes, both base64url.", prefsOf(me));
        }
        final PushDevice fresh = PushDevice.web(endpoint.trim(), p256dh.trim(), auth.trim(), clean(label),
                clean(origin), clock.getAsLong());
        return underLock(me, () -> upsert(me, fresh, actor));
    }

    /**
     * The person-level settings. Null = unchanged, blank = cleared; times are {@code HH:mm}, the zone an IANA
     * id. Quiet hours need both ends (or neither), and a start equal to its end is refused: a zero-length
     * window is never what anyone meant.
     */
    public Outcome setPrefs(final Person.Id me, final Boolean enabled, final String quietStart,
            final String quietEnd, final String timeZone) {
        if (me == null) {
            return Outcome.refused(REFUSED_STORE, "Sign in required.", PushPrefs.defaults());
        }
        if (!validTime(quietStart) || !validTime(quietEnd)) {
            return Outcome.refused(REFUSED_BAD_TIME, "Quiet hours must be HH:mm.", prefsOf(me));
        }
        if (timeZone != null && !timeZone.isBlank() && PushPrefs.parseZone(timeZone) == null) {
            return Outcome.refused(REFUSED_BAD_ZONE, "Unknown time zone.", prefsOf(me));
        }
        return underLock(me, () -> applySettings(me, enabled, quietStart, quietEnd, timeZone));
    }

    private Outcome applySettings(final Person.Id me, final Boolean enabled, final String quietStart,
            final String quietEnd, final String timeZone) {
        final PushPrefs updated = prefsOf(me).withSettings(enabled, quietStart, quietEnd, timeZone);
        if (updated.getQuietHoursStart() == null ^ updated.getQuietHoursEnd() == null) {
            return Outcome.refused(REFUSED_BAD_TIME, "Quiet hours need both a start and an end.", prefsOf(me));
        }
        if (updated.hasQuietHours() && updated.getQuietHoursStart().equals(updated.getQuietHoursEnd())) {
            return Outcome.refused(REFUSED_BAD_TIME, "Quiet hours cannot start and end at the same time.",
                    prefsOf(me));
        }
        return save(me, updated);
    }

    /** Removes one device by listing id or (iOS) full token. True when something was removed. */
    public boolean remove(final Person.Id me, final String idOrToken, final AuditActor actor) {
        if (me == null || idOrToken == null || idOrToken.isBlank()) {
            return false;
        }
        return underLock(me, () -> removeMatching(me, device -> device.matches(idOrToken), "removed by owner",
                actor)) > 0;
    }

    /** Removes the web subscription with this endpoint (the site's logout hook / unsubscribe). */
    public boolean removeWebByEndpoint(final Person.Id me, final String endpoint, final AuditActor actor) {
        if (me == null || endpoint == null || endpoint.isBlank()) {
            return false;
        }
        final String wanted = endpoint.trim();
        return underLock(me, () -> removeMatching(me,
                device -> device.getKind() == PushDevice.Kind.WEB && wanted.equals(device.getEndpoint()),
                "browser unsubscribed", actor)) > 0;
    }

    /** Prunes every iOS device registered by this sign-in (the revocation hook). Count removed. */
    public int removeBySelector(final Person.Id me, final String selector, final AuditActor actor) {
        if (me == null || selector == null || selector.isBlank()) {
            return 0;
        }
        return underLock(me, () -> removeMatching(me, device -> selector.equals(device.getSelector()),
                "sign-in revoked", actor));
    }

    /** Prunes every device of every kind (password change, credential deletion, account deletion). */
    public int removeAll(final Person.Id me, final AuditActor actor) {
        if (me == null) {
            return 0;
        }
        return underLock(me, () -> removeMatching(me, device -> true, "all credentials revoked", actor));
    }

    /** Prunes one device the push service refused (DROP_DEVICE). The system's act, not a person's. */
    public boolean prune(final Person.Id me, final PushDevice device) {
        if (me == null || device == null) {
            return false;
        }
        return underLock(me, () -> removeMatching(me, device::sameTargetAs, "refused by the push service",
                AuditActor.system())) > 0;
    }

    /** Stamps {@code lastSeenAt} on one device (a client heartbeat). True when the device exists. */
    public boolean touch(final Person.Id me, final String id) {
        if (me == null || id == null || id.isBlank()) {
            return false;
        }
        return underLock(me, () -> touchNow(me, id));
    }

    private boolean touchNow(final Person.Id me, final String id) {
        final PushPrefs current = prefsOf(me);
        final List<PushDevice> devices = new ArrayList<>();
        boolean found = false;
        for (final PushDevice device : current.getDevices()) {
            found |= device.matches(id);
            devices.add(device.matches(id) ? device.seenAt(clock.getAsLong()) : device);
        }
        return found && save(me, current.withDevices(devices)).ok();
    }

    private Outcome upsert(final Person.Id me, final PushDevice fresh, final AuditActor actor) {
        final PushPrefs current = prefsOf(me);
        final List<PushDevice> devices = new ArrayList<>();
        boolean replaced = false;
        for (final PushDevice device : current.getDevices()) {
            if (device.sameTargetAs(fresh)) {
                devices.add(device.refreshed(fresh, clock.getAsLong()));
                replaced = true;
            } else {
                devices.add(device);
            }
        }
        if (!replaced) {
            devices.add(fresh);
        }
        // Oldest-seen first, so the cap evicts the device nobody has used longest.
        devices.sort(Comparator.comparingLong(PushDevice::getLastSeenAt));
        while (devices.size() > PushPrefs.MAX_DEVICES) {
            final PushDevice evicted = devices.remove(0);
            audit(AuditAction.PUSH_DEVICE_REMOVE, me, evicted, "evicted: device cap reached", AuditActor.system());
        }
        final Outcome saved = save(me, current.withDevices(devices));
        if (saved.ok()) {
            audit(AuditAction.PUSH_DEVICE_REGISTER, me, fresh, replaced ? "re-registered" : "registered",
                    effective(actor));
        }
        return saved;
    }

    private int removeMatching(final Person.Id me, final java.util.function.Predicate<PushDevice> doomed,
            final String why, final AuditActor actor) {
        final PushPrefs current = prefsOf(me);
        final List<PushDevice> kept = new ArrayList<>();
        final List<PushDevice> removed = new ArrayList<>();
        for (final PushDevice device : current.getDevices()) {
            if (doomed.test(device)) {
                removed.add(device);
            } else {
                kept.add(device);
            }
        }
        if (removed.isEmpty() || !save(me, current.withDevices(kept)).ok()) {
            return 0;
        }
        for (final PushDevice device : removed) {
            audit(AuditAction.PUSH_DEVICE_REMOVE, me, device, why, effective(actor));
        }
        return removed.size();
    }

    private Outcome save(final Person.Id me, final PushPrefs prefs) {
        final PersonDataValue row = PersonDataValue.builder()
                .userId(me)
                .dataId(PUSH_DEVICES_ID)
                .type(PUSH_DEVICES_TYPE)
                .content(MAPPER.convertValue(prefs, Map.class))
                .build();
        try {
            if (store(row)) {
                return Outcome.ok(prefs);
            }
        } catch (final IOException | RuntimeException ex) {
            log.warn("Push devices not saved for {}", me.getValue(), ex);
        }
        return Outcome.refused(REFUSED_STORE, "Your notification settings were not saved. Try again.", prefsOf(me));
    }

    /**
     * Serialises the read-merge-write on the row. Up to five 50 ms waits for the lock, then proceeds anyway
     * with a warning: a stuck lock must cost a possible lost update, never a registration.
     */
    private <T> T underLock(final Person.Id me, final Supplier<T> write) {
        final CacheClient cache = dao().getCacheClient();
        final String key = CacheKeys.pushDeviceLockKey(me.getValue());
        boolean held = false;
        for (int attempt = 0; attempt < LOCK_ATTEMPTS && !held; attempt++) {
            held = cache.tryAcquireLock(key, CacheKeys.PUSH_DEVICE_LOCK_TTL);
            if (!held) {
                pause();
            }
        }
        if (!held) {
            log.warn("Proceeding without the push-device lock for {} after {} attempts", me.getValue(),
                    LOCK_ATTEMPTS);
        }
        try {
            return write.get();
        } finally {
            if (held) {
                cache.releaseLock(key);
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(LOCK_WAIT_MILLIS);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** A listable credential row (REFRESH or REMEMBER, never ACCESS) that belongs to {@code me}. */
    private boolean ownsSelector(final Person.Id me, final String selector) {
        if (selector == null || selector.isBlank()) {
            return false;
        }
        final AuthToken row = dao().getAuthToken(selector.trim(), Cached.NO).orElse(null);
        return row != null && row.getKind() != AuthToken.Kind.ACCESS && me.equals(row.getUserId());
    }

    static boolean validEndpoint(final String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return false;
        }
        try {
            final URI uri = URI.create(endpoint.trim());
            final String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            return "https".equals(uri.getScheme())
                    || ("http".equals(uri.getScheme()) && "localhost".equals(host));
        } catch (final IllegalArgumentException ex) {
            return false;
        }
    }

    static boolean validKeys(final String p256dh, final String auth) {
        if (p256dh == null || auth == null || p256dh.isBlank() || auth.isBlank()) {
            return false;
        }
        try {
            final byte[] point = EcKeys.decodeUrl(p256dh);
            final byte[] secret = EcKeys.decodeUrl(auth);
            return point.length == EcKeys.POINT_LENGTH && point[0] == 0x04
                    && secret.length >= WebPushCrypto.AUTH_MIN_LENGTH;
        } catch (final IllegalArgumentException ex) {
            return false;
        }
    }

    /** Null and blank (= clear) are fine; anything else must parse as {@code HH:mm}. */
    private static boolean validTime(final String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        final LocalTime parsed = PushPrefs.parseTime(value);
        return parsed != null && value.trim().length() == 5;
    }

    private static String clean(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
    }

    /** A known actor as given; otherwise the system, never a half-known record. */
    private static AuditActor effective(final AuditActor actor) {
        return actor != null && actor.isKnown() ? actor : AuditActor.system();
    }

    private static void audit(final AuditAction action, final Person.Id me, final PushDevice device,
            final String why, final AuditActor actor) {
        Audit.builder(action, AuditOutcome.SUCCESS)
                .actor(actor)
                .target(AuditEventBuilder.TARGET_PERSON, me.getValue())
                .message(device.getKind().wire() + " device " + device.id() + " (" + device.getLabel() + "): " + why)
                .log();
    }

    /** The stored content is a JSON object; a hand-written or foreign row reads as the defaults, not an error. */
    private static PushPrefs prefsOf(final PersonDataValue row) {
        try {
            return MAPPER.convertValue(row.getContent(), PushPrefs.class);
        } catch (final IllegalArgumentException ex) {
            log.warn("Push-device row for {} is not readable; treating as defaults", row.getUserId(), ex);
            return PushPrefs.defaults();
        }
    }

    /** The one write; a seam so a test can make the store fail without a mockable DAO (it is final). */
    protected boolean store(final PersonDataValue row) throws IOException {
        return Boolean.TRUE.equals(dao().savePersonDataValue(row));
    }

    /** Seam for tests that swap the store; production is the singleton. */
    protected DAO dao() {
        return DAO.getInstance();
    }
}
