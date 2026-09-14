package org.paulsens.trip.push;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.ECPrivateKey;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.config.KnownSettings;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The APNs gateway against a scripted transport: headers, host by environment, and the outcome map. */
public class ApnsClientTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final ECPrivateKey KEY = (ECPrivateKey) EcKeys.generate().getPrivate();

    private final Deque<Object> responses = new ArrayDeque<>();
    private final List<HttpRequest> requests = new ArrayList<>();
    private final List<Long> sleeps = new ArrayList<>();
    private ApnsClient client;

    @BeforeMethod
    public void setUp() {
        responses.clear();
        requests.clear();
        sleeps.clear();
        client = new ApnsClient(this::exchange, new Es256Jwt(KEY, "KID", Duration.ofMinutes(50)), "TEAM",
                () -> "org.paulsens.unitetrip", sleeps::add);
    }

    private HttpResponse<String> exchange(final HttpRequest request) throws IOException {
        requests.add(request);
        final Object next = responses.isEmpty() ? response(200, "") : responses.poll();
        if (next instanceof IOException io) {
            throw io;
        }
        @SuppressWarnings("unchecked")
        final HttpResponse<String> response = (HttpResponse<String>) next;
        return response;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(final int status, final String body) {
        final HttpResponse<String> response = Mockito.mock(HttpResponse.class);
        Mockito.when(response.statusCode()).thenReturn(status);
        Mockito.when(response.body()).thenReturn(body);
        return response;
    }

    private static PushDevice phone(final String environment) {
        return PushDevice.ios(TOKEN, environment, "sel", "Ken's iPhone", "1.0", 1L);
    }

    private static PushPayload alert() {
        return PushPayload.alert(PushPayload.KIND_CHAT_MENTION, "T", "S", "B", "trip:1", "unitetrip://chat/1",
                "https://x/trip/chat.jsf?trip=1").withBadge(3);
    }

    @Test
    public void anAlertGoesToTheEnvironmentsHostWithTheContractHeaders() {
        Assert.assertEquals(client.send(phone("production"), alert()), PushOutcome.DELIVERED);
        final HttpRequest request = requests.get(0);
        Assert.assertEquals(request.uri().toString(), ApnsClient.PRODUCTION_HOST + "/3/device/" + TOKEN);
        Assert.assertEquals(request.method(), "POST");
        Assert.assertTrue(request.headers().firstValue("authorization").orElseThrow().startsWith("bearer ey"));
        Assert.assertEquals(request.headers().firstValue("apns-topic").orElseThrow(), "org.paulsens.unitetrip");
        Assert.assertEquals(request.headers().firstValue("apns-push-type").orElseThrow(), "alert");
        Assert.assertEquals(request.headers().firstValue("apns-priority").orElseThrow(), "10");
        Assert.assertTrue(request.headers().firstValue("apns-id").isPresent());
        Assert.assertTrue(request.headers().firstValue("apns-collapse-id").isEmpty());
        final long expiry = Long.parseLong(request.headers().firstValue("apns-expiration").orElseThrow());
        Assert.assertTrue(expiry > System.currentTimeMillis() / 1000L + 23 * 3600L);
        Assert.assertEquals(request.timeout().orElseThrow(), Duration.ofSeconds(15));
        Assert.assertTrue(sleeps.isEmpty());
    }

    @Test
    public void aSilentPushIsBackgroundLowPriorityNowOrNeverAndCollapsed() {
        Assert.assertEquals(client.send(phone("sandbox"), PushPayload.silent()), PushOutcome.DELIVERED);
        final HttpRequest request = requests.get(0);
        Assert.assertTrue(request.uri().toString().startsWith(ApnsClient.SANDBOX_HOST));
        Assert.assertEquals(request.headers().firstValue("apns-push-type").orElseThrow(), "background");
        Assert.assertEquals(request.headers().firstValue("apns-priority").orElseThrow(), "5");
        Assert.assertEquals(request.headers().firstValue("apns-expiration").orElseThrow(), "0");
        Assert.assertEquals(request.headers().firstValue("apns-collapse-id").orElseThrow(), "refresh");
    }

    @Test
    public void deadTokensAreDroppedAndOtherClientErrorsAreNot() {
        responses.add(response(400, "{\"reason\":\"BadDeviceToken\"}"));
        Assert.assertEquals(client.send(phone("production"), alert()), PushOutcome.DROP_DEVICE);
        responses.add(response(400, "{\"reason\":\"DeviceTokenNotForTopic\"}"));
        Assert.assertEquals(client.send(phone("production"), alert()), PushOutcome.DROP_DEVICE);
        responses.add(response(410, "{\"reason\":\"Unregistered\",\"timestamp\":1}"));
        Assert.assertEquals(client.send(phone("production"), alert()), PushOutcome.DROP_DEVICE);
        responses.add(response(400, "{\"reason\":\"PayloadEmpty\"}"));
        Assert.assertEquals(client.send(phone("production"), alert()), PushOutcome.FAILED);
        responses.add(response(413, ""));
        Assert.assertEquals(client.send(phone("production"), alert()), PushOutcome.FAILED);
        Assert.assertTrue(sleeps.isEmpty(), "client errors are never retried");
    }

    @Test
    public void aForbiddenRemintsTheTokenAndRetriesOnce() {
        responses.add(response(403, "{\"reason\":\"ExpiredProviderToken\"}"));
        Assert.assertEquals(client.send(phone("production"), alert()), PushOutcome.DELIVERED);
        Assert.assertEquals(requests.size(), 2);
        Assert.assertNotEquals(requests.get(0).headers().firstValue("authorization"),
                requests.get(1).headers().firstValue("authorization"), "the retry carries a fresh JWT");

        responses.add(response(403, "{\"reason\":\"InvalidProviderToken\"}"));
        responses.add(response(403, "{\"reason\":\"InvalidProviderToken\"}"));
        Assert.assertEquals(client.send(phone("production"), alert()), PushOutcome.AUTH);
    }

    @Test
    public void transientFailuresPauseAndRetryOnceThenReportRetry() throws Exception {
        responses.add(response(503, ""));
        Assert.assertEquals(client.send(phone("production"), alert()), PushOutcome.DELIVERED);
        Assert.assertEquals(sleeps, List.of(ApnsClient.RETRY_DELAY_MILLIS));
        Assert.assertEquals(requests.size(), 2);

        responses.add(response(429, "{\"reason\":\"TooManyRequests\"}"));
        responses.add(response(429, "{\"reason\":\"TooManyRequests\"}"));
        Assert.assertEquals(client.send(phone("production"), alert()), PushOutcome.RETRY);

        responses.add(new IOException("connection reset"));
        responses.add(new IOException("connection reset"));
        Assert.assertEquals(client.send(phone("production"), alert()), PushOutcome.RETRY);
    }

    @Test
    public void anInterruptedSendFailsAndKeepsTheInterrupt() {
        final ApnsClient interrupted = new ApnsClient(ApnsClientTest::interrupt,
                new Es256Jwt(KEY, "KID", Duration.ofMinutes(50)), "TEAM", () -> "topic", sleeps::add);
        try {
            Assert.assertEquals(interrupted.send(phone("production"), alert()), PushOutcome.FAILED);
            Assert.assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Assert.assertTrue(Thread.interrupted(), "clear the flag for the next test");
        }
    }

    private static HttpResponse<String> interrupt(final HttpRequest request) throws InterruptedException {
        throw new InterruptedException("stop");
    }

    @Test
    public void onlyAPhoneWithATokenIsSendable() {
        Assert.assertEquals(client.send(null, alert()), PushOutcome.FAILED);
        Assert.assertEquals(client.send(PushDevice.web("https://e", "p", "a", "l", "o", 1L), alert()),
                PushOutcome.FAILED);
        Assert.assertEquals(client.send(phone("production"), null), PushOutcome.FAILED);
        Assert.assertTrue(requests.isEmpty());
    }

    @Test
    public void reasonsAreReadOffTheErrorBody() {
        Assert.assertEquals(ApnsClient.reasonOf("{\"reason\":\"BadDeviceToken\"}"), "BadDeviceToken");
        Assert.assertEquals(ApnsClient.reasonOf(""), "");
        Assert.assertEquals(ApnsClient.reasonOf(null), "");
        Assert.assertEquals(ApnsClient.reasonOf("plain text"), "plain text");
        Assert.assertEquals(ApnsClient.reasonOf("x".repeat(300)).length(), 200);
        Assert.assertEquals(ApnsClient.reasonOf("{\"reason\":42}"), "{\"reason\":42}");
        Assert.assertEquals(new ApnsClient.Attempt(-2, "interrupted").outcome(), PushOutcome.FAILED);
    }

    @Test
    public void theRealClientWiresTheTopicSettingAndTheJdkTransport() {
        final ConfigCommands config = Mockito.mock(ConfigCommands.class);
        Mockito.when(config.getString(KnownSettings.PUSH_APNS_TOPIC)).thenReturn("org.example.app");
        final ApnsClient real = ApnsClient.create(new PushSecrets.ApnsKey("KID", "TEAM", KEY), config);
        final HttpRequest request = real.request(ApnsClient.SANDBOX_HOST, phone("sandbox"), alert(), "{}");
        Assert.assertEquals(request.headers().firstValue("apns-topic").orElseThrow(), "org.example.app");
        final Supplier<PushTransport> jdk = PushTransport::jdk;
        Assert.assertNotNull(jdk.get());
    }
}
