package org.paulsens.trip.push;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** {@link PushSender}: the gates, the dedupe claim, quiet hours, the badge, pruning, and silent coalescing. */
public class PushSenderTest {

    private static final Person.Id ME = Person.Id.from("push-sender-me");
    private static final PushDevice PHONE = PushDevice.ios("ab".repeat(16), "production", "s", "Phone", "1", 1L);
    private static final PushDevice PHONE2 = PushDevice.ios("cd".repeat(16), "sandbox", "s", "Phone 2", "1", 1L);
    private static final PushDevice BROWSER = PushDevice.web("https://e/1", "p", "a", "Safari", "o", 1L);

    private PushDevices devices;
    private ConfigCommands config;
    private CacheClient cache;
    private final Map<PushDevice.Kind, LoggingPushGateway> gateways = new EnumMap<>(PushDevice.Kind.class);
    private final List<Person.Id> badgeAsked = new ArrayList<>();
    private Instant now;
    private PushSender sender;

    @BeforeMethod
    public void setUp() {
        devices = Mockito.mock(PushDevices.class);
        config = Mockito.mock(ConfigCommands.class);
        cache = Mockito.mock(CacheClient.class);
        gateways.clear();
        gateways.put(PushDevice.Kind.IOS, new LoggingPushGateway());
        gateways.put(PushDevice.Kind.WEB, new LoggingPushGateway());
        badgeAsked.clear();
        now = Instant.parse("2026-09-13T12:00:00Z");
        Mockito.when(config.getBoolean(KnownSettings.PUSH_ENABLED)).thenReturn(true);
        Mockito.when(config.getBoolean(KnownSettings.PUSH_WEB_ENABLED)).thenReturn(true);
        Mockito.when(config.getInt(KnownSettings.PUSH_SILENT_INTERVAL_MINUTES, 0, 240)).thenReturn(15);
        Mockito.when(cache.tryAcquireLock(ArgumentMatchers.anyString(), ArgumentMatchers.any(Duration.class)))
                .thenReturn(true);
        Mockito.when(devices.prefsOf(ME)).thenReturn(new PushPrefs(true, null, null, null,
                List.of(PHONE, BROWSER)));
        sender = new PushSender(devices, config, () -> cache, gateways::get, this::badge, () -> now);
    }

    private int badge(final Person.Id person) {
        badgeAsked.add(person);
        return 4;
    }

    private static PushPayload alert() {
        return PushPayload.alert(PushPayload.KIND_CHAT_MENTION, "T", "S", "B", "trip:1", "unitetrip://chat/1",
                "https://x");
    }

    @Test
    public void anAlertReachesEveryDeviceWithTheBadgeOnPhonesOnly() {
        final PushSender.Report report = sender.sendAlert(ME, alert(), "m1|me|PUSH");
        Assert.assertNull(report.skipped());
        Assert.assertEquals(report.sent(), 2);
        Assert.assertEquals(report.outcomes().size(), 2);
        Assert.assertEquals(report.outcomes().get(0).kind(), "ios");
        Assert.assertEquals(report.outcomes().get(0).label(), "Phone");
        Assert.assertEquals(report.outcomes().get(0).outcome(), PushOutcome.DELIVERED);
        Assert.assertEquals(gateways.get(PushDevice.Kind.IOS).sent().get(0).payload().getBadge(),
                Integer.valueOf(4));
        Assert.assertNull(gateways.get(PushDevice.Kind.WEB).sent().get(0).payload().getBadge());
        Assert.assertFalse(gateways.get(PushDevice.Kind.IOS).sent().get(0).payload().isPassive());
        Assert.assertEquals(badgeAsked, List.of(ME), "counted once per recipient");
        Mockito.verify(cache).tryAcquireLock(CacheKeys.chatNotifySentKey("m1|me|PUSH"),
                CacheKeys.CHAT_NOTIFY_SENT_TTL);
        Mockito.verify(cache).tryAcquireLock(CacheKeys.pushSilentKey(ME.getValue()), Duration.ofMinutes(15));
    }

