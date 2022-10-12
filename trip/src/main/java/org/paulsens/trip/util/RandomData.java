package org.paulsens.trip.util;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class RandomData {
    private static final Random rand = new Random();
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

    public static String genString(final int len, final char[] chars) {
        final StringBuilder buf = new StringBuilder();
        for (int count = 0; count < len; count++) {
            buf.append(chars[rand.nextInt(chars.length)]);
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
