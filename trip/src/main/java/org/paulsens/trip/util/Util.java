package org.paulsens.trip.util;

public final class Util {
    private Util() {
        throw new UnsupportedOperationException("Do not instantiate utility class.");
    }

    public static <T> T orDefault(final T value, final T defaultVal) {
        return value == null ? defaultVal : value;
    }

    /**
     * Checks if a string is null, empty, or blank (spaces only).
     */
    public static boolean isBlank(final String str) {
        return str == null || str.trim().isEmpty();
    }
}
