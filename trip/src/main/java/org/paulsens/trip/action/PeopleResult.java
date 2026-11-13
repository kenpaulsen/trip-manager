package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.model.Person;

/**
 * One people list as a page renders it ({@link PersonCommands#listOrSearch}): the rows, in display order and
 * capped; {@code needsQuery} when there was no query and too many candidates to list them all; {@code truncated}
 * when more people matched than the rows hold. Request-scoped only: it holds Person objects, which never belong
 * in a view or a session.
 *
 * @param rows        the people to render, immutable (never null). Immutable is safe only because no table
 *                    rendering it has a {@code sortBy}: PrimeFaces sorts a sortable table's value IN PLACE
 * @param needsQuery  true when the page should ask for a search instead of listing anyone
 * @param truncated   true when more people matched than {@code rows} holds
 */
public record PeopleResult(List<Person> rows, boolean needsQuery, boolean truncated) {
    /** Nothing to list and nothing to ask for: no candidates at all. */
    public static final PeopleResult NONE = new PeopleResult(List.of(), false, false);
    /** Too many candidates to list without a query. */
    public static final PeopleResult NEEDS_QUERY = new PeopleResult(List.of(), true, false);

    public PeopleResult {
        rows = List.copyOf(rows);
    }

    /** {@code matched} cut to its first {@code limit} rows, flagged {@code truncated} when it was longer. */
    static PeopleResult capped(final List<Person> matched, final int limit) {
        return matched.size() > limit
                ? new PeopleResult(matched.subList(0, limit), false, true)
                : new PeopleResult(matched, false, false);
    }
}
