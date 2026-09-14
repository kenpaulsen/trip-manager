package org.paulsens.trip.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Flow;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Web Push against a scripted transport. The encryption is proved two ways: the RFC 8291 Appendix A vector
 * is reproduced BYTE FOR BYTE with the appendix's keys and salt injected, and a random-key message decrypts
 * with an independent decryptor written from RFC 8188/8291 (the receiving side, as a browser would do it).
 */
public class WebPushClientTest {

    // RFC 8291 Appendix A.
    private static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String AS_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlml"
            + "MoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyou"
            + "BWLVWGNWQexSgSxsj_Qulcy4a-fN";

    private static final KeyPair VAPID = EcKeys.generate();
    private static final String VAPID_PUBLIC = EcKeys.encodeUrl(EcKeys.pointOf((ECPublicKey) VAPID.getPublic()));

    private final Deque<Object> responses = new ArrayDeque<>();
    private final List<HttpRequest> requests = new ArrayList<>();
    private final List<byte[]> bodies = new ArrayList<>();
    private WebPushClient client;

    @BeforeMethod
    public void setUp() {
        responses.clear();
        requests.clear();
        bodies.clear();
        client = new WebPushClient(this::exchange, new Es256Jwt((ECPrivateKey) VAPID.getPrivate(), null,
                Duration.ofHours(11)), VAPID_PUBLIC, "mailto:ken@example.org", EcKeys::generate,
                () -> new byte[WebPushCrypto.SALT_LENGTH]);
    }

    private HttpResponse<String> exchange(final HttpRequest request) throws IOException {
        requests.add(request);
        bodies.add(bodyOf(request));
        final Object next = responses.isEmpty() ? response(201) : responses.poll();
        if (next instanceof IOException io) {
            throw io;
        }
        @SuppressWarnings("unchecked")
        final HttpResponse<String> response = (HttpResponse<String>) next;
        return response;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(final int status) {
        final HttpResponse<String> response = Mockito.mock(HttpResponse.class);
        Mockito.when(response.statusCode()).thenReturn(status);
        Mockito.when(response.body()).thenReturn("");
        return response;
    }

    /** Drains a request body publisher synchronously (test-only; the JDK API is push-based). */
    private static byte[] bodyOf(final HttpRequest request) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(final Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(final ByteBuffer item) {
                final byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                out.writeBytes(chunk);
            }

            @Override
            public void onError(final Throwable throwable) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        try {
            Assert.assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return out.toByteArray();
    }

    private static PushDevice subscription(final String endpoint, final String p256dh, final String auth) {
        return PushDevice.web(endpoint, p256dh, auth, "Safari on Mac", "https://acme.example", 1L);
    }

    private static PushPayload alert() {
        return PushPayload.alert(PushPayload.KIND_CHAT_MENTION, "T", "S", "B", "trip:1", null,
                "https://x/trip/chat.jsf?trip=1").withIcon("https://x/logo.png");
    }

    /** RFC 8188 + 8291 receiving side, written independently of the encryptor. */
    static byte[] decrypt(final byte[] body, final KeyPair ua, final byte[] authSecret)
            throws GeneralSecurityException {
        final ByteBuffer in = ByteBuffer.wrap(body);
        final byte[] salt = new byte[16];
        in.get(salt);
        final int rs = in.getInt();
        final int idLen = in.get() & 0xff;
        final byte[] asPublic = new byte[idLen];
        in.get(asPublic);
        final byte[] ciphertext = new byte[in.remaining()];
        in.get(ciphertext);
        Assert.assertEquals(rs, 4096);
        Assert.assertEquals(idLen, 65);

        final byte[] uaPublic = EcKeys.pointOf((ECPublicKey) ua.getPublic());
        final byte[] ecdh = WebPushCrypto.agree(ua, EcKeys.publicKeyFromPoint(asPublic));
        final byte[] ikm = WebPushCrypto.hkdf(authSecret, ecdh, WebPushCrypto.concat(
                "WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic), 32);
        final byte[] cek = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: aes128gcm\0".getBytes(
                StandardCharsets.US_ASCII), 16);
        final byte[] nonce = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: nonce\0".getBytes(
                StandardCharsets.US_ASCII), 12);
        final Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        final byte[] record = gcm.doFinal(ciphertext);
        int end = record.length - 1;
        while (end > 0 && record[end] == 0) {
            end--;
        }
        Assert.assertEquals(record[end], 2, "the last record ends in the 0x02 delimiter");
        return Arrays.copyOf(record, end);
    }

