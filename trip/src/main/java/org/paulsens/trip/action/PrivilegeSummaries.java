package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;

/**
 * The read-only "who holds what" column of the admin People page: each listed person's site-wide privileges and,
 * per organization, whether they are its admin and which org privileges they hold there.
 *
 * <p>Built in one pass per request from whole partitions -- the global privilege partition once, and each
 * listed organization's row and privilege partition once -- never from per-row {@code check} calls, which on a
 * page of 60 people would be well over a thousand cache reads.</p>
 *
 * <p>Tenancy: the site row is filled only for a site admin, and an organization is described only when the
 * caller may view that organization's people. An org {@code peopleAdmin} therefore never learns another
 * tenant's name or grants from this column.</p>
 */
public final class PrivilegeSummaries {
    /** Display names for the global bases; org bases use {@link OrgCommands#orgPrivName}. Others show raw. */
    private static final Map<String, String> GLOBAL_NAMES = Map.of(
            PrivilegeCommands.PRIVILEGE_ADMIN, "Privilege Admin",
            PrivilegeCommands.CONFIG_ADMIN, "Settings Admin",
            PrivilegeCommands.AUDIT_ADMIN, "Audit Viewer",
            PrivilegeCommands.SITE_DEPLOYER, "Site Deployer",
            PrivilegeCommands.CONTENT_ADMIN, "Site Content Editor",
            PrivilegeCommands.MEDIA_ADMIN, "Site Media Editor",
            PrivilegeCommands.EVENT_ADMIN, "Events Editor",
            PrivilegeCommands.LODGING_ADMIN, "Lodging Admin",
            PrivilegeCommands.VIEW_FINANCES, "View Finances");

    /**
     * One person's privileges as the People page shows them.
     *
     * @param site  display names of the site-wide privileges held (empty unless the caller is a site admin)
     * @param orgs  one entry per organization the caller may see that the person is a member of, by org name
     */
    public record PrivSummary(List<String> site, List<OrgPrivs> orgs) {
        static final PrivSummary EMPTY = new PrivSummary(List.of(), List.of());

        public PrivSummary {
            site = List.copyOf(site);
            orgs = List.copyOf(orgs);
        }

        /** True when there is nothing to show (the page renders a dash). */
        public boolean isEmpty() {
            return site.isEmpty() && orgs.stream().allMatch(OrgPrivs::isEmpty);
        }
    }

    /**
     * One person's standing in one organization.
     *
     * @param orgName   the organization's name
     * @param orgAdmin  true when the person is one of its admins
     * @param privs     display names of the org-scoped privileges held there
     */
    public record OrgPrivs(String orgName, boolean orgAdmin, List<String> privs) {
        public OrgPrivs {
            privs = List.copyOf(privs);
        }

        /** True when the person is a plain member here: no admin, no privileges. */
        public boolean isEmpty() {
            return !orgAdmin && privs.isEmpty();
        }
    }

    private final boolean showSite;
    private final Predicate<String> mayViewOrg;
    /** Global privileges, base name to holders; empty when the site row is hidden. */
    private final Map<String, List<Person.Id>> global;
    /** Per visible org id: the org row and its org-scoped privileges (base name to holders). */
    private final Map<String, OrgView> orgs = new HashMap<>();

    private record OrgView(Organization org, Map<String, List<Person.Id>> privs) {
    }

    private PrivilegeSummaries(final boolean showSite, final Predicate<String> mayViewOrg) {
        this.showSite = showSite;
        this.mayViewOrg = mayViewOrg;
        this.global = showSite ? holders(DAO.getInstance().getGlobalPrivileges(Cached.YES)) : Map.of();
    }

    /**
     * The summaries for {@code people}, keyed by person id value (the page looks rows up by
     * {@code person.id.value}). {@code showSite} is "the caller is a site admin"; {@code mayViewOrg} answers
     * whether the caller may see one organization's people.
     */
    static Map<String, PrivSummary> of(final List<Person> people, final boolean showSite,
            final Predicate<String> mayViewOrg) {
        final Map<String, PrivSummary> result = new HashMap<>();
        if (people == null || people.isEmpty()) {
            return result;
        }
        final PrivilegeSummaries summaries = new PrivilegeSummaries(showSite, mayViewOrg);
        people.stream().filter(Objects::nonNull)
                .forEach(person -> result.put(person.getId().getValue(), summaries.summarize(person)));
        return result;
    }

    private PrivSummary summarize(final Person person) {
        final List<String> site = showSite ? namesHeld(global, person.getId(), GLOBAL_NAMES::get) : List.of();
        final List<OrgPrivs> orgPrivs = person.getOrgIds().stream()
                .map(orgId -> orgView(orgId.getValue()))
                .filter(Objects::nonNull)
                .map(view -> orgPrivs(view, person.getId()))
                .sorted(Comparator.comparing(OrgPrivs::orgName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new PrivSummary(site, orgPrivs);
    }

    private OrgPrivs orgPrivs(final OrgView view, final Person.Id personId) {
        return new OrgPrivs(view.org().getName(), view.org().isAdmin(personId),
                namesHeld(view.privs(), personId, OrgCommands::orgPrivName));
    }

    /** The org's view, read once per request; null when hidden from the caller or unknown. */
    private OrgView orgView(final String orgId) {
        if (!orgs.containsKey(orgId)) {
            orgs.put(orgId, loadOrgView(orgId));
        }
        return orgs.get(orgId);
    }

    private OrgView loadOrgView(final String orgId) {
        if (!mayViewOrg.test(orgId)) {
            return null;
        }
        final Organization org = DAO.getInstance()
                .getOrganization(Organization.Id.from(orgId), Cached.YES).orElse(null);
        if (org == null) {
            return null;
        }
        final Map<String, List<Person.Id>> privs = holders(DAO.getInstance().getTripPrivileges(orgId, Cached.YES));
        privs.keySet().retainAll(PrivilegeCommands.ORG_SCOPED_BASES);
        return new OrgView(org, privs);
    }

    /** Base name to holders, in base-name order so a person's chips read the same on every row. */
    private static Map<String, List<Person.Id>> holders(final List<Privilege> privileges) {
        final Map<String, List<Person.Id>> result = new LinkedHashMap<>();
        privileges.stream()
                .sorted(Comparator.comparing(Privilege::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(priv -> result.put(priv.getName(), new ArrayList<>(priv.getPeople())));
        return result;
    }

    private static List<String> namesHeld(final Map<String, List<Person.Id>> holders, final Person.Id personId,
            final Function<String, String> displayName) {
        return holders.entrySet().stream()
                .filter(entry -> entry.getValue().contains(personId))
                .map(entry -> Objects.requireNonNullElse(displayName.apply(entry.getKey()), entry.getKey()))
                .toList();
    }
}
