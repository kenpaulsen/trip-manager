package org.paulsens.trip.content;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.action.TripPageCommands;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.SitePage;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.util.Html;

/**
 * The page keys of the sites the content engine renders besides the classic shared landing page, and the
 * DEFAULT home page an organization's site starts with.
 *
 * <p>An org's page key carries its UUID, never its slug: a slug is a public label a site admin may rename,
 * and the page must survive that. The starter page is deliberately small ("less is more"): a welcome section
 * whose text is obviously a placeholder, the org's upcoming trips, and its pictures -- enough to show what
 * the page can do and land the org's editors on something that works, with nothing they would have to
 * delete first. Instance ids are minted UUIDs: the shared page's human-readable ids ({@code events},
 * {@code docs}) double as child-section keys and page anchors, so a second page could never reuse them.
 *
 * <p>Seeding runs through {@code OrgCommands.ensureHomePage} (the normal DAO save path, versions assigned
 * on write) exactly once per org -- the org row records when -- so an org that later empties its page is
 * never re-seeded behind its back.
 *
 * <p>Every other page of an org site is an Editable Page ({@link SitePage}, served by {@code page.xhtml}). A new site
 * also starts with the {@link #starterPages() starter Editable Pages} (About Us, Board of Directors, the metrics
 * dashboard), seeded once by {@code SitePageCommands.ensureStarterPages}. Their definition is the resource
 * {@value #STARTER_PAGES_RESOURCE}, which the private repo's backfill script for sites that predate them reads
 * too, so the two cannot drift.
 */
public final class OrgPageBootstrap {

    /**
     * The product's own (marketing) host page. Its sections are seeded by {@code MarketingPageBootstrap}
     * (the {@code bootstrap-marketing-page.sh} script in production, {@code FakeData} locally) and edited in
     * place from then on; until the script has run, the host shows a plain "coming soon" notice.
     */
    public static final String MARKETING_PAGE_KEY = "page:unitetrip-home";

    /** The name of a site's home page; every other named page ({@code board}, ...) is a secondary page. */
    public static final String HOME_PAGE = "home";

    /**
     * The name of an organization's "How to pay" page ({@code page:org:{uuid}:pay}, docs/org-admin.md, "The pay
     * page"). Not an Editable Page: {@code trip/pay.xhtml} serves it on any host that reaches the organization, so the
     * name is reserved ({@link #isReservedPageName}) and no Editable Page can take its content key.
     */
    public static final String PAY_PAGE = "pay";

    private static final String ORG_PAGE_PREFIX = "page:org:";
    private static final String SHARED_PAGE_PREFIX = "page:shared:";
    private static final String MARKETING_PAGE_PREFIX = "page:unitetrip:";
    /**
     * A TRIP's pages (its public details page, docs/conference.md): {@code page:trip:{tripUuid}:{pageName}}.
     * Keyed by the trip, never by the host, so the same trip page renders whichever site reaches the trip,
     * and its editors are derived from the trip (its org's content admins and its own trip managers).
     */
    private static final String TRIP_PAGE_PREFIX = "page:trip:";
    /** Trip ids are canonical UUIDs (FakeData's comment explains why); a key naming anything else is no key. */
    private static final Pattern TRIP_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /**
     * A page name is a key segment, never free text: lower-case so two spellings can never name two pages,
     * and colon-free so {@link #orgOf} can split an org key unambiguously.
     */
    private static final Pattern PAGE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    /**
     * The author every seeded row carries ({@link #isSeededPlaceholder}). Public so that a copy of a seeded
     * placeholder ({@code TripPageCommands.clonePage}) can keep it, and with it the "first save publishes" rule.
     */
    public static final String SEED_AUTHOR = "org-site-bootstrap";

    private OrgPageBootstrap() {
    }

    /**
     * Whether a stored row is still a seeded placeholder (docs/conference.md, "Placeholders start hidden"): written by
     * the bootstrap and never saved by an editor since (its author is still {@link #SEED_AUTHOR}), and carrying the
     * passed "Show until" that only {@link #tripRows} gives a row (the org and marketing seeds never set one). No
     * flag of its own: the first edit publishes it by clearing that date ({@code ContentCommands.saveContent}), and
     * the rows a script hid on an older page match exactly the same way.
     */
    public static boolean isSeededPlaceholder(final ContentInstance row) {
        return row != null && SEED_AUTHOR.equals(row.getModifiedBy()) && row.getEventDate() != null;
    }

    /** The section key of an organization's home page: {@code page:org:{uuid}:home}. */
    public static String pageKey(final Organization.Id orgId) {
        return pageKey(orgId, HOME_PAGE);
    }