    private static KeyPair pair(final String privateScalar, final String publicPoint) throws Exception {
        return new KeyPair(EcKeys.publicKeyFromPoint(EcKeys.decodeUrl(publicPoint)),
                EcKeys.privateKeyFromScalar(EcKeys.decodeUrl(privateScalar)));
    }

    @Test
    public void theRfc8291AppendixAVectorIsReproducedByteForByte() throws Exception {
        final byte[] body = WebPushCrypto.encrypt(PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                EcKeys.decodeUrl(UA_PUBLIC), EcKeys.decodeUrl(AUTH), pair(AS_PRIVATE, AS_PUBLIC),
                EcKeys.decodeUrl(SALT));
        Assert.assertEquals(EcKeys.encodeUrl(body), BODY);
        Assert.assertEquals(new String(decrypt(EcKeys.decodeUrl(BODY), pair(UA_PRIVATE, UA_PUBLIC),
                EcKeys.decodeUrl(AUTH)), StandardCharsets.UTF_8), PLAINTEXT, "the appendix body decrypts too");
    }

    @Test
    public void aRandomlyKeyedMessageDecryptsOnTheReceivingSide() throws Exception {
        final KeyPair ua = EcKeys.generate();
        final byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        final PushDevice device = subscription("https://push.example/s/1",
                EcKeys.encodeUrl(EcKeys.pointOf((ECPublicKey) ua.getPublic())), EcKeys.encodeUrl(auth));

        Assert.assertEquals(client.send(device, alert()), PushOutcome.DELIVERED);

        final String json = new String(decrypt(bodies.get(0), ua, auth), StandardCharsets.UTF_8);
        Assert.assertEquals(json, alert().toWebPush());
    }

    @Test
    public void theRequestCarriesVapidAndTheEncodingHeaders() throws Exception {
        final KeyPair ua = EcKeys.generate();
        final PushDevice device = subscription("https://push.example:8443/s/1",
                EcKeys.encodeUrl(EcKeys.pointOf((ECPublicKey) ua.getPublic())), EcKeys.encodeUrl(new byte[16]));
        Assert.assertEquals(client.send(device, alert().withPassive(true)), PushOutcome.DELIVERED);
        final HttpRequest request = requests.get(0);
        Assert.assertEquals(request.uri().toString(), "https://push.example:8443/s/1");
        final String authorization = request.headers().firstValue("Authorization").orElseThrow();
        Assert.assertTrue(authorization.startsWith("vapid t=ey"), authorization);
        Assert.assertTrue(authorization.endsWith(",k=" + VAPID_PUBLIC), authorization);
        Assert.assertEquals(request.headers().firstValue("Content-Encoding").orElseThrow(), "aes128gcm");
        Assert.assertEquals(request.headers().firstValue("Content-Type").orElseThrow(), "application/octet-stream");
        Assert.assertEquals(request.headers().firstValue("TTL").orElseThrow(), "86400");
        Assert.assertEquals(request.headers().firstValue("Urgency").orElseThrow(), "low");
        Assert.assertEquals(request.headers().firstValue("Topic").orElseThrow(), WebPushClient.topicOf("trip:1"));
        Assert.assertEquals(WebPushClient.topicOf("trip:1").length(), 32);
        Assert.assertTrue(WebPushClient.topicOf("trip:1").matches("[A-Za-z0-9_-]{32}"), "URL-safe only");
        Assert.assertEquals(WebPushClient.originOf(URI.create("https://push.example:8443/s/1")),
                "https://push.example:8443");
        Assert.assertEquals(WebPushClient.originOf(URI.create("https://push.example/s/1")), "https://push.example");
    }

