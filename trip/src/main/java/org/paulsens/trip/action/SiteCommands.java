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
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.ListingScope;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.site.SiteUrls;

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
     * The content section key of this site's page called {@code pageName} ({@code board}, ...): what a
     * secondary content page ({@code WEB-INF/contentPage.xhtml}) passes to the contentSections include.
     * Same per-request rule as {@link #getPageKey()}.
     */
    public String pageKey(final String pageName) {
        return OrgPageBootstrap.pageKeyOf(getCurrent(), pageName);
    }

    /**
     * The content section key of ANY trip's public page ({@code page:trip:{id}:home}, docs/conference.md), or
     * null when there is no such page to show here: no such trip, or a trip this site does not reach
     * ({@link ListingScope#reachable}). Same per-request rule as {@link #getPageKey()}: never stored.
     */
    public String tripPageKey(final String tripId) {
        if (tripId == null || tripId.isBlank()) {
            return null;
        }
        final Trip trip = DAO.getInstance().getTrip(tripId.trim(), Cached.YES).orElse(null);
        if (trip == null || !ListingScope.reachable(trip.getOrgId())) {
            return null;
        }
        try {
            return OrgPageBootstrap.tripPageKey(trip.getId());
        } catch (final IllegalArgumentException notAUuid) {
            return null;
        }
    }

    /**
     * The host prefix a link to something owned by {@code orgId} needs from THIS site: empty when the
     * current site reaches the org (a plain site-relative link works), else the org's own site (its custom
     * domain or {@code https://{slug}.{base}}, no trailing slash) -- for the org-admin pages, which a site admin
     * may open on the shared host for a hosted org whose trips that host does not list (see
     * {@code docs/org-admin.md}, "What a site can reach"). An org with no site of its own answers empty too:
     * there is nowhere else to send the link.
     */
    public String hostFor(final String orgId) {
        if (orgId == null || orgId.isBlank() || ListingScope.reachable(orgId)) {
            return "";
        }
        final Organization org = DAO.getInstance().getOrganization(Organization.Id.from(orgId), Cached.YES)
                .orElse(null);
        if (org == null || org.getSlug() == null || org.getSlug().isBlank()) {
            return "";
        }
        final String url = orgSiteUrlOf(org);
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
     * The public URL of the organization holding {@code slug}, for links on the admin pages: {@link
     * #orgSiteUrlOf(Organization)} of that org (a slug no org holds gets its subdomain shape).
     */
    public String orgSiteUrl(final String slug) {
        return orgSiteUrl(slug, facesRequest());
    }

    /**
     * {@link #orgSiteUrl(String)} for an explicit request (null off-request), by way of the org lookup: what the
     * REST edge calls, since a JAX-RS resource has the servlet request but no FacesContext.
     */
    public static String orgSiteUrl(final String slug, final HttpServletRequest request) {
        return orgSiteUrl(orgOfSlug(slug), request);
    }

    /**
     * The public URL of {@code org}'s site, for links on the admin pages (trailing slash included): THE org-site
     * builder of this class. In production that is {@link SiteUrls#orgSiteUrl(Organization, ConfigCommands)}
     * (its custom domain, else {@code https://{slug}.{base}}); when the request itself came in on localhost it
     * is {@code http://{slug}.localhost[:port]/}, since neither the base domain nor a customer's domain resolves
     * on a laptop and {@code *.localhost} does.
     */
    public String orgSiteUrlOf(final Organization org) {
        return orgSiteUrl(org, facesRequest());
    }

    /** {@link #orgSiteUrlOf} for an explicit request (null off-request). */
    public static String orgSiteUrl(final Organization org, final HttpServletRequest request) {
        return request == null ? orgSiteUrl(org, null, -1, new ConfigCommands())
                : orgSiteUrl(org, request.getServerName(), request.getServerPort(), new ConfigCommands());
    }

    /** {@link #orgSiteUrlOf} on explicit request facts (the testable half). */
    static String orgSiteUrl(final Organization org, final String requestHost, final int requestPort,
            final ConfigCommands config) {
        if (requestHost != null && requestHost.endsWith("localhost")) {
            final String port = requestPort > 0 && requestPort != 80 ? ":" + requestPort : "";
            return "http://" + org.getSlug() + ".localhost" + port + "/";
        }
        return SiteUrls.orgSiteUrl(org, config) + "/";
    }

    /**
     * The organization whose site {@code slug} names, from the cached org list; when none holds it (a stale
     * cache, an unreadable list), an org carrying only that slug, so the link keeps its subdomain shape.
     */
    private static Organization orgOfSlug(final String slug) {
        try {
            final Optional<Organization> holder = DAO.getInstance().getOrganizations(Cached.YES).stream()
                    .filter(org -> slug != null && slug.equals(org.getSlug()))
                    .findFirst();
            if (holder.isPresent()) {
                return holder.get();
            }
        } catch (final RuntimeException ex) {
            log.warn("Unable to read the organizations for the site of '{}'", slug, ex);
        }
        final Organization bare = new Organization();
        bare.setSlug(slug);
        return bare;
    }

    /** The servlet request behind the current FacesContext, or null off a Faces request. */
    private static HttpServletRequest facesRequest() {
        final FacesContext ctx = FacesContext.getCurrentInstance();
        final Object request = ctx == null ? null : ctx.getExternalContext().getRequest();
        return request instanceof HttpServletRequest http ? http : null;
    }

    /**
     * The browser title for a landing page: the org's name on its site, the product name on the marketing
     * host, and the page's own title on the shared site.
     */
    public String pageTitle(final String sharedTitle) {
        final SiteContext current = getCurrent();
        if (current.isOrg()) {
            final String own = siteTitleOf(current, id -> DAO.getInstance().getOrganization(id, Cached.YES));
            return own != null ? own : getOrgName();
        }
        return current.isMarketing() ? MARKETING_TITLE : sharedTitle;
    }

    /**
     * The org site's own title setting ({@code site.title}, org-only), or null when unset, off an org site, or
     * unreadable -- the caller then falls back to the org's name.
     */
    static String siteTitleOf(final SiteContext current,
            final Function<Organization.Id, Optional<Organization>> lookup) {
        if (!current.isOrg()) {
            return null;
        }
        try {
            final Organization org = lookup.apply(current.orgId()).orElse(null);
            final String own = org == null ? null : new ConfigCommands().getString(KnownSettings.SITE_TITLE, org);
            return (own == null || own.isBlank()) ? null : own.trim();
        } catch (final RuntimeException ex) {
            log.error("Unable to read the site title of " + current.host(), ex);
            return null;
        }
    }
}
