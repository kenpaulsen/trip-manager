package org.paulsens.trip.site;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.paulsens.trip.cache.Cached;
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
 * Reads go through the cached org lookup only (a public render path).
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

    ListingScope(final SiteContext site, final List<String> includeOrgs,
            final Function<Organization.Id, Optional<Organization>> orgs) {
        this.site = site;
        this.includeOrgs = includeOrgs == null ? List.of() : includeOrgs;
        this.orgs = orgs;
    }

    /** The rule for the current request's site and a section's curation list (null/empty = no list). */
    public static ListingScope forSite(final List<String> includeOrgs) {
        return new ListingScope(SiteContext.current(), includeOrgs, ListingScope::cachedOrg);
    }

    /** The rule for the current request's site without any curation list (menus, sidebar, countdowns). */
    public static ListingScope forSite() {
        return forSite(List.of());
    }

    /** The rule for the current request's site and a section instance's stored curation property. */
    public static ListingScope forInstance(final Map<String, String> instanceValues) {
        return forSite(parseIds(instanceValues == null ? null : instanceValues.get(INCLUDE_ORGS_PROPERTY)));
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
