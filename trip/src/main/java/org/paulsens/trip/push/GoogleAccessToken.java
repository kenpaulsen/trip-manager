package org.paulsens.trip.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * An OAuth2 access token for the FCM HTTP v1 API, minted from the Firebase service account: an RS256 JWT
 * assertion ({@code iss} = the account's email, {@code scope} = firebase.messaging, {@code aud} = the token
 * URI, one hour) exchanged at the token URI for a bearer token. Cached and handed out again until
 * {@value #REUSE_SECONDS} seconds before it expires; {@link #invalidate} after a 401 forces a fresh exchange.
 *
 * <p>No Google library: the JDK signs the assertion ({@code SHA256withRSA}) and the push transport carries the
 * exchange, so the FCM path has the same shape and the same test seams as APNs.
 */
@Slf4j
final class GoogleAccessToken {

    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    static final long ASSERTION_SECONDS = 3600L;
    /** Google tokens live an hour; a five-minute margin covers a slow exchange and a skewed clock. */
    static final long REUSE_SECONDS = 300L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Minted(String token, long expiresAt) {
    }

    private final PushTransport transport;
    private final PushSecrets.FcmKey key;
    private final LongSupplier clock;
    private volatile Minted minted;

    GoogleAccessToken(final PushTransport transport, final PushSecrets.FcmKey key) {
        this(transport, key, () -> System.currentTimeMillis() / 1000L);
    }

    /** Test seam: a controllable clock (epoch seconds). */
    GoogleAccessToken(final PushTransport transport, final PushSecrets.FcmKey key, final LongSupplier clock) {
        this.transport = transport;
        this.key = key;
        this.clock = clock;
    }

    /** The cached token while it has life left, else a fresh exchange; empty when Google refused or was unreachable. */
    Optional<String> token() {
        final long now = clock.getAsLong();
        final Minted cached = minted;
        if (cached != null && cached.expiresAt() - now > REUSE_SECONDS) {
            return Optional.of(cached.token());
        }
        final Minted fresh = exchange(now);
        minted = fresh;
        return Optional.ofNullable(fresh).map(Minted::token);
    }

    /** Forgets the token so the next send exchanges a new one (after a 401 / 403 from FCM). */
    void invalidate() {
        minted = null;
    }

    private Minted exchange(final long now) {
        final String form = "grant_type=" + URLEncoder.encode(GRANT_TYPE, StandardCharsets.UTF_8)
                + "&assertion=" + assertion(now);
        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(key.tokenUri()))
                .timeout(Duration.ofSeconds(15))
                .header("content-type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        try {
            final HttpResponse<String> response = transport.send(request);
            if (response.statusCode() != 200) {
                log.warn("Google token exchange answered {}: {}", response.statusCode(),
                        ApnsClient.reasonOf(response.body()));
                return null;
            }
            final JsonNode root = MAPPER.readTree(response.body());
            final String token = root.path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                log.warn("Google token exchange answered without an access_token");
                return null;
            }
            return new Minted(token, now + root.path("expires_in").asLong(ASSERTION_SECONDS));
        } catch (final IOException ex) {
            log.warn("Google token exchange failed: {}", ex.getMessage());
            return null;
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** {@code base64url(header).base64url(claims).base64url(RS256 signature)}. */
    String assertion(final long now) {
        final Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        final Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", key.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", key.tokenUri());
        claims.put("iat", now);
        claims.put("exp", now + ASSERTION_SECONDS);
        final String signingInput = EcKeys.encodeUrl(json(header)) + "." + EcKeys.encodeUrl(json(claims));
        try {
            final Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key.key());
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + EcKeys.encodeUrl(signer.sign());
        } catch (final GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot sign an RS256 assertion", ex);
        }
    }

    /** Package-private so a test can build a key without a secret. */
    static PushSecrets.FcmKey key(final String projectId, final String clientEmail, final String tokenUri,
            final RSAPrivateKey privateKey) {
        return new PushSecrets.FcmKey(projectId, clientEmail, tokenUri, privateKey);
    }

    private static byte[] json(final Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (final JsonProcessingException ex) {
            throw new IllegalStateException("JWT part is not serializable", ex);
        }
    }
}
