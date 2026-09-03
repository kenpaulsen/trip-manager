package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.ContentRenderer;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.SettingDef;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.web.Sessions;

/**
 * The look of the site a request is for, exposed to pages as {@code #{brand}}: theme and layout stylesheet
 * names, logo / wordmark, favicon, link-preview image, page background, footer, the "Questions?" contact
 * card, the Donate link and the analytics id.
 *
 * <p>On an ORGANIZATION's host every answer comes from the org's own Branding settings (the org-only rung:
 * {@code KnownSettings.BRANDING_SECTION}) or, where a setting is blank, from the neutral platform default --
 * NEVER from the shared site's look, whose logo, footer and contacts are literals in the shared pages and
 * stay there. On every other host (shared, marketing, no bound request) the answers are the NEUTRAL /
 * empty values: the pages branch on {@code #{site.orgSite}} and keep their literal chrome, so nothing here
 * changes a shared page. An org whose row cannot be read renders with the neutral look rather than failing.
 *
 * <p>Like {@code SiteCommands}, this reads only the request's {@link SiteContext} (never session or view
 * scope -- one session serves several sites) plus two session values read through {@code getSession(false)},
 * so no session is ever created for a visitor: the {@code dark} flag the topbar's personal toggle sets
 * (which OUTRANKS the organization's own {@code site.theme.dark} setting, and only when it is actually
 * set -- see {@link #isDark()}), and the org Appearance page's PREVIEW (see below). Every URL that lands in
 * an attribute is re-checked here even though the save paths validate: a row written by hand bypasses the
 * page, and an attribute is the wrong place to discover that.
 *
 * <p><b>The preview rung.</b> An administrator editing an organization's Appearance page sees their unsaved
 * choices applied to the page they are on, rendered by the server -- the theme stylesheet, the layout sheet
 * and the page background really change. The unsaved values live on that one admin's session (scalars only:
 * {@code Sessions.APPEARANCE_PREVIEW_ORG} plus a name/value map) and are consulted BEFORE the organization's
 * stored settings, but only when this request is rendering that org's Appearance page. The trigger is
 * deliberately the VIEW ID plus the {@code orgId} request parameter ({@link #appearanceViewOrgId}) rather
 * than a flag a page sets: the theme is resolved while {@code h:head} renders, and an {@code initPage}
 * handler that sets a request-scope marker has not necessarily run by then, so a marker would preview the
 * page unreliably. Everything else -- every other view, every other org, every other person, and the
 * organization's live site -- reads the stored values, always.
 */
@Slf4j
@Named("brand")
@ApplicationScoped
public class BrandCommands {

    /** The platform's own theme when no palette is chosen (and on every non-org host). */
    static final String DEFAULT_THEME_LIGHT = "freya-medj-l";
    static final String DEFAULT_THEME_DARK = "freya-medj-d";
    /** The favicon href that makes a browser fetch nothing -- no stray /favicon.ico hit on an org site. */
    static final String NO_FAVICON = "data:,";
    private static final String BG_NONE = "--site-bg:none";
    /** The custom property {@code site.css} paints when there is no background image. */
    private static final String BG_COLOR_PROPERTY = "--site-bg-color:";
    /** The color the palette fallback below falls back to, and what the page's color picker opens on. */
    static final String FALLBACK_BACKGROUND_COLOR = "#333333";
    /**
     * What an organization that has chosen NO background color paints: the palette's OWN tint, so the page
     * behind the cards follows the chosen colors and the light/dark choice by itself instead of sitting on a
     * fixed grey that suits one of them at best. Three rungs, outermost first:
     *
     * <ol>
     *   <li>{@code --site-bg-default} -- the palette's primary mixed into white (light) or near-black
     *       (dark), declared per palette by the Freya build (the sibling repo's
     *       {@code freya/.../sass/theme/_theme_{light,dark}.scss}). This is the rung that carries HUE:
     *       a green site comes out green and a purple one purple.</li>
     *   <li>{@code --surface-ground} -- the theme's ground color, for a stylesheet built before that
     *       property existed. It tracks light/dark but not hue: the shared theme partials give every light
     *       palette {@code #F2F4F6} and every dark one {@code #3E4754}, which is exactly the sameness this
     *       fallback chain exists to get out of.</li>
     *   <li>{@link #FALLBACK_BACKGROUND_COLOR} -- a literal, for a theme that declares neither.</li>
     * </ol>
     *
     * <p>A {@code var()} nested in a custom property's value is substituted where the property is DECLARED,
     * which is the {@code <html>} element carrying both this and the theme's {@code :root} block, so
     * {@code site.css}'s own {@code var(--site-bg-color, transparent)} sees a plain color.
     */
    static final String PALETTE_BACKGROUND =
            "var(--site-bg-default, var(--surface-ground, " + FALLBACK_BACKGROUND_COLOR + "))";

