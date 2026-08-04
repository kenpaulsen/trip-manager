package org.paulsens.trip.security;

import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

public class PasswordHasherTest {
    private static final byte[] KEY_ONE = "pepper-key-number-one-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_TWO = "pepper-key-number-two-9876543210".getBytes(StandardCharsets.UTF_8);

    private static PasswordHasher noPepper() {
        return new PasswordHasher(Pepper.none());
    }

    private static PasswordHasher peppered(final int version, final byte[] key) {
        return new PasswordHasher(Pepper.of(version, key));
    }

    @Test
    public void hashIsNotThePlaintext() {
        final String hash = noPepper().hash("hunter2");
        assertNotEquals(hash, "hunter2");
        assertFalse(hash.contains("hunter2"), "the stored hash must not embed the plaintext");
    }

    @Test
    public void hashProducesTheVersionedEnvelope() {
        assertTrue(noPepper().hash("x").matches("^p0:\\$2[aby]\\$.+"), "no-pepper hashes carry version 0");
        assertTrue(peppered(1, KEY_ONE).hash("x").matches("^p1:\\$2[aby]\\$.+"), "peppered hashes carry their version");
    }

    @Test
    public void verifyAcceptsTheCorrectPassword() {
        final PasswordHasher hasher = peppered(1, KEY_ONE);
        assertTrue(hasher.verify("correct horse", hasher.hash("correct horse")));
    }

    @Test
    public void verifyRejectsTheWrongPassword() {
        final PasswordHasher hasher = peppered(1, KEY_ONE);
        assertFalse(hasher.verify("battery staple", hasher.hash("correct horse")));
    }

    @Test
    public void saltMakesEveryHashOfTheSamePasswordDistinct() {
        final PasswordHasher hasher = peppered(1, KEY_ONE);
        final String a = hasher.hash("same");
        final String b = hasher.hash("same");
        assertNotEquals(a, b, "BCrypt's per-hash salt should make two hashes of one password differ");
        assertTrue(hasher.verify("same", a) && hasher.verify("same", b), "yet both must still verify");
    }

    @Test
    public void theSamePasswordUnderDifferentPeppersDoesNotCrossVerify() {
        final String hashedUnderOne = peppered(1, KEY_ONE).hash("secret");
        // A verifier holding a different key for the same version must reject it -- the pepper genuinely matters.
        assertFalse(peppered(1, KEY_TWO).verify("secret", hashedUnderOne));
    }

    @Test
    public void verifyReturnsFalseWhenNoKeyForTheStoredVersion() {
        final String hashedUnderOne = peppered(1, KEY_ONE).hash("secret");
        // A no-pepper verifier cannot possibly verify a version-1 hash; it must fail closed, not throw.
        assertFalse(noPepper().verify("secret", hashedUnderOne));
    }

    @Test
    public void legacyPlaintextVerifiesAndIsFlaggedForRehash() {
        final PasswordHasher hasher = peppered(1, KEY_ONE);
        assertTrue(hasher.verify("myOldPassword", "myOldPassword"), "a legacy plaintext row must still authenticate");
        assertFalse(hasher.verify("wrong", "myOldPassword"));
        assertFalse(hasher.isHashed("myOldPassword"));
        assertTrue(hasher.needsRehash("myOldPassword"), "plaintext must be flagged for upgrade");
    }

    @Test
    public void aCurrentVersionHashDoesNotNeedRehash() {
        final PasswordHasher hasher = peppered(1, KEY_ONE);
        assertFalse(hasher.needsRehash(hasher.hash("x")));
    }

    @Test
    public void aStaleVersionHashNeedsRehash() {
        // A row hashed under pepper v1, read by an app now on v2 (which still holds v1's key), must be re-hashed.
        final String hashedV1 = peppered(1, KEY_ONE).hash("secret");
        final Pepper rotated = Pepper.parseSecret(
                "{\"version\":2,\"keys\":{\"1\":\"" + b64(KEY_ONE) + "\",\"2\":\"" + b64(KEY_TWO) + "\"}}");
        final PasswordHasher afterRotation = new PasswordHasher(rotated);
        assertTrue(afterRotation.verify("secret", hashedV1), "the old key must still verify old hashes");
        assertTrue(afterRotation.needsRehash(hashedV1), "but the old-version hash must be flagged for upgrade");
        assertFalse(afterRotation.needsRehash(afterRotation.hash("secret")), "a fresh v2 hash is current");
    }

    @Test
    public void handlesLongAndUnicodePasswords() {
        final PasswordHasher hasher = peppered(1, KEY_ONE);
        final String longPass = "éèê".repeat(50); // 150 chars, >72 bytes in UTF-8
        assertTrue(hasher.verify(longPass, hasher.hash(longPass)),
                "pre-hashing must let passwords past BCrypt's 72-byte limit round-trip");
    }

    @Test
    public void verifyIsFalseForNulls() {
        final PasswordHasher hasher = peppered(1, KEY_ONE);
        assertFalse(hasher.verify(null, "p1:$2a$10$whatever"));
        assertFalse(hasher.verify("x", null));
    }

    private static String b64(final byte[] bytes) {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    public void needsRehashIsFalseForNull() {
        assertFalse(peppered(1, KEY_ONE).needsRehash(null));
    }

    /**
     * The singleton resolves its pepper EXACTLY once, and remembers a failure just as firmly as a success: a
     * misconfigured secret must not be re-fetched (and re-stack-dumped) on every login attempt.
     *
     * <p>The statics are reset around the test by reflection so its outcome never leaks into other tests that
     * touch {@code getInstance()}.
     */
    @Test
    public void getInstanceResolvesOnceAndCachesAFailureWithoutRedumping() throws Exception {
        if (System.getenv(Pepper.KEY_ENV) != null || System.getenv(Pepper.SECRET_ENV) != null) {
            throw new org.testng.SkipException("A pepper is configured in this environment");
        }
        resetSingleton();
        try {
            // A bad key makes resolution fail; the failure must be remembered, not retried.
            System.setProperty(Pepper.KEY_PROP, "!!! not base64 !!!");
            final IllegalStateException first = org.testng.Assert.expectThrows(
                    IllegalStateException.class, PasswordHasher::getInstance);
            assertTrue(first.getMessage().contains("unavailable"), first.getMessage());
            // Fixing the property does NOT help until restart -- by design, and this pins it.
            System.clearProperty(Pepper.KEY_PROP);
            org.testng.Assert.expectThrows(IllegalStateException.class, PasswordHasher::getInstance);

            // After a (simulated) restart, resolution succeeds and later calls reuse the same instance.
            resetSingleton();
            final PasswordHasher resolved = PasswordHasher.getInstance();
            org.testng.Assert.assertSame(PasswordHasher.getInstance(), resolved);
        } finally {
            System.clearProperty(Pepper.KEY_PROP);
            resetSingleton();
        }
    }

    private static void resetSingleton() throws Exception {
        for (final String field : new String[] {"instance", "resolutionFailure"}) {
            final java.lang.reflect.Field f = PasswordHasher.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(null, null);
        }
    }
}
