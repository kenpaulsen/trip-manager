package org.paulsens.trip.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.config.KnownSettings;

/**
 * Direct APNs over HTTP/2 with token auth (an ES256 JWT from the {@code .p8} key), no SDK
 * ({@code docs/push-notifications.md} "Gateways"). Host by device environment; one request per device.
 *
 * <p>Outcome map: 200 → DELIVERED; 400 {@code BadDeviceToken} / {@code DeviceTokenNotForTopic} and 410
 * {@code Unregistered} → DROP_DEVICE; 403 → re-mint the JWT and retry once, then AUTH; 429 / 5xx /
 * IOException → one retry after a second, then RETRY; anything else → FAILED.
 */
@Slf4j
public final class ApnsClient implements PushGateway {

    static final String PRODUCTION_HOST = "https://api.push.apple.com";
    static final String SANDBOX_HOST = "https://api.sandbox.push.apple.com";
    /** Apple refuses tokens older than an hour; ten minutes of slack covers a slow clock. */
    static final Duration TOKEN_REUSE = Duration.ofMinutes(50);
    static final Duration ALERT_EXPIRY = Duration.ofHours(24);
    static final long RETRY_DELAY_MILLIS = 1_000L;

    private final PushTransport transport;
    private final Es256Jwt jwt;
    private final String teamId;
    private final Supplier<String> topic;
    private final LongConsumer sleeper;

    /** Wires the real client from the secret and the topic setting. */
    public static ApnsClient create(final PushSecrets.ApnsKey key, final ConfigCommands config) {
        return new ApnsClient(PushTransport.jdk(), new Es256Jwt(key.key(), key.keyId(), TOKEN_REUSE),
                key.teamId(), () -> config.getString(KnownSettings.PUSH_APNS_TOPIC), ApnsClient::pause);
    }

    /** Test seam: scripted transport, fixed topic, no real sleeping. */
    ApnsClient(final PushTransport transport, final Es256Jwt jwt, final String teamId,
            final Supplier<String> topic, final LongConsumer sleeper) {
        this.transport = transport;
        this.jwt = jwt;
        this.teamId = teamId;
        this.topic = topic;
        this.sleeper = sleeper;
    }

    @Override
    public PushOutcome send(final PushDevice device, final PushPayload payload) {
        if (device == null || device.getKind() != PushDevice.Kind.IOS || device.getToken() == null
                || device.getToken().isBlank() || payload == null) {
            return PushOutcome.FAILED;
        }
        final String host = device.isSandbox() ? SANDBOX_HOST : PRODUCTION_HOST;
        final String body = payload.toApns();
        Attempt attempt = attempt(host, device, payload, body);
        if (attempt.retryable()) {
            sleeper.accept(RETRY_DELAY_MILLIS);
            attempt = attempt(host, device, payload, body);
        } else if (attempt.status() == 403) {
            jwt.invalidate(host);
            attempt = attempt(host, device, payload, body);
        }
        final PushOutcome outcome = attempt.outcome();
        if (outcome != PushOutcome.DELIVERED) {
            log.info("APNs {} for device …{} ({}): {}", outcome, device.id(), payload.getKind(), attempt.reason());
        }
        return outcome;
    }

    private Attempt attempt(final String host, final PushDevice device, final PushPayload payload,
            final String body) {
        final HttpRequest request = request(host, device, payload, body);
        try {
            final HttpResponse<String> response = transport.send(request);
            return new Attempt(response.statusCode(), reasonOf(response.body()));
        } catch (final IOException ex) {
            return new Attempt(-1, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Attempt(-2, "interrupted");
        }
    }

    HttpRequest request(final String host, final PushDevice device, final PushPayload payload,
            final String body) {
        final long now = System.currentTimeMillis() / 1000L;
        final HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(host + "/3/device/" + device.getToken()))
                .timeout(Duration.ofSeconds(15))
                .header("authorization", "bearer " + jwt.token(host, Es256Jwt.apnsClaims(teamId)))
                .header("apns-topic", topic.get())
                .header("apns-id", UUID.randomUUID().toString())
                .header("apns-push-type", payload.isSilent() ? "background" : "alert")
                .header("apns-priority", payload.isSilent() ? "5" : "10")
                // A silent refresh is "now or never": waking the app an hour late refreshes nothing useful.
                .header("apns-expiration", payload.isSilent() ? "0" : Long.toString(now + ALERT_EXPIRY.toSeconds()))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (payload.getCollapseId() != null) {
            request.header("apns-collapse-id", payload.getCollapseId());
        }
        return request.build();
    }

    /** APNs error bodies are {@code {"reason":"BadDeviceToken"}}; anything else is kept verbatim for the log. */
    static String reasonOf(final String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        final int at = body.indexOf("\"reason\"");
        if (at < 0) {
            return body.length() > 200 ? body.substring(0, 200) : body;
        }
        final int open = body.indexOf('"', body.indexOf(':', at) + 1);
        final int close = open < 0 ? -1 : body.indexOf('"', open + 1);
        return (open < 0 || close < 0) ? body : body.substring(open + 1, close);
    }

    private static void pause(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** One HTTP exchange, reduced to what the outcome map needs. Negative statuses are transport failures. */
    record Attempt(int status, String reason) {

        boolean retryable() {
            return status == -1 || status == 429 || status >= 500;
        }

        PushOutcome outcome() {
            if (status == 200) {
                return PushOutcome.DELIVERED;
            }
            if (status == 410 || (status == 400
                    && ("BadDeviceToken".equals(reason) || "DeviceTokenNotForTopic".equals(reason)))) {
                return PushOutcome.DROP_DEVICE;
            }
            if (status == 403) {
                return PushOutcome.AUTH;
            }
            return retryable() ? PushOutcome.RETRY : PushOutcome.FAILED;
        }
    }
}