    /** The section key of an organization's named page: {@code page:org:{uuid}:{pageName}}. */
    public static String pageKey(final Organization.Id orgId, final String pageName) {
        return ORG_PAGE_PREFIX + orgId.getValue() + ":" + requirePageName(pageName);
    }

    /** Whether {@code name} may name a page: a short lower-case slug ({@code board}, {@code our-team}). */
    public static boolean isPageName(final String name) {
        return name != null && PAGE_NAME.matcher(name).matches();
    }

    /**
     * Whether {@code name} is a page name the app keeps for itself, which an Editable Page may therefore not take: the
     * home page's and the pay page's. Both are well-formed names ({@link #isPageName}); they are just not free.
     */
    public static boolean isReservedPageName(final String name) {
        return HOME_PAGE.equals(name) || PAY_PAGE.equals(name);
    }

    /** The section key of an organization's "How to pay" page: {@code page:org:{uuid}:pay}. */
    public static String payPageKey(final Organization.Id orgId) {
        return pageKey(orgId, PAY_PAGE);
    }

    private static String requirePageName(final String name) {
        if (!isPageName(name)) {
            throw new IllegalArgumentException("Not a page name: " + name);
        }
        return name;
    }

    /**
     * The home-page section key of a SITE: the org's own page on its host, the marketing page on the
     * product host, the classic shared page ({@code V2PageBootstrap.PAGE_KEY}) everywhere else -- the one
     * mapping {@code SiteCommands.getPageKey} and {@code ListingScope}'s curation read both use.
     */
    public static String pageKeyOf(final SiteContext site) {
        return pageKeyOf(site, HOME_PAGE);
    }

    /**
     * The section key of the SITE's page called {@code pageName}. The home page keeps the keys it has always
     * had (no stored row moves); every other page is namespaced by site the same way, so an organization's
     * {@code board} page and the shared site's are different pages an editor of one can never reach from
     * the other: {@code page:shared:board}, {@code page:org:{uuid}:board}. The product's own site is the
     * platform org's, so its named pages are {@code page:org:} keys too; {@code page:unitetrip:board} is only
     * the placeholder marketing host's (no platform org yet).
     */
    public static String pageKeyOf(final SiteContext site, final String pageName) {
        final String name = requirePageName(pageName);
        // The marketing HOME page first: the platform organization's site is an org site too, but its home page
        // is the script-seeded marketing page, keyed independently of whichever org row holds the slug. Its other
        // pages are its Editable Pages (Pricing, Why UniteTrip), keyed like every org's, which is where the Editable
        // Pages admin page seeds and deletes them.
        if (site.isMarketing() && HOME_PAGE.equals(name)) {
            return MARKETING_PAGE_KEY;
        }
        if (site.isOrg()) {
            return pageKey(site.orgId(), name);
        }
        if (site.isMarketing()) {
            return MARKETING_PAGE_PREFIX + name;
        }
        return HOME_PAGE.equals(name) ? V2PageBootstrap.PAGE_KEY : SHARED_PAGE_PREFIX + name;
    }

    /**
     * Whether {@code pageKey} is one of the shared site's pages: its home ({@code V2PageBootstrap.PAGE_KEY}) or a
     * named page ({@code page:shared:{name}}). A language version answers false: strip its mark first.
     */
    public static boolean isSharedPageKey(final String pageKey) {
        return V2PageBootstrap.PAGE_KEY.equals(pageKey) || pageKey != null && pageKey.startsWith(SHARED_PAGE_PREFIX)
                && isPageName(pageKey.substring(SHARED_PAGE_PREFIX.length()));
    }

    /** Whether {@code tripId} can carry a page key at all: a canonical UUID (every real trip's id). */
    public static boolean isTripId(final String tripId) {
        return tripId != null && TRIP_ID.matcher(tripId).matches();
    }

    /** The section key of a trip's main public page: {@code page:trip:{uuid}:home}. */
    public static String tripPageKey(final String tripId) {
        return tripPageKey(tripId, HOME_PAGE);
    }

    /** The section key of a trip's page called {@code pageName}; reserved for a conference's sub-pages. */
    public static String tripPageKey(final String tripId, final String pageName) {
        if (tripId == null || !TRIP_ID.matcher(tripId).matches()) {
            throw new IllegalArgumentException("Not a trip id: " + tripId);
        }
        return TRIP_PAGE_PREFIX + tripId + ":" + requirePageName(pageName);
    }

