package org.paulsens.trip.util;

import java.util.Random;

public class RandomData {
    private static final Random rand = new Random();
    private static final char[] ALPHA =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] PASS_CHARS =
            "@#!*23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz".toCharArray();

    public static String genAlpha(final int len) {
        return genString(len, ALPHA);
    }

    public static String genPassChars(final int len) {
        return genString(len, PASS_CHARS);
    }

    public static String genString(final int len, final char[] chars) {
        final StringBuilder buf = new StringBuilder();
        for (int count = 0; count < len; count++) {
            buf.append(chars[rand.nextInt(chars.length)]);
        }
        return buf.toString();
    }
}
