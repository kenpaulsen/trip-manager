package org.paulsens.trip.action;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The ISO 3166-1 alpha-2 countries a trip's destination may name (issue 154), from the JDK's own list. Unlike
 * {@link Countries} (the address autocompletes' short list of free-text names), a trip stores the CODE, so a listing
 * or an app can filter and name it in any language; names here are US English, what the editor and the API speak.
 */
public final class IsoCountries {

    /** One country: its two-letter code (what a trip stores) and its English name. */
    public record Country(String code, String name) {
    }

    private static final Set<String> CODES = Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2);

    private static final List<Country> ALL = CODES.stream()
            .map(code -> new Country(code, Locale.of("", code).getDisplayCountry(Locale.US)))
            .sorted(Comparator.comparing(Country::name))
            .toList();

    private IsoCountries() {
    }

    /** Every country, sorted by name. */
    public static List<Country> all() {
        return Collections.unmodifiableList(ALL);
    }

    /** The stored form of a requested code: upper case when it is a real code, else null. */
    public static String normalize(final String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        final String upper = code.trim().toUpperCase(Locale.ROOT);
        return CODES.contains(upper) ? upper : null;
    }

    /** The English name of a code ({@code BA} is "Bosnia and Herzegovina"), or "" for no or an unknown code. */
    public static String nameOf(final String code) {
        final String known = normalize(code);
        return known == null ? "" : Locale.of("", known).getDisplayCountry(Locale.US);
    }
}
