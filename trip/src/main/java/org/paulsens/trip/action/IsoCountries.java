package org.paulsens.trip.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The ISO 3166-1 alpha-2 countries a trip's destination may name (issue 154), from the JDK's own list. Unlike
 * {@link Countries} (the address autocompletes' short list of free-text names), a trip stores the CODE, so a listing
 * or an app can filter and name it in any language; names here are US English, what the editor and the API speak.
 */
public final class IsoCountries {

    /**
     * One country: its two-letter code (what a trip stores), its English name (what is SHOWN, as the JDK spells
     * it), and the other English names a search also finds it by ({@link #aliasesOf}).
     */
    public record Country(String code, String name, List<String> aliases) {
    }

    private static final Set<String> CODES = Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2);

    /**
     * The English names people type that the JDK does not spell (owner decision 2026-11-17, "Add English search
     * aliases"). Search only: a shown name never changes. ONE list for every search over countries, used as
     * written: the trip editor's Countries filter, Discover's {@code q}, and both apps' Discover search, whose
     * copies must match it. Kept in this order so a diff against the apps' copies reads line for line. Typed
     * entries, so an unbalanced edit is a compile error; {@code IsoCountriesTest} proves every key a real code.
     */
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("TR", List.of("Turkey")),
            Map.entry("CI", List.of("Ivory Coast")),
            Map.entry("CZ", List.of("Czech Republic")),
            Map.entry("MM", List.of("Burma")),
            Map.entry("CV", List.of("Cape Verde")),
            Map.entry("SZ", List.of("Swaziland")),
            Map.entry("MK", List.of("Macedonia")),
            Map.entry("KR", List.of("South Korea", "Korea")),
            Map.entry("KP", List.of("North Korea")),
            Map.entry("VA", List.of("Vatican", "Holy See")),
            Map.entry("PS", List.of("Palestine")),
            Map.entry("GB", List.of("UK", "United Kingdom", "Great Britain", "Britain", "England", "Scotland",
                    "Wales", "Northern Ireland")),
            Map.entry("US", List.of("USA", "US", "United States", "America")),
            Map.entry("AE", List.of("UAE")),
            Map.entry("RU", List.of("Russia")),
            Map.entry("LA", List.of("Laos")),
            Map.entry("SY", List.of("Syria")),
            Map.entry("IR", List.of("Iran")),
            Map.entry("TL", List.of("East Timor")),
            Map.entry("CD", List.of("Congo", "DR Congo", "DRC")),
            Map.entry("CG", List.of("Congo")),
            Map.entry("BA", List.of("Bosnia and Herzegovina", "Bosnia")),
            Map.entry("NL", List.of("Holland")),
            Map.entry("VN", List.of("Vietnam")),
            Map.entry("TZ", List.of("Tanzania")),
            Map.entry("MD", List.of("Moldova")),
            Map.entry("BO", List.of("Bolivia")),
            Map.entry("VE", List.of("Venezuela")),
            Map.entry("FM", List.of("Micronesia")),
            Map.entry("ST", List.of("Sao Tome and Principe")),
            Map.entry("CW", List.of("Curacao")),
            Map.entry("RE", List.of("Reunion")),
            Map.entry("AX", List.of("Aland Islands")));

    private static final List<Country> ALL = CODES.stream()
            .map(code -> new Country(code, Locale.of("", code).getDisplayCountry(Locale.US), aliasesOf(code)))
            .sorted(Comparator.comparing(Country::name))
            .toList();

    private static final ObjectMapper JSON = new ObjectMapper();

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

    /**
     * The stored form of a requested list of codes: each one {@link #normalize normalized}, those that are no real
     * code dropped, a repeat kept once, the order as given.
     */
    public static List<String> normalizeAll(final List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        return codes.stream().map(IsoCountries::normalize).filter(Objects::nonNull).distinct().toList();
    }

    /**
     * The extra English names a search finds this country by ("Turkey" for {@code TR}); none for most countries,
     * and for no or an unknown code.
     */
    public static List<String> aliasesOf(final String code) {
        final String known = normalize(code);
        return known == null ? List.of() : ALIASES.getOrDefault(known, List.of());
    }

    /**
     * The aliases as the trip editor's Countries filter reads them, as JSON: each SHOWN name that has aliases,
     * mapped to them. Keyed by the shown name because a PrimeFaces filter is handed a row's label, not its value.
     */
    public static String aliasesByNameJson() {
        final Map<String, List<String>> byName = new LinkedHashMap<>();
        ALL.stream().filter(country -> !country.aliases().isEmpty())
                .forEach(country -> byName.put(country.name(), country.aliases()));
        try {
            return JSON.writeValueAsString(byName);
        } catch (final JsonProcessingException ex) {
            throw new IllegalStateException("A map of strings always serializes", ex);
        }
    }

    /** The English name of a code ({@code BA} is "Bosnia and Herzegovina"), or "" for no or an unknown code. */
    public static String nameOf(final String code) {
        final String known = normalize(code);
        return known == null ? "" : Locale.of("", known).getDisplayCountry(Locale.US);
    }
}
