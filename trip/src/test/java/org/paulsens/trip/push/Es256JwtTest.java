package org.paulsens.trip.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.Assert;
import org.testng.annotations.Test;

/** ES256 minting without a JWT library: the JOSE shape, a verifiable signature, per-audience reuse. */
public class Es256JwtTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final KeyPair PAIR = EcKeys.generate();

    private static JsonNode part(final String jwt, final int index) throws Exception {
        return MAPPER.readTree(EcKeys.decodeUrl(jwt.split("\\.")[index]));
    }

    @Test
    public void anApnsTokenIsThreeBase64UrlPartsWithAVerifiableP1363Signature() throws Exception {
        final AtomicLong clock = new AtomicLong(1_700_000_000L);
        final Es256Jwt jwt = new Es256Jwt((ECPrivateKey) PAIR.getPrivate(), "KEYID1", Duration.ofMinutes(50),
                clock::get);

        final String token = jwt.token("https://api.push.apple.com", Es256Jwt.apnsClaims("TEAM1"));
        final String[] parts = token.split("\\.");
        Assert.assertEquals(parts.length, 3);
        Assert.assertEquals(part(token, 0).get("alg").asText(), "ES256");
        Assert.assertEquals(part(token, 0).get("kid").asText(), "KEYID1");
        Assert.assertEquals(part(token, 1).get("iss").asText(), "TEAM1");
        Assert.assertEquals(part(token, 1).get("iat").asLong(), 1_700_000_000L);
        Assert.assertEquals(EcKeys.decodeUrl(parts[2]).length, 64, "raw r||s, not DER");

        final Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(PAIR.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        Assert.assertTrue(verifier.verify(EcKeys.decodeUrl(parts[2])));
    }

    @Test
    public void aVapidTokenHasNoKidAndCarriesAudienceExpiryAndSubject() throws Exception {
        final Es256Jwt jwt = new Es256Jwt((ECPrivateKey) PAIR.getPrivate(), null, Duration.ofHours(11),
                () -> 1_700_000_000L);
        final String token = jwt.token("https://web.push.apple.com",
                Es256Jwt.vapidClaims("https://web.push.apple.com", "mailto:ken@example.org"));
        Assert.assertNull(part(token, 0).get("kid"));
        Assert.assertEquals(part(token, 1).get("aud").asText(), "https://web.push.apple.com");
        Assert.assertEquals(part(token, 1).get("exp").asLong(), 1_700_000_000L + 12 * 3600L);
        Assert.assertEquals(part(token, 1).get("sub").asText(), "mailto:ken@example.org");
    }

    @Test
    public void tokensAreReusedPerAudienceUntilTheWindowLapsesOrTheyAreInvalidated() {
        final AtomicLong clock = new AtomicLong(1_000L);
        final Es256Jwt jwt = new Es256Jwt((ECPrivateKey) PAIR.getPrivate(), "k", Duration.ofMinutes(50),
                clock::get);
        final String first = jwt.token("a", Es256Jwt.apnsClaims("T"));
        Assert.assertSame(jwt.token("a", Es256Jwt.apnsClaims("T")), first, "cached within the window");
        Assert.assertNotEquals(jwt.token("b", Es256Jwt.apnsClaims("T")), first, "per audience");

        clock.set(1_000L + 50 * 60L);
        Assert.assertNotEquals(jwt.token("a", Es256Jwt.apnsClaims("T")), first, "re-minted after the window");

        final String current = jwt.token("a", Es256Jwt.apnsClaims("T"));
        jwt.invalidate("a");
        Assert.assertNotSame(jwt.token("a", Es256Jwt.apnsClaims("T")), current, "a 403 forces a fresh token");
    }

    @Test
    public void theProductionConstructorUsesTheWallClock() throws Exception {
        final Es256Jwt jwt = new Es256Jwt((ECPrivateKey) PAIR.getPrivate(), "k", Duration.ofMinutes(50));
        final long iat = part(jwt.token("a", Es256Jwt.apnsClaims("T")), 1).get("iat").asLong();
        Assert.assertTrue(Math.abs(iat - System.currentTimeMillis() / 1000L) < 5);
        Assert.assertEquals(Es256Jwt.apnsClaims("T").at(5L), Map.of("iss", "T", "iat", 5L));
    }
}
