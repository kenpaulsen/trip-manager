package org.paulsens.trip.util;

import java.util.List;

public final class Util {
    private Util() {
        throw new UnsupportedOperationException("Do not instantiate utility class.");
    }

    public static <T> T orDefault(final T value, final T defaultVal) {
        return value == null ? defaultVal : value;
    }

    /**
     * A list of names as a sentence reads it: "Joe", "Joe and Bob", "Joe, Bob, and Mike" (the payment worked
     * example's wording, serial comma included), in the order given; "" for none. The ONE spelling, so a ledger
     * row, a lodging bill and a report never word the same list three ways.
     */
    public static String joinNames(final List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        if (names.size() <= 2) {
            return String.join(" and ", names);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + ", and " + names.get(names.size() - 1);
    }

    /**
     * Checks if a string is null, empty, or blank (spaces only).
     */
    public static boolean isBlank(final String str) {
        return str == null || str.trim().isEmpty();
    }
}