    /** The Appearance page's view id: the one view a preview may change. */
    static final String APPEARANCE_VIEW_ID = "/admin/orgAppearance.xhtml";
    /** The Appearance page's URL, whose {@code orgId} parameter half of the preview trigger keys off. */
    static final String APPEARANCE_URL = "/admin/orgAppearance.jsf";
    private static final String ORG_PARAM = "orgId";

    /**
     * The background chooser's mode, carried in the edit map under a key that is NOT a setting (it is never
     * stored: it only decides which control the page shows and which settings a save clears). "Image" with a
     * blank URL is a real state -- somebody who has just switched to Image and not typed the URL yet -- so
     * the mode cannot simply be derived from the values on every render.
     *
     * <p>{@link #BG_MODE_PALETTE} is what a BLANK color means, and how a blank one round-trips through a
     * color picker that always has some value in it: choosing it saves both background settings blank, and
     * the site then takes a tint of the palette's own color.
     */
    public static final String BG_MODE_KEY = "bg.mode";
    public static final String BG_MODE_PALETTE = "palette";
    public static final String BG_MODE_COLOR = "color";
    public static final String BG_MODE_IMAGE = "image";

    /**
     * The branding settings the Appearance page draws with a control of its OWN (a palette menu, a dark-mode
     * choice, the background chooser); everything else in {@link KnownSettings#branding()} is rendered from
     * {@link #getDetailFields()} as a labelled text box, so a branding setting added later cannot land on
     * neither page. {@code OrgAppearancePreviewTest} holds the two halves against the section.
     */
    static final List<String> DEDICATED_FIELDS = List.of(
            KnownSettings.SITE_THEME_PALETTE.getName(), KnownSettings.SITE_THEME_DARK.getName(),
            KnownSettings.SITE_LAYOUT.getName(),
            KnownSettings.SITE_LOGO_URL.getName(), KnownSettings.SITE_FAVICON_URL.getName(),
            KnownSettings.SITE_BACKGROUND_URL.getName(), KnownSettings.SITE_BACKGROUND_COLOR.getName());

    /**
     * Test seam: the session's dark-mode flag, or null when this visitor has never set one (only the
     * theme/layout/dark getters consult it).
     */
    @Setter
    private Supplier<Boolean> darkSource = BrandCommands::sessionDark;

    /** Test seam: this request's session, if it already has one. NEVER creates one (getSession(false)). */
    @Setter
    private Supplier<HttpSession> sessionSource = BrandCommands::currentSession;

    /** Test seam: the org whose Appearance page this request renders, or null (the preview's trigger). */
    @Setter
    private Supplier<String> appearanceViewSource = BrandCommands::appearanceViewOrgId;

    /** Test seam: the org behind an org host (a failed read is the neutral look, never a broken page). */
    @Setter
    private Function<Organization.Id, Optional<Organization>> orgSource =
            id -> DAO.getInstance().getOrganization(id, Cached.YES);

    private final ConfigCommands config = new ConfigCommands();

    // --- theme ---

    /**
     * Whether this request is drawn dark, in precedence order:
     *
     * <ol>
     *   <li>the visitor's OWN choice, when they have made one -- the topbar's Dark Mode toggle, which is a
     *       personal preference and keeps working exactly as it did for whoever has used it;</li>
     *   <li>on an org site (or its Appearance preview), the organization's {@code site.theme.dark} setting;
     *   </li>
     *   <li>light.</li>
     * </ol>
     *
     * <p>Everything else derived from dark -- the theme name, the Freya layout stylesheet, the topbar and
     * menu classes -- reads this one answer, so an organization's choice reaches all of them.
     */
    public boolean isDark() {
        final Boolean own = darkSource.get();
        return (own == null) ? Boolean.parseBoolean(setting(KnownSettings.SITE_THEME_DARK)) : own;
    }