    /** The trip a trip-page key names, or null for any other key (org, shared and marketing pages, containers). */
    public static String tripOf(final String versionOrPageKey) {
        // A language version belongs to the same trip as its page (PageVersions); likewise in orgOf below.
        final String pageKey = PageVersions.baseOf(versionOrPageKey);
        if (pageKey == null || !pageKey.startsWith(TRIP_PAGE_PREFIX)) {
            return null;
        }
        final String rest = pageKey.substring(TRIP_PAGE_PREFIX.length());
        final int colon = rest.lastIndexOf(':');
        if (colon <= 0 || !isPageName(rest.substring(colon + 1))) {
            return null;
        }
        final String id = rest.substring(0, colon);
        return TRIP_ID.matcher(id).matches() ? id : null;
    }

    /**
     * Whether {@code pageKey} is the marketing home page, or a named page of the PLACEHOLDER marketing host
     * ({@code page:unitetrip:{name}}). The product site's own named pages (Pricing, Why UniteTrip) are the platform
     * org's Editable Pages, {@code page:org:{uuid}:{name}} keys, and answer false here: use {@link #orgOf} for them.
     */
    public static boolean isMarketingPageKey(final String versionOrPageKey) {
        final String pageKey = PageVersions.baseOf(versionOrPageKey);
        return MARKETING_PAGE_KEY.equals(pageKey)
                || pageKey != null && pageKey.startsWith(MARKETING_PAGE_PREFIX)
                        && isPageName(pageKey.substring(MARKETING_PAGE_PREFIX.length()));
    }

    /**
     * The org an org-page key names ({@code page:org:{uuid}:{pageName}}, any page), or null for any other key
     * (shared pages, marketing pages, containers).
     */
    public static Organization.Id orgOf(final String versionOrPageKey) {
        final String pageKey = PageVersions.baseOf(versionOrPageKey);
        if (pageKey == null || !pageKey.startsWith(ORG_PAGE_PREFIX)) {
            return null;
        }
        final String rest = pageKey.substring(ORG_PAGE_PREFIX.length());
        final int colon = rest.lastIndexOf(':');
        if (colon <= 0 || !isPageName(rest.substring(colon + 1))) {
            return null;
        }
        final String id = rest.substring(0, colon);
        // No colon (it would be a second key segment) and no version mark: a mark belongs after the page name
        // only, so one inside the id is not a key anyone writes and must name no organization.
        return id.isBlank() || id.indexOf(':') >= 0 || id.indexOf('@') >= 0 ? null : Organization.Id.from(id);
    }

    /**
     * The starter rows for {@code org}'s home page, unsaved (version 0; the save path assigns v1), each
     * pinned to the CURRENT version of its starter template as {@code templateVersion} reports it -- a
     * template edited since installation must not leave new pages rendering its first draft.
     */
    public static List<ContentInstance> rows(final Organization org,
            final ToIntFunction<String> templateVersion) {
        final String key = pageKey(org.getId());
        return List.of(
                row(key, "Welcome", StarterTemplates.TEXT_ONLY_ID, templateVersion, 0,
                        Map.of("body", welcomeBody(org.getName()))),
                // Every language: a fixed English filter left a Spanish-only org's home page empty (vyvm,
                // 2026-11-17), and an org is often seeded before it has a trip to infer a language from.
                row(key, "Upcoming Trips", StarterTemplates.PILGRIMAGES_ID, templateVersion, 1,
                        Map.of(PilgrimagesType.PROP_LANGUAGE, TripCommands.ANY_LANGUAGE,
                                PilgrimagesType.PROP_CFPW_ONLY, "false")),
                row(key, "Pictures", StarterTemplates.PHOTO_ALBUMS_ID, templateVersion, 2, Map.of()));
    }

    /**
     * The one row {@code org}'s "How to pay" page starts with, unsaved and pinned like {@link #rows}: a generic
     * block that sends the reader to the organization, since the app cannot know how an organization that takes
     * no payments here wants to be paid. Its id is DETERMINISTIC per org ({@link #payRowId}): the page is seeded
     * from a visitor's render, so two first visits at once must write the same row, not two.
     *
     * @param contactEmail the organization's usable contact address, or null to leave the address out
     */
    public static List<ContentInstance> payRows(final Organization org, final String contactEmail,
            final ToIntFunction<String> templateVersion) {
        return List.of(new ContentInstance(payRowId(org.getId()), payPageKey(org.getId()), "How to pay",
                StarterTemplates.TEXT_ONLY_ID, templateVersion.applyAsInt(StarterTemplates.TEXT_ONLY_ID),
                new HashMap<>(Map.of("body", payBody(org.getName(), contactEmail))), null, 0, 0, null,
                SEED_AUTHOR));
    }

    /** The template ids {@link #payRows} pins. */
    public static List<String> payRowTemplateIds() {
        return List.of(StarterTemplates.TEXT_ONLY_ID);
    }

