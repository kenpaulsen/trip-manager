package org.paulsens.trip.model;

import java.util.Locale;

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
