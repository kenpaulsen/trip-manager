package org.paulsens.trip.content;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.SiteContext;

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
    static final String SEED_AUTHOR = "org-site-bootstrap";

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
     * the other: {@code page:shared:board}, {@code page:org:{uuid}:board}, {@code page:unitetrip:board}.
     */
    public static String pageKeyOf(final SiteContext site, final String pageName) {
        final String name = requirePageName(pageName);
        // The marketing key first: the platform organization's site is an org site too, but its page is
        // the script-seeded marketing page, keyed independently of whichever org row holds the slug.
        if (site.isMarketing()) {
            return HOME_PAGE.equals(name) ? MARKETING_PAGE_KEY : MARKETING_PAGE_PREFIX + name;
        }
        if (site.isOrg()) {
            return pageKey(site.orgId(), name);
        }
        return HOME_PAGE.equals(name) ? V2PageBootstrap.PAGE_KEY : SHARED_PAGE_PREFIX + name;
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
    public static String tripOf(final String pageKey) {
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

    /** Whether {@code pageKey} is one of the marketing host's pages: its home page or a named one. */
    public static boolean isMarketingPageKey(final String pageKey) {
        return MARKETING_PAGE_KEY.equals(pageKey)
                || pageKey != null && pageKey.startsWith(MARKETING_PAGE_PREFIX)
                        && isPageName(pageKey.substring(MARKETING_PAGE_PREFIX.length()));
    }

    /**
     * The org an org-page key names ({@code page:org:{uuid}:{pageName}}, any page), or null for any other key
     * (shared pages, marketing pages, containers).
     */
    public static Organization.Id orgOf(final String pageKey) {
        if (pageKey == null || !pageKey.startsWith(ORG_PAGE_PREFIX)) {
            return null;
        }
        final String rest = pageKey.substring(ORG_PAGE_PREFIX.length());
        final int colon = rest.lastIndexOf(':');
        if (colon <= 0 || !isPageName(rest.substring(colon + 1))) {
            return null;
        }
        final String id = rest.substring(0, colon);
        return id.isBlank() || id.indexOf(':') >= 0 ? null : Organization.Id.from(id);
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
                row(key, "Upcoming Trips", StarterTemplates.PILGRIMAGES_ID, templateVersion, 1,
                        Map.of("language", "English", "cfpwOnly", "false")),
                row(key, "Pictures", StarterTemplates.PHOTO_ALBUMS_ID, templateVersion, 2, Map.of()));
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
     * A trip's starter page (every kind but a conference): a hero with the Join button, About, and a video section.
     * About carries the trip's description when it has one; otherwise it and the video are seeded as hidden
     * placeholders ("Show until" = {@code seededAt}, the rule {@link #conferenceRows} explains), so visitors see a
     * hero alone until an editor fills them in. The video's URL is a required prompt that
     * {@code ContentCommands.saveContent} enforces when the editor publishes it; the seed stores it blank.
     */
    public static List<ContentInstance> pilgrimageRows(final Trip trip, final String orgName,
            final ToIntFunction<String> templateVersion, final LocalDateTime seededAt) {
        final String key = tripPageKey(trip.getId());
        final String register = "/trip/joinTrip.jsf?trip=" + trip.getId();
        final Map<String, String> hero = new HashMap<>();
        hero.put("eyebrow", orgName == null ? "" : orgName);
        hero.put("headline", trip.getShortTitle());
        hero.put("subheadline", dateRange(trip));
        hero.put("primaryText", trip.getKind().getRegisterLabel());
        hero.put("primaryUrl", register);
        hero.put("secondaryText", "About");
        hero.put("secondaryUrl", "#" + tripRowId(trip, "about"));
        final boolean described = hasDescription(trip);
        final String about = described ? trip.getDescription() : ABOUT_PLACEHOLDER;
        return List.of(
                tripRow(trip, "hero", key, "", StarterTemplates.BAND_HERO_ID, templateVersion, 0, hero, null),
                tripRow(trip, "about", key, "About", StarterTemplates.BAND_TEXT_ID, templateVersion, 1,
                        Map.of("heading", "About", "body", about), described ? null : seededAt),
                tripRow(trip, "video", key, "Video", StarterTemplates.YOUTUBE_VIDEO_ID, templateVersion, 2,
                        Map.of("videoUrl", "", "caption", ""), seededAt));
    }

    /**
     * The ONE row a legacy trip's page starts from (docs/conference.md, "Legacy trips"): the stored description,
     * verbatim, as a text-only section with no title, so the page reads as the old description page did. Never a
     * placeholder (no "Show until"), so it is neither dimmed nor rewritten on its first save.
     */
    public static List<ContentInstance> legacyRows(final Trip trip, final ToIntFunction<String> templateVersion) {
        return List.of(tripRow(trip, "legacy-description", tripPageKey(trip.getId()), "",
                StarterTemplates.TEXT_ONLY_ID, templateVersion, 0,
                Map.of("body", trip.getDescription() == null ? "" : trip.getDescription()), null));
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
        final String register = "/trip/joinTrip.jsf?trip=" + trip.getId();
        final String registerLabel = trip.getKind().getRegisterLabel();
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
        final String about = described ? trip.getDescription() : ABOUT_PLACEHOLDER;
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

    /** "March 5 - 7, 2027" in English: computed without a FacesContext, since a REST save seeds too. */
    static String dateRange(final Trip trip) {
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
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
