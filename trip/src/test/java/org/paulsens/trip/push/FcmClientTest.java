package org.paulsens.trip.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The FCM gateway against a scripted transport: the token exchange, the v1 message, and the outcome map. */
public class FcmClientTest {

    static final String TOKEN =
            "dGhpcy1pcy1hbi1mY20tdG9rZW4:APA91bHxQ_wR2kJvL8mN3oP5qS7tU9vW1xY3zA5bC7dE9fG1hJ3kL5mN7oP9q";
    private static final String TOKEN_URI = "https://oauth2.example/token";
    private static final String TOKEN_BODY =
            "{\"access_token\":\"ya29.first\",\"expires_in\":3599,\"token_type\":\"Bearer\"}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RSAPrivateKey key;
    private final Deque<Object> responses = new ArrayDeque<>();
    private final List<HttpRequest> requests = new ArrayList<>();
    private final List<Long> sleeps = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private GoogleAccessToken tokens;
    private FcmClient client;

    @BeforeClass
    public static void keys() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final KeyPair pair = generator.generateKeyPair();
        key = (RSAPrivateKey) pair.getPrivate();
    }

    /** The service account's {@code private_key} as Firebase writes it. */
    static String pemOf(final RSAPrivateKey privateKey) {
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(privateKey.getEncoded()) + "\n-----END PRIVATE KEY-----\n";
    }

    static RSAPrivateKey testKey() {
        return key;
    }

    @BeforeMethod
    public void setUp() {
        responses.clear();
        requests.clear();
        sleeps.clear();
        tokens = new GoogleAccessToken(this::exchange,
                GoogleAccessToken.key("unitetrip", "fcm@unitetrip.iam", TOKEN_URI, key), clock::get);
        client = new FcmClient(this::exchange, tokens, "unitetrip", sleeps::add);
    }

    private HttpResponse<String> exchange(final HttpRequest request) throws IOException, InterruptedException {
        requests.add(request);
        final Object next = responses.isEmpty() ? response(200, "{\"name\":\"projects/unitetrip/messages/1\"}")
                : responses.poll();
        if (next instanceof IOException io) {
            throw io;
        }
        if (next instanceof InterruptedException interrupted) {
            throw interrupted;
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

    private static PushDevice phone() {
        return PushDevice.android(TOKEN, "sel", "Pixel 9", "1.0 (19)", 1L);
    }

    private static PushPayload alert() {
        return PushPayload.alert(PushPayload.KIND_CHAT_MENTION, "Spring", "Maria mentioned you", "hello", "trip:1",
                "unitetrip://chat/1", "https://x/trip/chat.jsf?trip=1").withChat("trip:1", "m1").withBadge(3)
                .withImage("https://files/x-small.jpg");
    }

    private void scriptToken() {
        responses.add(response(200, TOKEN_BODY));
    }

    @Test
    public void theFirstSendExchangesTheAssertionForABearerTokenThenReusesIt() throws Exception {
        scriptToken();
        Assert.assertEquals(client.send(phone(), alert()), PushOutcome.DELIVERED);
        Assert.assertEquals(requests.size(), 2, "one exchange, one send");
        final HttpRequest exchange = requests.get(0);
        Assert.assertEquals(exchange.uri().toString(), TOKEN_URI);
        Assert.assertEquals(exchange.headers().firstValue("content-type").orElseThrow(),
                "application/x-www-form-urlencoded");
        final String form = bodyOf(exchange);
        Assert.assertTrue(form.startsWith("grant_type=" + GoogleAccessToken.GRANT_TYPE.replace(":", "%3A")), form);
        final String assertion = form.substring(form.indexOf("assertion=") + "assertion=".length());
        final String[] parts = assertion.split("\\.");
        Assert.assertEquals(parts.length, 3);
        final JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
        Assert.assertEquals(header.get("alg").asText(), "RS256");
        final JsonNode claims = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        Assert.assertEquals(claims.get("iss").asText(), "fcm@unitetrip.iam");
        Assert.assertEquals(claims.get("scope").asText(), GoogleAccessToken.SCOPE);
        Assert.assertEquals(claims.get("aud").asText(), TOKEN_URI);
        Assert.assertEquals(claims.get("exp").asLong() - claims.get("iat").asLong(),
                GoogleAccessToken.ASSERTION_SECONDS);

        final HttpRequest send = requests.get(1);
        Assert.assertEquals(send.uri().toString(), "https://fcm.googleapis.com/v1/projects/unitetrip/messages:send");
        Assert.assertEquals(send.headers().firstValue("authorization").orElseThrow(), "Bearer ya29.first");
        final JsonNode message = MAPPER.readTree(bodyOf(send)).get("message");
        Assert.assertEquals(message.get("token").asText(), TOKEN);
        Assert.assertEquals(message.get("android").get("priority").asText(), "HIGH");
        Assert.assertEquals(message.get("android").get("ttl").asText(), "86400s");
        Assert.assertNull(message.get("android").get("collapse_key"));
        Assert.assertNull(message.get("notification"), "data-only: the app renders the notification");
        final JsonNode data = message.get("data");
        Assert.assertEquals(data.get("kind").asText(), "chat.mention");
        Assert.assertEquals(data.get("title").asText(), "Spring");
        Assert.assertEquals(data.get("subtitle").asText(), "Maria mentioned you");
        Assert.assertEquals(data.get("body").asText(), "hello");
        Assert.assertEquals(data.get("link").asText(), "unitetrip://chat/1");
        Assert.assertEquals(data.get("channelId").asText(), "trip:1");
        Assert.assertEquals(data.get("messageId").asText(), "m1");
        Assert.assertEquals(data.get("image").asText(), "https://files/x-small.jpg");
        Assert.assertEquals(data.get("badge").asText(), "3");
        Assert.assertEquals(data.get("threadId").asText(), "trip:1");
        Assert.assertNull(data.get("passive"));

        Assert.assertEquals(client.send(phone(), alert()), PushOutcome.DELIVERED);
        Assert.assertEquals(requests.size(), 3, "the cached token served the second send");
        clock.addAndGet(3599L - GoogleAccessToken.REUSE_SECONDS + 1);
        scriptToken();
        Assert.assertEquals(client.send(phone(), alert()), PushOutcome.DELIVERED);
        Assert.assertEquals(requests.size(), 5, "near expiry a fresh exchange precedes the send");
    }

    @Test
    public void silentAndPassivePushesGoNormalPriority() throws Exception {
        scriptToken();
        Assert.assertEquals(client.send(phone(), PushPayload.silent()), PushOutcome.DELIVERED);
        final JsonNode silent = MAPPER.readTree(bodyOf(requests.get(1))).get("message");
        Assert.assertEquals(silent.get("android").get("priority").asText(), "NORMAL");
        Assert.assertEquals(silent.get("android").get("ttl").asText(), "0s");
        Assert.assertEquals(silent.get("android").get("collapse_key").asText(), "refresh");
        Assert.assertEquals(silent.get("data").size(), 1);
        Assert.assertEquals(silent.get("data").get("kind").asText(), "silent");

        Assert.assertEquals(client.send(phone(), alert().withPassive(true)), PushOutcome.DELIVERED);
        final JsonNode passive = MAPPER.readTree(bodyOf(requests.get(2))).get("message");
        Assert.assertEquals(passive.get("android").get("priority").asText(), "NORMAL");
        Assert.assertEquals(passive.get("data").get("passive").asText(), "true");
    }

    @Test
    public void theOutcomeMapFollowsTheContract() throws Exception {
        scriptToken();
        responses.add(response(404, "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"details\":[{\"@type\":"
                + "\"type.googleapis.com/google.firebase.fcm.v1.FcmError\",\"errorCode\":\"UNREGISTERED\"}]}}"));
        Assert.assertEquals(client.send(phone(), alert()), PushOutcome.DROP_DEVICE);
        responses.add(response(400, "{\"error\":{\"code\":400,\"message\":\"The registration token is not a valid "
                + "FCM registration token\",\"status\":\"INVALID_ARGUMENT\"}}"));
        Assert.assertEquals(client.send(phone(), alert()), PushOutcome.DROP_DEVICE);
        responses.add(response(400, "{\"error\":{\"code\":400,\"message\":\"Data value too long\","
                + "\"status\":\"INVALID_ARGUMENT\"}}"));
        Assert.assertEquals(client.send(phone(), alert()), PushOutcome.FAILED);
        responses.add(response(413, "too big"));
        Assert.assertEquals(client.send(phone(), alert()), PushOutcome.FAILED);
        Assert.assertTrue(sleeps.isEmpty());

        responses.add(response(503, "{\"error\":{\"status\":\"UNAVAILABLE\"}}"));
        Assert.assertEquals(client.send(phone(), alert()), PushOutcome.DELIVERED, "one retry after a second");
        Assert.assertEquals(sleeps, List.of(FcmClient.RETRY_DELAY_MILLIS));
        responses.add(response(429, ""));
        responses.add(response(429, ""));
        Assert.assertEquals(client.send(phone(), alert()), PushOutcome.RETRY);
        responses.add(new IOException("reset"));
        responses.add(new IOException("reset again"));
        Assert.assertEquals(client.send(phone(), alert()), PushOutcome.RETRY);

        final int before = requests.size();
        responses.add(response(401, "{\"error\":{\"status\":\"UNAUTHENTICATED\"}}"));
        scriptToken();
        Assert.assertEquals(client.send(phone(), alert()), PushOutcome.DELIVERED, "a 401 re-mints and retries once");
        Assert.assertEquals(requests.size() - before, 3, "send, exchange, send");
        responses.add(response(403, ""));
        responses.add(response(200, TOKEN_BODY));
        responses.add(response(403, ""));
        Assert.assertEquals(client.send(phone(), alert()), PushOutcome.AUTH);

        Assert.assertEquals(client.send(PushDevice.ios("ab".repeat(16), "production", "s", "l", "v", 1L), alert()),
                PushOutcome.FAILED, "an iOS device is not FCM's");
        Assert.assertEquals(client.send(null, alert()), PushOutcome.FAILED);
        Assert.assertEquals(client.send(phone(), null), PushOutcome.FAILED);
    }

    @Test
    public void noAccessTokenIsAnAuthOutcomeAndTheExchangeToleratesJunk() {
        responses.add(response(500, "boom"));
        Assert.assertEquals(client.send(phone(), alert()), PushOutcome.AUTH);
        Assert.assertEquals(requests.size(), 1, "no send without a token");
        responses.add(response(200, "{\"token_type\":\"Bearer\"}"));
        Assert.assertTrue(tokens.token().isEmpty(), "no access_token field");
        responses.add(response(200, "not json"));
        Assert.assertTrue(tokens.token().isEmpty());
        responses.add(new IOException("down"));
        Assert.assertTrue(tokens.token().isEmpty());
        responses.add(response(200, "{\"access_token\":\"ya29.ok\"}"));
        Assert.assertEquals(tokens.token().orElseThrow(), "ya29.ok", "a missing expires_in reads as an hour");
        tokens.invalidate();
        responses.add(response(200, "{\"access_token\":\"ya29.two\",\"expires_in\":100}"));
        Assert.assertEquals(tokens.token().orElseThrow(), "ya29.two");
    }

    @Test
    public void reasonsComeFromTheFcmErrorCodeThenTheStatusThenTheBody() {
        Assert.assertEquals(FcmClient.reasonOf(null), "");
        Assert.assertEquals(FcmClient.reasonOf(" "), "");
        Assert.assertEquals(FcmClient.reasonOf("{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":"
                + "\"UNREGISTERED\"}]}}"), "UNREGISTERED");
        Assert.assertEquals(FcmClient.reasonOf("{\"error\":{\"status\":\"UNAVAILABLE\",\"message\":\"later\"}}"),
                "UNAVAILABLE: later");
        Assert.assertEquals(FcmClient.reasonOf("{\"error\":{\"status\":\"UNAVAILABLE\"}}"), "UNAVAILABLE");
        Assert.assertEquals(FcmClient.reasonOf("{\"error\":{\"details\":[{\"other\":1}]}}"),
                "{\"error\":{\"details\":[{\"other\":1}]}}");
        Assert.assertEquals(FcmClient.reasonOf("plain"), "plain");
        Assert.assertEquals(FcmClient.reasonOf("x".repeat(300)).length(), 200);
        Assert.assertEquals(new FcmClient.Attempt(-2, "interrupted").outcome(), PushOutcome.FAILED);
    }

    @Test
    public void theRsaPemRoundTripsAndJunkIsRefused() throws Exception {
        final RSAPrivateKey parsed = EcKeys.rsaPrivateKeyFromPem(pemOf(key));
        Assert.assertEquals(parsed.getModulus(), key.getModulus());
        Assert.assertThrows(java.security.GeneralSecurityException.class,
                () -> EcKeys.rsaPrivateKeyFromPem("not a key!"));
        Assert.assertThrows(java.security.GeneralSecurityException.class,
                () -> EcKeys.rsaPrivateKeyFromPem("-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----"));
        Assert.assertThrows(java.security.GeneralSecurityException.class,
                () -> EcKeys.privateKeyFromPem("??"));
    }

    @Test
    public void interruptionsAreSwallowedAndReflagged() throws Exception {
        responses.add(new InterruptedException("stop"));
        Assert.assertTrue(tokens.token().isEmpty(), "an interrupted exchange mints nothing");
        Assert.assertTrue(Thread.interrupted(), "the flag is re-set for the caller, and cleared here");
        scriptToken();
        responses.add(new InterruptedException("stop"));
        Assert.assertEquals(client.send(phone(), alert()), PushOutcome.FAILED, "an interrupted send is not retried");
        Assert.assertTrue(Thread.interrupted());

        FcmClient.pause(1L);
        Thread.currentThread().interrupt();
        FcmClient.pause(1L);
        Assert.assertTrue(Thread.interrupted(), "pause keeps the interrupt and returns");

        final GoogleAccessToken unsignable = new GoogleAccessToken(this::exchange,
                GoogleAccessToken.key("p", "e", TOKEN_URI, new TinyRsaKey()), clock::get);
        Assert.assertThrows(IllegalStateException.class, () -> unsignable.assertion(1L));
    }

    private static String bodyOf(final HttpRequest request) {
        final HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        final java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> collector = new BodyCollector();
        publisher.subscribe(collector);
        return ((BodyCollector) collector).text();
    }

    /** A 2-bit "RSA key": the provider refuses it at initSign, which is the signing failure the client wraps. */
    private static final class TinyRsaKey implements RSAPrivateKey {
        @Override
        public java.math.BigInteger getPrivateExponent() {
            return java.math.BigInteger.ONE;
        }

        @Override
        public String getAlgorithm() {
            return "RSA";
        }

        @Override
        public String getFormat() {
            return "PKCS#8";
        }

        @Override
        public byte[] getEncoded() {
            return new byte[0];
        }

        @Override
        public java.math.BigInteger getModulus() {
            return java.math.BigInteger.valueOf(3);
        }
    }

    /** Drains a request body the way the HTTP client would. */
    private static final class BodyCollector implements java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        @Override
        public void onSubscribe(final java.util.concurrent.Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(final java.nio.ByteBuffer item) {
            final byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            out.writeBytes(bytes);
        }

        @Override
        public void onError(final Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }

        String text() {
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
