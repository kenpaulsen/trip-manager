package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.ContentRenderer;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.OrgPageBootstrap.StarterPage;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.SitePage;
import org.paulsens.trip.model.SitePage.Placement;
import org.paulsens.trip.site.SiteContext;

/**
 * An organization site's own pages and menu links ({@link Organization#getSitePages()}), managed on the org's
 * Site Pages admin page ({@code admin/orgPages.xhtml}) and read by the menu ({@code WEB-INF/menu.xhtml}) and the
 * page host ({@code page.xhtml}). Everything is a Site Page on an org site (issue medjugorje#124): an org site's
 * content pages exist only as these entries, and a new site starts with the starter pages
 * ({@link #ensureStarterPages}).
 *
 * <p>Two audiences, two gates. The LIST (which pages exist, their titles, menu placement and order) belongs to
 * the org's admins ({@link OrgCommands#canManageOrg}); a page's CONTENT is edited on the org's site in the
 * ordinary content edit mode, which stays privilege-only ({@code contentAdmin} for the org, docs/org-admin.md
 * "Org-site editors"). So an org admin can add, rename, move and delete pages, and the Site Pages page tells
 * them when someone else has to write the words. Deleting a page deletes its content too, without the content
 * privilege (owner decision 2026-11-13: managing the list includes removing a page), and each deleted row gets
 * its own audit line, as a content editor's delete does.
 *
 * <p>Identity, never position: every row command names the page by its {@link SitePage#name()}, fixed at
 * creation. Reads go through the cached org; writes re-read it uncached, apply the change and save through
 * {@link OrgCommands#saveOrganization} (its authorization and optimistic version check), then audit what changed.
 */
@Slf4j
@Named("orgPages")
@ApplicationScoped
public class SitePageCommands {

    /** How many pages and links one site may have: a menu, not a content store. */
    static final int MAX_PAGES = 60;
    static final int MAX_TITLE = 60;
    /** The starter body of a new page, so it is never blank (and says where its words are edited). */
    static final String STARTER_BODY = "<p>This page is new. Open the page's edit mode to replace this text.</p>";
    private static final String PAGE_PATH = "/page.jsf?name=";

    private final Supplier<Caller> callerSource;
    private final OrgCommands orgs;
    private final ContentCommands content;

    public SitePageCommands() {
        this(Caller::current);
    }

    /** Test seam: {@code Caller.current()} needs a FacesContext. */
    public SitePageCommands(final Supplier<Caller> callerSource) {
        this.callerSource = callerSource;
        this.orgs = new OrgCommands(callerSource);
        this.content = new ContentCommands();
        this.content.setCallerSource(callerSource);
    }

    // ------------------------------------------------------------------ reads

    /** The org's pages and links in stored order; empty for an unknown org. */
    public List<SitePage> pagesOf(final String orgId) {
        return entries(orgs.findOrganization(orgId));
    }

    /**
     * The admin page's table: the org's entries grouped by placement (About, Help, then the ones out of the menu),
     * each group in its menu order. Up and Down move an entry within its own group ({@link #movePage}).
     */
    public List<SitePage> tableOf(final String orgId) {
        return pagesOf(orgId).stream().sorted(Comparator.comparing(SitePage::where)).toList();
    }

    /** One of the org's entries by name (the settings dialog's seed), or null. */
    public SitePage pageOf(final String orgId, final String name) {
        return name == null ? null : find(pagesOf(orgId), name.trim()).orElse(null);
    }

    /** The org's entries under one placement name, in menu order: the admin page's menu preview. */
    public List<SitePage> pagesIn(final String orgId, final String placement) {
        final Placement where = Placement.from(placement);
        return pagesOf(orgId).stream().filter(page -> page.where() == where).toList();
    }

    /** Whether Up ({@code delta} -1) or Down (+1) would move {@code page}: it is not at that end of its menu. */
    public boolean canMove(final String orgId, final SitePage page, final int delta) {
        final List<SitePage> pages = pagesOf(orgId);
        final int index = page == null ? -1 : indexOf(pages, page.name());
        return index >= 0 && neighborIndex(pages, index, delta) >= 0;
    }

    /** Where an entry shows, in the admin page's words. */
    public String placementLabel(final SitePage page) {
        return switch (page.where()) {
            case ABOUT -> "About menu";
            case HELP -> "Help menu";
            case HIDDEN -> "Not in the menu";
        };
    }

