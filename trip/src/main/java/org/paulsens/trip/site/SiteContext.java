package org.paulsens.trip.site;

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
