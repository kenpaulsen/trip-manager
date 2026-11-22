package org.paulsens.trip.model;

import java.util.Locale;
import java.util.Optional;

/**
 * What kind of event a {@link Trip} is. A conference is a trip, not a separate entity: it reuses the trip's
 * roles, registration, payments, reports, chat and lodging. The kind changes only copy and a few defaults
 * (see {@code docs/conference.md}). Serialized by name, never rename a constant without a data migration; a
 * trip stored before this field existed has none and reads as {@link #PILGRIMAGE}.
 */
public enum TripKind {
    PILGRIMAGE,
    CONFERENCE;

    /**
     * The constant a stored {@link #name()} names, or empty for null and for a name this build does not know (a
     * row written by a newer build), as {@code Language.named} does for languages.
     */
    public static Optional<TripKind> named(final String name) {
        for (final TripKind kind : values()) {
            if (kind.name().equals(name)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    public String getDisplayValue() {
        return EnumLabels.of(this);
    }

    /** {@link #getDisplayValue()} in {@code locale}: for a reader named outright (a Discover facet). */
    public String displayValueIn(final Locale locale) {
        return EnumLabels.of(this, locale);
    }

    /** The call to action on a public trip page: "Join Pilgrimage" or "Register". */
    public String getRegisterLabel() {
        return EnumLabels.of("labels.tripKind.register", this);
    }

    /**
     * {@link #getRegisterLabel()} in {@code locale}: the button text a seeded trip page STORES, which is written
     * in the page's own language, not in the language of whoever happened to create the trip.
     */
    public String registerLabelIn(final Locale locale) {
        return EnumLabels.ofIn(locale, "labels.tripKind.register", this);
    }

    /** The lower-case plural for the people who go: "travelers" or "attendees". */
    public String getPeopleNoun() {
        return EnumLabels.of("labels.tripKind.people", this);
    }

    /** {@link #getPeopleNoun()} capitalized, for button labels: "Travelers" or "Attendees". */
    public String getPeopleNounTitle() {
        final String noun = getPeopleNoun();
        return Character.toUpperCase(noun.charAt(0)) + noun.substring(1);
    }
}
