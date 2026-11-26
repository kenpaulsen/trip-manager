package org.paulsens.trip.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.model.SelectItem;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.PageVersions;
import org.paulsens.trip.content.V2PageBootstrap;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.SitePage;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripPageOptions;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.util.Util;

/**
 * Which stylesheet a content page links, exposed to pages as {@code #{pageStyles}} (docs/content-templates.md, "A
 * page's own stylesheet"). Every page's stylesheet is a setting OF THAT PAGE, stored with whoever owns the page:
 * <ul>
 *   <li>a trip's page: the trip ({@code Trip.page.stylesheetUrl}, the Trip Page dialog's menu, as before);</li>
 *   <li>an organization's pages, its home included: the organization row ({@code Organization.pageStylesheets},
 *       by page name; the home page has no {@link SitePage} row, so the setting cannot live there);</li>
 *   <li>the shared site's pages (its home and {@code page:shared:*}): the {@code site.pageStylesheets} setting, by
 *       page key, from the shared library (stylesheets no organization owns, which site admins keep).</li>
 * </ul>
 * Any other key (a container, a placeholder marketing host's page, a trip's sub-page, a key naming nobody) has no
 * owner ({@link Kind#NONE}) and so no stylesheet: a miss never falls through to the shared library.
 *
 * <p>The page's {@code <link>} is written in the head of every content page, anonymous visitors' included, so
 * {@link #linkForSitePage} reads cached rows only, never throws, and links only a {@code text/css} item of the
 * owner's own library: the stored value is never trusted as a URL (the shared one is a hand-editable setting).
 */
@Slf4j
@Named("pageStyles")
@ApplicationScoped
public class PageStylesheets {

    /** Who owns a page, and so where its stylesheet is stored and whose library it comes from. */
    public enum Kind { TRIP, ORG, SHARED, NONE }

    /**
     * A page's owner. {@code orgId} is the trip's or the organization's (null for a shared or unowned page),
     * {@code name} the organization page's name, {@code key} the page key without a language mark.
     */
    public record Owner(Kind kind, String orgId, String tripId, String name, String key) {
        static final Owner NONE = new Owner(Kind.NONE, null, null, null, null);

        /** The library the page's stylesheets come from: the organization's, or null (the shared library). */
        public String libraryOrg() {
            return kind == Kind.SHARED ? null : orgId;
        }

        public boolean isNone() {
            return kind == Kind.NONE;
        }
    }

    /** How far back the editor's "Used by" line reads trips (a delete reads them all, past the cache). */
    static final int TRIP_WINDOW = 1000;
    /** How many of the pages that use a stylesheet a refusal names before "and N more". */
    static final int USED_BY_SHOWN = 3;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, String>> MAP = new TypeReference<>() {
    };

    private final Supplier<Caller> callerSource;
    private final OrgCommands orgs;
    private final MediaCommands media;
    private final ConfigCommands config;

    public PageStylesheets() {
        this(Caller::bound);
    }

    /** Test seam: the caller, for the write gates. */
    public PageStylesheets(final Supplier<Caller> callerSource) {
        this.callerSource = callerSource;
        this.orgs = new OrgCommands(callerSource);
        this.media = new MediaCommands();
        this.config = new ConfigCommands();
    }

    // ------------------------------------------------------------------ owners

    /** The owner of a page key (a language version's is its page's); {@link Owner#NONE} for anything else. */
    public static Owner ownerOf(final String pageKey) {
        final String key = PageVersions.baseOf(pageKey);
        if (key == null || key.isBlank()) {
            return Owner.NONE;
        }
        final String tripId = OrgPageBootstrap.tripOf(key);
        if (tripId != null) {
            // The trip's own page only: a conference's sub-pages are reserved and have no look of their own.
            if (!key.equals(OrgPageBootstrap.tripPageKey(tripId))) {
                return Owner.NONE;
            }
            final String orgId = DAO.getInstance().getTrip(tripId, Cached.YES).map(Trip::getOrgId)
                    .map(Util::trimToNull).orElse(null);
            return orgId == null ? Owner.NONE : new Owner(Kind.TRIP, orgId, tripId, null, key);
        }
        if (OrgPageBootstrap.MARKETING_PAGE_KEY.equals(key)) {
            final Organization.Id platform = OrgCommands.platformOrgId();
            return platform == null ? Owner.NONE
                    : new Owner(Kind.ORG, platform.getValue(), null, OrgPageBootstrap.HOME_PAGE, key);
        }
        final Organization.Id org = OrgPageBootstrap.orgOf(key);
        if (org != null) {
            return new Owner(Kind.ORG, org.getValue(), null, key.substring(key.lastIndexOf(':') + 1), key);
        }
        if (OrgPageBootstrap.isSharedPageKey(key)) {
            return new Owner(Kind.SHARED, null, null, null, key);
        }
        return Owner.NONE;
    }