    /**
     * The PrimeFaces theme name: {@code freya-{palette}-{light|dark}} on an org host with a palette chosen,
     * else the platform default.
     */
    public String getTheme() {
        final String palette = palette();
        if (palette == null) {
            return isDark() ? DEFAULT_THEME_DARK : DEFAULT_THEME_LIGHT;
        }
        return "freya-" + palette + "-" + mode();
    }

    /**
     * The Freya layout stylesheet's basename (no {@code .css}): {@code layout-{light|dark}} for the platform
     * default, {@code layout-{palette}-{light|dark}} when a palette is chosen.
     */
    public String getLayoutCss() {
        final String palette = palette();
        return palette == null ? "layout-" + mode() : "layout-" + palette + "-" + mode();
    }

    private String mode() {
        return isDark() ? "dark" : "light";
    }

    // --- layout ---

    /**
     * Whether the HOME page drops the content card and the sidebar so its band sections span the window:
     * always on the product's own marketing host, and on an organization's site when it chose
     * {@code site.layout = full-width} (its Appearance page). False on the shared hosts, off a bound
     * request, and for any other stored value -- the classic layout is the safe answer, and the page
     * markup those hosts render is then byte-for-byte what it was. No session is consulted.
     */
    public boolean isFullWidth() {
        if (SiteContext.current().isMarketing()) {
            return true;
        }
        return KnownSettings.LAYOUT_FULL_WIDTH.equals(setting(KnownSettings.SITE_LAYOUT));
    }

    /**
     * The org's chosen palette, or null. Re-checked against the declared choices even though the save
     * path refuses others: a palette that is not shipped is a stylesheet 404 on every page of the site.
     */
    private String palette() {
        final String chosen = setting(KnownSettings.SITE_THEME_PALETTE);
        return chosen != null && KnownSettings.SITE_THEME_PALETTE.allows(chosen) ? chosen : null;
    }

    // --- identity ---

    /** The org's logo URL, or null (no logo, not an org host, or a URL unsafe for an attribute). */
    public String getLogoUrl() {
        return safeUrl(setting(KnownSettings.SITE_LOGO_URL));
    }

    /** The org's name, for the top bar when it has no logo; null when there is a logo or off an org host. */
    public String getWordmark() {
        if (!isBranded() || getLogoUrl() != null) {
            return null;
        }
        return orgName();
    }

    /**
     * The favicon href on an org host: the org's own icon, else {@code data:,} so the browser fetches
     * nothing (the shared site's /favicon.ico must not become an org site's icon by default). Null off an
     * org host, where the page keeps its own link.
     */
    public String getFaviconHref() {
        if (!isBranded()) {
            return null;
        }
        final String own = safeUrl(setting(KnownSettings.SITE_FAVICON_URL));
        return own == null ? NO_FAVICON : own;
    }

    /** The link-preview (Open Graph) image: the org's own, else its logo, else null; null off an org host. */
    public String getOgImage() {
        if (!isBranded()) {
            return null;
        }
        final String own = safeUrl(setting(KnownSettings.SITE_OG_IMAGE_URL));
        return own == null ? getLogoUrl() : own;
    }

    /**
     * The custom properties the root element carries on an org host, for {@code site.css} to paint:
     * {@code --site-bg:url(...)} with the org's background image, or {@code --site-bg:none} plus
     * {@code --site-bg-color:} when there is no image -- the org's chosen COLOR, or, when it has chosen
     * none, {@link #PALETTE_BACKGROUND} so the page takes the palette's own tint. Null off an org host:
     * the shared site's stylesheet keeps its own fallbacks (its rainbow image and no color), so that page is
     * unchanged to the byte.
     *
     * <p>The two are mutually exclusive and the image wins -- a color under an image is a setting that
     * silently does nothing, the rule chat backgrounds already follow. The URL is percent-encoded for the
     * {@code url()} context (see {@link #cssUrl}) so it can neither close the function nor start another
     * declaration, and the color is re-screened as a hex value for the same reason.
     */
    public String getRootStyle() {
        if (!isBranded()) {
            return null;
        }
        final String background = safeUrl(setting(KnownSettings.SITE_BACKGROUND_URL));
        if (background != null) {
            return "--site-bg:url(" + cssUrl(background) + ")";
        }
        return BG_NONE + ";" + BG_COLOR_PROPERTY + backgroundColor();
    }

