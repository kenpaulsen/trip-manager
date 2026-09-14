package org.paulsens.trip.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Standard Web Push (RFC 8030) to each browser's push service: RFC 8291 {@code aes128gcm} content, RFC
 * 8292 VAPID authorization, all JDK ({@code docs/push-notifications.md} "Gateways").
 *
 * <p>Outcome map: 200/201/202 → DELIVERED; 404/410 → DROP_DEVICE (the subscription is gone); 429 / 5xx /
 * IOException → RETRY; 401/403 → re-mint the VAPID token and retry once, then AUTH; 400/413 and every other
 * 4xx → FAILED.
 */
@Slf4j
public final class WebPushClient implements PushGateway {

    /** A VAPID token lives 12 h; hand the same one out for 11 of them. */
    static final Duration TOKEN_REUSE = Duration.ofHours(11);
    static final String TTL_SECONDS = "86400";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PushTransport transport;
    private final Es256Jwt jwt;
    private final String publicKey;
    private final String subject;
    private final Supplier<KeyPair> ephemeralKeys;
    private final Supplier<byte[]> salts;

    public static WebPushClient create(final PushSecrets.VapidKey key) {
        return new WebPushClient(PushTransport.jdk(), new Es256Jwt(key.key(), null, TOKEN_REUSE),
                key.publicKey(), key.subject(), EcKeys::generate, WebPushClient::randomSalt);
    }

    /** Test seam: scripted transport, and injectable ephemeral keys + salt so the RFC 8291 vector is exact. */
    WebPushClient(final PushTransport transport, final Es256Jwt jwt, final String publicKey,
            final String subject, final Supplier<KeyPair> ephemeralKeys, final Supplier<byte[]> salts) {
        this.transport = transport;
        this.jwt = jwt;
        this.publicKey = publicKey;
        this.subject = subject;
        this.ephemeralKeys = ephemeralKeys;
        this.salts = salts;
    }

    @Override
    public PushOutcome send(final PushDevice device, final PushPayload payload) {
        if (device == null || device.getKind() != PushDevice.Kind.WEB || device.getEndpoint() == null
                || payload == null || payload.isSilent()) {
            return PushOutcome.FAILED;
        }
        final URI endpoint;
        final byte[] body;
        try {
            endpoint = URI.create(device.getEndpoint());
            body = encrypt(payload.toWebPush(), device);
        } catch (final GeneralSecurityException | IllegalArgumentException ex) {
            log.warn("Web push to …{} not encryptable: {}", device.id(), ex.getMessage());
            return PushOutcome.FAILED;
        }
        final String audience = originOf(endpoint);
        Attempt attempt = attempt(endpoint, audience, payload, body);
        if (attempt.status() == 401 || attempt.status() == 403) {
            jwt.invalidate(audience);
            attempt = attempt(endpoint, audience, payload, body);
        }
        final PushOutcome outcome = attempt.outcome();
        if (outcome != PushOutcome.DELIVERED) {
            log.info("Web push {} for device …{} ({}): {}", outcome, device.id(), payload.getKind(),
                    attempt.detail());
        }
        return outcome;
    }

    byte[] encrypt(final String json, final PushDevice device) throws GeneralSecurityException {
        return WebPushCrypto.encrypt(json.getBytes(StandardCharsets.UTF_8), EcKeys.decodeUrl(device.getP256dh()),
                EcKeys.decodeUrl(device.getAuth()), ephemeralKeys.get(), salts.get());
    }

    private Attempt attempt(final URI endpoint, final String audience, final PushPayload payload,
            final byte[] body) {
        try {
            final HttpResponse<String> response = transport.send(request(endpoint, audience, payload, body));
            return new Attempt(response.statusCode(), response.body() == null ? "" : response.body());
        } catch (final IOException ex) {
            return new Attempt(-1, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Attempt(-2, "interrupted");
        }
    }

    HttpRequest request(final URI endpoint, final String audience, final PushPayload payload, final byte[] body) {
        final HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "vapid t=" + jwt.token(audience, Es256Jwt.vapidClaims(audience, subject))
                        + ",k=" + publicKey)
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", TTL_SECONDS)
                .header("Urgency", payload.isPassive() ? "low" : "normal")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (payload.getThreadId() != null) {
            request.header("Topic", topicOf(payload.getThreadId()));
        }
        return request.build();
    }

    /** {@code scheme://host[:port]} of the endpoint -- the VAPID audience. */
    static String originOf(final URI endpoint) {
        final StringBuilder origin = new StringBuilder(endpoint.getScheme()).append("://").append(endpoint.getHost());
        if (endpoint.getPort() > 0) {
            origin.append(':').append(endpoint.getPort());
        }
        return origin.toString();
    }

    /**
     * RFC 8030 allows at most 32 URL-safe base64 characters in {@code Topic}; a channel id ({@code trip:<uuid>})
     * is neither short enough nor URL-safe, so the header carries a digest of it. Same tag → same topic.
     */
    static String topicOf(final String tag) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(tag.getBytes(StandardCharsets.UTF_8));
            return EcKeys.encodeUrl(digest).substring(0, 32);
        } catch (final GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", ex);
        }
    }

    private static byte[] randomSalt() {
        final byte[] salt = new byte[WebPushCrypto.SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return salt;
    }

    /** One exchange; negative statuses are transport failures. */
    record Attempt(int status, String detail) {

        PushOutcome outcome() {
            if (status == 200 || status == 201 || status == 202) {
                return PushOutcome.DELIVERED;
            }
            if (status == 404 || status == 410) {
                return PushOutcome.DROP_DEVICE;
            }
            if (status == 401 || status == 403) {
                return PushOutcome.AUTH;
            }
            if (status == -1 || status == 429 || status >= 500) {
                return PushOutcome.RETRY;
            }
            return PushOutcome.FAILED;
        }
    }
}
