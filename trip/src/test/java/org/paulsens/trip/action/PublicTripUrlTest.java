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
 * Where a listed trip links ({@link TripCommands#publicTripUrl}): the landing page's card and the
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

    /**
     * The stored external URL is typed by a trip manager and lands in an href on a listing other organizations
     * share: anything that is not an absolute http(s) address links to the detail page instead of running.
     */
    @Test
    public void anExternalUrlThatIsNoWebAddressNeverReachesALink() {
        final Function<String, Optional<Organization>> home = owner("qop", null);
        for (final String bad : new String[] {"javascript:alert(1)", " JavaScript:alert(1)", "data:text/html,x",
                "//evil.example/x", "example.org/go", "not a url"}) {
            assertEquals(url(trip(bad), false, SHARED, home), DETAIL, bad);
            assertEquals(new TripCommands(() -> null).externalUrlOf(trip(bad)), "", bad);
            assertEquals(TripCommands.externalUrl(trip(bad)), "", bad);
            assertEquals(TripCommands.linkProblems(trip(bad), null).size(), 1, bad);
            assertEquals(TripCommands.linkProblems(trip(bad), trip(bad)), java.util.List.of(), "unchanged: " + bad);
        }
        // By scheme, not by java.net.URI's grammar: a browser opens these, and such values are already stored.
        for (final String works : new String[] {"https://tours.example/medjugorje?ref=a|b",
                "https://cdn.example/2026 Holy Angels.pdf", "HTTP://example.org/tour?d={x}"}) {
            assertEquals(url(trip(works), false, SHARED, home), works, works);
            assertEquals(TripCommands.linkProblems(trip(works), null), java.util.List.of(), works);
            assertEquals(TripCommands.flyerLink(works), works, works);
        }
        assertEquals(TripCommands.webAddress("https://example.org/a\nb"), "", "a control character");
        assertEquals(TripCommands.webAddress("https://"), "", "a scheme with nothing after it");
        assertEquals(TripCommands.webAddress(null), "");
        assertEquals(TripCommands.flyerLink("//evil.example/x"), "", "protocol-relative is another host");
        assertEquals(TripCommands.flyerLink(null), "");
        final TripCommands trips = new TripCommands(() -> null);
        assertEquals(trips.externalUrlOf(trip(" https://example.org/go ")), "https://example.org/go");
        assertEquals(trips.externalUrlOf(trip(null)), "", "not an external trip");
        assertEquals(trips.externalUrlOf(null), "");
        assertEquals(TripCommands.linkProblems(trip("https://example.org/go"), null), java.util.List.of());
        assertEquals(TripCommands.linkProblems(trip(null), null), java.util.List.of());
    }

    @Test
    public void aFlyerLinkIsAWebAddressOrAPathOnThisSite() {
        final TripCommands trips = new TripCommands(() -> null);
        final Trip trip = trip(null);
        assertEquals(trips.flyerUrlOf(trip), "", "none");
        assertEquals(trips.flyerUrlOf(null), "");
        trip.setFlyerUrl(" https://example.org/flyer.pdf ");
        assertEquals(trips.flyerUrlOf(trip), "https://example.org/flyer.pdf");
        assertEquals(TripCommands.linkProblems(trip, null), java.util.List.of());
        trip.setFlyerUrl("/media/flyer.pdf");
        assertEquals(trips.flyerUrlOf(trip), "/media/flyer.pdf");
        assertEquals(TripCommands.linkProblems(trip, null), java.util.List.of());
        trip.setFlyerUrl("javascript:alert(1)");
        assertEquals(trips.flyerUrlOf(trip), "");
        assertEquals(TripCommands.linkProblems(trip, null),
                java.util.List.of("The Flyer URL must be a web address starting with https:// or http://."));
        trip.setFlyerUrl("   ");
        assertEquals(TripCommands.linkProblems(trip, null), java.util.List.of(), "blank is no flyer, not a bad one");
    }

    @Test
    public void notStartedIsACalendarDayNotAnInstant() {
        final java.time.LocalDate today = java.time.LocalDate.of(2026, 12, 1);
        final Trip trip = Trip.builder().id(TRIP_ID).startDate(today.atStartOfDay()).build();
        assertEquals(TripCommands.notStartedBy(trip, today), true, "its own day");
        assertEquals(TripCommands.notStartedBy(trip, today.plusDays(1)), false);
        trip.setStartDate(null);
        assertEquals(TripCommands.notStartedBy(trip, today), false, "no start date");
    }

    /**
     * The pages call {@code trip.publicTripUrl(aTrip, userId)} with a null {@code userId} for an anonymous
     * visitor, and EL cannot pick between overloads for a null argument: a second public two-argument method of
     * that name broke every page with the Trips menu (2026-11-16, caught by the browser suite, not by a unit test).
     */
    @Test
    public void thePagesMethodHasNoPublicTwoArgumentOverload() {
        final long twoArgument = java.util.Arrays.stream(TripCommands.class.getMethods())
                .filter(method -> "publicTripUrl".equals(method.getName()) && method.getParameterCount() == 2)
                .count();
        assertEquals(twoArgument, 1, "EL resolves trip.publicTripUrl(aTrip, null) only while it is the only one");
        final TripCommands trips = new TripCommands(() -> null);
        assertEquals(trips.publicTripUrlFor(null, false), "/");
        assertEquals(trips.publicTripUrlFor(Trip.builder().id(TRIP_ID).build(), true), DETAIL);
    }
}