    /** The current org site's entries in the About submenu (empty off an org site). */
    public List<SitePage> getAboutEntries() {
        return menuEntries(Placement.ABOUT);
    }

    /** The current org site's entries in the Help submenu (empty off an org site). */
    public List<SitePage> getHelpEntries() {
        return menuEntries(Placement.HELP);
    }

    /** The current org site's entries shown under {@code where}, in menu order. */
    public List<SitePage> menuEntries(final Placement where) {
        final SiteContext site = SiteContext.current();
        if (!site.isOrg() || site.isMarketing()) {
            return List.of();
        }
        return pagesOf(site.orgId().getValue()).stream().filter(page -> page.where() == where).toList();
    }

    /**
     * The PAGE called {@code name} on the current org site, or null: off an org site, an unknown name, or a
     * LINK (a link has no page to show). What {@code page.xhtml} renders, or answers 404 for. Surrounding blanks
     * are forgiven (a pasted link), which is why the page host passes on the STORED name, never the parameter.
     */
    public SitePage currentPage(final String name) {
        final SiteContext site = SiteContext.current();
        if (!site.isOrg() || site.isMarketing() || name == null) {
            return null;
        }
        return find(pagesOf(site.orgId().getValue()), name.trim()).filter(page -> !page.isLink()).orElse(null);
    }

    /** The menu's target for an entry: a page's own address on this site, or a link's URL. */
    public String hrefOf(final SitePage page) {
        return page.isLink() ? page.url() : PAGE_PATH + page.name();
    }

    /** The Help menu's icon for an entry, like the built-in items have: a page here, or a link elsewhere. */
    public String iconOf(final SitePage page) {
        return page.isLink() ? "pi pi-external-link" : "pi pi-file";
    }

    /**
     * Where a page can be seen and edited: its address on THIS host when this is the org's site, else on the
     * org's own site as the admin pages' links build it ({@link SiteCommands#orgSiteUrl(String,
     * HttpServletRequest)}: {@code {slug}.localhost} from a localhost request, else the custom domain or the
     * subdomain); null when the org has no site yet. A link answers its URL.
     */
    public String viewUrl(final String orgId, final SitePage page) {
        if (page.isLink()) {
            return page.url();
        }
        if (SiteContext.current().isSiteOf(orgId)) {
            return PAGE_PATH + page.name();
        }
        final Organization org = orgs.findOrganization(orgId);
        if (org == null || org.getSlug() == null || org.getSlug().isBlank()) {
            return null;
        }
        final String site = SiteCommands.orgSiteUrl(org.getSlug(), currentRequest());
        return (site.endsWith("/") ? site.substring(0, site.length() - 1) : site) + PAGE_PATH + page.name();
    }

    /** Whether the caller may edit page CONTENT on this org's site: the content edit mode's own gate. */
    public boolean canEditContent(final String orgId) {
        final Caller current = callerSource.get();
        if (orgId == null || orgId.isBlank() || !current.isAuthenticated()) {
            return false;
        }
        return content.canEdit(OrgPageBootstrap.pageKey(Organization.Id.from(orgId.trim())), current.personId());
    }

