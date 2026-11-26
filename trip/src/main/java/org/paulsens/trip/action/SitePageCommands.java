package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.ContentRenderer;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.PageVersions;
import org.paulsens.trip.content.OrgPageBootstrap.StarterPage;
import org.paulsens.trip.content.StarterTemplates;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.SitePage;
import org.paulsens.trip.model.SitePage.Access;
import org.paulsens.trip.model.SitePage.Placement;
import org.paulsens.trip.model.SitePage.Sidebar;
import org.paulsens.trip.site.SiteContext;

/**
 * An organization site's own pages and menu links ({@link Organization#getSitePages()}), managed on the org's
 * Editable Pages admin page ({@code admin/orgPages.xhtml}) and read by the menu ({@code WEB-INF/menu.xhtml}) and the
 * page host ({@code page.xhtml}). Everything is an Editable Page on an org site (issue medjugorje#124): an org site's
 * content pages exist only as these entries, and a new site starts with the starter pages
 * ({@link #ensureStarterPages}).
 *
 * <p>Two audiences, two gates. The LIST (which pages exist, their titles, menu placement and order) belongs to
 * the org's admins ({@link OrgCommands#canManageOrg}); a page's CONTENT is edited on the org's site in the
 * ordinary content edit mode, which stays privilege-only ({@code contentAdmin} for the org, docs/org-admin.md
 * "Org-site editors"). So an org admin can add, rename, move and delete pages, and the Editable Pages page tells
 * them when someone else has to write the words. Deleting a page deletes its content too, without the content
 * privilege (owner decision 2026-11-13: managing the list includes removing a page), and each deleted row gets
 * its own audit line, as a content editor's delete does.
 *
 * <p>A third gate, on SEEING a page ({@link SitePage#who()}, {@link #canView}): the page host refuses a reader the
 * page's access excludes ({@link #hostFor}) and the menu lists only what its reader may open. An entry only site
 * admins may see is also theirs alone to manage: an org admin's list leaves it out, and a change or delete of it
 * is refused, so the gate on a page site admins put there cannot be lowered or removed from under them.
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
        return pagesOf(orgId).stream().filter(this::mayManage).sorted(Comparator.comparing(SitePage::where)).toList();
    }

    /** One of the org's entries by name (the settings dialog's seed), or null. */
    public SitePage pageOf(final String orgId, final String name) {
        return name == null ? null : find(pagesOf(orgId), name.trim()).orElse(null);
    }

    /** The org's entries under one placement name, in menu order: the admin page's menu preview. */
    public List<SitePage> pagesIn(final String orgId, final String placement) {
        final Placement where = Placement.from(placement);
        return pagesOf(orgId).stream().filter(page -> page.where() == where && mayManage(page)).toList();
    }

    /** Whether Up ({@code delta} -1) or Down (+1) would move {@code page}: it is not at that end of its menu. */
    public boolean canMove(final String orgId, final SitePage page, final int delta) {
        final List<SitePage> pages = pagesOf(orgId);
        final int index = page == null ? -1 : indexOf(pages, page.name());
        return index >= 0 && neighborIndex(pages, index, delta, this::mayManage) >= 0;
    }

    /** Where an entry shows, in the admin page's words. */
    public String placementLabel(final SitePage page) {
        return switch (page.where()) {
            case ABOUT -> Text.get("feedback.sitePageCommands.placement.ABOUT");
            case HELP -> Text.get("feedback.sitePageCommands.placement.HELP");
            case HIDDEN -> Text.get("feedback.sitePageCommands.placement.HIDDEN");
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

    /**
     * The current org site's entries shown under {@code where}, in menu order. The platform org's site (the
     * product's own, unitetrip.com) has Editable Pages like any other (its Pricing and Why UniteTrip pages); only the
     * placeholder marketing host, which no org holds, is not an org site.
     */
    public List<SitePage> menuEntries(final Placement where) {
        final SiteContext site = SiteContext.current();
        if (!site.isOrg()) {
            return List.of();
        }
        final String orgId = site.orgId().getValue();
        return pagesOf(orgId).stream().filter(page -> page.where() == where && canView(orgId, page)).toList();
    }

    /**
     * The PAGE called {@code name} on the current org site, or null: off an org site, an unknown name, or a
     * LINK (a link has no page to show). What {@code page.xhtml} renders, or answers 404 for. Surrounding blanks
     * are forgiven (a pasted link), which is why the page host passes on the STORED name, never the parameter.
     */
    public SitePage currentPage(final String name) {
        final SiteContext site = SiteContext.current();
        if (!site.isOrg() || name == null) {
            return null;
        }
        return find(pagesOf(site.orgId().getValue()), name.trim()).filter(page -> !page.isLink()).orElse(null);
    }

    /**
     * Whether the caller may see {@code page} of {@code orgId}'s site: the ONE rule the page host and the menu read
     * (the metrics dashboard's card and graphs keep their own, {@code MetricsCommands.canView}). Site admins see
     * every page; an org admin (of THIS org) every page but a site admins' one; anyone signed in a signed-in page;
     * everyone an open one. A link is never gated (it is not ours to serve), and no entry is visible to nobody.
     */
    public boolean canView(final String orgId, final SitePage page) {
        if (page == null) {
            return false;
        }
        final Caller current = callerSource.get();
        return switch (page.isLink() ? Access.EVERYONE : page.who()) {
            case EVERYONE -> true;
            case SIGNED_IN -> current.isAuthenticated();
            case ORG_ADMINS -> orgs.canManageOrg(orgId);
            case SITE_ADMINS -> isSiteAdmin();
        };
    }

    /**
     * Which composition {@code page.xhtml} renders for {@code name} on the current site: the content host for a
     * page the caller may see, the refusal for one they may not ({@code WEB-INF/pageRefused.xhtml}, which sends a
     * visitor to sign in and anyone else away), and the 404 for a name the site does not serve as a page.
     */
    public String hostFor(final String name) {
        final SitePage page = currentPage(name);
        if (page == null) {
            return "/WEB-INF/noSuchPage.xhtml";
        }
        return canView(SiteContext.current().orgId().getValue(), page) ? "/WEB-INF/contentPage.xhtml"
                : "/WEB-INF/pageRefused.xhtml";
    }

    /**
     * Where the refusal sends its reader: a visitor to sign in (and back here after), a signed-in reader home with
     * the reason, since signing in again would grant nothing.
     */
    public String refusedUrl() {
        final TripUtilCommands util = new TripUtilCommands();
        return callerSource.get().isAuthenticated() ? util.notAllowedUrl(null) : util.loginUrl();
    }

    /**
     * Whether {@code page} of {@code orgId}'s site shows the sidebar: the ONE rule page.xhtml reads. The
     * organization's {@code site.pages.sidebar} forced off hides it everywhere; otherwise the page's own choice
     * wins, and a page that has none follows the organization's on or off.
     */
    public boolean showSidebar(final String orgId, final SitePage page) {
        final String rule = new ConfigCommands().getString(KnownSettings.SITE_PAGES_SIDEBAR,
                orgs.findOrganization(orgId));
        if (KnownSettings.SIDEBAR_FORCED_OFF.equals(rule)) {
            return false;
        }
        final Sidebar own = page == null ? Sidebar.DEFAULT : page.side();
        return own == Sidebar.DEFAULT ? !KnownSettings.SIDEBAR_OFF.equals(rule) : own == Sidebar.SHOW;
    }

    /** {@link #showSidebar} for the page called {@code name} on the current org site; true off an org site. */
    public boolean sidebarFor(final String name) {
        final SitePage page = currentPage(name);
        return page == null || showSidebar(SiteContext.current().orgId().getValue(), page);
    }

    /** Whether {@code orgId} hides the sidebar on every page: the Editable Pages dialog then offers no choice. */
    public boolean sidebarForcedOff(final String orgId) {
        return KnownSettings.SIDEBAR_FORCED_OFF.equals(new ConfigCommands().getString(
                KnownSettings.SITE_PAGES_SIDEBAR, orgs.findOrganization(orgId)));
    }

    /** The dialog's sidebar choice in the admin's words, by {@link Sidebar} name, the default saying what it is. */
    public String sidebarLabel(final String orgId, final String sidebar) {
        return switch (Sidebar.from(sidebar)) {
            case DEFAULT -> showSidebar(orgId, null) ? Text.get("feedback.sitePageCommands.sidebar.defaultShown")
                    : Text.get("feedback.sitePageCommands.sidebar.defaultHidden");
            case SHOW -> Text.get("feedback.sitePageCommands.sidebar.show");
            case HIDE -> Text.get("feedback.sitePageCommands.sidebar.hide");
        };
    }

    /** The dialog's sidebar choices, as {@link Sidebar} names. */
    public List<String> sidebarChoices() {
        return Arrays.stream(Sidebar.values()).map(Sidebar::name).toList();
    }

    /** The dialog's access choices, as {@link Access} names: site admins' only for a site admin. */
    public List<String> accessChoices() {
        final boolean siteAdmin = isSiteAdmin();
        return Arrays.stream(Access.values()).filter(value -> siteAdmin || value != Access.SITE_ADMINS)
                .map(Access::name).toList();
    }

    /** An access in the admin page's words, by {@link Access} name (an entry's own, or a dialog choice). */
    public String accessLabel(final String access) {
        return switch (Access.from(access)) {
            case EVERYONE -> Text.get("feedback.sitePageCommands.access.everyone");
            case SIGNED_IN -> Text.get("feedback.sitePageCommands.access.signedIn");
            case ORG_ADMINS -> Text.get("feedback.sitePageCommands.access.orgAdmins");
            case SITE_ADMINS -> Text.get("feedback.sitePageCommands.access.siteAdmins");
        };
    }

    /**
     * Whether {@code orgId}'s Help menu holds Make Payment: every org's but the platform org's, because the product's
     * own site takes no payments. The ONE place that says so: the menu ({@link #isPaymentInHelp}), the Editable Pages
     * preview and {@link #helpMenuShown} all read it.
     */
    public boolean paymentInHelp(final String orgId) {
        final Organization org = orgs.findOrganization(orgId);
        return org == null || !org.isPlatform();
    }

    /**
     * {@link #paymentInHelp} for the current host, the menu's gate on Make Payment: an org site asks its org, a
     * shared host holds it, and the placeholder marketing host, which no org holds, does not.
     */
    public boolean isPaymentInHelp() {
        final SiteContext site = SiteContext.current();
        return site.isOrg() ? paymentInHelp(site.orgId().getValue()) : !site.isMarketing();
    }

    /**
     * Whether {@code orgId}'s site shows the Help submenu to the caller: the ONE rule the menu
     * ({@link #isHelpMenuShown}) and the Editable Pages preview both read, so they cannot disagree. A site whose
     * Help holds Make Payment always has one; otherwise (the product's own site) Help shows only once there is
     * something in it the caller may open: a Help-placed page or link, or the org's own donate link (its Donate
     * entry).
     */
    public boolean helpMenuShown(final String orgId) {
        if (paymentInHelp(orgId)) {
            return true;
        }
        final Organization org = orgs.findOrganization(orgId);
        // The entries the READER may open (canView, the menu's own filter), not the admin list's: a Help holding
        // only a signed-in page must not give an anonymous visitor an empty submenu.
        final boolean helpPage = pagesOf(orgId).stream().anyMatch(page -> page.where() == Placement.HELP
                && canView(orgId, page));
        return helpPage || new BrandCommands().lookOf(org).donateUrl() != null;
    }

    /**
     * {@link #helpMenuShown} for the current host, the menu's gate: true on every host but the marketing one, the
     * platform org's rule on its site, and false on the placeholder marketing host, which no org holds.
     */
    public boolean isHelpMenuShown() {
        final SiteContext site = SiteContext.current();
        if (!site.isMarketing()) {
            return true;
        }
        return site.isOrg() && helpMenuShown(site.orgId().getValue());
    }

    /**
     * The title a reader sees for an entry, in the menu and in a page's browser title: the one written for the
     * request's language ({@code SitePage.titles}), else the entry's own title. Null for no entry.
     */
    public String titleOf(final SitePage page) {
        return page == null ? null : page.titleIn(RequestContext.currentLocale().getLanguage());
    }

    /**
     * The languages {@code orgId}'s site offers besides its default: the Editable Pages dialog's one extra title field
     * each. Empty for a one-language site.
     */
    public List<Language> otherLanguages(final String orgId) {
        return new ConfigCommands().otherSiteLanguages(orgs.findOrganization(orgId));
    }

    /**
     * The dialog's per-language title fields, seeded: language tag to the entry's title in it ("" when none is
     * written, and for a new entry), for each of {@link #otherLanguages}. A mutable map, for the fields to bind.
     */
    public Map<String, String> titlesEdit(final String orgId, final SitePage page) {
        final Map<String, String> edit = new LinkedHashMap<>();
        for (final Language language : otherLanguages(orgId)) {
            edit.put(language.getTag(), page == null ? "" : page.titles().getOrDefault(language.getTag(), ""));
        }
        return edit;
    }

    /**
     * The title the site's default-language readers see for {@code page} when it is not the main title: one stored
     * for that language before it became the default. The dialog has no field for it, so it shows it under the
     * Title, since rewriting the Title replaces it. Null when there is none, or it reads the same.
     */
    public String defaultReadersSee(final String orgId, final SitePage page) {
        if (page == null) {
            return null;
        }
        final String tag = new ConfigCommands().siteLanguages(orgs.findOrganization(orgId)).get(0).getTag();
        final String shown = page.titles().get(tag);
        return shown == null || shown.equals(page.title()) ? null : shown;
    }

    /** The menu's target for an entry: a page's own address on this site, or a link's URL. */
    public String hrefOf(final SitePage page) {
        return page.isLink() ? page.url() : SitePage.PATH + page.name();
    }

    /** The Help menu's icon for an entry, like the built-in items have: a page here, or a link elsewhere. */
    public String iconOf(final SitePage page) {
        return page.isLink() ? "pi pi-external-link" : "pi pi-file";
    }

    /**
     * Where a page can be seen and edited: its address on THIS host when this is the org's site, else on the
     * org's own site as the admin pages' links build it ({@link SiteCommands#orgSiteRoot(Organization,
     * HttpServletRequest)}: {@code {slug}.localhost} from a localhost request, else the custom domain or the
     * subdomain); null when the org has no site yet. A link answers its URL.
     */
    public String viewUrl(final String orgId, final SitePage page) {
        if (page.isLink()) {
            return page.url();
        }
        if (SiteContext.current().isSiteOf(orgId)) {
            return SitePage.PATH + page.name();
        }
        final Organization org = orgs.findOrganization(orgId);
        if (org == null || org.getSlug() == null || org.getSlug().isBlank()) {
            return null;
        }
        return SiteCommands.orgSiteRoot(org, SiteCommands.facesRequest()) + SitePage.PATH + page.name();
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
        return addPage(orgId, title, Map.of(), name, placement, null, null);
    }

    /**
     * {@link #addPage(String, String, String, String)} with the page's title in the site's other languages, the
     * {@link Access} name of who may see it (null or blank for everyone; only a site admin may add one for site
     * admins) and its own sidebar choice (a {@link Sidebar} name; null or blank follows the organization's default).
     */
    public boolean addPage(final String orgId, final String title, final Map<String, String> titles,
            final String name, final String placement, final String access, final String sidebar) {
        if (!mayChoose(access)) {
            return false;
        }
        final String cleanName = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if (!OrgPageBootstrap.isPageName(cleanName)) {
            return PageFeedback.refuse(Text.get("feedback.sitePageCommands.invalidAddress"),
                    Text.get("feedback.sitePageCommands.addressShape"));
        }
        if (OrgPageBootstrap.isReservedPageName(cleanName)) {
            // "pay" as well as "home": its content key is the organization's How to pay page, which the pay
            // page serves and seeds, so an Editable Page there would be a second door onto the same rows.
            return PageFeedback.refuse(Text.get("feedback.sitePageCommands.addressTaken"),
                    Text.get("feedback.sitePageCommands.addressReserved"));
        }
        return add(orgId, new SitePage(cleanName, title, null, Placement.from(placement).name(), titles, access,
                sidebar), true);
    }

    /** Adds a menu link to {@code url} (absolute http(s) or a site path such as {@code /pay}). */
    public boolean addLink(final String orgId, final String title, final String url, final String placement) {
        return addLink(orgId, title, Map.of(), url, placement);
    }

    /** {@link #addLink(String, String, String, String)} with the link's title in the site's other languages. */
    public boolean addLink(final String orgId, final String title, final Map<String, String> titles,
            final String url, final String placement) {
        final String target = ContentRenderer.requireLinkUrl(url);
        if (target.isEmpty() || target.startsWith("#")) {
            return PageFeedback.refuse(Text.get("feedback.sitePageCommands.invalidLink"),
                    Text.get("feedback.sitePageCommands.linkShape"));
        }
        final String name = "link-" + UUID.randomUUID().toString().substring(0, 8);
        return add(orgId, new SitePage(name, title, target, Placement.from(placement).name(), titles), false);
    }

    /**
     * The Editable Pages dialog's one Save: {@code mode} "edit" changes the named entry, "link" adds a link, anything
     * else adds a page. One Java call rather than a branch in the page's script, whose comparisons are fragile.
     */
    public boolean saveDialog(final String orgId, final String mode, final String name, final String title,
            final String url, final String placement) {
        return saveDialog(orgId, mode, name, title, null, url, placement, null, null);
    }

    /**
     * {@link #saveDialog(String, String, String, String, String, String)} with the dialog's per-language titles
     * ({@link #titlesEdit}): only the languages the org's site offers besides its default are kept, a blank one
     * meaning the entry's own title, and each is held to the title's own length rule. With the page's access, an
     * {@link Access} name, and its sidebar choice, a {@link Sidebar} name: both ignored for a link, which is never
     * gated, and null keeps an edited entry's own.
     */
    public boolean saveDialog(final String orgId, final String mode, final String name, final String title,
            final Map<String, String> titles, final String url, final String placement, final String access,
            final String sidebar) {
        final Map<String, String> cleanTitles = titles == null ? null : cleanTitles(orgId, titles);
        if (titles != null && cleanTitles == null) {
            return false;
        }
        if ("edit".equals(mode)) {
            return updatePage(orgId, name, title, cleanTitles, url, placement, access, sidebar);
        }
        final Map<String, String> given = cleanTitles == null ? Map.of() : cleanTitles;
        return "link".equals(mode) ? addLink(orgId, title, given, url, placement)
                : addPage(orgId, title, given, name, placement, access, sidebar);
    }

    /** Changes an entry's title and placement (and a link's URL); its address never changes. */
    public boolean updatePage(final String orgId, final String name, final String title, final String url,
            final String placement) {
        return updatePage(orgId, name, title, null, url, placement, null, null);
    }

    /**
     * {@link #updatePage(String, String, String, String, String)} that also replaces the titles of the languages the
     * site offers (its default's only when the main title changes; a stored title for a language it does not offer
     * is kept; null {@code titles} keeps them all), and sets the page's access (an {@link Access} name; only a site
     * admin may choose site admins) and its sidebar choice (a {@link Sidebar} name). A null access or sidebar keeps
     * the one the page has.
     */
    public boolean updatePage(final String orgId, final String name, final String title,
            final Map<String, String> titles, final String url, final String placement, final String access,
            final String sidebar) {
        final String cleanTitle = cleanTitle(title);
        if (cleanTitle == null || !mayChoose(access)) {
            return false;
        }
        final List<Language> languages = new ConfigCommands().siteLanguages(orgs.findOrganization(orgId));
        final Set<String> offered = languages.stream().map(Language::getTag).collect(Collectors.toSet());
        final Details details = new Details(cleanTitle, titles, offered, languages.get(0).getTag(), url, placement,
                access, sidebar);
        return change(orgId, name, (pages, index) -> applyDetails(pages, index, details), "updated");
    }

    /**
     * The per-language titles worth storing: the offered other languages' non-blank ones, trimmed. Null (with a
     * growl) when one breaks the length rule {@link #cleanTitle} holds the default title to.
     */
    private Map<String, String> cleanTitles(final String orgId, final Map<String, String> titles) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (final Language language : otherLanguages(orgId)) {
            final String text = titles.get(language.getTag());
            final boolean written = text != null && !text.isBlank();
            if (written && cleanTitle(text) == null) {
                return null;
            }
            if (written) {
                out.put(language.getTag(), text.trim());
            }
        }
        return out;
    }

    /**
     * Moves an entry one place up ({@code delta} -1) or down (+1) WITHIN ITS OWN MENU: it swaps with the nearest
     * entry of the same placement, so every click changes what visitors see. The ends are no-ops.
     */
    public boolean movePage(final String orgId, final String name, final int delta) {
        return change(orgId, name, (pages, index) -> reorder(pages, index, delta, this::mayManage),
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
        // Its language versions go with it: left behind, a new page of the same name would serve the deleted
        // page's Spanish (PageVersions.resolve picks any version that has rows).
        final String key = OrgPageBootstrap.pageKey(Organization.Id.from(orgId.trim()), removed[0].name());
        boolean all = deleteContent(key);
        for (final Language language : Language.values()) {
            all &= deleteContent(PageVersions.keyFor(key, language));
        }
        return all;
    }

    /**
     * Seeds the org site's starter Editable Pages ({@link OrgPageBootstrap#starterPages()}) ONCE: on the subdomain's
     * first assignment ({@code OrgCommands.saveOrgEdits}, beside {@code ensureHomePage}) and for the local
     * fixture's sites; {@code scripts/seed-starter-site-pages.py} in the private repo does the same for sites hosted
     * before starters existed. Each starter the list lacks is appended and gets its starter content only when nothing
     * is stored under its key yet, so rows already there (a hand-built board) are adopted as-is; a starter whose name
     * the list already has is left alone. An appended starter's entry carries its menu title in every starter language,
     * the site's default one as its {@code title}; its content is English only. The org row records the seeding, so a
     * starter page deleted later never comes back. The platform org's site is the product's own, whose Editable Pages
     * are the marketing pages ({@code MarketingPageBootstrap.sitePages()}, installed by the private repo's
     * {@code update-marketing-site} script) rather than an About Us and a Board, so it gets only the starters meant
     * for every site ({@code everySite}: the metrics dashboard).
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
        final List<String> added = addStarters(fresh);
        if (added == null) {
            return false;
        }
        fresh.setSitePagesSeededAt(LocalDateTime.now());
        if (!orgs.saveOrganization(fresh)) {
            return false;
        }
        orgs.audit(callerSource.get(), fresh, "Starter Editable Pages seeded for the site '" + fresh.getSlug() + "': "
                + (added.isEmpty() ? "none added" : String.join(", ", added)));
        return true;
    }

    // ------------------------------------------------------------------ internals

    /** One edit of a fresh copy of the entry list; false (already growled) leaves the org untouched. */
    @FunctionalInterface
    interface Edit {
        boolean apply(List<SitePage> pages, int index);
    }

    /** What the settings dialog saves on one entry; a null {@code titles} or {@code access} keeps the entry's own. */
    private record Details(String title, Map<String, String> titles, Set<String> offered, String defaultTag,
            String url, String placement, String access, String sidebar) {
    }

    /**
     * The dialog's changes on one entry. Its titles replace those of the languages the site offers ({@link #replaced});
     * a title for a language the site does not offer yet (a starter's, seeded in every starter language) is kept, so
     * the entry still reads translated when the site adds that language (review, 2026-11-24).
     */
    private static boolean applyDetails(final List<SitePage> pages, final int index, final Details details) {
        final SitePage page = pages.get(index);
        final String target = page.isLink() ? ContentRenderer.requireLinkUrl(details.url()) : null;
        if (page.isLink() && (target.isEmpty() || target.startsWith("#"))) {
            return PageFeedback.refuse(Text.get("feedback.sitePageCommands.invalidLink"),
                    Text.get("feedback.sitePageCommands.linkShape"));
        }
        final SitePage changed = page.withDetails(details.title(), target, Placement.from(details.placement()));
        final SitePage titled = details.titles() == null ? changed
                : changed.withTitles(keptAlongside(page.titles(), details.titles(), replaced(page, details)));
        final boolean gated = details.access() != null && !page.isLink();
        final SitePage withAccess = gated ? titled.withAccess(Access.from(details.access())) : titled;
        final boolean sided = details.sidebar() != null && !page.isLink();
        pages.set(index, sided ? withAccess.withSidebar(Sidebar.from(details.sidebar())) : withAccess);
        return true;
    }

    /**
     * The languages whose stored titles the dialog's replace: those of its fields, and the site's default too when the
     * main title (the default's field) was changed. A stored title for the default, left from before the default
     * changed, is what the default's readers see; the dialog shows no field for it, so a save that leaves the main
     * title as it was keeps it (review, 2026-11-24), and one that rewrites the main title replaces it.
     */
    private static Set<String> replaced(final SitePage page, final Details details) {
        final Set<String> replaced = new HashSet<>(details.offered());
        if (details.title().equals(page.title())) {
            replaced.remove(details.defaultTag());
        }
        return replaced;
    }

    /** The dialog's titles, plus the stored ones for languages it did not replace. */
    private static Map<String, String> keptAlongside(final Map<String, String> stored,
            final Map<String, String> shown, final Set<String> replaced) {
        final Map<String, String> out = new LinkedHashMap<>(shown);
        for (final Map.Entry<String, String> title : stored.entrySet()) {
            if (!replaced.contains(title.getKey())) {
                out.putIfAbsent(title.getKey(), title.getValue());
            }
        }
        return out;
    }

    /**
     * Swaps the entry with its neighbor in the same menu that the caller can see on the list; at either end it stays
     * put (a success). A page only site admins see is passed over for anyone else, so an org admin's click always
     * moves the rows they see.
     */
    private static boolean reorder(final List<SitePage> pages, final int index, final int delta,
            final Predicate<SitePage> visible) {
        final int target = neighborIndex(pages, index, delta, visible);
        if (target >= 0) {
            pages.set(index, pages.set(target, pages.get(index)));
        }
        return true;
    }

    /**
     * The index of the nearest entry beyond {@code index} in {@code delta}'s direction with the same placement that is
     * {@code visible} on the caller's list.
     */
    static int neighborIndex(final List<SitePage> pages, final int index, final int delta,
            final Predicate<SitePage> visible) {
        final Placement where = pages.get(index).where();
        final int step = Integer.signum(delta);
        for (int i = index + step; step != 0 && i >= 0 && i < pages.size(); i += step) {
            if (pages.get(i).where() == where && visible.test(pages.get(i))) {
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
            return PageFeedback.refuse(Text.get("feedback.sitePageCommands.tooManyPages"),
                    Text.get("feedback.sitePageCommands.tooManyPagesDetail", MAX_PAGES));
        }
        final Optional<SitePage> clash = find(pages, entry.name());
        if (clash.isPresent()) {
            // No link to an entry the caller's list does not show (the site admins' metrics page): it would only
            // open a refusal.
            return PageFeedback.refuse(Text.get("feedback.sitePageCommands.addressTaken"), mayManage(clash.get())
                    ? Text.get("feedback.sitePageCommands.addressInUse", SitePage.PATH + entry.name())
                    : Text.get("feedback.sitePageCommands.addressHeld"));
        }
        pages.add(new SitePage(entry.name(), cleanTitle, entry.url(), entry.placement(), entry.titles(),
                entry.access(), entry.sidebar()));
        fresh.setSitePages(pages);
        if (!orgs.saveOrganization(fresh)) {
            return false;
        }
        orgs.audit(callerSource.get(), fresh, (entry.isLink() ? "Menu link '" : "Editable page '") + cleanTitle
                + "' (" + entry.name() + ") added");
        if (seed && !seedPage(OrgPageBootstrap.pageKey(fresh.getId(), entry.name()))) {
            return PageFeedback.refuse(Text.get("feedback.sitePageCommands.addedWithoutContent"),
                    Text.get("feedback.sitePageCommands.addedWithoutContentDetail"));
        }
        PageFeedback.info(Text.get("feedback.sitePageCommands.added", cleanTitle));
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
            return PageFeedback.refuse(Text.get("feedback.sitePageCommands.unknownPage"),
                    Text.get("feedback.sitePageCommands.pageGone"));
        }
        final SitePage before = pages.get(index);
        final String title = before.title();
        if (!mayManage(before)) {
            return PageFeedback.refuse(Text.get("feedback.shared.notAllowed"),
                    Text.get("feedback.sitePageCommands.siteAdminsOnly"));
        }
        if (!edit.apply(pages, index)) {
            return false;
        }
        fresh.setSitePages(pages.isEmpty() ? null : pages);
        if (indexOf(pages, name) < 0) {
            // A deleted page's stylesheet choice goes with it: a new page of the same name starts plain.
            dropStylesheet(fresh, name);
        }
        if (!orgs.saveOrganization(fresh)) {
            return false;
        }
        orgs.audit(callerSource.get(), fresh, "Editable page '" + title + "' (" + name + ") " + what
                + gateChange(before, pages, index));
        return true;
    }

    private static void dropStylesheet(final Organization org, final String name) {
        if (org.getPageStylesheets() != null && org.getPageStylesheets().containsKey(name)) {
            final Map<String, String> left = new LinkedHashMap<>(org.getPageStylesheets());
            left.remove(name);
            org.setPageStylesheets(left.isEmpty() ? null : left);
        }
    }

    /**
     * " (access EVERYONE -> ORG_ADMINS, sidebar DEFAULT -> HIDE)" when an edit changed who may see the entry or its
     * sidebar, else "": the audit line must say which save opened or closed a page. A move or a delete leaves
     * another entry (or none) at {@code index}, so it adds nothing.
     */
    private static String gateChange(final SitePage before, final List<SitePage> after, final int index) {
        if (index >= after.size() || !after.get(index).name().equals(before.name())) {
            return "";
        }
        final SitePage now = after.get(index);
        final List<String> parts = new ArrayList<>();
        if (now.who() != before.who()) {
            parts.add("access " + before.who() + " -> " + now.who());
        }
        if (now.side() != before.side()) {
            parts.add("sidebar " + before.side() + " -> " + now.side());
        }
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    /**
     * Whether the caller may see and change {@code page} on the Editable Pages admin page: every entry but a site
     * admins' page, which only a site admin manages.
     */
    private boolean mayManage(final SitePage page) {
        return page.isLink() || page.who() != Access.SITE_ADMINS || isSiteAdmin();
    }

    /**
     * Whether the caller may give a page {@code access}: anyone managing the list may, but site admins' only a site
     * admin.
     */
    private boolean mayChoose(final String access) {
        if (access == null || Access.from(access) != Access.SITE_ADMINS || isSiteAdmin()) {
            return true;
        }
        return PageFeedback.refuse(Text.get("feedback.shared.notAllowed"),
                Text.get("feedback.sitePageCommands.siteAdminsOnly"));
    }

    private boolean isSiteAdmin() {
        return callerSource.get().isSignedInSiteAdmin();
    }

    /** A fresh org the caller may manage, or null (with a growl). */
    private Organization editable(final String orgId) {
        if (!orgs.canManageOrg(orgId)) {
            PageFeedback.refuse(Text.get("feedback.shared.notAllowed"),
                    Text.get("feedback.sitePageCommands.adminsOnly"));
            return null;
        }
        final Organization fresh = orgs.freshOrg(orgId);
        if (fresh == null) {
            PageFeedback.refuse(Text.get("feedback.shared.unableToSave"),
                    Text.get("feedback.orgCommands.unknownOrg"));
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
        final Language siteDefault = new ConfigCommands().siteLanguages(org).get(0);
        for (final StarterPage starter : startersFor(org)) {
            final boolean wanted = find(pages, starter.name()).isEmpty() && pages.size() < MAX_PAGES;
            if (wanted && !seedStarter(org.getId(), starter)) {
                return null;
            }
            if (wanted) {
                pages.add(starter.entry(siteDefault));
                added.add(starter.name());
            }
        }
        org.setSitePages(pages.isEmpty() ? null : pages);
        return added;
    }

    /**
     * The starters {@code org}'s site gets: every one, except that the product's own site (the platform org) gets
     * only those meant for every site ({@code everySite}, the metrics dashboard), never About Us or the Board.
     */
    static List<StarterPage> startersFor(final Organization org) {
        return OrgPageBootstrap.starterPages().stream().filter(starter -> starter.everySite() || !org.isPlatform())
                .toList();
    }

    private static boolean seedStarter(final Organization.Id orgId, final StarterPage starter) {
        final String key = OrgPageBootstrap.pageKey(orgId, starter.name());
        try {
            if (OrgCommands.seedIfEmpty(key, OrgPageBootstrap.starterRows(orgId, starter,
                    OrgCommands::currentTemplateVersion))) {
                return true;
            }
        } catch (final RuntimeException ex) {
            log.error("Unable to seed the starter Editable Page {}", key, ex);
        }
        return PageFeedback.refuse(Text.get("feedback.sitePageCommands.startersNotCreated"),
                Text.get("feedback.sitePageCommands.starterNotSaved", starter.title()));
    }

    private static String cleanTitle(final String title) {
        final String clean = title == null ? "" : title.trim();
        if (clean.isEmpty() || clean.length() > MAX_TITLE) {
            PageFeedback.refuse(Text.get("feedback.sitePageCommands.titleNeeded"),
                    Text.get("feedback.sitePageCommands.titleShape", MAX_TITLE));
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
            log.error("Unable to seed Editable Page {}", pageKey, ex);
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
            return all || PageFeedback.refuse(Text.get("feedback.sitePageCommands.contentNotDeleted"),
                    Text.get("feedback.sitePageCommands.someSectionsLeft"));
        } catch (final RuntimeException ex) {
            log.error("Unable to delete the content of {}", pageKey, ex);
            return PageFeedback.refuse(Text.get("feedback.sitePageCommands.contentNotDeleted"),
                    Text.get("feedback.sitePageCommands.sectionsLeft"));
        }
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
