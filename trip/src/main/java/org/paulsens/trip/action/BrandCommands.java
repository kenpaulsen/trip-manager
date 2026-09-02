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
 * so no session is ever created for a visitor: the existing {@code dark} flag, and the org Appearance page's
 * PREVIEW (see below). Every URL that lands in an attribute is re-checked here even though the save paths
 * validate: a row written by hand bypasses the page, and an attribute is the wrong place to discover that.
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
    /**
     * An organization's site with no settings at all shows THIS, and no image: a plain dark page, never the
     * shared site's photograph. Pinned here as well as on the declaration because it is the answer whenever
     * a stored value is missing OR unusable, and {@code SiteBackgroundTest} holds the two together.
     */
    static final String DEFAULT_BACKGROUND_COLOR = "#333333";

    /** The Appearance page's view id: the one view a preview may change. */
    static final String APPEARANCE_VIEW_ID = "/admin/orgAppearance.xhtml";
    /** The Appearance page's URL, whose {@code orgId} parameter half of the preview trigger keys off. */
    static final String APPEARANCE_URL = "/admin/orgAppearance.jsf";
    private static final String ORG_PARAM = "orgId";

    /**
     * The background chooser's mode, carried in the edit map under a key that is NOT a setting (it is never
     * stored: it only decides which control the page shows and which setting a save clears). "Image" with a
     * blank URL is a real state -- somebody who has just switched to Image and not typed the URL yet -- so
     * the mode cannot simply be derived from the values on every render.
     */
    public static final String BG_MODE_KEY = "bg.mode";
    public static final String BG_MODE_COLOR = "color";
    public static final String BG_MODE_IMAGE = "image";

    /** Test seam: the session's dark-mode flag (only the theme/layout/dark getters consult it). */
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

    /** The session's dark-mode preference; light for a visitor without a session or outside JSF. */
    public boolean isDark() {
        return Boolean.TRUE.equals(darkSource.get());
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
     * {@code --site-bg-color:} with its background COLOR when there is no image. Null off an org host: the
     * shared site's stylesheet keeps its own fallbacks (its rainbow image and no color), so that page is
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
     * The page background color: the org's own when it is a usable hex value, else the shipped
     * {@link #DEFAULT_BACKGROUND_COLOR}. Never null and never anything but a hex value, because it is
     * interpolated straight into the root element's {@code style}.
     */
    private String backgroundColor() {
        final String chosen = SettingDef.hexColor(setting(KnownSettings.SITE_BACKGROUND_COLOR));
        return chosen == null ? DEFAULT_BACKGROUND_COLOR : chosen;
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
        final Map<String, String> preview = preview();
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
     * setting and never reaches a row), with the LOSING background cleared -- picking a color drops the
     * image URL and picking an image drops the color -- so what is stored is what shows.
     */
    public Map<String, String> forSave(final Map<String, String> values, final String colorHex) {
        final Map<String, String> edited = withColor(values, colorHex);
        final Map<String, String> out = new LinkedHashMap<>();
        for (final SettingDef def : KnownSettings.branding()) {
            out.put(def.getName(), trimmed(edited.get(def.getName())));
        }
        final boolean image = BG_MODE_IMAGE.equals(backgroundMode(edited));
        out.put(KnownSettings.SITE_BACKGROUND_COLOR.getName(), image ? ""
                : savedColor(trimmed(edited.get(KnownSettings.SITE_BACKGROUND_COLOR.getName()))));
        out.put(KnownSettings.SITE_BACKGROUND_URL.getName(),
                image ? trimmed(edited.get(KnownSettings.SITE_BACKGROUND_URL.getName())) : "");
        return out;
    }

    /**
     * A chosen color as it is stored: blank when it IS the shipped default. The picker always has some
     * value, so storing it verbatim would stamp an override on every organization that saves the page
     * without touching the color, and the shipped default could then never change under them.
     */
    private static String savedColor(final String color) {
        return KnownSettings.SITE_BACKGROUND_COLOR.getDefaultValue().equalsIgnoreCase(color) ? "" : color;
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
        return (chosen == null ? DEFAULT_BACKGROUND_COLOR : chosen).substring(1);
    }

    /**
     * The unsaved values this request must render with, or null (every request but one). Null unless ALL of:
     * the view is the Appearance page and its {@code orgId} names an org, a session already exists, and that
     * session's preview is for the SAME org. A preview for another org, on another page, or in another
     * person's session is not a preview here.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> preview() {
        final String pageOrg = appearanceViewSource.get();
        final HttpSession session = (pageOrg == null) ? null : sessionSource.get();
        if (session == null || !pageOrg.equals(session.getAttribute(Sessions.APPEARANCE_PREVIEW_ORG))) {
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
        if (BG_MODE_IMAGE.equals(chosen) || BG_MODE_COLOR.equals(chosen)) {
            return chosen;
        }
        return trimmed(values.get(KnownSettings.SITE_BACKGROUND_URL.getName())).isEmpty()
                ? BG_MODE_COLOR : BG_MODE_IMAGE;
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
     * One previewed value, with the chooser's mode applied: in COLOR mode the background image reads as
     * unset, so the preview shows the color the admin picked rather than an image URL they have moved off.
     * The URL itself is kept in the map, so switching back does not lose what they typed.
     */
    private static String previewValue(final Map<String, String> values, final SettingDef def) {
        if (KnownSettings.SITE_BACKGROUND_URL.getName().equals(def.getName())
                && !BG_MODE_IMAGE.equals(backgroundMode(values))) {
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
    private static boolean sessionDark() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx == null) {
            return false;
        }
        return darkOf(ctx.getExternalContext().getSession(false));
    }

    /** The flag as the topbar stores it ({@code sessionScope.dark = !dark}: a Boolean) or as a string. */
    static boolean darkOf(final Object session) {
        if (!(session instanceof HttpSession http)) {
            return false;
        }
        final Object flag = http.getAttribute(Sessions.DARK);
        return Boolean.TRUE.equals(flag) || (flag instanceof String s && Boolean.parseBoolean(s));
    }
}