    @Test
    public void everyGateStopsTheSendWithItsOwnReason() {
        Mockito.when(config.getBoolean(KnownSettings.PUSH_ENABLED)).thenReturn(false);
        Assert.assertEquals(sender.sendAlert(ME, alert(), "k").skipped(), PushSender.SKIPPED_DISABLED);
        Assert.assertFalse(sender.isEnabled());
        Mockito.when(config.getBoolean(KnownSettings.PUSH_ENABLED)).thenReturn(true);

        Mockito.when(devices.prefsOf(ME)).thenReturn(new PushPrefs(false, null, null, null, List.of(PHONE)));
        Assert.assertEquals(sender.sendAlert(ME, alert(), "k").skipped(), PushSender.SKIPPED_OFF);

        Mockito.when(devices.prefsOf(ME)).thenReturn(PushPrefs.defaults());
        Assert.assertEquals(sender.sendAlert(ME, alert(), "k").skipped(), PushSender.SKIPPED_NO_DEVICES);

        Mockito.when(devices.prefsOf(ME)).thenReturn(new PushPrefs(true, null, null, null, List.of(PHONE)));
        Mockito.when(cache.tryAcquireLock(ArgumentMatchers.eq(CacheKeys.chatNotifySentKey("k")),
                ArgumentMatchers.any(Duration.class))).thenReturn(false);
        Assert.assertEquals(sender.sendAlert(ME, alert(), "k").skipped(), PushSender.SKIPPED_DEDUPED);
        Assert.assertTrue(gateways.get(PushDevice.Kind.IOS).sent().isEmpty());

        Assert.assertEquals(sender.sendAlert(ME, alert(), null).sent(), 1, "no key, no claim, always sent");
        Assert.assertEquals(sender.sendAlert(null, alert(), null).skipped(), PushSender.SKIPPED_DISABLED);
        Assert.assertEquals(sender.sendAlert(ME, null, null).skipped(), PushSender.SKIPPED_DISABLED);
        Assert.assertEquals(sender.sendAlert(ME, PushPayload.silent(), null).skipped(),
                PushSender.SKIPPED_DISABLED, "a silent payload is not an alert");
    }

    @Test
    public void quietHoursMakeTheAlertPassiveAndWebCanBeSwitchedOffOnItsOwn() {
        Mockito.when(devices.prefsOf(ME)).thenReturn(new PushPrefs(true, "22:00", "07:00", "UTC",
                List.of(PHONE, BROWSER)));
        now = Instant.parse("2026-09-13T23:00:00Z");
        Assert.assertEquals(sender.sendAlert(ME, alert(), null).sent(), 2);
        Assert.assertTrue(gateways.get(PushDevice.Kind.IOS).sent().get(0).payload().isPassive());
        Assert.assertTrue(gateways.get(PushDevice.Kind.WEB).sent().get(0).payload().isPassive());

        Mockito.when(config.getBoolean(KnownSettings.PUSH_WEB_ENABLED)).thenReturn(false);
        gateways.get(PushDevice.Kind.WEB).clear();
        Assert.assertEquals(sender.sendAlert(ME, alert(), null).sent(), 1);
        Assert.assertTrue(gateways.get(PushDevice.Kind.WEB).sent().isEmpty(), "browsers skipped");

        Mockito.when(devices.prefsOf(ME)).thenReturn(new PushPrefs(true, null, null, null, List.of(BROWSER)));
        Assert.assertEquals(sender.sendAlert(ME, alert(), null).skipped(), PushSender.SKIPPED_NO_DEVICES);
    }

    @Test
    public void aDroppedDeviceIsPrunedAndAThrowingGatewayCostsOnlyItsDevice() {
        gateways.put(PushDevice.Kind.WEB, new LoggingPushGateway(PushOutcome.DROP_DEVICE, null));
        final PushSender.Report report = sender.sendAlert(ME, alert(), null);
        Assert.assertEquals(report.sent(), 1);
        Assert.assertEquals(report.outcomes().get(1).outcome(), PushOutcome.DROP_DEVICE);
        Mockito.verify(devices).prune(ME, BROWSER);

        final Map<PushDevice.Kind, PushGateway> broken = new EnumMap<>(PushDevice.Kind.class);
        broken.put(PushDevice.Kind.IOS, PushSenderTest::explode);
        broken.put(PushDevice.Kind.WEB, (device, payload) -> null);
        Mockito.clearInvocations(cache);
        final PushSender fragile = new PushSender(devices, config, () -> cache, broken::get, this::badge,
                () -> now);
        final PushSender.Report worst = fragile.sendAlert(ME, alert(), null);
        Assert.assertEquals(worst.sent(), 0);
        Assert.assertEquals(worst.outcomes().get(0).outcome(), PushOutcome.FAILED);
        Assert.assertEquals(worst.outcomes().get(1).outcome(), PushOutcome.FAILED, "a null outcome is a failure");
        Mockito.verify(cache, Mockito.never()).tryAcquireLock(ArgumentMatchers.eq(
                CacheKeys.pushSilentKey(ME.getValue())), ArgumentMatchers.any(Duration.class));
    }

