package org.paulsens.trip.action;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.paulsens.trip.model.Organization;

/**
 * The organizations whose people data the signed-in viewer may see: THE rule for which of a person's trips and
 * ledger rows the admin people pages show (the People page's Trips column, Manage People, the admin person page's
 * Registrations, Trips and Transactions). A site admin sees everything, rows with no organization included; anyone
 * else only the organizations they manage or hold {@code peopleAdmin} in ({@link OrgCommands#canViewOrgPeople}),
 * and never a row with no organization.
 *
 * <p>Not {@code Person.orgIds}: membership is self-grantable (choosing a default organization in the app,
 * registering for one of its trips), so a people admin of one organization who joined another as a plain member
 * read that organization's trips and balances (owner decision 2026-11-14). Computed once per call from the small,
 * cached organization list rather than asked per row.
 */
final class PeopleReach {

    private static final PeopleReach EVERYTHING = new PeopleReach(true, Set.of());

    private final boolean everything;
    private final Set<String> orgIds;

    private PeopleReach(final boolean everything, final Set<String> orgIds) {
        this.everything = everything;
        this.orgIds = orgIds;
    }

    /** The reach of {@code org}'s caller: everything for a site admin, nothing for nobody. */
    static PeopleReach of(final OrgCommands org) {
        if (org.isSiteAdmin()) {
            return EVERYTHING;
        }
        return new PeopleReach(false, org.getOrganizations().stream()
                .map(Organization::getId)
                .map(Organization.Id::getValue)
                .filter(org::canViewOrgPeople)
                .collect(Collectors.toUnmodifiableSet()));
    }

    /** A site admin's reach: every row, whatever its organization. */
    boolean isEverything() {
        return everything;
    }

    /** No organization at all: every filter answers empty. */
    boolean isNone() {
        return !everything && orgIds.isEmpty();
    }

    /** Whether a row of this organization is visible; a row with no organization is a site admin's only. */
    boolean admits(final String orgId) {
        return everything || (orgId != null && orgIds.contains(orgId));
    }

    /** The {@code rows} this reach admits, in their order, by the organization {@code orgOf} reads off each. */
    <T> List<T> filter(final List<T> rows, final Function<T, String> orgOf) {
        return everything ? rows : rows.stream().filter(row -> admits(orgOf.apply(row))).toList();
    }
}
