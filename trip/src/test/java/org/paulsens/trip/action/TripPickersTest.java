package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The trip pickers ({@code admin/batchTx.xhtml}'s Trip menu, {@code admin/editTripPrivs.xhtml}, the trip filter of
 * {@code WEB-INF/selectPeople.xhtml}): which trips they offer whom, and that the trip a picker was opened for stays
 * a choice past the recent cap. Organization is the tenancy boundary and a picker is a read path, so a trip's
 * Finance Admin is offered the trips they hold the privilege on, never the rest of the site.
 */
public class TripPickersTest {

    private static final SiteContext SHARED = SiteContext.shared("localhost");
    private static final String FIN_ADMIN = PrivilegeCommands.TRIP_FIN_ADMIN;

    /** A CFPW trip whose Finance Admin is {@link #finAdmin}. */
    private Trip ownTrip;
    /** Another sharing tenant's trip, reachable on the shared site: what the Finance Admin must never see. */
    private Trip otherOrgTrip;
    private Person.Id finAdmin;
    private Person.Id traveler;

    @BeforeClass
    public void init() throws IOException {
        DAO.getInstance();
        FakeData.addFakeData();
        final Organization other = Organization.builder().name("Picker other org " + RandomData.genAlpha(6)).build();
        Assert.assertTrue(DAO.getInstance().saveOrganization(other));
        traveler = Person.Id.newInstance();
        ownTrip = saveTrip("Picker own trip", FakeData.CFPW_ORG_ID, 2098);
        otherOrgTrip = saveTrip("Picker other trip", other.getId().getValue(), 2099);
        finAdmin = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate(FIN_ADMIN, ownTrip.getId(), FIN_ADMIN)
                .withNewPerson(finAdmin)));
    }

    private Trip saveTrip(final String title, final String orgId, final int year) throws IOException {
        final Trip trip = Trip.builder().title(title + " " + RandomData.genAlpha(6))
                .startDate(LocalDateTime.of(year, 1, 1, 9, 0))
                .endDate(LocalDateTime.of(year, 1, 2, 17, 0))
                .people(new ArrayList<>(List.of(traveler)))
                .build();
        trip.setOrgId(orgId);
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static <T> T onShared(final ScopedValue.CallableOp<T, Exception> body) throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, SHARED)).call(body);
    }

    private static TripCommands as(final Caller caller) {
        return new TripCommands(() -> null, () -> caller);
    }

    private static List<String> ids(final List<Trip> found) {
        return found.stream().map(Trip::getId).toList();
    }

    @Test
    public void aSiteAdminIsOfferedEveryTripTheSiteReaches() throws Exception {
        final TripCommands admin = as(TestCallers.siteAdmin());
        final List<String> offered = ids(onShared(() -> admin.getRecentTripsFor(FIN_ADMIN, 0)));
        Assert.assertTrue(offered.contains(ownTrip.getId()));
        Assert.assertTrue(offered.contains(otherOrgTrip.getId()), "the other tenant's trip is on the shared site");
        Assert.assertEquals(offered, ids(onShared(() -> admin.getRecentTrips(0))), "the whole site, unfiltered");
        Assert.assertEquals(ids(onShared(() -> admin.getRecentTripsFor(null, 0))), offered,
                "a site admin's list does not depend on the page's privilege");
        Assert.assertFalse(ids(onShared(() -> admin.getRecentTripsFor(FIN_ADMIN, 0)))
                .contains(FakeData.ACME_TRIP_ID), "and the site gate still holds: a hosted org's trip stays off");
    }

    @Test
    public void aTripFinanceAdminIsOfferedOnlyTheTripsTheyHoldItOn() throws Exception {
        final TripCommands mine = as(TestCallers.person(finAdmin));
        Assert.assertEquals(ids(onShared(() -> mine.getRecentTripsFor(FIN_ADMIN, 0))), List.of(ownTrip.getId()),
                "one trip's Finance Admin sees that trip, and no other tenant's");
        Assert.assertEquals(ids(onShared(() -> mine.getRecentTripsWith(FIN_ADMIN, 0, otherOrgTrip.getId()))),
                List.of(ownTrip.getId()), "a pinned id the caller does not hold adds nothing");
        // The privilege is the page's: holding tripFinAdmin is no key to a picker gated by another one.
        Assert.assertEquals(onShared(() -> mine.getRecentTripsFor(PrivilegeCommands.TRIP_MGR, 0)), List.of());
        Assert.assertEquals(onShared(() -> mine.getRecentTripsFor(null, 0)), List.of(), "no privilege, no trips");
        Assert.assertEquals(onShared(() -> mine.getRecentTripsFor("noSuchPrivilege", 0)), List.of());
        Assert.assertEquals(onShared(() -> mine.getRecentTripsFor(PrivilegeCommands.PRIVILEGE_ADMIN, 0)),
                List.of(), "a global base the caller does not hold offers nothing");

        final TripCommands nobody = as(TestCallers.person(Person.Id.newInstance()));
        Assert.assertEquals(onShared(() -> nobody.getRecentTripsFor(FIN_ADMIN, 0)), List.of());
    }

    @Test
    public void aPickedTripLoadsOnlyWhenTheCallerMayPickIt() throws Exception {
        final TripCommands mine = as(TestCallers.person(finAdmin));
        final Trip own = onShared(() -> mine.getPickableTrip(FIN_ADMIN, ownTrip.getId()));
        Assert.assertEquals(own.getId(), ownTrip.getId());
        Assert.assertEquals(own.getPeople(), List.of(traveler));

        // The batch editor's ajax loads the picked trip's roster through this: another tenant's comes back blank.
        final Trip other = onShared(() -> mine.getPickableTrip(FIN_ADMIN, otherOrgTrip.getId()));
        Assert.assertNotEquals(other.getId(), otherOrgTrip.getId());
        Assert.assertNull(other.getTitle());
        Assert.assertEquals(other.getPeople(), List.of(), "no roster of a trip the caller may not pick");
        Assert.assertEquals(other.getTripEvents(), List.of());
        Assert.assertNull(onShared(() -> mine.getPickableTrip(FIN_ADMIN, "none")).getTitle());

        final TripCommands admin = as(TestCallers.siteAdmin());
        Assert.assertEquals(onShared(() -> admin.getPickableTrip(FIN_ADMIN, otherOrgTrip.getId())).getId(),
                otherOrgTrip.getId());
        Assert.assertNull(onShared(() -> admin.getPickableTrip(FIN_ADMIN, FakeData.ACME_TRIP_ID)).getTitle(),
                "out of site is blank for a site admin too");
    }

    @Test
    public void aGlobalPrivilegeAdminIsOfferedTheSitesTrips() throws Exception {
        // The privilege editors are gated by the GLOBAL privilegeAdmin, a site-wide grant that edits every
        // trip's privileges; their trip picker lists what the site reaches, as for a site admin.
        final Person.Id holder = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        final String name = PrivilegeCommands.PRIVILEGE_ADMIN;
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate(name, null, name).withNewPerson(holder)));
        try {
            final TripCommands global = as(TestCallers.person(holder));
            final List<String> offered = ids(onShared(() -> global.getRecentTripsFor(name, 0)));
            Assert.assertTrue(offered.contains(otherOrgTrip.getId()));
            Assert.assertTrue(offered.contains(ownTrip.getId()));
            Assert.assertEquals(onShared(() -> global.getRecentTripsFor(FIN_ADMIN, 0)), List.of(),
                    "a global privilegeAdmin is no Finance Admin anywhere");
        } finally {
            priv.remove(name, null, holder);
        }
    }

    @Test
    public void aTripPickerListsTheTripItWasOpenedForEvenPastTheRecentCap() throws Exception {
        // The group transaction editor opened for a trip older than its menu's recent cap: without the trip
        // among the choices the menu posted its first option ("none") and the group was saved with no trip.
        final Trip old = Trip.builder().title("Picker trip " + RandomData.genAlpha(6))
                .startDate(LocalDateTime.of(2001, 1, 1, 9, 0))
                .endDate(LocalDateTime.of(2001, 1, 2, 17, 0))
                .build();
        Assert.assertTrue(DAO.getInstance().saveTrip(old));
        final String oldId = old.getId();
        final TripCommands admin = as(TestCallers.siteAdmin());
        final List<String> recent = ids(onShared(() -> admin.getRecentTripsFor(FIN_ADMIN, 1)));
        Assert.assertFalse(recent.contains(oldId), "precondition: the 2001 trip is not the most recent one");

        final List<String> choices = ids(onShared(() -> admin.getRecentTripsWith(FIN_ADMIN, 1, oldId)));
        Assert.assertEquals(choices.getFirst(), oldId, "the trip the picker was opened for comes first");
        Assert.assertEquals(choices.subList(1, choices.size()), recent, "then the recent trips, unchanged");

        // Already listed, an unknown id, or a trip this site does not reach: just the recent.
        final List<String> all = ids(onShared(() -> admin.getRecentTripsFor(FIN_ADMIN, 0)));
        Assert.assertEquals(ids(onShared(() -> admin.getRecentTripsWith(FIN_ADMIN, 0, oldId))), all, "no duplicate");
        Assert.assertEquals(ids(onShared(() -> admin.getRecentTripsWith(FIN_ADMIN, 1, "no-such-trip"))), recent);
        Assert.assertEquals(ids(onShared(() -> admin.getRecentTripsWith(FIN_ADMIN, 1, FakeData.ACME_TRIP_ID))),
                recent, "a hosted org's trip is never offered on the shared site");
    }

    @Test
    public void noTripAtAllCostsNoLookup() throws Exception {
        // The editor's menu holds "none" once a group has no trip, and every render and every validation of
        // the menu asks again: a lookup of a key that cannot exist is an uncached DynamoDB miss each time.
        final AtomicInteger lookups = new AtomicInteger();
        final TripCommands counting = new TripCommands(() -> null, TestCallers::siteAdmin) {
            @Override
            public Trip getTrip(final String id) {
                lookups.incrementAndGet();
                return super.getTrip(id);
            }
        };
        final List<String> recent = ids(onShared(() -> counting.getRecentTripsFor(FIN_ADMIN, 1)));
        for (final String none : new String[] {null, "", " ", "none"}) {
            Assert.assertEquals(ids(onShared(() -> counting.getRecentTripsWith(FIN_ADMIN, 1, none))), recent);
            Assert.assertNull(onShared(() -> counting.getPickableTrip(FIN_ADMIN, none)).getTitle());
        }
        Assert.assertEquals(lookups.get(), 0, "no trip, no lookup");
    }
}
