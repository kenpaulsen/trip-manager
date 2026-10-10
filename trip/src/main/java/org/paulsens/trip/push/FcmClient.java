package org.paulsens.trip.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongConsumer;
import lombok.extern.slf4j.Slf4j;

/**
 * Firebase Cloud Messaging over the HTTP v1 API, no SDK ({@code docs/push-notifications.md} "Gateways"): one
 * {@code messages:send} per device with a bearer token from {@link GoogleAccessToken}. Messages are
 * data-only, carrying the same keys as the APNs payload, so the Android app renders the notification itself
 * (channel per kind, big picture, badge) and a tap routes through the same {@code link}.
 *
 * <p>Outcome map: 200 → DELIVERED; 404 / {@code UNREGISTERED} and 400 {@code INVALID_ARGUMENT} naming the
 * token → DROP_DEVICE; 401 / 403 → re-mint the access token and retry once, then AUTH; 429 / 5xx /
 * IOException → one retry after a second, then RETRY; no access token → AUTH; anything else → FAILED.
 */
@Slf4j
public final class FcmClient implements PushGateway {

    static final String SEND_URL = "https://fcm.googleapis.com/v1/projects/%s/messages:send";
    static final Duration ALERT_TTL = Duration.ofHours(24);
    static final long RETRY_DELAY_MILLIS = 1_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PushTransport transport;
    private final GoogleAccessToken tokens;
    private final String projectId;
    private final LongConsumer sleeper;

    /** Wires the real client from the secret's service account. */
    public static FcmClient create(final PushSecrets.FcmKey key) {
        final PushTransport transport = PushTransport.jdk();
        return new FcmClient(transport, new GoogleAccessToken(transport, key), key.projectId(), FcmClient::pause);
    }

    /** Test seam: scripted transport, no real sleeping. */
    FcmClient(final PushTransport transport, final GoogleAccessToken tokens, final String projectId,
            final LongConsumer sleeper) {
        this.transport = transport;
        this.tokens = tokens;
        this.projectId = projectId;
        this.sleeper = sleeper;
    }

    @Override
    public PushOutcome send(final PushDevice device, final PushPayload payload) {
        if (device == null || device.getKind() != PushDevice.Kind.ANDROID || device.getToken() == null
                || device.getToken().isBlank() || payload == null) {
            return PushOutcome.FAILED;
        }
        final String body = message(device, payload);
        Attempt attempt = attempt(body);
        if (attempt.retryable()) {
            sleeper.accept(RETRY_DELAY_MILLIS);
            attempt = attempt(body);
        } else if (attempt.status() == 401 || attempt.status() == 403) {
            tokens.invalidate();
            attempt = attempt(body);
        }
        final PushOutcome outcome = attempt.outcome();
        if (outcome != PushOutcome.DELIVERED) {
            log.info("FCM {} for device …{} ({}): {}", outcome, device.id(), payload.getKind(), attempt.reason());
        }
        return outcome;
    }

    private Attempt attempt(final String body) {
        final Optional<String> token = tokens.token();
        if (token.isEmpty()) {
            return new Attempt(-3, "no access token");
        }
        try {
            final HttpResponse<String> response = transport.send(request(token.get(), body));
            return new Attempt(response.statusCode(), reasonOf(response.body()));
        } catch (final IOException ex) {
            return new Attempt(-1, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Attempt(-2, "interrupted");
        }
    }

    HttpRequest request(final String accessToken, final String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(String.format(SEND_URL, projectId)))
                .timeout(Duration.ofSeconds(15))
                .header("authorization", "Bearer " + accessToken)
                .header("content-type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * The v1 envelope: {@code {message: {token, data, android: {priority, ttl, collapse_key?}}}}. User-facing
     * kinds go high priority with a day to live; the silent refresh is normal priority (doze may hold it, which
     * is the point) and collapses so a burst wakes the app once.
     */
    String message(final PushDevice device, final PushPayload payload) {
        final Map<String, Object> android = new LinkedHashMap<>();
        android.put("priority", payload.isSilent() || payload.isPassive() ? "NORMAL" : "HIGH");
        android.put("ttl", (payload.isSilent() ? 0 : ALERT_TTL.toSeconds()) + "s");
        if (payload.getCollapseId() != null) {
            android.put("collapse_key", payload.getCollapseId());
        }
        final Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", device.getToken());
        message.put("data", payload.toFcmData());
        message.put("android", android);
        try {
            return MAPPER.writeValueAsString(Map.of("message", message));
        } catch (final JsonProcessingException ex) {
            throw new IllegalStateException("FCM message is not serializable", ex);
        }
    }

    /**
     * The FCM error code when the body carries one ({@code error.details[].errorCode}, e.g. {@code UNREGISTERED}),
     * else {@code error.status}, else the body itself (truncated) for the log.
     */
    static String reasonOf(final String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            final JsonNode error = MAPPER.readTree(body).path("error");
            for (final JsonNode detail : error.path("details")) {
                final String code = detail.path("errorCode").asText(null);
                if (code != null && !code.isBlank()) {
                    return code;
                }
            }
            final String status = error.path("status").asText(null);
            if (status != null && !status.isBlank()) {
                final String text = error.path("message").asText("");
                return text.isBlank() ? status : status + ": " + text;
            }
        } catch (final IOException ex) {
            // Not JSON: fall through to the raw body.
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    /** Package-private so the test can exercise the interrupted branch without a real send. */
    static void pause(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** One HTTP exchange, reduced to what the outcome map needs. Negative statuses are transport or auth failures. */
    record Attempt(int status, String reason) {

        boolean retryable() {
            return status == -1 || status == 429 || status >= 500;
        }

        PushOutcome outcome() {
            if (status == 200) {
                return PushOutcome.DELIVERED;
            }
            final boolean badToken = status == 400 && reason.startsWith("INVALID_ARGUMENT")
                    && reason.toLowerCase(java.util.Locale.ROOT).contains("token");
            if (status == 404 || reason.startsWith("UNREGISTERED") || badToken) {
                return PushOutcome.DROP_DEVICE;
            }
            if (status == 401 || status == 403 || status == -3) {
                return PushOutcome.AUTH;
            }
            return retryable() ? PushOutcome.RETRY : PushOutcome.FAILED;
        }
    }
}
