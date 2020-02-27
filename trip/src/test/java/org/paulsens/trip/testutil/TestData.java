package org.paulsens.trip.testutil;

import java.util.Random;

public class TestData {
    private static final char[] ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final Random rand = new Random();

    public static String genAlpha(final int len) {
        return genString(len, ALPHA);
    }

    public static String genString(final int len, final char[] chars) {
        final StringBuilder buf = new StringBuilder();
        for (int count = 0; count < len; count++) {
            buf.append(chars[rand.nextInt(chars.length)]);
        }
        return buf.toString();
    }
}
