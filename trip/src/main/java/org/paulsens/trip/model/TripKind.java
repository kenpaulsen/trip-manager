package org.paulsens.trip.model;

/**
 * What kind of event a {@link Trip} is. A conference is a trip, not a separate entity: it reuses the trip's
 * roles, registration, payments, reports, chat and lodging. The kind changes only copy and a few defaults
 * (see {@code docs/conference.md}). Serialized by name, never rename a constant without a data migration; a
 * trip stored before this field existed has none and reads as {@link #PILGRIMAGE}.
 */
public enum TripKind {
    PILGRIMAGE("Pilgrimage", "Join Pilgrimage", "travelers"),
    CONFERENCE("Conference", "Register", "attendees");

    private final String displayValue;
    private final String registerLabel;
    private final String peopleNoun;

    TripKind(final String displayValue, final String registerLabel, final String peopleNoun) {
        this.displayValue = displayValue;
        this.registerLabel = registerLabel;
        this.peopleNoun = peopleNoun;
    }

    public String getDisplayValue() {
        return displayValue;
    }

    /** The call to action on a public trip page: "Join Pilgrimage" or "Register". */
    public String getRegisterLabel() {
        return registerLabel;
    }

    /** The lower-case plural for the people who go: "travelers" or "attendees". */
    public String getPeopleNoun() {
        return peopleNoun;
    }

    /** {@link #getPeopleNoun()} capitalized, for button labels: "Travelers" or "Attendees". */
    public String getPeopleNounTitle() {
        return Character.toUpperCase(peopleNoun.charAt(0)) + peopleNoun.substring(1);
    }
}