    /**
     * The page background color: the org's own when it is a usable hex value, else {@link #PALETTE_BACKGROUND}
     * so the page follows the chosen palette's own tint and the light/dark mode. Never null, and never
     * anything but a hex value or that one fixed expression, because it is interpolated straight into the
     * root element's {@code style}.
     */
    private String backgroundColor() {
        final String chosen = SettingDef.hexColor(setting(KnownSettings.SITE_BACKGROUND_COLOR));
        return chosen == null ? PALETTE_BACKGROUND : chosen;
    }

    // --- footer ---

    /** The footer heading: the org's setting, else its name; null off an org host. */
    public String getFooterTitle() {
        if (!isBranded()) {
            return null;
        }
        final String own = setting(KnownSettings.SITE_FOOTER_TITLE);
        return own == null ? orgName() : own;
    }

    /** The footer's line of text, or null (plain text: the page escapes it). */
    public String getFooterText() {
        return setting(KnownSettings.SITE_FOOTER_TEXT);
    }

    // --- the "Questions?" card ---

    public String getContactName() {
        return setting(KnownSettings.SITE_CONTACT_NAME);
    }

    public String getContactPhone() {
        return setting(KnownSettings.SITE_CONTACT_PHONE);
    }

    /** The organization profile's contact email, or null. */
    public String getContactEmail() {
        return org().map(Organization::getContactEmail).filter(email -> !email.isBlank()).orElse(null);
    }

    /** Whether the card has anything to show: any of name, phone, email. */
    public boolean isShowContact() {
        return getContactName() != null || getContactPhone() != null || getContactEmail() != null;
    }

    // --- donate ---

    public String getDonateUrl() {
        return safeUrl(setting(KnownSettings.SITE_DONATE_URL));
    }

    public boolean isShowDonate() {
        return getDonateUrl() != null;
    }

    // --- analytics ---

    /** The org's own analytics id, or null; null off an org host, where the shared pages keep theirs. */
    public String getAnalyticsId() {
        return setting(KnownSettings.SITE_ANALYTICS_ID);
    }

    // --- the preview: one admin's unsaved appearance, on one page ---

    /**
     * Stores this administrator's unsaved appearance values for {@code orgId}, replacing any earlier ones.
     * They apply to that org's Appearance page, for this session only, until {@link #clearPreview} (Cancel,
     * or a successful Save) drops them; the page redirects to itself afterwards so the whole document --
     * including the theme stylesheet link, which no ajax update can replace -- is re-rendered by the server.
     *
     * <p>Deliberately NOT an authorization point: everything stored here is the caller's own submission,
     * shown back only to the caller, on a page they must pass {@code OrgCommands.canManageOrg} to see at
     * all. The authorization is on the SAVE ({@code OrgCommands.saveOrgSettings} re-checks server-side).
     *
     * @param colorHex the color picker's value, which PrimeFaces reports WITHOUT its leading '#'.
     */
    public void preview(final String orgId, final Map<String, String> values, final String colorHex) {
        final HttpSession session = sessionSource.get();
        if (session == null || orgId == null || orgId.isBlank()) {
            return;
        }
        final Map<String, String> edited = withColor(values, colorHex);
        final LinkedHashMap<String, String> stored = new LinkedHashMap<>();
        for (final SettingDef def : KnownSettings.branding()) {
            stored.put(def.getName(), trimmed(edited.get(def.getName())));
        }
        stored.put(BG_MODE_KEY, backgroundMode(edited));
        session.setAttribute(Sessions.APPEARANCE_PREVIEW_ORG, orgId);
        session.setAttribute(Sessions.APPEARANCE_PREVIEW, stored);
    }

    /** Discards the preview: the page (and this admin's view of the site) returns to the stored values. */
    public void clearPreview() {
        final HttpSession session = sessionSource.get();
        if (session != null) {
            session.removeAttribute(Sessions.APPEARANCE_PREVIEW_ORG);
            session.removeAttribute(Sessions.APPEARANCE_PREVIEW);
        }
    }

    /** Whether this request is rendering a page with unsaved preview values applied (the page's banner). */
    public boolean isPreviewing() {
        return preview() != null;
    }

