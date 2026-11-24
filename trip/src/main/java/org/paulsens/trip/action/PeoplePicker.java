package org.paulsens.trip.action;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.Util;

/**
 * What a people autocomplete answers from a roster its bean has ALREADY scoped: the content dialog's person
 * prompt ({@link ContentCommands#completeForContent}) and the trip editor's Add Manager picker
 * ({@link OrgCommands#completeTripManagerCandidates}) filtered and ordered their organization's members with the
 * same lines.
 *
 * <p>Tenancy stays in the bean. This class reads no people and knows no organization: it only narrows the
 * list it is handed, so it can never widen what a caller may search. Whoever calls it decides, and tests, whose
 * roster that is.
 */
final class PeoplePicker {

    private PeoplePicker() {
    }

    /**
     * The people of {@code scopedRoster} whose names or email contain {@code query} (case-insensitive; a blank
     * query matches everyone), by last name then preferred name, at most {@code limit} of them.
     */
    static List<Person> pick(final Collection<Person> scopedRoster, final String query, final int limit) {
        final String needle = (query == null) ? "" : query.trim().toLowerCase(Locale.ROOT);
        return scopedRoster.stream()
                .filter(person -> matches(person, needle))
                .sorted(Comparator.comparing(PeoplePicker::sortKey))
                .limit(limit)
                .toList();
    }

    /** Case-insensitive substring over the names and email the picker's row label shows. */
    private static boolean matches(final Person person, final String lowerNeedle) {
        if (lowerNeedle.isEmpty()) {
            return true;
        }
        // Joined null-safe: a missing last name or email once put the word "null" into the text, so typing
        // "null" (or "nul") found everyone without one.
        final String haystack = String.join(" ", Util.orDefault(person.getPreferredName(), ""),
                Util.orDefault(person.getFirst(), ""), Util.orDefault(person.getLast(), ""),
                Util.orDefault(person.getEmail(), "")).toLowerCase(Locale.ROOT);
        return haystack.contains(lowerNeedle);
    }

    // Null-safe and trimmed: a missing last name once sorted as the word "null", among the N's. Such a person now
    // sorts by the name they have (the trimmed key starts with it), and someone with no name at all comes first.
    private static String sortKey(final Person person) {
        return (Util.trimmed(person.getLast()) + " " + Util.trimmed(person.getPreferredName())).trim()
                .toLowerCase(Locale.ROOT);
    }
}
