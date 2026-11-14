package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.audit.AuditEventBuilder;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.ContentRenderer;
import org.paulsens.trip.content.OrgPageBootstrap;
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
 * page host ({@code page.xhtml}).
 *
 * <p>Two audiences, two gates. The LIST (which pages exist, their titles, menu placement and order) belongs to
 * the org's admins ({@link OrgCommands#canManageOrg}); a page's CONTENT is edited on the org's site in the
 * ordinary content edit mode, which stays privilege-only ({@code contentAdmin} for the org, docs/org-admin.md
 * "Org-site editors"). So an org admin can add, rename, move and delete pages, and the Site Pages page tells
 * them when someone else has to write the words.
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
    private final Supplier<OrgCommands> orgSource;

    public SitePageCommands() {
        this(Caller::current);
    }

    /** Test seam: {@code Caller.current()} needs a FacesContext. */
    public SitePageCommands(final Supplier<Caller> callerSource) {
        this.callerSource = callerSource;
        this.orgSource = () -> new OrgCommands(callerSource);
    }

    // ------------------------------------------------------------------ reads

    /** The org's pages and links in menu order (the admin page's table); empty for an unknown org. */
    public List<SitePage> pagesOf(final String orgId) {
        return cachedOrg(orgId).map(SitePageCommands::entries).orElse(List.of());
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
        return cachedOrg(site.orgId().getValue()).map(SitePageCommands::entries).orElse(List.of()).stream()
                .filter(page -> page.where() == where)
                .toList();
    }

    /**
     * The PAGE called {@code name} on the current org site, or null: off an org site, an unknown name, or a
     * LINK (a link has no page to show). What {@code page.xhtml} renders, or answers "no such page" for.
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

    /**
     * Where a page can be seen and edited: its address on THIS host when this is the org's site, else on the
     * org's own site (custom domain or subdomain); null when the org has no site yet. A link answers its URL.
     */
    public String viewUrl(final String orgId, final SitePage page) {
        if (page.isLink()) {
            return page.url();
        }
        if (SiteContext.current().isSiteOf(orgId)) {
            return PAGE_PATH + page.name();
        }
        final Organization org = cachedOrg(orgId).orElse(null);
        if (org == null || org.getSlug() == null || org.getSlug().isBlank()) {
            return null;
        }
        // The admin pages' one org-site builder: it keeps the *.localhost rule, so a laptop's link stays local.
        return new SiteCommands().orgSiteUrlOf(org) + PAGE_PATH.substring(1) + page.name();
    }

    /** Whether the caller may edit page CONTENT on this org's site (the content edit mode's own gate). */
    public boolean canEditContent(final String orgId) {
        final Caller current = callerSource.get();
        return current.isSiteAdmin() || current.has(PrivilegeCommands.CONTENT_ADMIN)
                || current.has(PrivilegeCommands.CONTENT_ADMIN, orgId);
    }

    /** A page address suggested from a title ({@code "Pray the Divine Mercy"} -> {@code pray-the-divine-mercy}). */
    public String suggestName(final String title) {
        final String ascii = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        String name = ascii.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (name.length() > 40) {
            name = name.substring(0, 40).replaceAll("-+$", "");
        }
        if (name.isEmpty() || !Character.isLetter(name.charAt(0))) {
            name = "page" + (name.isEmpty() ? "" : "-" + name);
        }
        return name.length() > 40 ? name.substring(0, 40).replaceAll("-+$", "") : name;
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

    /** Moves an entry to another place in the menu (the row's own placement select). */
    public boolean setPlacement(final String orgId, final String name, final String placement) {
        return change(orgId, name, (pages, index) -> place(pages, index, placement),
                "moved to " + Placement.from(placement).name().toLowerCase(Locale.ROOT));
    }

    /** Moves an entry one place up ({@code delta} -1) or down (+1) in the menu order; the ends are no-ops. */
    public boolean movePage(final String orgId, final String name, final int delta) {
        return change(orgId, name, (pages, index) -> reorder(pages, index, delta),
                delta < 0 ? "moved up" : "moved down");
    }

    /**
     * Deletes an entry; for a PAGE also its content (every section and every container's children), which is
     * what the confirmation says. The org row is saved first: a content delete that then fails leaves orphaned
     * rows nobody can reach, never a menu entry pointing at a half-deleted page.
     */
    public boolean deletePage(final String orgId, final String name) {
        final SitePage[] removed = new SitePage[1];
        final boolean saved = change(orgId, name, (pages, index) -> removeInto(pages, index, removed), "deleted");
        if (!saved || removed[0].isLink()) {
            return saved;
        }
        return deleteContent(OrgPageBootstrap.pageKey(Organization.Id.from(orgId.trim()), removed[0].name()));
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

    private static boolean place(final List<SitePage> pages, final int index, final String placement) {
        final SitePage page = pages.get(index);
        pages.set(index, page.withDetails(page.title(), page.url(), Placement.from(placement)));
        return true;
    }

    /** Swaps the entry with its neighbour; at either end of the list it stays put (still a success). */
    private static boolean reorder(final List<SitePage> pages, final int index, final int delta) {
        final int target = index + Integer.signum(delta);
        if (target >= 0 && target < pages.size()) {
            pages.add(target, pages.remove(index));
        }
        return true;
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
        if (!orgSource.get().saveOrganization(fresh)) {
            return false;
        }
        audit(fresh, "Site " + (entry.isLink() ? "link" : "page") + " '" + cleanTitle + "' ("
                + entry.name() + ") added");
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
        if (!orgSource.get().saveOrganization(fresh)) {
            return false;
        }
        audit(fresh, "Site page '" + title + "' (" + name + ") " + what);
        return true;
    }

    /** A fresh org the caller may manage, or null (with a growl). */
    private Organization editable(final String orgId) {
        if (!orgSource.get().canManageOrg(orgId)) {
            PageFeedback.refuse("Not allowed", "Only this organization's admins can change its site pages.");
            return null;
        }
        final Organization fresh = DAO.getInstance().getOrganization(Organization.Id.from(orgId.trim()), Cached.NO)
                .orElse(null);
        if (fresh == null) {
            PageFeedback.refuse("Unable to save", "Unknown organization.");
        }
        return fresh;
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

    /** Deletes every row of a page and every container's children, through the DAO's single-row removal. */
    private static boolean deleteContent(final String pageKey) {
        final DAO dao = DAO.getInstance();
        try {
            boolean all = true;
            for (final ContentInstance section : dao.getContentForSection(pageKey, Cached.NO)) {
                for (final ContentInstance child : dao.getContentForSection(section.getId(), Cached.NO)) {
                    all &= Boolean.TRUE.equals(dao.deleteContent(child.getId()));
                }
                all &= Boolean.TRUE.equals(dao.deleteContent(section.getId()));
            }
            return all || PageFeedback.refuse("Content not fully deleted", "The page is gone from the menu, but "
                    + "some of its sections could not be deleted. They are unreachable and harmless.");
        } catch (final RuntimeException ex) {
            log.error("Unable to delete the content of {}", pageKey, ex);
            return PageFeedback.refuse("Content not fully deleted", "The page is gone from the menu, but its "
                    + "sections could not be deleted. They are unreachable and harmless.");
        }
    }

    private void audit(final Organization org, final String message) {
        Audit.builder(AuditAction.ORGANIZATION, AuditOutcome.SUCCESS)
                .actor(callerSource.get().auditActor())
                .target(AuditEventBuilder.TARGET_ORGANIZATION, org.getId().getValue())
                .org(org.getId().getValue())
                .message(message)
                .log();
    }

    private static Optional<Organization> cachedOrg(final String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return Optional.empty();
        }
        try {
            return DAO.getInstance().getOrganization(Organization.Id.from(orgId.trim()), Cached.YES);
        } catch (final RuntimeException ex) {
            log.error("Unable to read organization {} for its site pages", orgId, ex);
            return Optional.empty();
        }
    }

    private static List<SitePage> entries(final Organization org) {
        return org.getSitePages() == null ? List.of() : org.getSitePages();
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
