package org.paulsens.trip.util;

import java.security.SecureRandom;
import java.util.Base64;

public final class RandomData {
    private RandomData() {
    }

    // The ONLY generator here. There used to be a shared java.util.Random beside it for "display data", and the
    // one production caller (DataId) took its row ids from it; a second, weaker source is a trap for the next
    // caller, and the CSPRNG is fast enough for everything this class does.
    private static final SecureRandom SECURE = new SecureRandom();
    // Strings, not char[]: a public array is writable by any code, and one stray write would silently weaken
    // every id and password generated afterwards.
    public static final String ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    public static final String ALPHA_NUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    public static final String PASS_CHARS = "@#!*23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";

    public static String genAlpha(final int len) {
        return genString(len, ALPHA);
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

    /** A generated password: {@code len} characters of {@link #PASS_CHARS}. */
    public static String genSecurePassChars(final int len) {
        return genString(len, PASS_CHARS);
    }

    public static String genString(final int len, final String chars) {
        final StringBuilder buf = new StringBuilder();
        for (int count = 0; count < len; count++) {
            buf.append(chars.charAt(SECURE.nextInt(chars.length())));
        }
        return buf.toString();
    }
}
