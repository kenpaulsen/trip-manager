package org.paulsens.trip.site;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Organization;

/**
 * THE rule for whether an organization's public content (a trip, an album) is listed on the site a request
 * is for. Every public listing -- landing-page sections, the trip menu, the sidebar, the countdown cards --
 * asks this one question, so the two directions of tenant isolation live in one place:
 * <ul>
 *   <li><b>An organization's own site lists only its own content.</b> Not an option, a property of the
 *       site: an org-less (legacy) trip belongs to nobody and does not show there either.</li>
 *   <li><b>A shared site shows an org's content only when BOTH sides agree.</b> The SITE side is the
 *       section's curation list ({@code includeOrgs}, the org ids a site admin picked); with no list, a
 *       shared site shows the orgs that have NO site of their own (today's tenants), so a newly-hosted
 *       org is kept off the shared sites until a site admin adds it -- with no data migration. The ORG
 *       side is {@link Organization#allowsSharedSites()}: an org that opted out never appears, whatever
 *       the site picked. Org-less content stays listed, as it always was.</li>
 * </ul>
 * {@link #reaches} is the same rule asked of a single PAGE rather than a listing (a trip's contacts page, a
 * chat, a ledger row): what a site does not list, it does not serve either. Reads go through the cached org
 * lookup only (a public render path).
 */
public final class ListingScope {

    /**
     * The values-property on a shared site's programmatic sections (pilgrimages, photo albums) holding the
     * curated org ids, comma-separated; absent or blank = the no-list default described above.
     */
    public static final String INCLUDE_ORGS_PROPERTY = "includeOrgs";

    private final SiteContext site;
    private final List<String> includeOrgs;
    private final Function<Organization.Id, Optional<Organization>> orgs;
    private final Supplier<List<String>> siteCuration;

    ListingScope(final SiteContext site, final List<String> includeOrgs,
            final Function<Organization.Id, Optional<Organization>> orgs) {
        this(site, includeOrgs, orgs, List::of);
    }

    ListingScope(final SiteContext site, final List<String> includeOrgs,
            final Function<Organization.Id, Optional<Organization>> orgs,
            final Supplier<List<String>> siteCuration) {
        this.site = site;
        this.includeOrgs = includeOrgs == null ? List.of() : includeOrgs;
        this.orgs = orgs;
        this.siteCuration = siteCuration;
    }

    /** The rule for the current request's site and a section's curation list (null/empty = no list). */
    public static ListingScope forSite(final List<String> includeOrgs) {
        final SiteContext site = SiteContext.current();
        return new ListingScope(site, includeOrgs, ListingScope::cachedOrg, () -> curatedOnPageOf(site));
    }

    /** The rule for the current request's site without any curation list (menus, sidebar, countdowns). */
    public static ListingScope forSite() {
        return forSite(List.of());
    }

    /** The rule for the current request's site and a section instance's stored curation property. */
    public static ListingScope forInstance(final Map<String, String> instanceValues) {
        return forSite(parseIds(instanceValues == null ? null : instanceValues.get(INCLUDE_ORGS_PROPERTY)));
    }

    /** {@link #reaches} for the current request's site: the one-liner the read paths gate on. */
    public static boolean reachable(final String ownerOrgId) {
        return forSite().reaches(ownerOrgId);
    }

    /** The stored comma-separated org ids as a list; null/blank = no list (the MULTI_CHOICE stored form). */
    public static List<String> parseIds(final String stored) {
        return ContentInstance.splitList(stored);
    }

    /** Whether content owned by {@code ownerOrgId} (null = org-less legacy content) lists on this site. */
    public boolean shows(final String ownerOrgId) {
        if (site.isOrg()) {
            return site.isSiteOf(ownerOrgId);
        }
        if (ownerOrgId == null || ownerOrgId.isBlank()) {
            return true;
        }
        final Organization owner = orgs.apply(Organization.Id.from(ownerOrgId)).orElse(null);
        if (owner == null) {
            return includeOrgs.contains(ownerOrgId);    // unknown org: only an explicit pick lists it
        }
        if (!owner.allowsSharedSites()) {
            return false;
        }
        return includeOrgs.isEmpty() ? !hasOwnSite(owner) : includeOrgs.contains(ownerOrgId);
    }

    /**
     * Whether a PAGE about something owned by {@code ownerOrgId} -- a trip and everything hanging off it
     * (contacts, itinerary, registration, chat, its ledger rows and payments) -- may be served on this
     * site: {@code SiteContext.admits(org) && shows(org)}, so an org's own site reaches only its own, and
     * a shared site reaches a hosted org only while that shared page's sections curate it (any section's
     * {@code includeOrgs} pick, the org still allowing shared sites); orgs without a site of their own and
     * org-less legacy content stay reachable on the shared sites as they always were. What the site does
     * not reach behaves exactly like something that does not exist (a blank bean answer, a REST 404).
     *
     * <p>Off a bound request ({@link SiteContext#isBound()} false: the system context of mail, digests and
     * schedulers, and unit tests) everything is reachable -- there is no host to draw a boundary from.
     */
    public boolean reaches(final String ownerOrgId) {
        if (!site.isBound()) {
            return true;
        }
        if (!site.admits(ownerOrgId)) {
            return false;
        }
        if (shows(ownerOrgId)) {
            return true;
        }
        // Only a non-org site with a real owner org gets here: an org site's admits() IS its shows(), and
        // org-less content shows on every other site.
        return new ListingScope(site, siteCuration.get(), orgs, List::of).shows(ownerOrgId);
    }

    /**
     * Every org id any section of this site's home page curates ({@link #INCLUDE_ORGS_PROPERTY}, top-level
     * instances and one level of container children), deduplicated: the SITE side of the double gate for
     * a page reached by link rather than by listing. Read from the cached content rows; a read failure
     * curates nothing (a public path never errors).
     */
    static List<String> curatedOnPageOf(final SiteContext site) {
        final Set<String> curated = new LinkedHashSet<>();
        try {
            final DAO dao = DAO.getInstance();
            for (final ContentInstance section : dao.getContentForSection(OrgPageBootstrap.pageKeyOf(site),
                    Cached.YES)) {
                curated.addAll(parseIds(section.getValues().get(INCLUDE_ORGS_PROPERTY)));
                for (final ContentInstance child : dao.getContentForSection(section.getId(), Cached.YES)) {
                    curated.addAll(parseIds(child.getValues().get(INCLUDE_ORGS_PROPERTY)));
                }
            }
        } catch (final RuntimeException ex) {
            return List.of();
        }
        return List.copyOf(curated);
    }

    private static boolean hasOwnSite(final Organization org) {
        return org.getSlug() != null && !org.getSlug().isBlank();
    }

    private static Optional<Organization> cachedOrg(final Organization.Id id) {
        try {
            return DAO.getInstance().getOrganization(id, Cached.YES);
        } catch (final RuntimeException ex) {
            return Optional.empty();
        }
    }
}
