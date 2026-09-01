package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import org.paulsens.trip.audit.RequestContext;
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
@Named("site")
@ApplicationScoped
public class SiteCommands {

    /** The request's site, or the SHARED default when no request context is bound. */
    public SiteContext getCurrent() {
        return RequestContext.SCOPE.isBound() ? RequestContext.SCOPE.get().site() : SiteContext.shared(null);
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
}
