package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.PushCommands;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.push.PushDevice;
import org.paulsens.trip.push.PushDevices;
import org.paulsens.trip.push.PushOutcome;
import org.paulsens.trip.push.PushPrefs;
import org.paulsens.trip.push.PushSender;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The push edge: self-scoped, CSRF-checked mutations, every registry refusal mapped to a status. */
public class PushResourceTest extends ResourceTestSupport {

    private static final Person.Id ME = Person.Id.from("push-me");
    private static final PushPrefs PREFS = new PushPrefs(true, "22:00", "07:00", "UTC", List.of(
            PushDevice.ios("ab".repeat(16), "sandbox", "s", "Phone", "1", 5L),
            PushDevice.web("https://e/1", "p", "a", "Safari", "https://acme.example", 6L)));

    private PushCommands push;
    private PushResource resource;

    @BeforeMethod
    public void bind() {
        push = bindMock(PushCommands.class);
        Mockito.when(push.describe(ArgumentMatchers.any())).thenCallRealMethod();
        resource = resource(new PushResource());
        signedInAs(ME);
        Mockito.when(request.getScheme()).thenReturn("https");
        Mockito.when(request.getServerName()).thenReturn("acme.unitetrip.com");
        Mockito.when(request.getServerPort()).thenReturn(443);
        Mockito.when(request.getContextPath()).thenReturn("");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(final Response response) {
        return (Map<String, Object>) response.getEntity();
    }

    @Test
    public void everyMutationNeedsTheCsrfSentinel() {
        Assert.assertEquals(resource.registerDevice(null, Map.of()).getStatus(), 403);
        Assert.assertEquals(resource.removeDevice("x", null).getStatus(), 403);
        Assert.assertEquals(resource.subscribeWeb(null, Map.of()).getStatus(), 403);
        Assert.assertEquals(resource.unsubscribeWeb(null, Map.of()).getStatus(), 403);
        Assert.assertEquals(resource.savePrefs(null, Map.of()).getStatus(), 403);
        Assert.assertEquals(resource.test(null).getStatus(), 403);
        Mockito.verifyNoInteractions(push);
    }

    @Test
    public void registeringAPhoneAnswersTheDeviceListAndMapsRefusals() {
        Mockito.when(push.registerIos(ArgumentMatchers.eq(ME), ArgumentMatchers.eq("AB"),
                ArgumentMatchers.eq("sandbox"), ArgumentMatchers.eq("sel"), ArgumentMatchers.eq("Phone"),
                ArgumentMatchers.eq("1.0"), ArgumentMatchers.any()))
                .thenReturn(new PushDevices.Outcome(true, null, null, PREFS));
        final Response ok = resource.registerDevice(CSRF_OK, Map.of("token", "AB", "environment", "sandbox",
                "selector", "sel", "label", "Phone", "appVersion", "1.0"));
        assertOk(ok);
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> devices = (List<Map<String, Object>>) body(ok).get("devices");
        Assert.assertEquals(devices.size(), 2);
        Assert.assertEquals(devices.get(0).get("kind"), "ios");
        Assert.assertEquals(devices.get(0).get("id"), "abababab");
        Assert.assertEquals(devices.get(0).get("environment"), "sandbox");
        Assert.assertNull(devices.get(0).get("origin"));
        Assert.assertEquals(devices.get(1).get("kind"), "web");
        Assert.assertEquals(devices.get(1).get("origin"), "https://acme.example");
        Assert.assertEquals(devices.get(1).get("lastSeenAt"), 6L);
        Assert.assertFalse(devices.get(0).containsKey("token"), "a token never leaves the server");

        Mockito.when(push.registerIos(ArgumentMatchers.eq(ME), ArgumentMatchers.isNull(),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), ArgumentMatchers.isNull(),
                ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(new PushDevices.Outcome(false, PushDevices.REFUSED_BAD_TOKEN, "hex", PREFS));
        assertError(resource.registerDevice(CSRF_OK, null), 400, ApiErrors.BAD_REQUEST);

        Mockito.when(push.registerIos(ArgumentMatchers.eq(ME), ArgumentMatchers.eq("theirs"), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new PushDevices.Outcome(false, PushDevices.REFUSED_SELECTOR, "not yours", PREFS));
        assertError(resource.registerDevice(CSRF_OK, Map.of("token", "theirs")), 403, ApiErrors.FORBIDDEN);

        Mockito.when(push.registerIos(ArgumentMatchers.eq(ME), ArgumentMatchers.eq("store"), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new PushDevices.Outcome(false, PushDevices.REFUSED_STORE, "down", PREFS));
        assertError(resource.registerDevice(CSRF_OK, Map.of("token", "store")), 500, ApiErrors.STORE_FAILED);

        Mockito.when(push.registerIos(ArgumentMatchers.eq(ME), ArgumentMatchers.eq("odd"), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new PushDevices.Outcome(false, null, "odd", PREFS));
        assertError(resource.registerDevice(CSRF_OK, Map.of("token", "odd")), 400, ApiErrors.BAD_REQUEST);
    }

    @Test
    public void removingADeviceIsIdempotent() {
        Mockito.when(push.removeDevice(ArgumentMatchers.eq(ME), ArgumentMatchers.eq("abababab"),
                ArgumentMatchers.any())).thenReturn(true);
        assertOk(resource.removeDevice("abababab", CSRF_OK));
        Assert.assertEquals(body(resource.removeDevice("abababab", CSRF_OK)).get("removed"), true);
        Assert.assertEquals(body(resource.removeDevice("never-there", CSRF_OK)).get("removed"), true);
    }

    @Test
    public void aBrowserSubscribesWithTheRequestsOriginAndCanUnsubscribe() {
        Mockito.when(push.registerWeb(ArgumentMatchers.eq(ME), ArgumentMatchers.eq("https://push.example/s/1"),
                ArgumentMatchers.eq("P"), ArgumentMatchers.eq("A"), ArgumentMatchers.eq("Safari"),
                ArgumentMatchers.eq("https://acme.unitetrip.com"), ArgumentMatchers.any()))
                .thenReturn(new PushDevices.Outcome(true, null, null, PREFS));
        final Response ok = resource.subscribeWeb(CSRF_OK, Map.of("endpoint", "https://push.example/s/1",
                "keys", Map.of("p256dh", "P", "auth", "A"), "label", "Safari"));
        assertOk(ok);
        Assert.assertTrue(body(ok).containsKey("devices"));

        Mockito.when(push.registerWeb(ArgumentMatchers.eq(ME), ArgumentMatchers.eq("bad"), ArgumentMatchers.isNull(),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new PushDevices.Outcome(false, PushDevices.REFUSED_BAD_ENDPOINT, "https only", PREFS));
        assertError(resource.subscribeWeb(CSRF_OK, Map.of("endpoint", "bad", "keys", "not a map")), 400,
                ApiErrors.BAD_REQUEST);

        Mockito.when(push.unsubscribeWeb(ArgumentMatchers.eq(ME), ArgumentMatchers.eq("https://push.example/s/1"),
                ArgumentMatchers.any())).thenReturn(true);
        Assert.assertEquals(body(resource.unsubscribeWeb(CSRF_OK, Map.of("endpoint", "https://push.example/s/1")))
                .get("removed"), true);
        Assert.assertEquals(body(resource.unsubscribeWeb(CSRF_OK, null)).get("removed"), true, "idempotent");
    }

    @Test
    public void theNonStandardPortRidesInTheOrigin() {
        Mockito.when(request.getScheme()).thenReturn("http");
        Mockito.when(request.getServerName()).thenReturn("localhost");
        Mockito.when(request.getServerPort()).thenReturn(8080);
        Mockito.when(push.registerWeb(ArgumentMatchers.eq(ME), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.eq("http://localhost:8080"),
                ArgumentMatchers.any())).thenReturn(new PushDevices.Outcome(true, null, null, PREFS));
        assertOk(resource.subscribeWeb(CSRF_OK, Map.of("endpoint", "https://push.example/s/1")));
    }

    @Test
    public void theVapidKeyIs404UntilConfigured() {
        Mockito.when(push.vapidPublicKey()).thenReturn(null);
        assertError(resource.webPushKey(), 404, ApiErrors.NOT_FOUND);
        Mockito.when(push.vapidPublicKey()).thenReturn("BP4z");
        final Response ok = resource.webPushKey();
        assertOk(ok);
        Assert.assertEquals(body(ok).get("publicKey"), "BP4z");
    }

    @Test
    public void prefsReadAndWriteTheCallersOwnSettings() {
        Mockito.when(push.prefsOf(ME)).thenReturn(PREFS);
        final Response got = resource.prefs();
        assertOk(got);
        Assert.assertEquals(body(got).get("enabled"), true);
        Assert.assertEquals(body(got).get("quietHoursStart"), "22:00");
        Assert.assertEquals(body(got).get("timeZone"), "UTC");

        Mockito.when(push.setPrefs(ME, false, "", "", null))
                .thenReturn(new PushDevices.Outcome(true, null, null, PushPrefs.defaults()));
        final Response saved = resource.savePrefs(CSRF_OK, Map.of("enabled", "false", "quietHoursStart", "",
                "quietHoursEnd", ""));
        assertOk(saved);
        Assert.assertEquals(body(saved).get("quietHoursStart"), "", "unset renders as an empty string");
        Assert.assertEquals(body(saved).get("timeZone"), "");
        Assert.assertEquals(body(saved).get("devices"), List.of());

        Mockito.when(push.setPrefs(ME, null, "25:00", null, null))
                .thenReturn(new PushDevices.Outcome(false, PushDevices.REFUSED_BAD_TIME, "HH:mm", PREFS));
        assertError(resource.savePrefs(CSRF_OK, Map.of("quietHoursStart", "25:00")), 400,
                ApiErrors.VALIDATION_FAILED);
        Mockito.when(push.setPrefs(ME, null, null, null, "Mars"))
                .thenReturn(new PushDevices.Outcome(false, PushDevices.REFUSED_BAD_ZONE, "zone", PREFS));
        assertError(resource.savePrefs(CSRF_OK, Map.of("timeZone", "Mars")), 400, ApiErrors.VALIDATION_FAILED);
    }

    @Test
    public void theTestPushReportsEveryDevicesOutcome() {
        Mockito.when(push.sendTest(ME)).thenReturn(new PushSender.Report(1, List.of(
                new PushSender.DeviceOutcome("ios", "Phone", PushOutcome.DELIVERED),
                new PushSender.DeviceOutcome("web", null, PushOutcome.RETRY)), null));
        final Response ok = resource.test(CSRF_OK);
        assertOk(ok);
        Assert.assertEquals(body(ok).get("sent"), 1);
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> outcomes = (List<Map<String, Object>>) body(ok).get("outcomes");
        Assert.assertEquals(outcomes.get(0), Map.of("kind", "ios", "label", "Phone", "outcome", "DELIVERED"));
        Assert.assertEquals(outcomes.get(1).get("label"), "");
        Assert.assertFalse(body(ok).containsKey("skipped"));

        Mockito.when(push.sendTest(ME)).thenReturn(PushSender.Report.skipped(PushSender.SKIPPED_NO_DEVICES));
        Assert.assertEquals(body(resource.test(CSRF_OK)).get("skipped"), PushSender.SKIPPED_NO_DEVICES);
    }

    @Test
    public void aBearerCallerIsExemptFromTheSentinel() {
        bearer(new org.paulsens.trip.security.TokenPrincipal(ME, "me@example.org", "user",
                org.paulsens.trip.model.AuthToken.Scope.MEMBER, "sel"));
        Mockito.when(push.sendTest(ME)).thenReturn(PushSender.Report.skipped(PushSender.SKIPPED_DISABLED));
        assertOk(resource.test(null));
        accepting(ApiMediaTypes.PUSH_V1);
        assertVersionedType(resource.test(null), ApiMediaTypes.PUSH_V1);
    }
}
