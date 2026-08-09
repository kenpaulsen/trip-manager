package org.paulsens.trip.security;

import org.testng.Assert;
import org.testng.annotations.Test;

public class DigestsTest {

    /** A known SHA-256 vector, so a refactor cannot silently change what every stored hash means. */
    @Test
    public void sha256MatchesTheKnownVector() {
        Assert.assertEquals(Digests.sha256Base64("abc"), "ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=");
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
