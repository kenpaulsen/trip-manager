package org.paulsens.trip.push;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.PassCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuthToken;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.security.SelectorTokens;
import org.paulsens.trip.security.TokenService;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** The device registry against the real (fake-persistence) DAO: one reserved person-data row per person. */
public class PushDevicesTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String P256DH =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final AuditActor ACTOR = new AuditActor("me@example.org", "me-id");

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final PushDevices devices = new PushDevices(clock::get);
    private DAO dao;
    private TokenService tokens;

    @BeforeClass
    public void init() {
        dao = DAO.getInstance();
        final ConfigCommands config = Mockito.mock(ConfigCommands.class);
        Mockito.when(config.getBoolean(KnownSettings.API_TOKEN_ENABLED)).thenReturn(true);
        Mockito.when(config.getInt(KnownSettings.API_TOKEN_ACCESS_MINUTES, 5, 240)).thenReturn(30);
        Mockito.when(config.getInt(KnownSettings.API_TOKEN_REFRESH_DAYS, 1, 365)).thenReturn(60);
        Mockito.when(config.getInt(KnownSettings.API_TOKEN_REFRESH_ADMIN_DAYS, 1, 30)).thenReturn(7);
        tokens = new TokenService(config);
    }

    private Person saved() throws IOException {
        final Person person = Person.builder()
                .first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("push." + RandomData.genAlpha(10).toLowerCase(Locale.ROOT) + "@example.com")
                .build();
        Assert.assertTrue(dao.savePerson(person));
        return person;
    }

    /** A real local-mode sign-in ({@code user*}/{@code user}) with a REFRESH token, so selectors are real rows. */
    private String refreshSelectorFor(final String persona) {
        final Creds creds = new PassCommands().login(persona, "user");
        Assert.assertNotNull(creds, "local-mode login for " + persona);
        final TokenService.Grant grant = tokens.issue(creds, AuthToken.Scope.MEMBER, "Phone");
        return SelectorTokens.parse(grant.refreshToken()).orElseThrow().selector();
    }

    private static Person.Id ownerOf(final String selector) {
        return DAO.getInstance().getAuthToken(selector, Cached.NO).orElseThrow().getUserId();
    }

    @Test
    public void iosRegistrationIsAnUpsertByTokenThatKeepsTheFirstSighting() {
        final String selector = refreshSelectorFor("user8");
        final Person.Id me = ownerOf(selector);
        devices.removeAll(me, ACTOR);

        final PushDevices.Outcome first = devices.registerIos(me, TOKEN.toUpperCase(Locale.ROOT), "sandbox",
                selector, "Ken's iPhone", "1.0 (1)", ACTOR);
        Assert.assertTrue(first.ok(), first.message());
        Assert.assertEquals(first.prefs().getDevices().size(), 1);
        Assert.assertEquals(first.prefs().getDevices().get(0).getRegisteredAt(), 1_000L);
        Assert.assertTrue(dao.getPersonDataValue(me, PushDevices.PUSH_DEVICES_ID, Cached.NO).isPresent(),
                "stored as one reserved row");

        clock.set(2_000L);
        final PushDevices.Outcome again = devices.registerIos(me, TOKEN, "sandbox", selector, "Ken's iPhone",
                "1.0 (2)", ACTOR);
        Assert.assertEquals(again.prefs().getDevices().size(), 1, "same token, one device");
        final PushDevice stored = again.prefs().getDevices().get(0);
        Assert.assertEquals(stored.getRegisteredAt(), 1_000L);
        Assert.assertEquals(stored.getLastSeenAt(), 2_000L);
        Assert.assertEquals(stored.getAppVersion(), "1.0 (2)");
        Assert.assertEquals(devices.prefsOf(me).getDevices().get(0).getToken(), TOKEN);

        Assert.assertTrue(devices.touch(me, stored.id()));
        clock.set(3_000L);
        Assert.assertTrue(devices.touch(me, TOKEN));
        Assert.assertEquals(devices.prefsOf(me).getDevices().get(0).getLastSeenAt(), 3_000L);
        Assert.assertFalse(devices.touch(me, "nope"));
        Assert.assertFalse(devices.touch(me, ""));
        Assert.assertFalse(devices.touch(null, "x"));

        Assert.assertTrue(devices.remove(me, TOKEN, ACTOR), "the full token names the device too");
        Assert.assertTrue(devices.prefsOf(me).getDevices().isEmpty());
        Assert.assertFalse(devices.remove(me, TOKEN, ACTOR), "already gone");
        Assert.assertFalse(devices.remove(me, " ", ACTOR));
        Assert.assertFalse(devices.remove(null, TOKEN, ACTOR));
    }

    @Test
    public void iosRegistrationRefusesBadTokensEnvironmentsAndForeignSelectors() {
        final String mine = refreshSelectorFor("user9");
        final Person.Id me = ownerOf(mine);
        final String theirs = refreshSelectorFor("user10");
        Assert.assertEquals(devices.registerIos(me, "not hex", "sandbox", mine, "l", "v", ACTOR).code(),
                PushDevices.REFUSED_BAD_TOKEN);
        Assert.assertEquals(devices.registerIos(me, "abc", "sandbox", mine, "l", "v", ACTOR).code(),
                PushDevices.REFUSED_BAD_TOKEN, "too short");
        Assert.assertEquals(devices.registerIos(null, TOKEN, "sandbox", mine, "l", "v", ACTOR).code(),
                PushDevices.REFUSED_BAD_TOKEN);
        Assert.assertEquals(devices.registerIos(me, TOKEN, "staging", mine, "l", "v", ACTOR).code(),
                PushDevices.REFUSED_BAD_ENVIRONMENT);
        Assert.assertEquals(devices.registerIos(me, TOKEN, "production", theirs, "l", "v", ACTOR).code(),
                PushDevices.REFUSED_SELECTOR, "someone else's sign-in");
        Assert.assertEquals(devices.registerIos(me, TOKEN, "production", "no-such", "l", "v", ACTOR).code(),
                PushDevices.REFUSED_SELECTOR);
        Assert.assertEquals(devices.registerIos(me, TOKEN, "production", " ", "l", "v", ACTOR).code(),
                PushDevices.REFUSED_SELECTOR);
        final String access = SelectorTokens.parse(tokens.issue(new PassCommands().login("user9", "user"),
                AuthToken.Scope.MEMBER, null).accessToken()).orElseThrow().selector();
        Assert.assertEquals(devices.registerIos(me, TOKEN, "production", access, "l", "v", ACTOR).code(),
                PushDevices.REFUSED_SELECTOR, "an ACCESS selector is not a device's sign-in");
        Assert.assertTrue(devices.prefsOf(me).getDevices().isEmpty());
    }

    @Test
    public void webRegistrationIsAnUpsertByEndpointWithShapeValidation() throws IOException {
        final Person.Id me = saved().getId();
        Assert.assertEquals(devices.registerWeb(me, "ftp://x", P256DH, AUTH, "l", "o", ACTOR).code(),
                PushDevices.REFUSED_BAD_ENDPOINT);
        Assert.assertEquals(devices.registerWeb(me, "https://", P256DH, AUTH, "l", "o", ACTOR).code(),
                PushDevices.REFUSED_BAD_ENDPOINT);
        Assert.assertEquals(devices.registerWeb(me, "https://push.example/s/1", "AAAA", AUTH, "l", "o", ACTOR)
                .code(), PushDevices.REFUSED_BAD_KEYS);
        Assert.assertEquals(devices.registerWeb(me, "https://push.example/s/1", P256DH, "AAAA", "l", "o", ACTOR)
                .code(), PushDevices.REFUSED_BAD_KEYS);
        Assert.assertEquals(devices.registerWeb(me, "https://push.example/s/1", P256DH, "%%%", "l", "o", ACTOR)
                .code(), PushDevices.REFUSED_BAD_KEYS);
        Assert.assertEquals(devices.registerWeb(me, "https://push.example/s/1", null, AUTH, "l", "o", ACTOR)
                .code(), PushDevices.REFUSED_BAD_KEYS);

        final PushDevices.Outcome first = devices.registerWeb(me, "https://example.invalid/s/1", P256DH, AUTH,
                "Safari on Mac", "https://acme.example", ACTOR);
        Assert.assertTrue(first.ok(), "shape only, never reachability: " + first.message());
        Assert.assertTrue(devices.registerWeb(me, "http://localhost:8080/s/2", P256DH, AUTH, null, null, ACTOR)
                .ok(), "the local recipe's endpoint");
        Assert.assertTrue(devices.registerWeb(me, "https://example.invalid/s/1", P256DH, AUTH, "Renamed",
                "https://acme.example", ACTOR).ok());
        final PushPrefs prefs = devices.prefsOf(me);
        Assert.assertEquals(prefs.getDevices().size(), 2, "same endpoint, one device");
        Assert.assertEquals(prefs.devicesOf(PushDevice.Kind.WEB).get(0).getLabel(), "Renamed");

        Assert.assertTrue(devices.removeWebByEndpoint(me, "https://example.invalid/s/1", ACTOR));
        Assert.assertFalse(devices.removeWebByEndpoint(me, "https://example.invalid/s/1", ACTOR));
        Assert.assertFalse(devices.removeWebByEndpoint(me, "", ACTOR));
        Assert.assertFalse(devices.removeWebByEndpoint(null, "x", ACTOR));
        Assert.assertEquals(devices.prefsOf(me).getDevices().size(), 1);
        Assert.assertTrue(devices.remove(me, devices.prefsOf(me).getDevices().get(0).id(), null),
                "a null actor is recorded as the system");
        Assert.assertTrue(PushDevices.validEndpoint("https://web.push.apple.com/QAbc"));
        Assert.assertFalse(PushDevices.validEndpoint("http://push.example/s"));
        Assert.assertFalse(PushDevices.validEndpoint(null));
        Assert.assertFalse(PushDevices.validEndpoint("ht tp://bad url"));
        Assert.assertFalse(PushDevices.validKeys("", AUTH));
    }

    @Test
    public void theRowIsCappedAndTheLeastRecentlySeenDeviceIsEvicted() throws IOException {
        final Person.Id me = saved().getId();
        for (int i = 0; i < PushPrefs.MAX_DEVICES + 2; i++) {
            clock.set(10_000L + i);
            Assert.assertTrue(devices.registerWeb(me, "https://push.example/s/" + i, P256DH, AUTH, "b" + i, "o",
                    ACTOR).ok());
        }
        final PushPrefs prefs = devices.prefsOf(me);
        Assert.assertEquals(prefs.getDevices().size(), PushPrefs.MAX_DEVICES);
        Assert.assertEquals(prefs.getDevices().get(0).getLabel(), "b2", "b0 and b1 were evicted");
        Assert.assertEquals(devices.removeAll(me, ACTOR), PushPrefs.MAX_DEVICES);
        Assert.assertEquals(devices.removeAll(me, ACTOR), 0);
        Assert.assertEquals(devices.removeAll(null, ACTOR), 0);
    }

    @Test
    public void revocationHooksPruneBySelectorAndPruneDropsOneTarget() {
        final String selector = refreshSelectorFor("user11");
        final String other = refreshSelectorFor("user11");
        final Person.Id me = ownerOf(selector);
        devices.removeAll(me, ACTOR);
        Assert.assertTrue(devices.registerIos(me, TOKEN, "production", selector, "A", "v", ACTOR).ok());
        Assert.assertTrue(devices.registerIos(me, "ff" + TOKEN.substring(2), "production", other, "B", "v",
                ACTOR).ok());
        Assert.assertTrue(devices.registerWeb(me, "https://push.example/s/x", P256DH, AUTH, "W", "o", ACTOR).ok());

        Assert.assertEquals(devices.removeBySelector(me, selector, AuditActor.system()), 1);
        Assert.assertEquals(devices.removeBySelector(me, selector, AuditActor.system()), 0);
        Assert.assertEquals(devices.removeBySelector(me, "", AuditActor.system()), 0);
        Assert.assertEquals(devices.removeBySelector(null, selector, AuditActor.system()), 0);
        Assert.assertEquals(devices.prefsOf(me).getDevices().size(), 2);

        final PushDevice web = devices.prefsOf(me).devicesOf(PushDevice.Kind.WEB).get(0);
        Assert.assertTrue(devices.prune(me, web));
        Assert.assertFalse(devices.prune(me, web));
        Assert.assertFalse(devices.prune(me, null));
        Assert.assertFalse(devices.prune(null, web));
        Assert.assertEquals(devices.prefsOf(me).getDevices().size(), 1);

        // The real revocation path: revoking the sign-in prunes the phone it registered.
        Assert.assertTrue(tokens.revokeSession(me, other));
        Assert.assertTrue(devices.prefsOf(me).getDevices().isEmpty(), "revokeFamily → removeBySelector");
    }

    @Test
    public void settingsValidateTimesZonesAndBothEndsOfTheWindow() throws IOException {
        final Person.Id me = saved().getId();
        Assert.assertEquals(devices.setPrefs(null, true, null, null, null).code(), PushDevices.REFUSED_STORE);
        Assert.assertEquals(devices.setPrefs(me, null, "25:00", "07:00", null).code(), PushDevices.REFUSED_BAD_TIME);
        Assert.assertEquals(devices.setPrefs(me, null, "9:00", "17:00", null).code(), PushDevices.REFUSED_BAD_TIME,
                "HH:mm exactly");
        Assert.assertEquals(devices.setPrefs(me, null, "22:00", null, null).code(), PushDevices.REFUSED_BAD_TIME,
                "one end without the other");
        Assert.assertEquals(devices.setPrefs(me, null, "07:00", "07:00", null).code(),
                PushDevices.REFUSED_BAD_TIME);
        Assert.assertEquals(devices.setPrefs(me, null, null, null, "Mars/Olympus").code(),
                PushDevices.REFUSED_BAD_ZONE);

        final PushDevices.Outcome saved = devices.setPrefs(me, false, "22:00", "07:00", "America/Los_Angeles");
        Assert.assertTrue(saved.ok(), saved.message());
        Assert.assertFalse(saved.prefs().isEnabled());
        Assert.assertEquals(saved.prefs().getQuietHoursStart(), "22:00");
        final PushPrefs unchanged = devices.setPrefs(me, null, null, null, null).prefs();
        Assert.assertEquals(unchanged, saved.prefs(), "nulls change nothing");
        final PushPrefs cleared = devices.setPrefs(me, true, "", "", "").prefs();
        Assert.assertTrue(cleared.isEnabled());
        Assert.assertFalse(cleared.hasQuietHours());
        Assert.assertNull(cleared.getTimeZone());
        Assert.assertEquals(devices.prefsOf(null), PushPrefs.defaults());
    }

    @Test
    public void aStoreFailureIsReportedNotThrown() throws IOException {
        final Person.Id me = saved().getId();
        final PushDevices broken = new PushDevices(clock::get) {
            @Override
            protected boolean store(final PersonDataValue row) throws IOException {
                throw new IOException("table gone");
            }
        };
        final PushDevices.Outcome outcome = broken.setPrefs(me, false, null, null, null);
        Assert.assertEquals(outcome.code(), PushDevices.REFUSED_STORE);
        Assert.assertTrue(outcome.prefs().isEnabled(), "the stored state is answered, not the refused one");
        final PushDevices refusing = new PushDevices(clock::get) {
            @Override
            protected boolean store(final PersonDataValue row) {
                return false;
            }
        };
        Assert.assertFalse(refusing.registerWeb(me, "https://push.example/s/1", P256DH, AUTH, "l", "o", ACTOR).ok());
        Assert.assertFalse(refusing.remove(me, "x", ACTOR));
    }

    @Test
    public void anUnreadableRowReadsAsTheDefaults() throws IOException {
        final Person.Id me = saved().getId();
        Assert.assertTrue(dao.savePersonDataValue(PersonDataValue.builder().userId(me)
                .dataId(PushDevices.PUSH_DEVICES_ID).type(PushDevices.PUSH_DEVICES_TYPE)
                .content("garbage").build()));
        Assert.assertEquals(devices.prefsOf(me), PushPrefs.defaults());
        Assert.assertTrue(devices.setPrefs(me, false, null, null, null).ok(), "and can be overwritten");
    }

    @Test
    public void aHeldLockIsWaitedForThenSteppedAround() throws IOException {
        final Person.Id me = saved().getId();
        final String key = CacheKeys.pushDeviceLockKey(me.getValue());
        Assert.assertTrue(dao.getCacheClient().tryAcquireLock(key, Duration.ofSeconds(30)), "another task holds it");
        try {
            final long started = System.currentTimeMillis();
            Assert.assertTrue(devices.setPrefs(me, false, null, null, null).ok(), "proceeds after the waits");
            Assert.assertTrue(System.currentTimeMillis() - started >= 200L, "five 50 ms waits first");
        } finally {
            dao.getCacheClient().releaseLock(key);
        }
        Assert.assertTrue(devices.setPrefs(me, true, null, null, null).ok());
        Assert.assertSame(PushDevices.getInstance(), PushDevices.getInstance());
        Assert.assertNotNull(new PushDevices().prefsOf(me));
        Assert.assertEquals(List.of(), PushPrefs.defaults().getDevices());
    }
}