    /**
     * The Appearance page's edit map: the preview when one is in force, else the org's STORED overrides
     * ("" for each one it inherits). Blank means "not set", exactly as on the generic settings editor.
     *
     * <p>Because a fresh visit is a GET without a preview for that org, arriving at the page always starts
     * from what is stored -- a preview can never outlive the visit that made it, however the admin left.
     */
    public Map<String, String> appearanceEdit(final String orgId) {
        final Map<String, String> preview = previewFor(orgId);
        if (preview != null) {
            return new LinkedHashMap<>(preview);
        }
        final Optional<Organization> org = orgById(orgId);
        final Map<String, String> edit = new LinkedHashMap<>();
        for (final SettingDef def : KnownSettings.branding()) {
            edit.put(def.getName(), storedOverride(org, def));
        }
        edit.put(BG_MODE_KEY, backgroundMode(edit));
        return edit;
    }

    /**
     * The map to hand {@code OrgCommands.saveOrgSettings}: the branding settings only (the mode key is not a
     * setting and never reaches a row), with the LOSING backgrounds cleared -- picking a color drops the
     * image URL, picking an image drops the color, and following the palette drops both -- so what is
     * stored is what shows.
     */
    public Map<String, String> forSave(final Map<String, String> values, final String colorHex) {
        final Map<String, String> edited = withColor(values, colorHex);
        final Map<String, String> out = new LinkedHashMap<>();
        for (final SettingDef def : KnownSettings.branding()) {
            out.put(def.getName(), trimmed(edited.get(def.getName())));
        }
        final String mode = backgroundMode(edited);
        out.put(KnownSettings.SITE_BACKGROUND_COLOR.getName(), BG_MODE_COLOR.equals(mode)
                ? trimmed(edited.get(KnownSettings.SITE_BACKGROUND_COLOR.getName())) : "");
        out.put(KnownSettings.SITE_BACKGROUND_URL.getName(), BG_MODE_IMAGE.equals(mode)
                ? trimmed(edited.get(KnownSettings.SITE_BACKGROUND_URL.getName())) : "");
        return out;
    }

    /**
     * The branding settings with no purpose-built control of their own, in declaration order: the page
     * renders exactly these as labelled text boxes in its "Site details" fieldset. Deriving the list here,
     * rather than hand-listing rows in the page, is what stops a new branding setting from being editable
     * on neither page -- which is how the footer, contact and donate settings went missing.
     */
    public List<SettingDef> getDetailFields() {
        return KnownSettings.branding().stream().filter(def -> !DEDICATED_FIELDS.contains(def.getName()))
                .toList();
    }

    /** The palettes the Appearance page's menu offers. */
    public List<String> getPalettes() {
        return KnownSettings.THEME_PALETTES;
    }

    /** The Appearance page's own URL for this org: what every preview, Save and Cancel redirects back to. */
    public String appearanceUrl(final String orgId) {
        return APPEARANCE_URL + "?" + ORG_PARAM + "=" + (orgId == null ? "" : orgId);
    }

    /** What the color picker starts on: the chosen color WITHOUT its '#', which is the widget's shape. */
    public String colorHex(final Map<String, String> values) {
        final String chosen = (values == null) ? null
                : normalizeHex(values.get(KnownSettings.SITE_BACKGROUND_COLOR.getName()));
        return (chosen == null ? FALLBACK_BACKGROUND_COLOR : chosen).substring(1);
    }

    /**
     * The unsaved values this request must render with, or null (every request but one). Null unless ALL of:
     * the view is the Appearance page and its {@code orgId} names an org, a session already exists, and that
     * session's preview is for the SAME org. A preview for another org, on another page, or in another
     * person's session is not a preview here.
     */
    private Map<String, String> preview() {
        final String pageOrg = appearanceViewSource.get();
        return (pageOrg == null) ? null : previewFor(pageOrg);
    }

    /**
     * This session's unsaved values for ONE organization, or null. Unlike {@link #preview()} it does not ask
     * what the current request is rendering, which is what the Appearance page's upload dialog needs: the
     * dialog's forms are siblings of the page's form and post WITHOUT its {@code orgId} parameter, so the
     * view-id trigger cannot fire for them, yet an upload must extend the edits already in flight rather
     * than silently reverting to what is stored.
     *
     * <p>It is not an authorization point either way: what comes back is this caller's own submission, on a
     * page they must pass {@code OrgCommands.canManageOrg} to reach, and saving re-checks server-side.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> previewFor(final String orgId) {
        final HttpSession session = (orgId == null || orgId.isBlank()) ? null : sessionSource.get();
        if (session == null || !orgId.equals(session.getAttribute(Sessions.APPEARANCE_PREVIEW_ORG))) {
            return null;
        }
        final Object values = session.getAttribute(Sessions.APPEARANCE_PREVIEW);
        return (values instanceof Map) ? (Map<String, String>) values : null;
    }

    /** The org whose look this request renders: the previewed one, else the host's; null for neither. */
    private String previewOrgId() {
        return preview() == null ? null : appearanceViewSource.get();
    }

