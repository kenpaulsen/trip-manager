package org.paulsens.trip.action;

import jakarta.faces.context.FacesContext;
import java.util.List;
import java.util.Map;
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
 * read that organization's trips and balances (owner decision 2026-11-14).
 *
 * <p>Built once per request: {@link #of} memoizes it in the Faces request map, keyed by the viewer, because the
 * People page asks for every row and each build walks every organization (60 rows on a platform of 100
 * organizations was 60 builds and about 6,000 lookups per render). Keyed by the viewer, not just the request, so
 * a View As switch mid-request never reads the other identity's reach. Off a Faces request (a unit test) it is
 * built per call.
 */
final class PeopleReach {

    private static final PeopleReach EVERYTHING = new PeopleReach(true, Set.of());
    static final String MEMO_PREFIX = "peopleReach:";

    private final boolean everything;
    private final Set<String> orgIds;

    private PeopleReach(final boolean everything, final Set<String> orgIds) {
        this.everything = everything;
        this.orgIds = orgIds;
    }

    /** The reach of {@code org}'s caller: everything for a site admin, nothing for nobody. Memoized per request. */
    static PeopleReach of(final OrgCommands org) {
        final Caller caller = org.caller();
        if (caller.isSiteAdmin()) {
            return EVERYTHING;
        }
        final FacesContext ctx = FacesContext.getCurrentInstance();
        final Map<String, Object> memo = ctx == null ? null : ctx.getExternalContext().getRequestMap();
        final String key = MEMO_PREFIX + caller.personId();
        if (memo != null && memo.get(key) instanceof PeopleReach cached) {
            return cached;
        }
        final PeopleReach reach = build(org);
        if (memo != null) {
            memo.put(key, reach);
        }
        return reach;
    }

    private static PeopleReach build(final OrgCommands org) {
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
