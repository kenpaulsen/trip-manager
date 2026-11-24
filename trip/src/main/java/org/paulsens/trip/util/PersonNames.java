package org.paulsens.trip.util;

import org.paulsens.trip.model.Person;

/**
 * A person's display name, "Preferred Last", for a page, a mail or a report.
 *
 * <p>The ONE spelling. A dozen sites joined {@code getPreferredName() + " " + getLast()} by hand, and a person
 * with no last name (a lodging contact, an imported row) read "Ann null" on every one of them.
 */
public final class PersonNames {
    private PersonNames() {
    }

    /** "Preferred Last", a null or blank part dropped; {@code ""} for a null or nameless person. Never null. */
    public static String display(final Person person) {
        if (person == null) {
            return "";
        }
        return (part(person.getPreferredName()) + " " + part(person.getLast())).trim();
    }

    /** {@link #display}, or {@code fallback} (an id, "Someone") when there is no person or no name to show. */
    public static String displayOr(final Person person, final String fallback) {
        final String name = display(person);
        return name.isEmpty() ? fallback : name;
    }

    private static String part(final String value) {
        return (value == null || value.isBlank()) ? "" : value;
    }
}
