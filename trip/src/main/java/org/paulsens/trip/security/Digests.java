package org.paulsens.trip.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * SHA-256, the one way: the base64 form the auth flows share (values checked but never stored in the clear),
 * and the raw and hex forms behind device ids, push topics, ETags and a signed document's fingerprint.
 */
public final class Digests {

    private Digests() {
    }

    public static String sha256Base64(final String value) {
        return digestBase64("SHA-256", value);
    }

    /** The 32 digest bytes of the value's UTF-8 bytes. */
    public static byte[] sha256(final String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] sha256(final byte[] bytes) {
        return digest("SHA-256", bytes);
    }

    /** Lower-case hex, 64 characters. */
    public static String sha256Hex(final String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    static String digestBase64(final String algorithm, final String value) {
        return Base64.getEncoder().encodeToString(digest(algorithm, value.getBytes(StandardCharsets.UTF_8)));
    }

    // Takes the algorithm so the impossible-on-a-real-JDK catch is still exercisable by a test.
    private static byte[] digest(final String algorithm, final byte[] bytes) {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
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