    /**
     * The org whose Appearance page the current view is, or null. The VIEW ID is the trigger because the
     * theme is resolved while {@code h:head} renders: a request-scope flag set by the page's {@code initPage}
     * is not reliably there yet, while the view id and the request's parameters always are. The page always
     * carries {@code ?orgId=} (every preview, Save and Cancel redirects to {@link #appearanceUrl}), so the
     * parameter is present on every render that has to show a preview.
     */
    private static String appearanceViewOrgId() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx == null || ctx.getViewRoot() == null
                || !APPEARANCE_VIEW_ID.equals(ctx.getViewRoot().getViewId())) {
            return null;
        }
        final String orgId = ctx.getExternalContext().getRequestParameterMap().get(ORG_PARAM);
        return (orgId == null || orgId.isBlank()) ? null : orgId;
    }

    /** This request's session if it already has one, never a new one. */
    private static HttpSession currentSession() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return (ctx != null && ctx.getExternalContext().getSession(false) instanceof HttpSession http)
                ? http : null;
    }

    /** The values plus the color picker's answer, which arrives without its '#'; an unusable one is ignored. */
    private static Map<String, String> withColor(final Map<String, String> values, final String colorHex) {
        final Map<String, String> out = new LinkedHashMap<>(values == null ? Map.of() : values);
        final String color = normalizeHex(colorHex);
        if (color != null) {
            out.put(KnownSettings.SITE_BACKGROUND_COLOR.getName(), color);
        }
        return out;
    }

    /** A hex color with or without its leading '#', normalized to {@code #rrggbb} form, or null. */
    private static String normalizeHex(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        final String value = raw.trim();
        return SettingDef.hexColor(value.startsWith("#") ? value : "#" + value);
    }

    /** The chooser's mode: what was chosen, else derived from which background the values actually carry. */
    private static String backgroundMode(final Map<String, String> values) {
        final String chosen = values.get(BG_MODE_KEY);
        if (BG_MODE_IMAGE.equals(chosen) || BG_MODE_COLOR.equals(chosen) || BG_MODE_PALETTE.equals(chosen)) {
            return chosen;
        }
        if (!trimmed(values.get(KnownSettings.SITE_BACKGROUND_URL.getName())).isEmpty()) {
            return BG_MODE_IMAGE;
        }
        // Blank IS the palette choice, which is how a stored blank round-trips through a picker that can
        // never hold one.
        return trimmed(values.get(KnownSettings.SITE_BACKGROUND_COLOR.getName())).isEmpty()
                ? BG_MODE_PALETTE : BG_MODE_COLOR;
    }

    private static String storedOverride(final Optional<Organization> org, final SettingDef def) {
        return org.map(row -> row.settingOverride(def.getName())).map(String::trim).orElse("");
    }

    private static String trimmed(final String value) {
        return (value == null) ? "" : value.trim();
    }

    // --- plumbing ---

    /**
     * Whether this request renders an organization's look at all: its own host, or an admin previewing its
     * Appearance page. False everywhere else, which is what keeps a shared page byte-for-byte what it was.
     */
    private boolean isBranded() {
        return previewOrgId() != null || SiteContext.current().isOrg();
    }

    /**
     * The org whose look applies: the previewed one first (so the admin sees their unsaved choices), else
     * the one behind an org host. Empty off both, and when the row cannot be read.
     */
    private Optional<Organization> org() {
        final String previewing = previewOrgId();
        if (previewing != null) {
            return orgById(Organization.Id.from(previewing));
        }
        final SiteContext site = SiteContext.current();
        return site.isOrg() ? orgById(site.orgId()) : Optional.empty();
    }

    private Optional<Organization> orgById(final String orgId) {
        return (orgId == null || orgId.isBlank()) ? Optional.empty() : orgById(Organization.Id.from(orgId));
    }

    /** One org row; empty (and logged) when it cannot be read, so a page renders neutral rather than failing. */
    private Optional<Organization> orgById(final Organization.Id orgId) {
        try {
            return orgSource.apply(orgId);
        } catch (final RuntimeException ex) {
            log.error("Unable to read organization " + orgId.getValue(), ex);
            return Optional.empty();
        }
    }

    /** The org's name, its slug when the row cannot be read (the same fallback {@code SiteCommands} uses). */
    private String orgName() {
        return org().map(Organization::getName).orElse(SiteContext.current().slug());
    }

    /**
     * A branding setting as it applies to this request: the admin's unsaved PREVIEW value when one is in
     * force, else the org's override, else the compiled default -- every branding def is org-only, so the
     * site's row is never consulted -- normalized to null when blank. Null off an org host and without a
     * preview, or when the org cannot be read: the neutral look.
     */
    private String setting(final SettingDef def) {
        final Map<String, String> preview = preview();
        if (preview != null && preview.containsKey(def.getName())) {
            return blankToDefault(previewValue(preview, def), def);
        }
        final Optional<Organization> org = org();
        if (org.isEmpty()) {
            return null;
        }
        return blankToDefault(config.getString(def, org.get()), def);
    }

    /**
     * One previewed value, with the chooser's mode applied: off IMAGE mode the background image reads as
     * unset, and off COLOR mode so does the color, so the preview shows what the admin has actually chosen
     * rather than a value they have moved away from. Both are kept in the map, so switching back does not
     * lose what they typed or picked.
     */
    private static String previewValue(final Map<String, String> values, final SettingDef def) {
        final String mode = backgroundMode(values);
        if (KnownSettings.SITE_BACKGROUND_URL.getName().equals(def.getName()) && !BG_MODE_IMAGE.equals(mode)) {
            return "";
        }
        if (KnownSettings.SITE_BACKGROUND_COLOR.getName().equals(def.getName())
                && !BG_MODE_COLOR.equals(mode)) {
            return "";
        }
        return values.get(def.getName());
    }

    /** Blank means "not set", which for an org-only setting is the compiled default; then blank means null. */
    private static String blankToDefault(final String value, final SettingDef def) {
        final String resolved = (value == null || value.isBlank()) ? def.getDefaultValue() : value;
        return (resolved == null || resolved.isBlank()) ? null : resolved.trim();
    }

    /**
     * An http(s) URL safe to place in an attribute, or null. Beyond parsing (the save path's rule), a
     * quote, angle bracket, backslash or whitespace is refused outright: the value is emitted where any of
     * them could end the attribute, and returning null draws the neutral look instead of a broken page.
     */
    static String safeUrl(final String raw) {
        if (raw == null || ContentRenderer.requireHttpUrl(raw).isEmpty()) {
            return null;
        }
        for (final char c : raw.toCharArray()) {
            if (c == '"' || c == '\'' || c == '<' || c == '>' || c == '\\' || Character.isWhitespace(c)) {
                return null;
            }
        }
        return raw;
    }

    /**
     * A {@link #safeUrl safe} URL made safe for an unquoted CSS {@code url(...)} too: parentheses, quotes,
     * semicolons, backslashes and braces are percent-encoded (the browser decodes them again when it
     * fetches), so the value can neither close the function nor start a new declaration.
     */
    static String cssUrl(final String url) {
        final StringBuilder out = new StringBuilder(url.length() + 8);
        for (final char c : url.toCharArray()) {
            if (c == '(' || c == ')' || c == '\'' || c == '"' || c == ';' || c == '\\' || c == '{' || c == '}') {
                out.append('%').append(Integer.toHexString(c).toUpperCase());
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The session's {@code dark} flag through {@code getSession(false)}: never creates a session. */
    private static Boolean sessionDark() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        return (ctx == null) ? null : darkOf(ctx.getExternalContext().getSession(false));
    }

    /**
     * The flag as the topbar stores it ({@code sessionScope.dark = !dark}: a Boolean) or as a string, and
     * NULL when this visitor has never set one -- which is what lets an organization's own setting decide.
     * Toggling back to light stores {@code false}, an explicit choice that still outranks the org.
     */
    static Boolean darkOf(final Object session) {
        if (!(session instanceof HttpSession http)) {
            return null;
        }
        final Object flag = http.getAttribute(Sessions.DARK);
        if (flag instanceof Boolean set) {
            return set;
        }
        return (flag instanceof String spelling) ? Boolean.valueOf(spelling) : null;
    }
}
