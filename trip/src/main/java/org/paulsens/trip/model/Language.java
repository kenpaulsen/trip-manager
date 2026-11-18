package org.paulsens.trip.model;

import java.util.Locale;
import java.util.Optional;

/**
 * The language a pilgrimage is conducted in. Serialized by name -- never rename a constant without a data
 * migration. Declaration order is display order (English first, the site default); pages that group trips by
 * language iterate {@code values()} so a new constant added here appears on the site automatically.
 */
public enum Language {
    English("English", Locale.ENGLISH, "English Medjugorje Pilgrimages"),
    Spanish("Español", Locale.forLanguageTag("es"), "Peregrinaciones españolas a Medjugorje");

    /** The language's name in that language -- used for grouping labels. */
    private final String displayName;
    private final Locale locale;
    /** The landing page's section heading, phrased in the language itself. */
    private final String sectionHeading;

    Language(final String displayName, final Locale locale, final String sectionHeading) {
        this.displayName = displayName;
        this.locale = locale;
        this.sectionHeading = sectionHeading;
    }

    /**
     * The constant a stored {@link #name()} names, or empty for null and for a name this build does not know (a
     * row written by a newer build). The one by-name lookup for code that holds the language as text, so a new
     * constant reaches it with no second list of names to keep.
     */
    public static Optional<Language> named(final String name) {
        for (final Language language : values()) {
            if (language.name().equals(name)) {
                return Optional.of(language);
            }
        }
        return Optional.empty();
    }

    public String getDisplayName() {
        return displayName;
    }

    public Locale getLocale() {
        return locale;
    }

    public String getSectionHeading() {
        return sectionHeading;
    }
}
