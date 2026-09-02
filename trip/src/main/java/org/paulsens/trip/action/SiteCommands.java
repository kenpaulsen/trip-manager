package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.site.ListingScope;
import org.paulsens.trip.site.SiteContext;

/**
 * The request's resolved {@link SiteContext}, exposed to pages as {@code #{site}}.
 *
 * <p>Reads the {@code RequestContext} ScopedValue that {@code SessionRecoveryFilter} binds -- the ONLY
 * sanctioned way for a page or bean to learn which site a request is for. Never copy any of these values
 * into sessionScope or viewScope: once the session cookie spans {@code *.unitetrip.com} one session serves
 * several sites, so a stashed site is a cross-tenant bug (see {@link SiteContext}). Off a bound request
 * (schedulers, background work) every answer degrades to the SHARED defaults.
 */
@Slf4j
@Named("site")
@ApplicationScoped
public class SiteCommands {

    /** The page title of the product's own host. */
    static final String MARKETING_TITLE = "UniteTrip";

    /** The request's site, or the SHARED default when no request context is bound. */
    public SiteContext getCurrent() {
        return SiteContext.current();
    }

    public boolean isOrgSite() {
        return getCurrent().isOrg();
    }

    public boolean isMarketingSite() {
        return getCurrent().isMarketing();
    }

    public boolean isSharedSite() {
        return getCurrent().isShared();
    }

    /** The current org site's slug, or null on any other site. */
    public String getSlug() {
        return getCurrent().slug();
    }

    /** The current org site's organization id (as a string, for EL), or null on any other site. */
    public String getOrgId() {
        final SiteContext current = getCurrent();
        return current.orgId() == null ? null : current.orgId().getValue();
    }

    /** The hostname the request arrived on, as resolved by the site index (null off-request). */
    public String getHost() {
        return getCurrent().host();
    }

    /**
     * The content section key of this site's home page: the classic shared page, the org's own page, or
     * the marketing page -- what the landing-page host passes to the contentSections include. Computed
     * from the request's host, never stored anywhere a later request could read it back.
     */
    public String getPageKey() {
        return OrgPageBootstrap.pageKeyOf(getCurrent());
    }

    /**
     * The host prefix a link to something owned by {@code orgId} needs from THIS site: empty when the
     * current site reaches the org (a plain site-relative link works), else the org's own site
     * ({@code https://{slug}.{base}}, no trailing slash) -- for the org-admin pages, which a site admin may
     * open on the shared host for a hosted org whose trips that host does not list (see
     * {@code docs/org-admin.md}, "What a site can reach"). An org with no site of its own answers empty too:
     * there is nowhere else to send the link.
     */
    public String hostFor(final String orgId) {
        if (orgId == null || orgId.isBlank() || ListingScope.reachable(orgId)) {
            return "";
        }
        final String slug = DAO.getInstance().getOrganization(Organization.Id.from(orgId), Cached.YES)
                .map(Organization::getSlug).orElse(null);
        if (slug == null || slug.isBlank()) {
            return "";
        }
        final String url = orgSiteUrl(slug);
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The current org site's organization name (its slug if the org cannot be read); null elsewhere. */
    public String getOrgName() {
        return orgNameOf(getCurrent(), id -> DAO.getInstance().getOrganization(id, Cached.YES));
    }

    /** {@link #getOrgName()} against an explicit lookup (the testable half): a failed read is the slug. */
    static String orgNameOf(final SiteContext current, final Function<Organization.Id, Optional<Organization>> lookup) {
        if (!current.isOrg()) {
            return null;
        }
        try {
            return lookup.apply(current.orgId()).map(Organization::getName).orElse(current.slug());
        } catch (final RuntimeException ex) {
            log.error("Unable to read the organization behind site " + current.host(), ex);
            return current.slug();
        }
    }

    /**
     * The public URL of an organization's site, for links on the admin pages: {@code https://{slug}.{base}}
     * in production, and {@code http://{slug}.localhost[:port]} when the request itself came in on localhost
     * (the org-site base domain does not resolve on a laptop; {@code *.localhost} does).
     */
    public String orgSiteUrl(final String slug) {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        final Object request = ctx == null ? null : ctx.getExternalContext().getRequest();
        return orgSiteUrl(slug, request instanceof HttpServletRequest http ? http : null);
    }

    /** {@link #orgSiteUrl(String)} for an explicit request (null off-request): the testable half. */
    static String orgSiteUrl(final String slug, final HttpServletRequest request) {
        if (request != null) {
            return orgSiteUrl(slug, request.getServerName(), request.getServerPort(), baseDomain());
        }
        return orgSiteUrl(slug, null, -1, baseDomain());
    }

    /** {@link #orgSiteUrl(String)} on explicit request facts (the testable half). */
    static String orgSiteUrl(final String slug, final String requestHost, final int requestPort,
            final String baseDomain) {
        if (requestHost != null && requestHost.endsWith("localhost")) {
            final String port = requestPort > 0 && requestPort != 80 ? ":" + requestPort : "";
            return "http://" + slug + ".localhost" + port + "/";
        }
        return "https://" + slug + "." + baseDomain + "/";
    }

    private static String baseDomain() {
        return new ConfigCommands().getString(KnownSettings.SITE_ORGSITES_BASE_DOMAIN);
    }

    /**
     * The browser title for a landing page: the org's name on its site, the product name on the marketing
     * host, and the page's own title on the shared site.
     */
    public String pageTitle(final String sharedTitle) {
        final SiteContext current = getCurrent();
        if (current.isOrg()) {
            return getOrgName();
        }
        return current.isMarketing() ? MARKETING_TITLE : sharedTitle;
    }
}