    private static PushOutcome explode(final PushDevice device, final PushPayload payload) {
        throw new IllegalStateException("gateway bug");
    }

    private static int noChats(final Person.Id person) {
        throw new IllegalStateException("chats down");
    }

    @Test
    public void aFailingBadgeCountSendsWithoutABadge() {
        final PushSender noBadge = new PushSender(devices, config, () -> cache, gateways::get,
                PushSenderTest::noChats, () -> now);
        Assert.assertEquals(noBadge.sendAlert(ME, alert(), null).sent(), 2);
        Assert.assertNull(gateways.get(PushDevice.Kind.IOS).sent().get(0).payload().getBadge());
    }

    @Test
    public void silentRefreshGoesToPhonesOnlyOncePerInterval() {
        Mockito.when(devices.prefsOf(ME)).thenReturn(new PushPrefs(true, null, null, null,
                List.of(PHONE, BROWSER, PHONE2)));
        Assert.assertTrue(sender.sendSilent(ME));
        Assert.assertEquals(gateways.get(PushDevice.Kind.IOS).sent().size(), 2);
        Assert.assertTrue(gateways.get(PushDevice.Kind.IOS).sent().get(0).payload().isSilent());
        Assert.assertTrue(gateways.get(PushDevice.Kind.WEB).sent().isEmpty(), "browsers never get a silent push");
        Mockito.verify(cache).tryAcquireLock(CacheKeys.pushSilentKey(ME.getValue()), Duration.ofMinutes(15));

        Mockito.when(cache.tryAcquireLock(ArgumentMatchers.anyString(), ArgumentMatchers.any(Duration.class)))
                .thenReturn(false);
        Assert.assertFalse(sender.sendSilent(ME), "coalesced: someone pushed within the interval");

        Mockito.when(cache.tryAcquireLock(ArgumentMatchers.anyString(), ArgumentMatchers.any(Duration.class)))
                .thenReturn(true);
        Mockito.when(config.getInt(KnownSettings.PUSH_SILENT_INTERVAL_MINUTES, 0, 240)).thenReturn(0);
        Assert.assertFalse(sender.sendSilent(ME), "interval 0 turns silent refreshes off");
        Mockito.when(config.getInt(KnownSettings.PUSH_SILENT_INTERVAL_MINUTES, 0, 240)).thenReturn(15);

        Mockito.when(devices.prefsOf(ME)).thenReturn(new PushPrefs(true, null, null, null, List.of(BROWSER)));
        Assert.assertFalse(sender.sendSilent(ME), "no phones");
        Mockito.when(devices.prefsOf(ME)).thenReturn(new PushPrefs(false, null, null, null, List.of(PHONE)));
        Assert.assertFalse(sender.sendSilent(ME), "master switch off");
        Mockito.when(config.getBoolean(KnownSettings.PUSH_ENABLED)).thenReturn(false);
        Assert.assertFalse(sender.sendSilent(ME));
        Assert.assertFalse(sender.sendSilent(null));

        gateways.put(PushDevice.Kind.IOS, new LoggingPushGateway(PushOutcome.RETRY, null));
        Mockito.when(config.getBoolean(KnownSettings.PUSH_ENABLED)).thenReturn(true);
        Mockito.when(devices.prefsOf(ME)).thenReturn(new PushPrefs(true, null, null, null, List.of(PHONE)));
        Assert.assertFalse(sender.sendSilent(ME), "nothing delivered");
    }

    @Test
    public void theDefaultWiringCountsUnreadChatsForTheBadge() {
        Assert.assertSame(PushSender.getInstance(), PushSender.getInstance());
        Assert.assertEquals(PushSender.unreadChats(Person.Id.from("nobody-" + System.nanoTime())), 0);
        Assert.assertNotNull(new PushSender());
    }
}
