package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * {@code TripCommands.getRecentTripsInMyOrgs}, the admin person page's Registrations list: a site admin sees the
 * site's recent trips, anyone else only the trips of the organizations they belong to (owner decision
 * 2026-11-14; organization is the tenancy boundary, and an org's people admin also reaches a member who belongs to
 * another organization). The trips are dated centuries out so they lead the recency index whatever else the
 * suite has saved.
 */
public class TripsInMyOrgsTest {

    private static final SiteContext SHARED = SiteContext.shared("localhost");
    private static final SiteContext ACME_SITE =
            SiteContext.org(Organization.Id.from(FakeData.ACME_ORG_ID), "acme", "acme.localhost");

    private Trip ownNewer;
    private Trip ownOlder;
    /** Newer than both own trips: on a recency cap without over-fetch it would crowd them out. */
    private Trip otherOrgTrip;
    /** In a hosted org the viewer belongs to too: reached on that org's site only. */
    private Trip acmeTrip;
    private Person.Id viewer;

    @BeforeClass
    public void init() throws IOException {
        DAO.getInstance();
        FakeData.addFakeData();
        final Organization own = saveOrg("My orgs own");
        final Organization other = saveOrg("My orgs other");
        ownOlder = saveTrip("My orgs own older", own.getId().getValue(), 2900);
        ownNewer = saveTrip("My orgs own newer", own.getId().getValue(), 2901);
        otherOrgTrip = saveTrip("My orgs other", other.getId().getValue(), 2902);
        acmeTrip = saveTrip("My orgs acme", FakeData.ACME_ORG_ID, 2903);
        final Person person = Person.builder().first("Viewer").last("Myorgs" + RandomData.genAlpha(6)).build();
        person.setOrgIds(List.of(own.getId(), Organization.Id.from(FakeData.ACME_ORG_ID)));
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        viewer = person.getId();
    }

    private static Organization saveOrg(final String name) throws IOException {
        final Organization org = Organization.builder().name(name + " " + RandomData.genAlpha(6)).build();
        Assert.assertTrue(DAO.getInstance().saveOrganization(org));
        return org;
    }

    private static Trip saveTrip(final String title, final String orgId, final int year) throws IOException {
        final Trip trip = Trip.builder().title(title + " " + RandomData.genAlpha(6))
                .startDate(LocalDateTime.of(year, 3, 1, 9, 0))
                .endDate(LocalDateTime.of(year, 3, 9, 17, 0))
                .build();
        trip.setOrgId(orgId);
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static <T> T on(final SiteContext site, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).call(body);
    }

    private static TripCommands as(final Caller caller) {
        return new TripCommands(() -> null, () -> caller);
    }

    private static List<String> ids(final List<Trip> found) {
        return found.stream().map(Trip::getId).toList();
    }

    @Test
    public void aMemberSeesOnlyTheirOwnOrganizationsTrips() throws Exception {
        final List<String> mine = ids(on(SHARED, () -> as(TestCallers.person(viewer)).getRecentTripsInMyOrgs(0)));
        Assert.assertEquals(mine, List.of(ownOlder.getId(), ownNewer.getId()),
                "the own org's trips, in display order (by start), and no other tenant's");
        Assert.assertEquals(ids(on(SHARED, () -> as(TestCallers.person(viewer)).getRecentTripsInMyOrgs(100))), mine,
                "a cap above the count keeps them all");
    }

    @Test
    public void anotherTenantsNewerTripsDoNotCrowdOutTheCallersOwn() throws Exception {
        // The other org's trip and the Acme trip are the two most recent on the site: a limit-1 window of the
        // site's trips would hold neither own trip.
        Assert.assertEquals(ids(on(SHARED, () -> as(TestCallers.person(viewer)).getRecentTripsInMyOrgs(1))),
                List.of(ownNewer.getId()), "the limit keeps the caller's own most recent trip");
    }

    @Test
    public void theSiteGateStillHolds() throws Exception {
        final TripCommands mine = as(TestCallers.person(viewer));
        Assert.assertFalse(ids(on(SHARED, () -> mine.getRecentTripsInMyOrgs(0))).contains(acmeTrip.getId()),
                "a hosted org's trip stays off the shared site, member or not");
        Assert.assertTrue(ids(on(ACME_SITE, () -> mine.getRecentTripsInMyOrgs(0))).contains(acmeTrip.getId()),
                "and its own site lists it");
        Assert.assertFalse(ids(on(ACME_SITE, () -> mine.getRecentTripsInMyOrgs(0))).contains(ownNewer.getId()),
                "an org site lists only its own trips");
    }

    @Test
    public void aSiteAdminSeesTheSitesRecentTrips() throws Exception {
        final TripCommands admin = as(TestCallers.siteAdmin());
        final List<String> all = ids(on(SHARED, () -> admin.getRecentTripsInMyOrgs(0)));
        Assert.assertEquals(all, ids(on(SHARED, () -> admin.getRecentTrips(0))), "the whole site, unfiltered");
        Assert.assertTrue(all.contains(otherOrgTrip.getId()) && all.contains(ownNewer.getId()));
    }

    @Test
    public void nobodyAnUnknownPersonOrAPersonWithNoOrganizationSeesNothing() throws Exception {
        final Caller nobody = new Caller(null, false, TestCallers.siteAdmin().auditActor(), new PrivilegeCommands());
        Assert.assertEquals(on(SHARED, () -> as(nobody).getRecentTripsInMyOrgs(0)), List.of());
        Assert.assertEquals(on(SHARED, () -> as(TestCallers.person(Person.Id.newInstance()))
                .getRecentTripsInMyOrgs(0)), List.of(), "no such person");
        final Person loner = Person.builder().first("Loner").last("Myorgs" + RandomData.genAlpha(6)).build();
        Assert.assertTrue(DAO.getInstance().savePerson(loner));
        Assert.assertEquals(on(SHARED, () -> as(TestCallers.person(loner.getId())).getRecentTripsInMyOrgs(0)),
                List.of(), "in no organization");
    }
}
