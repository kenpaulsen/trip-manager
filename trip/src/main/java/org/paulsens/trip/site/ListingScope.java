package org.paulsens.trip.site;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
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
import org.paulsens.trip.model.Trip;

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
    /** {@link #curated()}'s memo: a per-trip filter must read the page's curation once, not once per trip. */
    private ListingScope curatedScope;

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

    /**
     * THE rule for Discover, the one listing that crosses organizations on purpose (medjugorje#155): the apps'
     * signed-out browse and search, and the marketing host's open-trips list. A trip is discoverable when it is
     * shown on its homepage ({@code openToPublic}), it belongs to an organization that exists and still allows
     * shared sites ({@link Organization#allowsSharedSites()}; a value nobody ever set counts as allowed, as the
     * checkbox shows it), and it has not ended: its end date, else its start date, is {@code today} or later.
     *
     * <p>A trip with no organization (a legacy row) or one naming an organization that cannot be read is NOT
     * discoverable: there is no organization to consent, to name on the card, or to join. That is the reverse
     * of {@link #shows}, where org-less content keeps listing on the shared sites it always listed on. A trip
     * with no dates at all is not discoverable either: it can be neither sorted nor called upcoming.
     *
     * <p>Static and pure on purpose: it never asks which site the request is for
     * ({@code SiteContext.reachesEverything()} above all), so no host can widen it. A caller on an
     * organization's own host narrows the answer further with {@code SiteContext.admits}.
     */
    public static boolean discoverable(final Trip trip, final Optional<Organization> owner, final LocalDate today) {
        if (today == null || !sharedAcrossOrgs(trip, owner)) {
            return false;
        }
        final LocalDateTime last = trip.getEndDate() != null ? trip.getEndDate() : trip.getStartDate();
        return last != null && !last.toLocalDate().isBefore(today);
    }

    /**
     * The CONSENT half of {@link #discoverable}, with no word about dates: the trip is shown on its homepage and
     * its own organization exists and allows shared sites. What a public thing that OUTLIVES the trip asks (its
     * album, which fills as the trip ends): "not ended" is a rule about a list of upcoming trips, and letting it
     * decide the album too closed every open trip's album on the product's own host the day after the trip.
     */
    public static boolean sharedAcrossOrgs(final Trip trip, final Optional<Organization> owner) {
        if (trip == null || !Boolean.TRUE.equals(trip.getOpenToPublic())) {
            return false;
        }
        final String orgId = trip.getOrgId();
        if (orgId == null || orgId.isBlank() || owner == null || owner.isEmpty()) {
            return false;
        }
        final Organization org = owner.get();
        // The org handed in must be the trip's own: another organization's consent is not this one's.
        return org.getId() != null && orgId.equals(org.getId().getValue()) && org.allowsSharedSites();
    }

    /** {@link #discoverable} today, against the cached organization read every public listing uses. */
    public static boolean discoverableNow(final Trip trip) {
        return hasOrg(trip) && discoverable(trip, cachedOrg(Organization.Id.from(trip.getOrgId())), LocalDate.now());
    }

    /** {@link #sharedAcrossOrgs} against the cached organization read every public listing uses. */
    public static boolean sharedAcrossOrgsNow(final Trip trip) {
        return hasOrg(trip) && sharedAcrossOrgs(trip, cachedOrg(Organization.Id.from(trip.getOrgId())));
    }

    private static boolean hasOrg(final Trip trip) {
        return trip != null && trip.getOrgId() != null && !trip.getOrgId().isBlank();
    }

    /**
     * THE rule for a signed-in caller on the product's own host ({@code SiteContext.reachesEverything()}), where
     * {@link #shows} admits every organization: whether {@code trip}'s entry in the OPEN LISTING may be shown to
     * a caller who belongs to {@code callerOrgIds}. It may when it is not crossing an organization for them (a
     * legacy trip with none, or a trip of an organization they belong to), or when the trip may cross
     * organizations for anyone ({@link #discoverable}). A caller's own right to read the trip (its roster, a role
     * on it) is the resource's to add on top.
     *
     * <p>This and {@link #albumAcrossOrgs} are the two reads on that host, side by side so they cannot drift
     * apart: before 2026-11-17 each trusted the app to narrow, and an organization that had turned Shared sites
     * off was still handed to strangers there.
     */
    public static boolean listedAcrossOrgs(final Trip trip, final Collection<String> callerOrgIds) {
        return !crossesAnOrgFor(trip, callerOrgIds) || discoverableNow(trip);
    }

    /**
     * {@link #listedAcrossOrgs} for the trip's public ALBUM: the same question with the consent half of the rule
     * only ({@link #sharedAcrossOrgs}). A listing of trips to join drops a trip that has ended; its album does
     * not close that day, it is when the album fills.
     */
    public static boolean albumAcrossOrgs(final Trip trip, final Collection<String> callerOrgIds) {
        return !crossesAnOrgFor(trip, callerOrgIds) || sharedAcrossOrgsNow(trip);
    }

    /** Whether showing {@code trip} to a member of {@code callerOrgIds} crosses an organization at all. */
    private static boolean crossesAnOrgFor(final Trip trip, final Collection<String> callerOrgIds) {
        return hasOrg(trip) && !callerOrgIds.contains(trip.getOrgId());
    }

    /** The stored comma-separated org ids as a list; null/blank = no list (the MULTI_CHOICE stored form). */
    public static List<String> parseIds(final String stored) {
        return ContentInstance.splitList(stored);
    }

    /** Whether content owned by {@code ownerOrgId} (null = org-less legacy content) lists on this site. */
    public boolean shows(final String ownerOrgId) {
        if (site.reachesEverything()) {
            // The app on the product's own host: the marketing page's curation does not decide what a person
            // sees there, so this answers true for EVERY organization. It deliberately does not narrow: what
            // a caller may be shown across organizations depends on who the caller is, which a site cannot
            // know. Every API read on that host must therefore also ask listedAcrossOrgs (TripsResource's
            // open listing, MediaResource's album gate); the clients are not trusted to narrow.
            return true;
        }
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
     * The rule for the site-wide surfaces -- the Trips menu, the sidebar and countdown, the home albums, the
     * open-trips API: {@link #shows} without a section list, plus, on a SHARED site, a hosted org that any
     * section of the site's page curates ({@link #curatedOnPageOf}; the org still allowing shared sites). A
     * shared site that lists an org's trips in its sections lists them in its menus too (owner decision
     * 2026-11-13: visitqueenofpeace.com keeps Center for Peace West's trips after CFPW got its own site), while
     * a hosted org nobody curated stays off. An org site's answer is {@link #shows}: its own content only, and
     * so is a section's that carries its own pick list (that list is exhaustive).
     */
    public boolean listsSiteWide(final String ownerOrgId) {
        if (shows(ownerOrgId)) {
            return true;
        }
        if (!includeOrgs.isEmpty() || site.isOrg() || site.reachesEverything() || ownerOrgId == null
                || ownerOrgId.isBlank()) {
            return false;
        }
        return curated().shows(ownerOrgId);
    }

    /** This site with its page's whole curation as the section list (read once per scope instance). */
    private ListingScope curated() {
        if (curatedScope == null) {
            curatedScope = new ListingScope(site, siteCuration.get(), orgs, List::of);
        }
        return curatedScope;
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
        if (!site.isBound() || site.reachesEverything()) {
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
        return curated().shows(ownerOrgId);
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