    /** {@link #ownerOf} for EL: the kind's name ({@code TRIP}, {@code ORG}, {@code SHARED}, {@code NONE}). */
    public String kindOf(final String pageKey) {
        return ownerOf(pageKey).kind().name();
    }

    /** The library a page's stylesheets come from: its organization's id, or null for the shared library. */
    public String libraryOrgOf(final String pageKey) {
        return ownerOf(pageKey).libraryOrg();
    }

    // ------------------------------------------------------------------ reads

    /** The URL the page's owner has stored for it, or null (no stylesheet, or no owner). Cached reads. */
    public String storedUrl(final Owner owner) {
        return storedUrl(owner, Cached.YES);
    }

    /** {@link #storedUrl(Owner)}, read past the caches ({@code Cached.NO}) where a write decides on it. */
    String storedUrl(final Owner owner, final Cached cached) {
        return switch (owner.kind()) {
            case TRIP -> DAO.getInstance().getTrip(owner.tripId(), cached)
                    .map(Trip::pageOrNull).map(TripPageOptions::getStylesheetUrl).orElse(null);
            case ORG -> {
                final Organization org = cached == Cached.YES ? orgs.findOrganization(owner.orgId())
                        : orgs.freshOrg(owner.orgId());
                yield org == null || org.getPageStylesheets() == null ? null
                        : Util.trimToNull(org.getPageStylesheets().get(owner.name()));
            }
            case SHARED -> Util.trimToNull(sharedLinks(cached).get(owner.key()));
            case NONE -> null;
        };
    }

    /** The library item the page links, read fresh (an editor path), or null. */
    public MediaItem linkedItem(final String pageKey) {
        final Owner owner = ownerOf(pageKey);
        final String url = storedUrl(owner);
        if (url == null) {
            return null;
        }
        final MediaItem item = media.libraryItemFor(owner.libraryOrg(), url);
        return MediaCommands.isStylesheet(item) ? item : null;
    }

    /** {@link #linkedItem} over the CACHED listing, for what the editor renders on every update. */
    MediaItem cachedLinkedItem(final String pageKey) {
        final Owner owner = ownerOf(pageKey);
        final String url = storedUrl(owner);
        final MediaItem item = url == null ? null : media.cachedLibraryItemFor(owner.libraryOrg(), url);
        return MediaCommands.isStylesheet(item) ? item : null;
    }

    /**
     * The {@code href} of the stylesheet a SITE's page called {@code pageName} links, for its head: the screened
     * library URL plus the item's cache buster, or null. Cached reads only, and never a throw: the head of every
     * content page renders this for every visitor. A trip page's head links through {@code tripPage.lookFor}.
     */
    public String linkForSitePage(final String pageName) {
        try {
            if (!OrgPageBootstrap.isPageName(pageName)) {
                return null;
            }
            return linkFor(OrgPageBootstrap.pageKeyOf(SiteContext.current(), pageName));
        } catch (final RuntimeException ex) {
            log.warn("Unable to resolve the stylesheet of page " + pageName, ex);
            return null;
        }
    }

    /** {@link #linkForSitePage} by page key; null for a trip page (its look links it) and for any unowned key. */
    String linkFor(final String pageKey) {
        final Owner owner = ownerOf(pageKey);
        if (owner.kind() != Kind.ORG && owner.kind() != Kind.SHARED) {
            return null;
        }
        final String stored = storedUrl(owner);
        if (stored == null) {
            return null;
        }
        final MediaItem item = media.cachedLibraryItemFor(owner.libraryOrg(), stored);
        if (!MediaCommands.isStylesheet(item)) {
            return null;
        }
        final String safe = BrandCommands.safeLinkUrl(media.getUrl(item));
        return safe == null ? null : safe + item.getCacheBuster();
    }

    /**
     * Every page that links {@code item}, in words for the stylesheet editor ("Used by ..."): the trips of its
     * organization whose page links it, the organization's pages, or the shared site's pages for a shared sheet.
     * Matched by the item's key, so a stored URL with a cache buster or another spelling of the host still counts.
     */
    List<String> usedBy(final MediaItem item) {
        return usedBy(item, false);
    }

