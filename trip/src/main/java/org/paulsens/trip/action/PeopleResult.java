package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.model.Person;

/**
 * One people list as a page renders it ({@link PersonCommands#listOrSearch}): the rows, in display order and
 * capped; {@code needsQuery} when there was no query and too many candidates to list them all; {@code truncated}
 * when more people matched than the rows hold; {@code tooShort} when the query was refused as too short to
 * search ({@link org.paulsens.trip.dynamo.PersonMatch#tooShort}). Request-scoped only: it holds Person objects,
 * which never belong in a view or a session.
 *
 * @param rows        the people to render (never null), a private MUTABLE copy: PrimeFaces sorts a sortable
 *                    table's value IN PLACE, so an immutable list would 500 the page the day someone adds a
 *                    {@code sortBy} (the same stance as {@link ChatRoster#view} and {@code getPeopleByIds})
 * @param needsQuery  true when the page should ask for a search instead of listing anyone
 * @param truncated   true when more people matched than {@code rows} holds
 * @param tooShort    true when the query is too short to search, so the page asks for more characters
 */
public record PeopleResult(List<Person> rows, boolean needsQuery, boolean truncated, boolean tooShort) {
    /** Nothing to list and nothing to ask for: no candidates at all. */
    public static final PeopleResult NONE = new PeopleResult(List.of(), false, false);
    /** Too many candidates to list without a query. */
    public static final PeopleResult NEEDS_QUERY = new PeopleResult(List.of(), true, false);
    /** A query too short to search: nobody, and the page asks for at least two characters. */
    public static final PeopleResult TOO_SHORT = new PeopleResult(List.of(), false, false, true);

    public PeopleResult {
        rows = new ArrayList<>(rows);
    }

    /** A result for a query that was long enough to search (or absent). */
    public PeopleResult(final List<Person> rows, final boolean needsQuery, final boolean truncated) {
        this(rows, needsQuery, truncated, false);
    }

    /** {@code matched} cut to its first {@code limit} rows, flagged {@code truncated} when it was longer. */
    static PeopleResult capped(final List<Person> matched, final int limit) {
        return matched.size() > limit
                ? new PeopleResult(matched.subList(0, limit), false, true)
                : new PeopleResult(matched, false, false);
    }
}