    @Test
    public void statusesMapToOutcomesAndAuthFailuresRemintOnce() throws Exception {
        final KeyPair ua = EcKeys.generate();
        final PushDevice device = subscription("https://push.example/s/1",
                EcKeys.encodeUrl(EcKeys.pointOf((ECPublicKey) ua.getPublic())), EcKeys.encodeUrl(new byte[16]));
        responses.add(response(410));
        Assert.assertEquals(client.send(device, alert()), PushOutcome.DROP_DEVICE);
        responses.add(response(404));
        Assert.assertEquals(client.send(device, alert()), PushOutcome.DROP_DEVICE);
        responses.add(response(500));
        Assert.assertEquals(client.send(device, alert()), PushOutcome.RETRY);
        responses.add(response(429));
        Assert.assertEquals(client.send(device, alert()), PushOutcome.RETRY);
        responses.add(new IOException("reset"));
        Assert.assertEquals(client.send(device, alert()), PushOutcome.RETRY);
        responses.add(response(413));
        Assert.assertEquals(client.send(device, alert()), PushOutcome.FAILED);
        responses.add(response(400));
        Assert.assertEquals(client.send(device, alert()), PushOutcome.FAILED);
        responses.add(response(202));
        Assert.assertEquals(client.send(device, alert()), PushOutcome.DELIVERED);

        requests.clear();
        responses.add(response(401));
        Assert.assertEquals(client.send(device, alert()), PushOutcome.DELIVERED, "re-minted and retried");
        Assert.assertEquals(requests.size(), 2);
        Assert.assertNotEquals(requests.get(0).headers().firstValue("Authorization"),
                requests.get(1).headers().firstValue("Authorization"));
        responses.add(response(403));
        responses.add(response(403));
        Assert.assertEquals(client.send(device, alert()), PushOutcome.AUTH);
    }

    @Test
    public void onlyAnEncryptableWebSubscriptionIsSendable() {
        Assert.assertEquals(client.send(null, alert()), PushOutcome.FAILED);
        Assert.assertEquals(client.send(PushDevice.ios("ab".repeat(16), "production", "s", "l", "v", 1L), alert()),
                PushOutcome.FAILED);
        final PushDevice good = subscription("https://push.example/s/1", UA_PUBLIC, AUTH);
        Assert.assertEquals(client.send(good, null), PushOutcome.FAILED);
        Assert.assertEquals(client.send(good, PushPayload.silent()), PushOutcome.FAILED, "browsers get no silent push");
        Assert.assertEquals(client.send(subscription("https://push.example/s/1", "AAAA", AUTH), alert()),
                PushOutcome.FAILED, "a bad p256dh cannot be encrypted for");
        Assert.assertEquals(client.send(subscription("https://push.example/s/1", UA_PUBLIC, "AAAA"), alert()),
                PushOutcome.FAILED, "a short auth secret is refused");
        Assert.assertEquals(client.send(subscription("::not a url::", UA_PUBLIC, AUTH), alert()),
                PushOutcome.FAILED);
        Assert.assertTrue(requests.isEmpty());

        final WebPushClient interrupted = new WebPushClient(WebPushClientTest::interrupt,
                new Es256Jwt((ECPrivateKey) VAPID.getPrivate(), null, Duration.ofHours(11)), VAPID_PUBLIC,
                "mailto:x", EcKeys::generate, () -> new byte[16]);
        try {
            Assert.assertEquals(interrupted.send(good, alert()), PushOutcome.FAILED);
        } finally {
            Assert.assertTrue(Thread.interrupted());
        }
    }

    private static HttpResponse<String> interrupt(final HttpRequest request) throws InterruptedException {
        throw new InterruptedException("stop");
    }

    @Test
    public void theCryptoGuardsItsInputs() {
        Assert.assertThrows(GeneralSecurityException.class, () -> WebPushCrypto.encrypt(new byte[1],
                EcKeys.decodeUrl(UA_PUBLIC), EcKeys.decodeUrl(AUTH), EcKeys.generate(), new byte[3]));
        Assert.assertThrows(GeneralSecurityException.class, () -> WebPushCrypto.encrypt(new byte[1],
                EcKeys.decodeUrl(UA_PUBLIC), new byte[4], EcKeys.generate(), new byte[16]));
        Assert.assertThrows(GeneralSecurityException.class,
                () -> WebPushCrypto.hkdf(new byte[16], new byte[16], new byte[1], 64));
    }

    @Test
    public void theRealClientWiresRandomSaltsAndFreshKeys() throws Exception {
        final PushSecrets.VapidKey key = new PushSecrets.VapidKey(VAPID_PUBLIC, "x", "mailto:ken@example.org",
                (ECPrivateKey) VAPID.getPrivate());
        final WebPushClient real = WebPushClient.create(key);
        final PushDevice device = subscription("https://push.example/s/1", UA_PUBLIC, AUTH);
        final byte[] first = real.encrypt("{}", device);
        final byte[] second = real.encrypt("{}", device);
        Assert.assertFalse(Arrays.equals(first, second), "a fresh salt and key pair per message");
        final HttpRequest request = real.request(URI.create("https://push.example/s/1"), "https://push.example",
                alert(), first);
        Assert.assertEquals(request.headers().firstValue("TTL").orElseThrow(), WebPushClient.TTL_SECONDS);
    }
}
