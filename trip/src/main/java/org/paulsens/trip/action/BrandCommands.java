package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpSession;
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
 * scope -- one session serves several sites) plus, for dark mode, the session's existing {@code dark} flag
 * through {@code getSession(false)}: a visitor without a session is light, and no session is ever created
 * for them. Every URL that lands in an attribute is re-checked here even though the save paths validate:
 * a row written by hand bypasses the page, and an attribute is the wrong place to discover that.
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

    /** Test seam: the session's dark-mode flag (only the theme/layout/dark getters consult it). */
    @Setter
    private Supplier<Boolean> darkSource = BrandCommands::sessionDark;

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
        if (!isOrgHost() || getLogoUrl() != null) {
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
        if (!isOrgHost()) {
            return null;
        }
        final String own = safeUrl(setting(KnownSettings.SITE_FAVICON_URL));
        return own == null ? NO_FAVICON : own;
    }

    /** The link-preview (Open Graph) image: the org's own, else its logo, else null; null off an org host. */
    public String getOgImage() {
        if (!isOrgHost()) {
            return null;
        }
        final String own = safeUrl(setting(KnownSettings.SITE_OG_IMAGE_URL));
        return own == null ? getLogoUrl() : own;
    }

    /**
     * The custom property the root element carries on an org host: {@code --site-bg:url(...)} with the
     * org's background image, or {@code --site-bg:none} without one. Null off an org host: the shared
     * site's stylesheet keeps its own fallback. The URL is percent-encoded for the {@code url()} context
     * (see {@link #cssUrl}) so it can neither close the function nor start another declaration.
     */
    public String getRootStyle() {
        if (!isOrgHost()) {
            return null;
        }
        final String background = safeUrl(setting(KnownSettings.SITE_BACKGROUND_URL));
        return background == null ? BG_NONE : "--site-bg:url(" + cssUrl(background) + ")";
    }

    // --- footer ---

    /** The footer heading: the org's setting, else its name; null off an org host. */
    public String getFooterTitle() {
        if (!isOrgHost()) {
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

    // --- plumbing ---

    private static boolean isOrgHost() {
        return SiteContext.current().isOrg();
    }

    /** The org behind the current org host; empty off an org host or when the row cannot be read. */
    private Optional<Organization> org() {
        final SiteContext site = SiteContext.current();
        if (!site.isOrg()) {
            return Optional.empty();
        }
        try {
            return orgSource.apply(site.orgId());
        } catch (final RuntimeException ex) {
            log.error("Unable to read the organization behind site " + site.host(), ex);
            return Optional.empty();
        }
    }

    /** The org's name, its slug when the row cannot be read (the same fallback {@code SiteCommands} uses). */
    private String orgName() {
        return org().map(Organization::getName).orElse(SiteContext.current().slug());
    }

    /**
     * A branding setting as it applies to the current org host: the org's override, else the compiled
     * default -- every branding def is org-only, so the site's row is never consulted -- normalized to
     * null when blank. Null off an org host or when the org cannot be read: the neutral look.
     */
    private String setting(final SettingDef def) {
        final Optional<Organization> org = org();
        if (org.isEmpty()) {
            return null;
        }
        final String value = config.getString(def, org.get());
        return value == null || value.isBlank() ? null : value.trim();
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
