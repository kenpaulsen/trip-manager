package org.paulsens.trip.action;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.SharedSiteRegisterTarget;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.SiteContext;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * Where a listed trip links ({@link TripCommands#publicTripUrl}): the landing page's Register chip and the
 * Trips menu both ask, so every branch of the shared-site Register target is pinned here.
 */
public class PublicTripUrlTest {

    private static final String TRIP_ID = "4f1e2d3c-0000-4000-8000-000000000001";
    private static final String ORG_ID = "4f1e2d3c-0000-4000-8000-0000000000aa";
    private static final SiteContext SHARED = SiteContext.shared("visitqueenofpeace.com");
    private static final UnaryOperator<String> ORG_SITE = slug -> "https://" + slug + ".unitetrip.com/";
    private static final String DETAIL = "/trip/tripDetails.jsf?trip=" + TRIP_ID;

    private static Trip trip(final String externalUrl) {
        final Trip trip = Trip.builder().id(TRIP_ID).build();
        trip.setOrgId(ORG_ID);
        trip.setNonHostedTripUrl(externalUrl);
        return trip;
    }

    private static Function<String, Optional<Organization>> owner(final String slug,
            final SharedSiteRegisterTarget target) {
        final Organization org = Organization.builder().name("Owner").build();
        org.setSlug(slug);
        org.setSharedSiteRegisterTarget(target == null ? null : target.name());
        return id -> ORG_ID.equals(id) ? Optional.of(org) : Optional.empty();
    }

    private static String url(final Trip trip, final boolean registered, final SiteContext site,
            final Function<String, Optional<Organization>> owner) {
        return TripCommands.publicTripUrl(trip, registered, site, owner, ORG_SITE);
    }

    @Test
    public void theOrgsChoiceSteersTheSharedSite() {
        assertEquals(url(trip(null), false, SHARED, owner("qop", null)), DETAIL, "unset: the detail page");
        assertEquals(url(trip(null), false, SHARED, owner("qop", SharedSiteRegisterTarget.DETAIL_PAGE)), DETAIL);
        assertEquals(url(trip(null), false, SHARED, owner("qop", SharedSiteRegisterTarget.ORG_TRIP_PAGE)),
                "https://qop.unitetrip.com/trip/tripDetails.jsf?trip=" + TRIP_ID);
        assertEquals(url(trip(null), false, SHARED, owner("qop", SharedSiteRegisterTarget.ORG_HOME)),
                "https://qop.unitetrip.com/");
        assertEquals(TripCommands.publicTripUrl(trip(null), false, SHARED,
                owner("qop", SharedSiteRegisterTarget.ORG_HOME), slug -> "http://" + slug + ".localhost:8080"),
                "http://qop.localhost:8080/", "a base without its trailing slash still forms a whole URL");
    }

    @Test
    public void everyFallbackLandsOnTheDetailPage() {
        final Function<String, Optional<Organization>> home = owner("qop", SharedSiteRegisterTarget.ORG_HOME);
        assertEquals(url(trip(null), true, SHARED, home), DETAIL, "a registered viewer's status lives here");
        assertEquals(url(trip(null), false, SHARED, owner(null, SharedSiteRegisterTarget.ORG_HOME)), DETAIL,
                "no subdomain: there is no org site to send anyone to");
        assertEquals(url(trip(null), false, SHARED, owner(" ", SharedSiteRegisterTarget.ORG_HOME)), DETAIL);
        assertEquals(url(trip(null), false, SHARED, id -> Optional.empty()), DETAIL, "an unreadable owner");
        final SiteContext orgSite = SiteContext.org(Organization.Id.from(ORG_ID), "qop", "qop.unitetrip.com");
        assertEquals(url(trip(null), false, orgSite, home), DETAIL, "the org's own site links its own page");
        assertEquals(url(trip(null), false, SiteContext.marketing("unitetrip.com"), home), DETAIL);
    }

    @Test
    public void anExternalTripKeepsItsOwnSiteUntilTheViewerRegisters() {
        final Function<String, Optional<Organization>> home = owner("qop", SharedSiteRegisterTarget.ORG_HOME);
        assertEquals(url(trip(" https://example.org/go "), false, SHARED, home), "https://example.org/go",
                "registration happens on the external site, whatever the org chose");
        assertEquals(url(trip("https://example.org/go"), true, SHARED, home), DETAIL);
    }

    @Test
    public void thePageEntryPointIsAnonymousSafe() {
        final TripCommands trips = new TripCommands(() -> null);
        assertEquals(trips.publicTripUrl(null, null), "/");
        assertEquals(trips.publicTripUrl(Trip.builder().build(), null).startsWith("/trip/tripDetails.jsf?trip="),
                true, "no viewer: no registration lookup, and no request bound: not a shared-site redirect");
    }
}
