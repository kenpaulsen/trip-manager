package org.paulsens.trip.util;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class RandomData {
    private RandomData() {
    }

    private static final Random RAND = new Random();
    // Anything an attacker could profit from predicting (login codes, remember-me tokens) must come from
    // here, never from the shared java.util.Random above.
    private static final SecureRandom SECURE = new SecureRandom();
    public static final char[] ALPHA =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    public static final char[] ALPHA_NUM =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    public static final char[] PASS_CHARS =
            "@#!*23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz".toCharArray();

    public static String genAlpha(final int len) {
        return genString(len, ALPHA);
    }

    public static String genPassChars(final int len) {
        return genString(len, PASS_CHARS);
    }

    /** A digits-only one-time code (leading zeros allowed) from the CSPRNG. */
    public static String genSecureDigits(final int len) {
        final StringBuilder buf = new StringBuilder(len);
        for (int count = 0; count < len; count++) {
            buf.append((char) ('0' + SECURE.nextInt(10)));
        }
        return buf.toString();
    }

    /** {@code numBytes} of CSPRNG entropy as unpadded base64url — cookie- and key-safe. */
    public static String genSecureToken(final int numBytes) {
        final byte[] bytes = new byte[numBytes];
        SECURE.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** {@link #genPassChars} from the CSPRNG, for generated passwords rather than display data. */
    public static String genSecurePassChars(final int len) {
        final StringBuilder buf = new StringBuilder(len);
        for (int count = 0; count < len; count++) {
            buf.append(PASS_CHARS[SECURE.nextInt(PASS_CHARS.length)]);
        }
        return buf.toString();
    }

    public static String genString(final int len, final char[] chars) {
        final StringBuilder buf = new StringBuilder();
        for (int count = 0; count < len; count++) {
            buf.append(chars[RAND.nextInt(chars.length)]);
        }
        return buf.toString();
    }

    public static int randomInt(final int max) {
        return ThreadLocalRandom.current().nextInt(max);
    }

    public static long randomLong(final long max) {
        return ThreadLocalRandom.current().nextLong(max);
    }

    public static float randomFloat(final float max) {
        return ThreadLocalRandom.current().nextFloat(max);
    }

    public static <E extends Enum<E>> E randomEnum(final Class<E> enumClass) {
        final E[] possible = enumClass.getEnumConstants();
        return possible[randomInt(possible.length)];
    }
}