    /**
     * The pages that still link {@code item} in a few words ("Summer Fest, Home page and 2 more"), or null when it is
     * no stylesheet or no page links it: what both deletes (the editor's and the Media page's, and the API's) refuse
     * on, so no page ever links a missing file. Read past every cache, every trip of the organization included.
     */
    public String usersInWords(final MediaItem item) {
        final List<String> pages = usedBy(item, true);
        if (pages.isEmpty()) {
            return null;
        }
        final int shown = Math.min(pages.size(), USED_BY_SHOWN);
        final String named = String.join(", ", pages.subList(0, shown));
        return pages.size() > shown ? Text.get("feedback.pageStylesheets.andMore", named, pages.size() - shown)
                : named;
    }

    /**
     * {@link #usedBy}, read past the caches when {@code fresh} (a delete must not pass on a cached row that does not
     * yet show a page's new link): the organization row, the shared setting and EVERY trip, not the recent window
     * the editor's "Used by" line reads.
     */
    List<String> usedBy(final MediaItem item, final boolean fresh) {
        final List<String> pages = new ArrayList<>();
        if (!MediaCommands.isStylesheet(item)) {
            return pages;
        }
        final String org = Util.trimToNull(item.getOrgId());
        if (org == null) {
            for (final Map.Entry<String, String> entry : sharedLinks(fresh ? Cached.NO : Cached.YES).entrySet()) {
                if (names(item, entry.getValue())) {
                    pages.add(sharedPageLabel(entry.getKey()));
                }
            }
            return pages;
        }
        final List<Trip> trips = fresh ? DAO.getInstance().getRecentTrips(0, Cached.NO)
                : DAO.getInstance().getRecentTrips(TRIP_WINDOW, Cached.YES);
        for (final Trip trip : trips) {
            if (org.equals(trip.getOrgId()) && trip.pageOrNull() != null
                    && names(item, trip.pageOrNull().getStylesheetUrl())) {
                pages.add(trip.getTitle());
            }
        }
        final Organization owner = fresh ? orgs.freshOrg(org) : orgs.findOrganization(org);
        if (owner != null && owner.getPageStylesheets() != null) {
            for (final Map.Entry<String, String> entry : owner.getPageStylesheets().entrySet()) {
                if (names(item, entry.getValue())) {
                    pages.add(orgPageLabel(owner, entry.getKey()));
                }
            }
        }
        return pages;
    }