    /**
     * The pay page's starter row id: the same organization always names the same row. Public for the local-mode
     * seed ({@code FakeData}), which writes the fake organization's instructions into that very row, as
     * {@code scripts/seed-pay-pages.sh} does for a real one.
     */
    public static String payRowId(final Organization.Id orgId) {
        return UUID.nameUUIDFromBytes(("pay-page:" + orgId.getValue() + ":how-to-pay")
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * The pay page's starter text: who is being paid as the heading, then whom to ask. The address sentence is
     * left out when the organization has no usable contact address, rather than naming the site's (the site is
     * not the payee). No colors and no styles: it renders inside the organization's theme.
     */
    public static String payBody(final String orgName, final String contactEmail) {
        final String name = escape(orgName);
        final String heading = "<h2>How to pay " + name + "</h2>";
        final String tail = " They will tell you which payment methods they accept and where to send your"
                + " payment.</p>";
        if (contactEmail == null || contactEmail.isBlank()) {
            return heading + "<p>To make a payment to " + name + ", please contact the organization." + tail;
        }
        final String email = escape(contactEmail.trim());
        return heading + "<p>To make a payment to " + name + ", please contact the organization at <a href=\"mailto:"
                + email + "\">" + email + "</a>." + tail;
    }

    /** The classpath resource holding the starter Editable Pages: one JSON list of {@link StarterPage}. */
    public static final String STARTER_PAGES_RESOURCE = "/content/starter-site-pages.json";

    /**
     * One starter Editable Page: its menu entry ({@code name} is its address and content key, {@code placement} a
     * {@link SitePage.Placement} name) and the rows its content starts with. {@code title} is English and
     * {@code titles} the menu title in other languages (tag to title, or null). Only the title is translated: the
     * content is seeded in English alone, since a seeded language version would keep outranking the English page
     * for its readers after the organization rewrote that page ({@code PageVersions.resolve} serves any version
     * with rows). {@code access} is who may see the page, a {@link SitePage.Access} name; absent means everyone.
     * {@code everySite} puts the page on the platform org's site too, which otherwise takes no starters (its pages
     * are the product's marketing pages): the metrics dashboard is about the servers, not about an audience.
     * {@code sidebar} is the page's own sidebar choice, a {@link SitePage.Sidebar} name; absent follows the
     * organization's default.
     */
    public record StarterPage(String name, String title, String placement, Map<String, String> titles,
            List<StarterRow> rows, String access, boolean everySite, String sidebar) {

        /**
         * The page's menu entry on a site whose default language is {@code siteDefault}. A {@link SitePage}'s
         * {@code title} is in the site's DEFAULT language and its {@code titles} in the others (the Editable Pages
         * dialog edits only those, and a save keeps only those), so on a Spanish-first site the Spanish title is
         * the entry's own and English joins the other languages. A default with no starter title keeps English. (The
         * marketing site's seed passes English.)
         */
        public SitePage entry(final Language siteDefault) {
            final Map<String, String> all = new LinkedHashMap<>();
            all.put(Language.English.getTag(), title);
            if (titles != null) {
                all.putAll(titles);
            }
            final String own = all.containsKey(siteDefault.getTag()) ? siteDefault.getTag() : Language.English.getTag();
            final String ownTitle = all.remove(own);
            return new SitePage(name, ownTitle, null, placement, all, access, sidebar);
        }
    }

    /**
     * One row of a starter page, in page order. {@code slot} names the row within its page (its id derives from
     * the org, the page and the slot, so a slot is unique across the whole page, children included);
     * {@code allowedChildTemplateIds} is a container's child allow-list, or null; {@code children} are a container's
     * rows, in order, or null.
     */
    public record StarterRow(String slot, String title, String templateId, Map<String, String> values,
            List<String> allowedChildTemplateIds, List<StarterRow> children) {
    }

    /** The starter Editable Pages, in menu order, read from {@link #STARTER_PAGES_RESOURCE} once. */
    public static List<StarterPage> starterPages() {
        return StarterPagesHolder.PAGES;
    }

    /** Lazy holder: the resource is parsed on first use, and a broken resource fails that use loudly. */
    private static final class StarterPagesHolder {
        static final List<StarterPage> PAGES = readStarterPages();
    }

    static List<StarterPage> readStarterPages() {
        return readPages(STARTER_PAGES_RESOURCE);
    }

    /**
     * A classpath resource of page definitions (one JSON list of {@link StarterPage}): the starter Editable Pages, and
     * the platform org's own pages ({@code MarketingPageBootstrap.sitePages()}). The private repo's seeding scripts
     * read the same files, so the app and the scripts cannot drift.
     */
    public static List<StarterPage> readPages(final String resource) {
        try (InputStream in = OrgPageBootstrap.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return List.copyOf(new ObjectMapper().readValue(in, new TypeReference<List<StarterPage>>() { }));
        } catch (final IOException ex) {
            throw new UncheckedIOException("Unreadable resource " + resource, ex);
        }
    }

    /**
     * The rows {@code page} starts with on {@code orgId}'s site, unsaved and pinned like {@link #rows}, parents
     * before their children. Row ids are DETERMINISTIC per org, page and slot ({@link #starterRowId}), so a retried
     * seed rewrites the same rows; a container's children are filed under the container's id, as the editor files
     * them.
     */
    public static List<ContentInstance> starterRows(final Organization.Id orgId, final StarterPage page,
            final ToIntFunction<String> templateVersion) {
        final List<ContentInstance> rows = new ArrayList<>();
        addRows(rows, orgId, page.name(), pageKey(orgId, page.name()), page.rows(), templateVersion);
        return rows;
    }

    private static void addRows(final List<ContentInstance> into, final Organization.Id orgId, final String page,
            final String section, final List<StarterRow> specs, final ToIntFunction<String> templateVersion) {
        for (int position = 0; position < specs.size(); position++) {
            final StarterRow spec = specs.get(position);
            final String id = starterRowId(orgId, page, spec.slot());
            into.add(new ContentInstance(id, section, spec.title(), spec.templateId(),
                    templateVersion.applyAsInt(spec.templateId()), valuesFor(orgId, spec), null, position, 0, null,
                    SEED_AUTHOR, null, spec.allowedChildTemplateIds()));
            if (spec.children() != null) {
                addRows(into, orgId, page, id, spec.children(), templateVersion);
            }
        }
    }

    /**
     * A starter row's values with {@value #ORG_ID_TOKEN} written as the organization's id, so a starter can link to
     * the organization's own admin pages (the metrics dashboard's way back to the hub). The backfill script does
     * the same.
     */
    static Map<String, String> valuesFor(final Organization.Id orgId, final StarterRow spec) {
        final Map<String, String> values = new HashMap<>();
        if (spec.values() != null) {
            spec.values().forEach((name, value) -> values.put(name, value == null ? null
                    : value.replace(ORG_ID_TOKEN, orgId.getValue())));
        }
        return values;
    }

    /** What a starter row writes where it means the organization's id. */
    public static final String ORG_ID_TOKEN = "{orgId}";

    /**
     * A starter row's fixed id: {@code UUID.nameUUIDFromBytes("site-page:{orgId}:{page}:{slot}")}, which the
     * backfill script reproduces (a version-3 UUID of the MD5 of those bytes).
     */
    static String starterRowId(final Organization.Id orgId, final String page, final String slot) {
        return UUID.nameUUIDFromBytes(("site-page:" + orgId.getValue() + ":" + page + ":" + slot)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /**
     * The starter rows for a trip's page ({@link #tripPageKey}), by kind: {@link #conferenceRows} for a conference,
     * {@link #pilgrimageRows} for any other trip. Unsaved and pinned like {@link #rows}; ids are DETERMINISTIC per
     * trip ({@link #tripRowId}), so a retried seed rewrites the same rows instead of doubling them.
     */
    public static List<ContentInstance> tripRows(final Trip trip, final String orgName,
            final ToIntFunction<String> templateVersion, final LocalDateTime seededAt) {
        return trip.isConference() ? conferenceRows(trip, orgName, templateVersion, seededAt)
                : pilgrimageRows(trip, orgName, templateVersion, seededAt);
    }

    /**
     * {@link #tripRows}, headed by the trip's top block when {@code withTop}: its pictures and its facts as two
     * sections ({@link #topRows}), the starter sections after them. The caller passes {@code withTop} only when
     * both top templates are installed ({@link #tripTopTemplateIds}): a deploy that runs before the installer
     * still seeds the page, and the page keeps the fixed top block until it holds these rows.
     */
    public static List<ContentInstance> tripRows(final Trip trip, final String orgName,
            final ToIntFunction<String> templateVersion, final LocalDateTime seededAt, final boolean withTop) {
        final List<ContentInstance> rows = tripRows(trip, orgName, templateVersion, seededAt);
        return withTop ? withTopRows(trip, rows, templateVersion) : rows;
    }

    /**
     * {@code rows} (a page's starter sections, positions from 0) moved down two places, under the trip's top
     * block. Used for the kinds' starters and for a legacy trip's one converted section alike.
     */
    public static List<ContentInstance> withTopRows(final Trip trip, final List<ContentInstance> rows,
            final ToIntFunction<String> templateVersion) {
        final List<ContentInstance> all = new ArrayList<>(topRows(trip, templateVersion));
        for (final ContentInstance row : rows) {
            row.setPosition(row.getPosition() + all.size());
        }
        all.addAll(rows);
        return List.copyOf(all);
    }

    /**
     * The trip's top block as two sections (docs/trip-pictures.md, "On the trip page"): its pictures, then its
     * facts, both with blank values (the gallery, and the facts the line always showed). Never placeholders: they
     * show the trip's own data from the start, and render nothing for a trip with no picture or no fact. Ids are
     * deterministic per trip ({@link #tripRowId}), so the backfill script reproduces them and a retry rewrites the
     * same rows.
     */
    public static List<ContentInstance> topRows(final Trip trip, final ToIntFunction<String> templateVersion) {
        final String key = tripPageKey(trip.getId());
        return List.of(
                tripRow(trip, TOP_PICTURES_SLOT, key, "", TripPicturesType.TYPE_ID, templateVersion, 0, Map.of(),
                        null),
                tripRow(trip, TOP_FACTS_SLOT, key, "", TripFactsType.TYPE_ID, templateVersion, 1, Map.of(), null));
    }

    /**
     * The ids {@link #topRows} gives {@code trip}'s two rows. Fixed per trip, so a page can be asked whether it
     * already holds them (a seed whose rows were written but whose trip was not, retried).
     */
    public static List<String> topRowIds(final Trip trip) {
        return List.of(tripRowId(trip, TOP_PICTURES_SLOT), tripRowId(trip, TOP_FACTS_SLOT));
    }

    /** The slot names of {@link #topRows}' ids; the backfill script names the same two. */
    static final String TOP_PICTURES_SLOT = "pictures";
    static final String TOP_FACTS_SLOT = "details";

    /** The template ids of {@link #topRows}: optional for seeding, which adds the rows only when both exist. */
    public static List<String> tripTopTemplateIds() {
        return List.of(TripPicturesType.TYPE_ID, TripFactsType.TYPE_ID);
    }

    /**
     * A trip's starter page (every kind but a conference): a hero with the Join button, About, and a video section.
     * About carries the trip's description when it has one; otherwise it and the video are seeded as hidden
     * placeholders ("Show until" = {@code seededAt}, the rule {@link #conferenceRows} explains), so visitors see a
     * hero alone until an editor fills them in. The video's URL is a required prompt that
     * {@code ContentCommands.saveContent} enforces when the editor publishes it; the seed stores it blank.
     */
    public static List<ContentInstance> pilgrimageRows(final Trip trip, final String orgName,
            final ToIntFunction<String> templateVersion, final LocalDateTime seededAt) {
        final String key = tripPageKey(trip.getId());
        // Stored as OUR registration page even for an external trip: the render resolves it to where the trip
        // registers now (TripPageCommands.contentLink), so a URL added or changed after seeding still applies.
        final String register = TripPageCommands.JOIN_URL + trip.getId();
        final Map<String, String> hero = new HashMap<>();
        hero.put("eyebrow", orgName == null ? "" : orgName);
        hero.put("headline", trip.getShortTitle());
        hero.put("subheadline", dateRange(trip));
        hero.put("primaryText", trip.getKind().getRegisterLabel(pageLocaleOf(trip)));
        hero.put("primaryUrl", register);
        hero.put("secondaryText", "About");
        hero.put("secondaryUrl", "#" + tripRowId(trip, "about"));
        final boolean described = hasDescription(trip);
        final String about = described ? PageHtml.clean(trip.getDescription()) : ABOUT_PLACEHOLDER;
        return List.of(
                tripRow(trip, "hero", key, "", StarterTemplates.BAND_HERO_ID, templateVersion, 0, hero, null),
                tripRow(trip, "about", key, "About", StarterTemplates.BAND_TEXT_ID, templateVersion, 1,
                        Map.of("heading", "About", "body", about), described ? null : seededAt),
                tripRow(trip, "video", key, "Video", StarterTemplates.YOUTUBE_VIDEO_ID, templateVersion, 2,
                        Map.of("videoUrl", "", "caption", ""), seededAt));
    }

    /**
     * The ONE row a legacy trip's page starts from (docs/conference.md, "Legacy trips"): the stored description,
     * through the allowlist like any typed HTML ({@link PageHtml}), as a text-only section with no title, so the
     * page reads as the old description page did. Never a placeholder (no "Show until"), so it is neither dimmed
     * nor rewritten on its first save.
     */
    public static List<ContentInstance> legacyRows(final Trip trip, final ToIntFunction<String> templateVersion) {
        return List.of(tripRow(trip, "legacy-description", tripPageKey(trip.getId()), "",
                StarterTemplates.TEXT_ONLY_ID, templateVersion, 0,
                Map.of("body", PageHtml.clean(trip.getDescription())), null));
    }

    /**
     * The language a trip's page is written in ({@code docs/i18n.md}, "Language versions of a page"): the trip's
     * own language when its site offers it, else that site's default. Seeded text is STORED in the page, so it
     * must never take the language of the request that happened to seed it (an admin browsing in Spanish would
     * otherwise leave a Spanish button on an English page for every visitor).
     */
    public static Locale pageLocaleOf(final Trip trip) {
        return pageLanguageOf(trip).getLocale();
    }

    /**
     * THE rule for the language a trip's unmarked page is written in: the trip's language when its site offers
     * it, else that site's default. The seeded text, the version bar, the page's lang mark, its hreflang links
     * and the version a reader is served all ask here, so they never disagree about which version is the own.
     */
    public static Language pageLanguageOf(final Trip trip) {
        final List<Language> offered = new ConfigCommands().siteLanguages(trip.getOrganization());
        final Language own = trip.getLanguage();
        return own != null && offered.contains(own) ? own : offered.get(0);
    }

    private static final String ABOUT_PLACEHOLDER =
            "<p>Tell visitors what this event is about: who it is for, what happens and why it matters.</p>";

    private static boolean hasDescription(final Trip trip) {
        return trip.getDescription() != null && !trip.getDescription().isBlank();
    }

    /**
     * A CONFERENCE's starter rows: a hero with the Register button, About, Speakers (a people list with one
     * placeholder speaker), the schedule and the registration block, the venue, questions, and a closing call to
     * action. The hero's Schedule button anchors to the schedule section. Every text is plainly a placeholder its
     * editors replace.
     *
     * <p>The rows that are ONLY placeholders (the sample speaker, the venue, the sample question and their two
     * containers, and About when the trip has no description) are seeded with "Show until" set to {@code seededAt},
     * the section's existing expiry ({@link ContentInstance#isVisibleAt}): visitors never see "Speaker name", while
     * editors see the sections dimmed in edit mode and publish one by filling it in and clearing that date. The
     * hero, the schedule, the registration block and the closing call to action carry the trip's own data and show
     * at once.
     */
    public static List<ContentInstance> conferenceRows(final Trip trip, final String orgName,
            final ToIntFunction<String> templateVersion, final LocalDateTime seededAt) {
        final String key = tripPageKey(trip.getId());
        // Stored as OUR registration page even for an external trip: the render resolves it to where the trip
        // registers now (TripPageCommands.contentLink), so a URL added or changed after seeding still applies.
        final String register = TripPageCommands.JOIN_URL + trip.getId();
        final String registerLabel = trip.getKind().getRegisterLabel(pageLocaleOf(trip));
        final String speakers = tripRowId(trip, "speakers");
        final String schedule = tripRowId(trip, "schedule");
        final String faq = tripRowId(trip, "faq");
        final Map<String, String> hero = new HashMap<>();
        hero.put("eyebrow", orgName == null ? "" : orgName);
        hero.put("headline", trip.getShortTitle());
        hero.put("subheadline", dateRange(trip));
        hero.put("primaryText", registerLabel);
        hero.put("primaryUrl", register);
        hero.put("secondaryText", "Schedule");
        hero.put("secondaryUrl", "#" + schedule);
        final boolean described = hasDescription(trip);
        final String about = described ? PageHtml.clean(trip.getDescription()) : ABOUT_PLACEHOLDER;
        return List.of(
                tripRow(trip, "hero", key, "", StarterTemplates.BAND_HERO_ID, templateVersion, 0, hero, null),
                tripRow(trip, "about", key, "About", StarterTemplates.BAND_TEXT_ID, templateVersion, 1,
                        Map.of("heading", "About", "body", about), described ? null : seededAt),
                new ContentInstance(speakers, key, "Speakers", StarterTemplates.PEOPLE_LIST_ID,
                        templateVersion.applyAsInt(StarterTemplates.PEOPLE_LIST_ID), new HashMap<>(), seededAt, 2, 0,
                        null, SEED_AUTHOR),
                tripRow(trip, "speaker-1", speakers, "", StarterTemplates.PERSON_ID, templateVersion, 0,
                        Map.of("name", "Speaker name", "subtitle", "Talk title",
                                "bio", "<p>A few sentences about the speaker.</p>"), seededAt),
                new ContentInstance(schedule, key, "Schedule", StarterTemplates.TRIP_SCHEDULE_ID,
                        templateVersion.applyAsInt(StarterTemplates.TRIP_SCHEDULE_ID), new HashMap<>(), null, 3, 0,
                        null, SEED_AUTHOR),
                tripRow(trip, "registration", key, "Registration", StarterTemplates.TRIP_REGISTRATION_ID,
                        templateVersion, 4, Map.of(), null),
                tripRow(trip, "venue", key, "Venue", StarterTemplates.BAND_SPLIT_ID, templateVersion, 5,
                        Map.of("tone", "tint", "icon", "pi-map-marker", "heading", "Venue",
                                "body", "<p>Where the event takes place, with directions and parking.</p>"), seededAt),
                new ContentInstance(faq, key, "Questions", StarterTemplates.BAND_FAQ_ID,
                        templateVersion.applyAsInt(StarterTemplates.BAND_FAQ_ID), new HashMap<>(), seededAt, 6, 0,
                        null, SEED_AUTHOR),
                tripRow(trip, "faq-1", faq, "How do I register?", StarterTemplates.TEXT_ONLY_ID, templateVersion,
                        0, Map.of("body", "<p>Use the " + escape(registerLabel) + " button on this page.</p>"),
                        seededAt),
                tripRow(trip, "cta", key, "", StarterTemplates.BAND_CTA_ID, templateVersion, 7,
                        Map.of("tone", "dark", "heading", "Join us", "buttonText", registerLabel,
                                "buttonUrl", register), null));
    }

    /**
     * The template ids {@link #tripRows} may pin, both kinds together: every one must be installed before a trip
     * page can seed (one check, whatever the kind).
     */
    public static List<String> tripRowTemplateIds() {
        return List.of(StarterTemplates.BAND_HERO_ID, StarterTemplates.BAND_TEXT_ID, StarterTemplates.PEOPLE_LIST_ID,
                StarterTemplates.PERSON_ID, StarterTemplates.TRIP_SCHEDULE_ID, StarterTemplates.TRIP_REGISTRATION_ID,
                StarterTemplates.BAND_SPLIT_ID, StarterTemplates.BAND_FAQ_ID, StarterTemplates.TEXT_ONLY_ID,
                StarterTemplates.BAND_CTA_ID, StarterTemplates.YOUTUBE_VIDEO_ID);
    }

    /** The template ids {@link #legacyRows} pins. */
    public static List<String> legacyRowTemplateIds() {
        return List.of(StarterTemplates.TEXT_ONLY_ID);
    }

    /** A trip page row's fixed id: the same trip and slot always name the same row. */
    static String tripRowId(final Trip trip, final String slot) {
        return UUID.nameUUIDFromBytes(("trip-page:" + trip.getId() + ":" + slot)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static ContentInstance tripRow(final Trip trip, final String slot, final String section,
            final String title, final String templateId, final ToIntFunction<String> templateVersion,
            final int position, final Map<String, String> values, final LocalDateTime showUntil) {
        return new ContentInstance(tripRowId(trip, slot), section, title, templateId,
                templateVersion.applyAsInt(templateId), new HashMap<>(values), showUntil, position, 0, null,
                SEED_AUTHOR);
    }

    /**
     * "March 5 - 7, 2027" in English: computed without a FacesContext, since a REST save seeds too. Public because a
     * page copied from another trip ({@code TripPageCommands.clonePage}) recognizes the hero line seeded this way.
     */
    public static String dateRange(final Trip trip) {
        if (trip.getStartDate() == null || trip.getEndDate() == null) {
            return "";
        }
        final LocalDate start = trip.getStartDate().toLocalDate();
        final LocalDate end = trip.getEndDate().toLocalDate();
        final DateTimeFormatter month = DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH);
        final String endPart = start.getMonth() == end.getMonth() && start.getYear() == end.getYear()
                ? String.valueOf(end.getDayOfMonth()) : month.format(end);
        return month.format(start) + (start.equals(end) ? "" : " - " + endPart) + ", " + end.getYear();
    }

    /** The welcome section: the org's name as the page heading, then text that is plainly a placeholder. */
    static String welcomeBody(final String orgName) {
        return "<div style=\"text-align:center\"><h1 style=\"margin-bottom:0px;\">Welcome to "
                + escape(orgName) + "</h1></div>"
                + "<p>This is your organization's home page. Replace this introduction with a few words about"
                + " who you are and what you offer. Open the page's edit mode to change any section, add new"
                + " ones, or remove the ones you do not need.</p>";
    }

    private static ContentInstance row(final String section, final String title, final String templateId,
            final ToIntFunction<String> templateVersion, final int position, final Map<String, String> values) {
        return new ContentInstance(UUID.randomUUID().toString(), section, title, templateId,
                templateVersion.applyAsInt(templateId), new HashMap<>(values), null, position, 0, null,
                SEED_AUTHOR);
    }

    /** Minimal HTML escaping for the one authored-by-us string that embeds user data (the org name). */
    static String escape(final String text) {
        return Html.escapeKeepingApostrophes(text);
    }
}
