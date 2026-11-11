package org.paulsens.trip.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Arbitrary values for test data. These lived in {@link RandomData} until 2026-11, but only tests called
 * them, and a non-cryptographic generator on the main classpath invites a production caller to use it for
 * something that matters. {@code RandomData} is now CSPRNG-only; nothing here needs to be unpredictable.
 */
public final class TestRandom {
    private TestRandom() {
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
