package org.paulsens.trip.security;

import org.testng.Assert;
import org.testng.annotations.Test;

public class DigestsTest {

    /** A known SHA-256 vector, so a refactor cannot silently change what every stored hash means. */
    @Test
    public void sha256MatchesTheKnownVector() {
        Assert.assertEquals(Digests.sha256Base64("abc"), "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=");
    }

    /** The raw and hex forms are the same digest: device ids, push topics and waiver fingerprints depend on it. */
    @Test
    public void theRawAndHexFormsAreTheSameKnownVector() {
        final String hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        Assert.assertEquals(Digests.sha256Hex("abc"), hex);
        Assert.assertEquals(java.util.HexFormat.of().formatHex(Digests.sha256("abc")), hex);
        Assert.assertEquals(Digests.sha256("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Digests.sha256("abc"));
    }

    @Test
    public void matchesComparesEqualityAndToleratesNulls() {
        final String hash = Digests.sha256Base64("value");
        Assert.assertTrue(Digests.matches(hash, Digests.sha256Base64("value")));
        Assert.assertFalse(Digests.matches(hash, Digests.sha256Base64("other")));
        Assert.assertFalse(Digests.matches(null, hash));
        Assert.assertFalse(Digests.matches(hash, null));
    }

    @Test
    public void aMissingAlgorithmFailsLoudly() {
        Assert.assertThrows(IllegalStateException.class, () -> Digests.digestBase64("NOT-AN-ALGORITHM", "x"));
    }
}
