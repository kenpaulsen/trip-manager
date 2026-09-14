package org.paulsens.trip.push;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link PushDevice} and {@link PushPrefs}: the stored shapes, listing ids and the quiet-hours rule. */
public class PushModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789ABCDEF";

    @Test
    public void iosDevicesAreNamedByTheirTokenSuffixAndMatchTheFullTokenToo() {
        final PushDevice phone = PushDevice.ios(TOKEN, "sandbox", "sel1", "Ken's iPhone", "1.0 (1)", 100L);
        Assert.assertEquals(phone.getToken(), TOKEN.toLowerCase(java.util.Locale.ROOT), "tokens are lower-cased");
        Assert.assertEquals(phone.id(), "89abcdef");
        Assert.assertTrue(phone.matches("89ABCDEF"));
        Assert.assertTrue(phone.matches(TOKEN));
        Assert.assertFalse(phone.matches("nope"));
        Assert.assertFalse(phone.matches(" "));
        Assert.assertTrue(phone.isSandbox());
        Assert.assertEquals(phone.getLastSeenAt(), 100L);
        Assert.assertEquals(phone.seenAt(200L).getLastSeenAt(), 200L);
    }

    @Test
    public void webDevicesAreNamedByAnEndpointHashNeverTheEndpoint() {
        final PushDevice browser = PushDevice.web("https://push.example/sub/abc", "p", "a", "Safari", "https://x",
                100L);
        Assert.assertEquals(browser.id().length(), 16);
        Assert.assertEquals(browser.id(), PushDevice.endpointId("https://push.example/sub/abc"));
        Assert.assertTrue(browser.matches(browser.id()));
        Assert.assertFalse(browser.matches("https://push.example/sub/abc"), "an endpoint is not a listing id");
        Assert.assertFalse(browser.isSandbox());
        Assert.assertEquals(new PushDevice(PushDevice.Kind.WEB, null, null, null, null, null, null, null, null,
                null, null, null).id(), "");
        Assert.assertEquals(new PushDevice(PushDevice.Kind.IOS, null, null, null, null, null, null, null, null,
                null, null, null).id(), "");
    }

    @Test
    public void sameTargetIsByTokenForPhonesAndByEndpointForBrowsers() {
        final PushDevice phone = PushDevice.ios(TOKEN, "production", "s", "a", "v", 1L);
        final PushDevice again = PushDevice.ios(TOKEN, "sandbox", "s2", "b", "v2", 2L);
        final PushDevice browser = PushDevice.web("https://e/1", "p", "a", "l", "o", 1L);
        Assert.assertTrue(phone.sameTargetAs(again));
        Assert.assertFalse(phone.sameTargetAs(browser));
        Assert.assertFalse(phone.sameTargetAs(null));
        Assert.assertTrue(browser.sameTargetAs(PushDevice.web("https://e/1", "q", "b", "m", "p", 9L)));
        Assert.assertFalse(browser.sameTargetAs(PushDevice.web("https://e/2", "p", "a", "l", "o", 1L)));

        final PushDevice refreshed = phone.refreshed(again, 50L);
        Assert.assertEquals(refreshed.getRegisteredAt(), 1L, "a re-registration keeps the first sighting");
        Assert.assertEquals(refreshed.getLastSeenAt(), 50L);
        Assert.assertEquals(refreshed.getSelector(), "s2");
        Assert.assertEquals(refreshed.getLabel(), "b");
        Assert.assertEquals(refreshed.getEnvironment(), "sandbox");
        final PushDevice partial = phone.refreshed(new PushDevice(PushDevice.Kind.IOS, TOKEN, null, null, null,
                null, null, null, null, null, 5L, 5L), 60L);
        Assert.assertEquals(partial.getLabel(), "a", "absent details keep the stored ones");
        Assert.assertEquals(partial.getSelector(), "s");
    }

    @Test
    public void kindsTravelLowerCaseOnTheWireAndUnknownOnesReadAsNull() throws Exception {
        Assert.assertEquals(PushDevice.Kind.IOS.wire(), "ios");
        Assert.assertEquals(PushDevice.Kind.fromWire("WEB"), PushDevice.Kind.WEB);
        Assert.assertNull(PushDevice.Kind.fromWire("android"));
        Assert.assertNull(PushDevice.Kind.fromWire(null));
        final PushDevice phone = PushDevice.ios(TOKEN, "production", "s", "Phone", "1", 7L);
        final String json = MAPPER.writeValueAsString(phone);
        Assert.assertTrue(json.contains("\"kind\":\"ios\""), json);
        Assert.assertFalse(json.contains("endpoint"), "absent fields are omitted: " + json);
        final PushDevice back = MAPPER.readValue(json, PushDevice.class);
        Assert.assertEquals(back, phone);
    }

    @Test
    public void prefsDefaultOnWithNoQuietHoursAndDropUnreadableDevices() throws Exception {
        final PushPrefs defaults = PushPrefs.defaults();
        Assert.assertTrue(defaults.isEnabled());
        Assert.assertFalse(defaults.hasQuietHours());
        Assert.assertEquals(defaults.getDevices(), List.of());
        Assert.assertEquals(defaults.zone(), ZoneId.of("UTC"));
        Assert.assertFalse(defaults.isQuietAt(Instant.now()));

        final PushPrefs stored = MAPPER.convertValue(Map.of("enabled", false, "quietHoursStart", "22:00",
                "quietHoursEnd", "07:00", "timeZone", "America/Los_Angeles",
                "devices", List.of(Map.of("kind", "android", "token", "x"), Map.of("kind", "ios", "token", TOKEN))),
                PushPrefs.class);
        Assert.assertFalse(stored.isEnabled());
        Assert.assertEquals(stored.getDevices().size(), 1, "an unknown kind is skipped, not fatal");
        Assert.assertEquals(stored.devicesOf(PushDevice.Kind.IOS).size(), 1);
        Assert.assertEquals(stored.devicesOf(PushDevice.Kind.WEB).size(), 0);
        Assert.assertEquals(stored.zone(), ZoneId.of("America/Los_Angeles"));
    }

    @Test
    public void quietHoursHonourTheZoneAndMayCrossMidnight() {
        final PushPrefs overnight = new PushPrefs(true, "22:00", "07:00", "UTC", null);
        Assert.assertTrue(overnight.isQuietAt(Instant.parse("2026-09-13T23:30:00Z")));
        Assert.assertTrue(overnight.isQuietAt(Instant.parse("2026-09-13T03:00:00Z")));
        Assert.assertTrue(overnight.isQuietAt(Instant.parse("2026-09-13T22:00:00Z")), "start is inclusive");
        Assert.assertFalse(overnight.isQuietAt(Instant.parse("2026-09-13T07:00:00Z")), "end is exclusive");
        Assert.assertFalse(overnight.isQuietAt(Instant.parse("2026-09-13T12:00:00Z")));

        final PushPrefs daytime = new PushPrefs(true, "09:00", "17:00", "UTC", null);
        Assert.assertTrue(daytime.isQuietAt(Instant.parse("2026-09-13T12:00:00Z")));
        Assert.assertFalse(daytime.isQuietAt(Instant.parse("2026-09-13T20:00:00Z")));

        // 12:00Z is 05:00 in Los Angeles: inside a 22:00-07:00 window there, outside it in UTC.
        final PushPrefs zoned = new PushPrefs(true, "22:00", "07:00", "America/Los_Angeles", null);
        Assert.assertTrue(zoned.isQuietAt(Instant.parse("2026-09-13T12:00:00Z")));

        Assert.assertFalse(new PushPrefs(true, "22:00", null, "UTC", null).isQuietAt(Instant.now()));
        Assert.assertFalse(new PushPrefs(true, "nope", "07:00", "UTC", null).isQuietAt(Instant.now()),
                "an unparseable stored value never silences someone forever");
        Assert.assertFalse(new PushPrefs(true, "07:00", "07:00", "UTC", null).isQuietAt(Instant.now()));
        Assert.assertFalse(overnight.isQuietAt(null));
        Assert.assertEquals(new PushPrefs(true, null, null, "Mars/Olympus", null).zone(), ZoneId.of("UTC"));
    }

    @Test
    public void settingsCopyKeepsNullsAndClearsBlanks() {
        final PushPrefs base = new PushPrefs(true, "22:00", "07:00", "UTC", List.of());
        final PushPrefs kept = base.withSettings(null, null, null, null);
        Assert.assertEquals(kept, base);
        final PushPrefs cleared = base.withSettings(false, "", "", "");
        Assert.assertFalse(cleared.isEnabled());
        Assert.assertNull(cleared.getQuietHoursStart());
        Assert.assertNull(cleared.getTimeZone());
        Assert.assertFalse(cleared.hasQuietHours());
        Assert.assertNull(PushPrefs.parseTime(" "));
        Assert.assertNull(PushPrefs.parseTime("25:99"));
        Assert.assertNull(PushPrefs.parseZone(""));
        Assert.assertEquals(base.withDevices(List.of(PushDevice.web("https://e", "p", "a", "l", "o", 1L)))
                .getDevices().size(), 1);
    }
}
