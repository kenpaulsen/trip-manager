package org.paulsens.trip.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * ES256 JWT minting for APNs token auth ({@code {"iss":team,"iat":now}} + {@code kid}) and VAPID
 * ({@code {"aud":origin,"exp":+12h,"sub":subject}}), cached per audience and re-minted after
 * {@code reuseFor} or on demand ({@link #invalidate}) after a 403.
 *
 * <p>No dependency: {@code Signature.getInstance("SHA256withECDSAinP1363Format")} produces the fixed-size
 * {@code r || s} that JOSE wants, so the DER unwrapping every JWT library carries is simply not needed.
 * Tokens are per task, in-JVM: both providers allow many concurrent valid tokens per key.
 */
public final class Es256Jwt {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Supplies the claims for a fresh token, given the mint time in epoch seconds. */
    public interface Claims {
        Map<String, Object> at(long nowSeconds);
    }

    private record Minted(String token, long mintedAt) {
    }

    private final ECPrivateKey key;
    private final String keyId;
    private final long reuseSeconds;
    private final LongSupplier clock;
    private final Map<String, Minted> cache = new ConcurrentHashMap<>();

    /**
     * @param keyId the JOSE {@code kid}, or null to omit it (VAPID keys have none)
     * @param reuseFor how long a minted token is handed out again before a fresh one is signed
     */
    public Es256Jwt(final ECPrivateKey key, final String keyId, final Duration reuseFor) {
        this(key, keyId, reuseFor, () -> System.currentTimeMillis() / 1000L);
    }

    /** Test seam: a controllable clock. */
    Es256Jwt(final ECPrivateKey key, final String keyId, final Duration reuseFor, final LongSupplier clock) {
        this.key = key;
        this.keyId = keyId;
        this.reuseSeconds = reuseFor.toSeconds();
        this.clock = clock;
    }

    /** The cached token for {@code audience}, minted (or re-minted) when absent or older than the reuse window. */
    public String token(final String audience, final Claims claims) {
        final long now = clock.getAsLong();
        final Minted cached = cache.get(audience);
        if (cached != null && now - cached.mintedAt() < reuseSeconds) {
            return cached.token();
        }
        final Minted fresh = new Minted(sign(claims.at(now)), now);
        cache.put(audience, fresh);
        return fresh.token();
    }

    /** Forgets the token for {@code audience}, so the next call signs a new one (after a 403 / 401). */
    public void invalidate(final String audience) {
        cache.remove(audience);
    }

    /** The APNs claim set: issuer = team id, issued-at = now. */
    public static Claims apnsClaims(final String teamId) {
        return now -> Map.of("iss", teamId, "iat", now);
    }

    /** The VAPID claim set (RFC 8292): the push service's origin, a 12 h expiry, and a contact. */
    public static Claims vapidClaims(final String audience, final String subject) {
        return now -> vapidClaimMap(audience, subject, now);
    }

    private static Map<String, Object> vapidClaimMap(final String audience, final String subject, final long now) {
        final Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", audience);
        claims.put("exp", now + Duration.ofHours(12).toSeconds());
        claims.put("sub", subject);
        return claims;
    }

    /** {@code base64url(header).base64url(claims).base64url(signature)}, signature as raw {@code r || s}. */
    String sign(final Map<String, Object> claims) {
        final Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "ES256");
        header.put("typ", "JWT");
        if (keyId != null) {
            header.put("kid", keyId);
        }
        final String signingInput = EcKeys.encodeUrl(json(header)) + "." + EcKeys.encodeUrl(json(claims));
        try {
            final Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(key);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + EcKeys.encodeUrl(signer.sign());
        } catch (final GeneralSecurityException ex) {
            throw new IllegalStateException("Cannot sign an ES256 JWT", ex);
        }
    }

    private static byte[] json(final Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (final JsonProcessingException ex) {
            throw new IllegalStateException("JWT part is not serializable", ex);
        }
    }
}
