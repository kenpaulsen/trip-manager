package org.paulsens.trip.model;

/**
 * Where a SHARED site sends a visitor who clicks an organization's trip (the landing page's Register chip and
 * the Trips menu). The organization chooses, on its profile, beside the "Allow shared sites" checkbox; the
 * choice means nothing on the org's own site, where a trip link is always its own detail page.
 *
 * <p>Both org-site targets need the org to HAVE a site (a subdomain); without one every target falls back to
 * {@link #DETAIL_PAGE}, so a stored choice can never strand a visitor on a host that does not exist.
 */
public enum SharedSiteRegisterTarget {
    /** The trip's detail page on the shared site itself: the behavior every org had before the choice. */
    DETAIL_PAGE,
    /** The same trip's detail page on the organization's own site. */
    ORG_TRIP_PAGE,
    /** The organization's own site's home page. */
    ORG_HOME;

    /** The stored name back as a target; null, blank or a name this build does not know read as the default. */
    public static SharedSiteRegisterTarget from(final String stored) {
        if (stored == null || stored.isBlank()) {
            return DETAIL_PAGE;
        }
        for (final SharedSiteRegisterTarget target : values()) {
            if (target.name().equals(stored.trim())) {
                return target;
            }
        }
        return DETAIL_PAGE;
    }
}
