package org.paulsens.trip.site;

import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.model.Organization;

/**
 * Which site this request is for, resolved from the {@code Host} header by {@link SiteIndex} and carried for
 * the rest of the request on {@code RequestContext}. This is the keystone of the per-organization subdomain
 * feature: an ORG host renders one tenant's site, a SHARED host renders the classic multi-tenant site, the
 * MARKETING host is the product's own page, and an UNKNOWN host under the org-site base domain is answered
 * with a cheap static page before any application work runs.
 *
 * <p><b>Hard rule: never store one of these in sessionScope or viewScope.</b> The site is a fact about the
 * REQUEST's hostname, and once the session cookie spans {@code *.unitetrip.com} one session serves several
 * sites -- a site cached in the session is a cross-tenant bug by construction. That is also why this type is
 * deliberately NOT {@link java.io.Serializable} and lives outside the {@code model} package (whose classes
 * must all serialize): an attempt to stash it in a view fails loudly at session save instead of leaking.
 */
public record SiteContext(Mode mode, Organization.Id orgId, String slug, String host) {

    /** How the host resolved. */
    public enum Mode {
        /** A classic shared-site host (visitqueenofpeace.com and friends): today's behavior. */
        SHARED,
        /** One organization's own site ({slug}.unitetrip.com). */
        ORG,
        /** The product's marketing host (the org-site base domain's apex and www). */
        MARKETING,
        /** A host under the org-site base domain that matches no organization. */
        UNKNOWN
    }

    public SiteContext {
        if (mode == null) {
            mode = Mode.SHARED;
        }
    }

    public static SiteContext shared(final String host) {
        return new SiteContext(Mode.SHARED, null, null, host);
    }

    public static SiteContext marketing(final String host) {
        return new SiteContext(Mode.MARKETING, null, null, host);
    }

    public static SiteContext org(final Organization.Id orgId, final String slug, final String host) {
        return new SiteContext(Mode.ORG, orgId, slug, host);
    }

    public static SiteContext unknown(final String host) {
        return new SiteContext(Mode.UNKNOWN, null, null, host);
    }

    /**
     * The site of the request in progress, read from the {@code RequestContext} ScopedValue that
     * {@code SessionRecoveryFilter} binds -- the one sanctioned way for a bean to learn which site it is
     * rendering for. Off a bound request (schedulers, background work, unit tests) this is the SHARED
     * default, so code that runs there must take the organization explicitly.
     */
    public static SiteContext current() {
        return RequestContext.SCOPE.isBound() ? RequestContext.SCOPE.get().site() : shared(null);
    }

    /**
     * Whether this site's tenant boundary admits something owned by {@code ownerOrgId} (a trip, an album):
     * an org site admits only its own; every other site admits everything at this level -- what a SHARED
     * site then chooses to list is {@link ListingScope}'s double gate, on top of this.
     */
    public boolean admits(final String ownerOrgId) {
        if (!isOrg()) {
            return true;
        }
        return isSiteOf(ownerOrgId);
    }

    /**
     * Whether this is exactly {@code ownerOrgId}'s own site -- the rule for things that belong to ONE
     * tenant and to no shared surface at all (an org's templates): false on every non-org site.
     */
    public boolean isSiteOf(final String ownerOrgId) {
        return isOrg() && ownerOrgId != null && ownerOrgId.equals(orgId.getValue());
    }

    public boolean isOrg() {
        return mode == Mode.ORG;
    }

    public boolean isMarketing() {
        return mode == Mode.MARKETING;
    }

    public boolean isShared() {
        return mode == Mode.SHARED;
    }

    public boolean isUnknown() {
        return mode == Mode.UNKNOWN;
    }
}
