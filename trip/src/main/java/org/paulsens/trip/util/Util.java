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

    /** The value, or null when it is null or blank. NOT trimmed: see {@link #trimToNull}. */
    public static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * The value trimmed, or null when there is nothing left to keep.
     *
     * <p>Trimmed FIRST, then tested with {@code isBlank()}: the two disagree at the edges, and either one alone
     * lets a kind of nothing through as text. {@code trim()} strips control characters that {@code isBlank()}
     * does not count as blank (so testing first answered {@code ""}), and {@code isBlank()} counts Unicode spaces
     * that {@code trim()} keeps (so trimming alone answered the spaces back). Text with something in it comes
     * back exactly as {@code trim()} leaves it.
     */
    public static String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    /** The value trimmed, or {@code ""} for null. */
    public static String trimmed(final String value) {
        return (value == null) ? "" : value.trim();
    }

    /** At most {@code max} characters of the text, cut without a marker; null stays null. */
    public static String truncate(final String text, final int max) {
        return (text == null || text.length() <= max) ? text : text.substring(0, max);
    }

    /**
     * Whether a character is space of any kind, visible or not: what {@link Character#isWhitespace} counts (space,
     * tab, line breaks), plus the Unicode spaces it leaves out (no-break space U+00A0, figure space U+2007, narrow
     * no-break space U+202F) and the invisible format characters a paste carries in (zero-width space U+200B,
     * joiners, the byte-order mark U+FEFF). A value meant to hold one token, an address or a code, must refuse all
     * of them: they look like nothing on the screen, yet a mail service or a lookup reads them as part of the value.
     */
    public static boolean isAnySpace(final int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                || Character.getType(codePoint) == Character.FORMAT;
    }

    /** Whether the text holds any character {@link #isAnySpace} counts; false for null. */
    public static boolean hasAnySpace(final String text) {
        return text != null && text.codePoints().anyMatch(Util::isAnySpace);
    }

    /**
     * Checks if a string is null, empty, or blank (spaces only).
     */
    public static boolean isBlank(final String str) {
        return str == null || str.trim().isEmpty();
    }
}
