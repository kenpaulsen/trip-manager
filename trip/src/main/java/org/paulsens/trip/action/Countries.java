package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The country list behind the address autocompletes. A fixed list rather than a lookup: it only has to cover
 * where this site's trips go and where its people live, and the field saves free text anyway, so a country
 * missing from it costs nothing.
 */
final class Countries {

    private Countries() {
    }

    static final List<String> ALL = List.of("Argentina", "Australia", "Austria", "Belgium",
            "Bosnia and Herzegovina", "Brazil", "Canada", "Chile", "Colombia", "Croatia", "Czechia", "Denmark",
            "Egypt", "Fiji", "Finland", "France", "Germany", "Greece", "Guatemala", "Hungary", "India", "Indonesia",
            "Ireland", "Israel", "Italy", "Japan", "Jordan", "Kenya", "Lebanon", "Lithuania", "Malta", "Mexico",
            "Montenegro", "Morocco", "Netherlands", "New Zealand", "Nicaragua", "Norway", "Palestine", "Peru",
            "Philippines", "Poland", "Portugal", "Serbia", "Slovakia", "Slovenia", "South Africa", "South Korea",
            "Spain", "Sweden", "Switzerland", "Tanzania", "Thailand", "Turkey", "Uganda", "Ukraine",
            "United Arab Emirates", "United Kingdom", "United States", "Vatican City", "Vietnam");

    /** The country autocomplete: any country containing the query (case-insensitive); free text still saves. */
    static List<String> suggest(final String query) {
        final String q = LodgingCommands.nullSafe(query).trim().toLowerCase(Locale.ROOT);
        final List<String> matches = new ArrayList<>();
        for (final String country : ALL) {
            if (country.toLowerCase(Locale.ROOT).contains(q)) {
                matches.add(country);
            }
        }
        return matches;
    }
}