    /**
     * {@code page.xhtml}'s answer for a name the current site does not serve: HTTP 404 through the container's
     * error page ({@code /notFound}), like every other unknown address, so a removed page drops out of search
     * indexes and a sweep of made-up names costs no JSF render.
     */
    public void answerNotFound() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx == null || ctx.getResponseComplete()) {
            return;
        }
        try {
            ctx.getExternalContext().responseSendError(404, null);
        } catch (final IOException ex) {
            throw new UncheckedIOException("Unable to answer 404", ex);
        }
        ctx.responseComplete();
    }

    // ------------------------------------------------------------------ writes

    /**
     * Adds an editable page: {@code name} is its address and content key, fixed from now on. The page gets one
     * starter text block (unless content already sits under that key, which it then adopts as-is, never
     * overwriting it), so it is never blank.
     */
    public boolean addPage(final String orgId, final String title, final String name, final String placement) {
        final String cleanName = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (!OrgPageBootstrap.isPageName(cleanName) || OrgPageBootstrap.HOME_PAGE.equals(cleanName)) {
            return PageFeedback.refuse("Invalid address", "A page address starts with a letter and uses lowercase "
                    + "letters, digits and hyphens, at most 40 characters, and \"home\" is the home page's own.");
        }
        return add(orgId, new SitePage(cleanName, title, null, Placement.from(placement).name()), true);
    }

    /** Adds a menu link to {@code url} (absolute http(s) or a site path such as {@code /pay}). */
    public boolean addLink(final String orgId, final String title, final String url, final String placement) {
        final String target = ContentRenderer.requireLinkUrl(url);
        if (target.isEmpty() || target.startsWith("#")) {
            return PageFeedback.refuse("Invalid link", "A link is a web address (https://...) or a path on this "
                    + "site starting with a slash, such as /pay.");
        }
        final String name = "link-" + UUID.randomUUID().toString().substring(0, 8);
        return add(orgId, new SitePage(name, title, target, Placement.from(placement).name()), false);
    }

    /**
     * The Site Pages dialog's one Save: {@code mode} "edit" changes the named entry, "link" adds a link, anything
     * else adds a page. One Java call rather than a branch in the page's script, whose comparisons are fragile.
     */
    public boolean saveDialog(final String orgId, final String mode, final String name, final String title,
            final String url, final String placement) {
        if ("edit".equals(mode)) {
            return updatePage(orgId, name, title, url, placement);
        }
        return "link".equals(mode) ? addLink(orgId, title, url, placement) : addPage(orgId, title, name, placement);
    }

    /** Changes an entry's title and placement (and a link's URL); its address never changes. */
    public boolean updatePage(final String orgId, final String name, final String title, final String url,
            final String placement) {
        final String cleanTitle = cleanTitle(title);
        if (cleanTitle == null) {
            return false;
        }
        return change(orgId, name, (pages, index) -> applyDetails(pages, index, cleanTitle, url, placement),
                "updated");
    }

    /**
     * Moves an entry one place up ({@code delta} -1) or down (+1) WITHIN ITS OWN MENU: it swaps with the nearest
     * entry of the same placement, so every click changes what visitors see. The ends are no-ops.
     */
    public boolean movePage(final String orgId, final String name, final int delta) {
        return change(orgId, name, (pages, index) -> reorder(pages, index, delta),
                delta < 0 ? "moved up" : "moved down");
    }

    /**
     * Deletes an entry; for a PAGE also its content (every section and every container's children), which is
     * what the confirmation says. An org admin may do this without the content privilege; each deleted row is
     * audited on its own ({@link ContentCommands#deleteWithChildren}). The org row is saved first: a content
     * delete that then fails leaves orphaned rows nobody can reach, never a menu entry pointing at a
     * half-deleted page.
     */
    public boolean deletePage(final String orgId, final String name) {
        final SitePage[] removed = new SitePage[1];
        final boolean saved = change(orgId, name, (pages, index) -> removeInto(pages, index, removed), "deleted");
        if (!saved || removed[0].isLink()) {
            return saved;
        }
        return deleteContent(OrgPageBootstrap.pageKey(Organization.Id.from(orgId.trim()), removed[0].name()));
    }

    /**
     * Seeds the org site's starter Site Pages ({@link OrgPageBootstrap#starterPages()}) ONCE: on the subdomain's
     * first assignment ({@code OrgCommands.saveOrgEdits}, beside {@code ensureHomePage}) and for the local
     * fixture's sites; {@code scripts/seed-starter-site-pages.py} in the private repo does the same for sites
     * hosted before starters existed. Each starter the list lacks is appended and gets its starter content only
     * when nothing is stored under its key yet, so rows already there (a hand-built board) are adopted as-is; a
     * starter whose name the list already has is left alone. The org row records the seeding, so a starter page
     * deleted later never comes back. The platform org's site is the marketing site, which shows no Site Pages,
     * so it is only marked.
     *
     * @return whether the org now has (or already had) its starters; false without a subdomain, for a caller who
     *         may not manage the org, or when a save failed (with a growl)
     */
    public boolean ensureStarterPages(final String orgId) {
        final Organization fresh = orgs.freshOrg(orgId);
        if (fresh == null || fresh.getSlug() == null || fresh.getSlug().isBlank()) {
            return false;
        }
        if (fresh.getSitePagesSeededAt() != null) {
            return true;
        }
        if (!orgs.canManageOrg(orgId)) {
            return false;
        }
        final List<String> added = fresh.isPlatform() ? List.of() : addStarters(fresh);
        if (added == null) {
            return false;
        }
        fresh.setSitePagesSeededAt(LocalDateTime.now());
        if (!orgs.saveOrganization(fresh)) {
            return false;
        }
        orgs.audit(callerSource.get(), fresh, "Starter site pages seeded for the site '" + fresh.getSlug() + "': "
                + (added.isEmpty() ? "none added" : String.join(", ", added)));
        return true;
    }

    // ------------------------------------------------------------------ internals

    /** One edit of a fresh copy of the entry list; false (already growled) leaves the org untouched. */
    @FunctionalInterface
    interface Edit {
        boolean apply(List<SitePage> pages, int index);
    }

    private static boolean applyDetails(final List<SitePage> pages, final int index, final String title,
            final String url, final String placement) {
        final SitePage page = pages.get(index);
        final String target = page.isLink() ? ContentRenderer.requireLinkUrl(url) : null;
        if (page.isLink() && (target.isEmpty() || target.startsWith("#"))) {
            return PageFeedback.refuse("Invalid link", "A link is a web address (https://...) or a path on "
                    + "this site starting with a slash, such as /pay.");
        }
        pages.set(index, page.withDetails(title, target, Placement.from(placement)));
        return true;
    }

    /** Swaps the entry with its neighbor in the same menu; at either end of that menu it stays put (a success). */
    private static boolean reorder(final List<SitePage> pages, final int index, final int delta) {
        final int target = neighborIndex(pages, index, delta);
        if (target >= 0) {
            pages.set(index, pages.set(target, pages.get(index)));
        }
        return true;
    }

    /** The index of the nearest entry beyond {@code index} in {@code delta}'s direction with the same placement. */
    static int neighborIndex(final List<SitePage> pages, final int index, final int delta) {
        final Placement where = pages.get(index).where();
        final int step = Integer.signum(delta);
        for (int i = index + step; step != 0 && i >= 0 && i < pages.size(); i += step) {
            if (pages.get(i).where() == where) {
                return i;
            }
        }
        return -1;
    }

    private static boolean removeInto(final List<SitePage> pages, final int index, final SitePage[] removed) {
        removed[0] = pages.remove(index);
        return true;
    }

    private boolean add(final String orgId, final SitePage entry, final boolean seed) {
        final String cleanTitle = cleanTitle(entry.title());
        final Organization fresh = editable(orgId);
        if (cleanTitle == null || fresh == null) {
            return false;
        }
        final List<SitePage> pages = new ArrayList<>(entries(fresh));
        if (pages.size() >= MAX_PAGES) {
            return PageFeedback.refuse("Too many pages", "A site may have at most " + MAX_PAGES
                    + " pages and links. Delete one first.");
        }
        if (find(pages, entry.name()).isPresent()) {
            return PageFeedback.refuse("Address taken", "This site already has a page at " + PAGE_PATH
                    + entry.name() + ". Choose another address.");
        }
        pages.add(new SitePage(entry.name(), cleanTitle, entry.url(), entry.placement()));
        fresh.setSitePages(pages);
        if (!orgs.saveOrganization(fresh)) {
            return false;
        }
        orgs.audit(callerSource.get(), fresh, "Site " + (entry.isLink() ? "link" : "page") + " '" + cleanTitle
                + "' (" + entry.name() + ") added");
        if (seed && !seedPage(OrgPageBootstrap.pageKey(fresh.getId(), entry.name()))) {
            return PageFeedback.refuse("Page added without content", "The page was added, but its starter "
                    + "text could not be saved. Open the page and add a section.");
        }
        PageFeedback.info("Added \"" + cleanTitle + "\".");
        return true;
    }

    private boolean change(final String orgId, final String name, final Edit edit, final String what) {
        final Organization fresh = editable(orgId);
        if (fresh == null) {
            return false;
        }
        final List<SitePage> pages = new ArrayList<>(entries(fresh));
        final int index = indexOf(pages, name);
        if (index < 0) {
            return PageFeedback.refuse("Unknown page", "That page is no longer on this site. Reload the page.");
        }
        final String title = pages.get(index).title();
        if (!edit.apply(pages, index)) {
            return false;
        }
        fresh.setSitePages(pages.isEmpty() ? null : pages);
        if (!orgs.saveOrganization(fresh)) {
            return false;
        }
        orgs.audit(callerSource.get(), fresh, "Site page '" + title + "' (" + name + ") " + what);
        return true;
    }

    /** A fresh org the caller may manage, or null (with a growl). */
    private Organization editable(final String orgId) {
        if (!orgs.canManageOrg(orgId)) {
            PageFeedback.refuse("Not allowed", "Only this organization's admins can change its site pages.");
            return null;
        }
        final Organization fresh = orgs.freshOrg(orgId);
        if (fresh == null) {
            PageFeedback.refuse("Unable to save", "Unknown organization.");
        }
        return fresh;
    }

    /**
     * Appends the starters {@code org}'s list lacks (their content first, only into an empty key) and answers
     * their names; null when a starter's content could not be saved (growled), leaving the list as it was.
     */
    private static List<String> addStarters(final Organization org) {
        final List<SitePage> pages = new ArrayList<>(entries(org));
        final List<String> added = new ArrayList<>();
        for (final StarterPage starter : OrgPageBootstrap.starterPages()) {
            final boolean wanted = find(pages, starter.name()).isEmpty() && pages.size() < MAX_PAGES;
            if (wanted && !seedStarter(org.getId(), starter)) {
                return null;
            }
            if (wanted) {
                pages.add(starter.entry());
                added.add(starter.name());
            }
        }
        org.setSitePages(pages.isEmpty() ? null : pages);
        return added;
    }

    private static boolean seedStarter(final Organization.Id orgId, final StarterPage starter) {
        final String key = OrgPageBootstrap.pageKey(orgId, starter.name());
        try {
            if (OrgCommands.seedIfEmpty(key, OrgPageBootstrap.starterRows(orgId, starter,
                    OrgCommands::currentTemplateVersion))) {
                return true;
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to seed the starter site page {}", key, ex);
        }
        return PageFeedback.refuse("Starter pages not created", "The starter page '" + starter.title()
                + "' could not be saved.");
    }

    private static String cleanTitle(final String title) {
        final String clean = title == null ? "" : title.trim();
        if (clean.isEmpty() || clean.length() > MAX_TITLE) {
            PageFeedback.refuse("Title needed", "A page's title is what the menu shows: 1 to " + MAX_TITLE
                    + " characters.");
            return null;
        }
        return clean;
    }

    /** The one starter text block, written only when nothing is stored under the key yet. */
    private static boolean seedPage(final String pageKey) {
        try {
            final int version = OrgCommands.currentTemplateVersion(StarterTemplates.TEXT_ONLY_ID);
            final Map<String, String> values = new HashMap<>(Map.of("body", STARTER_BODY));
            final ContentInstance row = new ContentInstance(UUID.randomUUID().toString(), pageKey, "Introduction",
                    StarterTemplates.TEXT_ONLY_ID, version, values, null, 0, 0, null, "site-pages");
            return OrgCommands.seedIfEmpty(pageKey, List.of(row));
        } catch (final RuntimeException ex) {
            log.error("Unable to seed site page {}", pageKey, ex);
            return false;
        }
    }

    /** Deletes every section of a page through the content delete cascade, one audit line per row. */
    private boolean deleteContent(final String pageKey) {
        final Caller caller = callerSource.get();
        try {
            boolean all = true;
            for (final ContentInstance section : DAO.getInstance().getContentForSection(pageKey, Cached.NO)) {
                all &= ContentCommands.deleteWithChildren(caller, section);
            }
            return all || PageFeedback.refuse("Content not fully deleted", "The page is gone from the menu, but "
                    + "some of its sections could not be deleted. They are unreachable and harmless.");
        } catch (final RuntimeException ex) {
            log.error("Unable to delete the content of {}", pageKey, ex);
            return PageFeedback.refuse("Content not fully deleted", "The page is gone from the menu, but its "
                    + "sections could not be deleted. They are unreachable and harmless.");
        }
    }

    /** The current request (null off a JSF request): what the cross-site View link's host rule reads. */
    private static HttpServletRequest currentRequest() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        final Object request = ctx == null ? null : ctx.getExternalContext().getRequest();
        return request instanceof HttpServletRequest http ? http : null;
    }

    private static List<SitePage> entries(final Organization org) {
        return org == null || org.getSitePages() == null ? List.of() : org.getSitePages();
    }

    private static Optional<SitePage> find(final List<SitePage> pages, final String name) {
        return pages.stream().filter(page -> page.name().equals(name)).findFirst();
    }

    private static int indexOf(final List<SitePage> pages, final String name) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