    /** {@link #usedBy} for an item id; empty for a new or unknown sheet. No page reads it: its callers gate it. */
    List<String> usedByItem(final String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return List.of();
        }
        return usedBy(DAO.getInstance().getMedia(itemId.trim(), Cached.YES).orElse(null));
    }

    private boolean names(final MediaItem item, final String url) {
        return url != null && item.getS3Key().equals(media.keyOfUrl(url));
    }

    private static String orgPageLabel(final Organization org, final String name) {
        if (OrgPageBootstrap.HOME_PAGE.equals(name)) {
            return Text.get("feedback.pageStylesheets.homePage");
        }
        if (org.getSitePages() != null) {
            for (final SitePage page : org.getSitePages()) {
                if (name.equals(page.name())) {
                    return page.title();
                }
            }
        }
        return name;
    }

    private static String sharedPageLabel(final String key) {
        if (V2PageBootstrap.PAGE_KEY.equals(key)) {
            return Text.get("feedback.pageStylesheets.sharedHomePage");
        }
        return Text.get("feedback.pageStylesheets.sharedPage", key.substring(key.lastIndexOf(':') + 1));
    }

    // ------------------------------------------------------------------ writes

    /**
     * Whether the caller may choose the page's stylesheet: what changing the page's own settings takes. A trip's
     * page: the organization's admins (the editor's Use button; the trip's managers pick in the Trip Page dialog,
     * and keep the right here as its owners). An
     * organization's page: its admins (the Editable Pages page). A shared page: site admins.
     */
    public boolean mayChoose(final String pageKey) {
        return mayChoose(ownerOf(pageKey));
    }

    boolean mayChoose(final Owner owner) {
        final Caller caller = callerSource.get();
        return switch (owner.kind()) {
            case TRIP -> orgs.canManageOrg(owner.orgId())
                    || caller.has(PrivilegeCommands.TRIP_MGR, owner.tripId());
            case ORG -> orgs.canManageOrg(owner.orgId());
            case SHARED -> caller.isSiteAdmin();
            case NONE -> false;
        };
    }

    /**
     * Links one of the owner's library stylesheets to the page ("Use on this page"). The item must be a
     * {@code text/css} item of the page owner's own library, read fresh.
     *
     * @return whether the page now links it (false, with a growl, on a refusal or a failed save)
     */
    public boolean link(final String pageKey, final String itemId) {
        final Owner owner = ownerOf(pageKey);
        if (!mayChoose(owner)) {
            return PageFeedback.refuse(Text.get("feedback.shared.notAllowed"),
                    Text.get("feedback.pageStylesheets.notYours"));
        }
        final MediaItem item = itemId == null || itemId.isBlank() ? null
                : DAO.getInstance().getMedia(itemId.trim(), Cached.NO).orElse(null);
        if (!MediaCommands.isStylesheet(item)
                || !Objects.equals(Util.trimToNull(item.getOrgId()), owner.libraryOrg())) {
            return PageFeedback.refuse(Text.get("feedback.shared.notSaved"),
                    Text.get("feedback.pageStylesheets.notInLibrary"));
        }
        if (!write(owner, media.getUrl(item))) {
            return false;
        }
        AuditCommands.media(item.getS3Key(), owner.libraryOrg(),
                "Stylesheet " + item.getS3Key() + " linked to page " + owner.key(), null);
        return true;
    }

    /** Stops the page linking any stylesheet ("Stop using"). */
    public boolean unlink(final String pageKey) {
        final Owner owner = ownerOf(pageKey);
        if (!mayChoose(owner)) {
            return PageFeedback.refuse(Text.get("feedback.shared.notAllowed"),
                    Text.get("feedback.pageStylesheets.notYours"));
        }
        final String before = storedUrl(owner, Cached.NO);
        if (before == null) {
            return true;
        }
        if (!write(owner, null)) {
            return false;
        }
        AuditCommands.media(Util.trimToNull(media.keyOfUrl(before)), owner.libraryOrg(),
                "Page " + owner.key() + " no longer links a stylesheet", null);
        return true;
    }

    /**
     * The Editable Pages settings dialog's Stylesheet menu: links the item whose URL is chosen, or none for blank.
     * The URL must name a stylesheet of the organization's library (the menu offers nothing else).
     */
    public boolean choose(final String orgId, final String pageName, final String url) {
        // The address as the Editable Pages page stores it (trimmed, lower case), whatever was typed.
        final String name = pageName == null ? null : pageName.trim().toLowerCase(Locale.ROOT);
        if (orgId == null || orgId.isBlank() || !OrgPageBootstrap.isPageName(name)) {
            return PageFeedback.refuse(Text.get("feedback.shared.notSaved"),
                    Text.get("feedback.pageStylesheets.pageGone"));
        }
        final String key = OrgPageBootstrap.pageKey(Organization.Id.from(orgId.trim()), name);
        if (url == null || url.isBlank()) {
            return unlink(key);
        }
        final MediaItem item = media.libraryItemFor(orgId.trim(), url.trim());
        if (item == null) {
            return PageFeedback.refuse(Text.get("feedback.shared.notSaved"),
                    Text.get("feedback.pageStylesheets.notInLibrary"));
        }
        return Objects.equals(url.trim(), storedUrl(ownerOf(key), Cached.NO)) || link(key, item.getId());
    }

    /**
     * The settings dialog's Save, after the page itself is saved: {@link #choose} for a page, nothing for a menu
     * link (a link has no page to style). One call, because the dialog's JSFT script may not branch on it.
     */
    public boolean chooseAfterSave(final String orgId, final String pageName, final boolean isLink,
            final String url, final String opened) {
        if (isLink) {
            return true;
        }
        // Compared with what the dialog OPENED with, not with what is stored now: a dialog left open while the page's
        // own Styles editor linked another sheet must not put the older choice back on a title-only save.
        final String wanted = url == null ? "" : url.trim();
        return wanted.equals(opened == null ? "" : opened.trim()) || choose(orgId, pageName, wanted);
    }

    /** What an organization page links now, for the settings dialog's menu: the stored URL, or blank. Cached. */
    public String storedFor(final String orgId, final String pageName) {
        if (orgId == null || orgId.isBlank() || !OrgPageBootstrap.isPageName(pageName)) {
            return "";
        }
        final String url = storedUrl(ownerOf(OrgPageBootstrap.pageKey(Organization.Id.from(orgId.trim()),
                pageName)));
        return url == null ? "" : url;
    }

    /**
     * The settings dialog's Stylesheet menu: the organization's stylesheets by "title (file name)", plus the page's
     * current value when it names nothing the library lists, so the menu never posts back a different one.
     */
    public List<SelectItem> menu(final String orgId, final String current) {
        final List<SelectItem> items = new ArrayList<>();
        final String owner = Util.trimToNull(orgId);
        if (owner == null) {
            return items;
        }
        boolean hasCurrent = current == null || current.isBlank();
        for (final MediaItem item : media.libraryItems(owner, "text/css")) {
            final String url = media.getUrl(item);
            hasCurrent |= Objects.equals(url, current);
            items.add(new SelectItem(url, media.pickLabel(item)));
        }
        if (!hasCurrent) {
            items.add(new SelectItem(current, media.describeChoice(owner, current)));
        }
        return items;
    }

    /** Stores {@code url} (null: none) as the owner's choice for the page, on a fresh read of the owner's row. */
    private boolean write(final Owner owner, final String url) {
        return switch (owner.kind()) {
            case TRIP -> writeTrip(owner, url);
            case ORG -> writeOrg(owner, url);
            case SHARED -> writeShared(owner, url);
            case NONE -> false;
        };
    }

    private boolean writeTrip(final Owner owner, final String url) {
        final Trip fresh = DAO.getInstance().getTrip(owner.tripId(), Cached.NO).orElse(null);
        if (fresh == null) {
            return PageFeedback.refuse(Text.get("feedback.shared.unableToSave"),
                    Text.get("feedback.pageStylesheets.pageGone"));
        }
        if (url == null && fresh.pageOrNull() == null) {
            return true;
        }
        // Only the stylesheet changes: the rest of the stored row is written back as it was read.
        fresh.getPage().setStylesheetUrl(url);
        try {
            if (Boolean.TRUE.equals(DAO.getInstance().saveTrip(fresh))) {
                return true;
            }
        } catch (final IOException | RuntimeException ex) {
            log.error("Unable to save the stylesheet of trip " + owner.tripId(), ex);
        }
        PageFeedback.failure(Text.get("feedback.shared.unableToSave"));
        return false;
    }

    private boolean writeOrg(final Owner owner, final String url) {
        final Organization fresh = orgs.freshOrg(owner.orgId());
        if (fresh == null) {
            return PageFeedback.refuse(Text.get("feedback.shared.unableToSave"),
                    Text.get("feedback.orgCommands.unknownOrg"));
        }
        final Map<String, String> links = fresh.getPageStylesheets() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(fresh.getPageStylesheets());
        if (url == null) {
            links.remove(owner.name());
        } else {
            links.put(owner.name(), url);
        }
        fresh.setPageStylesheets(links.isEmpty() ? null : links);
        return orgs.saveOrganization(fresh);
    }

    private boolean writeShared(final Owner owner, final String url) {
        // A read-modify-write of the whole map: seeded past the cache, so another server's newer link is kept.
        final Map<String, String> links = new LinkedHashMap<>(sharedLinks(Cached.NO));
        if (url == null) {
            links.remove(owner.key());
        } else {
            links.put(owner.key(), url);
        }
        final String value = links.isEmpty() ? "" : JSON.valueToTree(links).toString();
        return config.save(new Config(KnownSettings.SITE_PAGE_STYLESHEETS.getName(), value, Config.Type.STRING,
                null, null, null), TripStylesheets.actorEmail());
    }

    /** The shared pages' stored links (page key to URL); empty when unset or unreadable. */
    Map<String, String> sharedLinks(final Cached cached) {
        final String raw = cached == Cached.YES ? config.getString(KnownSettings.SITE_PAGE_STYLESHEETS)
                : DAO.getInstance().getConfig(KnownSettings.SITE_PAGE_STYLESHEETS.getName(), Cached.NO)
                        .map(Config::getValue).orElse(null);
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            final Map<String, String> parsed = JSON.readValue(raw, MAP);
            return parsed == null ? Map.of() : parsed;
        } catch (final IOException ex) {
            log.warn("The site.pageStylesheets setting is not a JSON object; no shared page links a stylesheet");
            return Map.of();
        }
    }
}
