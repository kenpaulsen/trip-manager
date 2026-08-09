package org.paulsens.trip.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** The one hash the auth flows share: SHA-256 as base64, for values checked but never stored in the clear. */
public final class Digests {

    private Digests() {
    }

    public static String sha256Base64(final String value) {
        return digestBase64("SHA-256", value);
    }

    // Package-private so the impossible-on-a-real-JDK catch is still exercisable by a test.
    static String digestBase64(final String algorithm, final String value) {
        try {
            final MessageDigest digest = MessageDigest.getInstance(algorithm);
            return Base64.getEncoder().encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException(algorithm + " is a required JDK algorithm", ex);
        }
    }

    /** Constant-time equality over the base64 forms; timing must not leak how close a guess was. */
    public static boolean matches(final String expectedBase64, final String actualBase64) {
        if (expectedBase64 == null || actualBase64 == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedBase64.getBytes(StandardCharsets.UTF_8),
                actualBase64.getBytes(StandardCharsets.UTF_8));
    }
}
