package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * {@link PeopleReach} and the admin people pages' lists built on it: {@code TripCommands
 * .getRecentTripsInPeopleReach} (the admin person page's Registrations), {@code getTripsForUserInPeopleReach} (its
 * Trips), {@code TransactionsCommands.getTransactionsInPeopleReach} (its transaction count and balance) and {@code
 * OrgCommands.visibleTripsOf} (the People page's Trips column). A site admin sees everything; anyone else only the
 * organizations they manage or hold {@code peopleAdmin} in, never one they merely belong to, since membership is
 * self-grantable (owner decision 2026-11-14). Also the cap of {@code getTripsForOrg}, which shares their
 * latest-ending rule. The trips are dated centuries out so they lead the recency index whatever else the suite has
 * saved.
 */
public class TripsInPeopleReachTest {

    private static final SiteContext SHARED = SiteContext.shared("localhost");
    private static final SiteContext ACME_SITE =
            SiteContext.org(Organization.Id.from(FakeData.ACME_ORG_ID), "acme", "acme.localhost");
    private static final OrgCommands SITE_ADMIN_ORGS = new OrgCommands(TestCallers::siteAdmin);

    private Trip ownNewer;
    private Trip ownOlder;
    /** Newer than both own trips, in an org the viewer is a plain MEMBER of: never listed for them. */
    private Trip otherOrgTrip;
    /** In a hosted org the viewer holds peopleAdmin in too: reached on that org's site only. */
    private Trip acmeTrip;
    /** In an org the manager administers (holding no privilege there). */
    private Trip managedTrip;
    private Organization other;
    /** peopleAdmin in the own org and in Acme, and a plain member of the other org. */
    private Person.Id viewer;
    /** Admin of the managed org only. */
    private Person.Id manager;
    /** On the rosters of the newer own trip, the other org's trip, the Acme trip and the managed org's trip. */
    private Person.Id subject;

    @BeforeClass
    public void init() throws IOException {
        DAO.getInstance();
        FakeData.addFakeData();
        final Organization own = saveOrg("Reach own");
        other = saveOrg("Reach other");
        final Organization managed = saveOrg("Reach managed");
        subject = Person.Id.newInstance();
        ownOlder = saveTrip("Reach own older", own.getId().getValue(), 2900);
        ownNewer = saveTrip("Reach own newer", own.getId().getValue(), 2901, subject);
        otherOrgTrip = saveTrip("Reach other", other.getId().getValue(), 2902, subject);
        acmeTrip = saveTrip("Reach acme", FakeData.ACME_ORG_ID, 2903, subject);
        managedTrip = saveTrip("Reach managed", managed.getId().getValue(), 2899, subject);
        viewer = savePerson("Viewer");
        peopleAdminIn(own.getId().getValue(), viewer);
        peopleAdminIn(FakeData.ACME_ORG_ID, viewer);
        Assert.assertTrue(SITE_ADMIN_ORGS.addMember(other.getId().getValue(), viewer), "a plain member of other");
        manager = savePerson("Manager");
        Assert.assertTrue(SITE_ADMIN_ORGS.setOrgAdmin(managed.getId().getValue(), manager, true));
    }

    private static Organization saveOrg(final String name) throws IOException {
        final Organization org = Organization.builder().name(name + " " + RandomData.genAlpha(6)).build();
        Assert.assertTrue(DAO.getInstance().saveOrganization(org));
        return org;
    }

    private static Person.Id savePerson(final String first) throws IOException {
        final Person person = Person.builder().first(first).last("Reach" + RandomData.genAlpha(6)).build();
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person.getId();
    }

    private static void peopleAdminIn(final String orgId, final Person.Id personId) {
        Assert.assertTrue(SITE_ADMIN_ORGS.addMember(orgId, personId));
        Assert.assertTrue(SITE_ADMIN_ORGS.grantOrgPrivilege(orgId, personId, PrivilegeCommands.PEOPLE_ADMIN));
    }

    private static Trip saveTrip(final String title, final String orgId, final int year,
            final Person.Id... people) throws IOException {
        return saveTrip(title, orgId, LocalDateTime.of(year, 3, 1, 9, 0), LocalDateTime.of(year, 3, 9, 17, 0),
                people);
    }

    private static Trip saveTrip(final String title, final String orgId, final LocalDateTime start,
            final LocalDateTime end, final Person.Id... people) throws IOException {
        final Trip trip = Trip.builder().title(title + " " + RandomData.genAlpha(6))
                .startDate(start)
                .endDate(end)
                .people(new ArrayList<>(List.of(people)))
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
        return new TripCommands(() -> new OrgCommands(() -> caller), () -> caller);
    }

    private static TripCommands as(final Person.Id personId) {
        return as(TestCallers.person(personId));
    }

    private static Caller nobody() {
        return new Caller(null, false, TestCallers.siteAdmin().auditActor(), new PrivilegeCommands());
    }

    private static List<String> ids(final List<Trip> found) {
        return found.stream().map(Trip::getId).toList();
    }

    @Test
    public void aPeopleAdminSeesOnlyTheirOrganizationsTrips() throws Exception {
        final List<String> mine = ids(on(SHARED, () -> as(viewer).getRecentTripsInPeopleReach(0)));
        Assert.assertEquals(mine, List.of(ownOlder.getId(), ownNewer.getId()),
                "the own org's trips, in display order (by start), and no other tenant's");
        Assert.assertEquals(ids(on(SHARED, () -> as(viewer).getRecentTripsInPeopleReach(100))), mine,
                "a cap above the count keeps them all");
    }

    /** Membership is self-grantable (a default org, a registration): on its own it reaches nothing. */
    @Test
    public void aPlainMemberOfAnOrganizationDoesNotSeeItsTrips() throws Exception {
        final Person self = DAO.getInstance().getPerson(viewer, Cached.NO).orElseThrow();
        Assert.assertTrue(self.getOrgIds().contains(other.getId()), "the viewer IS a member of the other org");
        Assert.assertFalse(ids(on(SHARED, () -> as(viewer).getRecentTripsInPeopleReach(0)))
                .contains(otherOrgTrip.getId()), "Registrations");
        Assert.assertFalse(ids(on(SHARED, () -> as(viewer).getTripsForUserInPeopleReach(subject)))
                .contains(otherOrgTrip.getId()), "Trips");
        final OrgCommands viewerOrgs = new OrgCommands(() -> TestCallers.person(viewer));
        final List<String> column = ids(viewerOrgs.visibleTripsOf(Person.builder().id(subject).build()));
        Assert.assertTrue(column.contains(ownNewer.getId()) && !column.contains(otherOrgTrip.getId()),
                "the People page's Trips column agrees: " + column);
    }

    @Test
    public void anOrgAdminSeesTheTripsOfTheOrgTheyManage() throws Exception {
        Assert.assertEquals(ids(on(SHARED, () -> as(manager).getRecentTripsInPeopleReach(0))),
                List.of(managedTrip.getId()), "managing an org reaches its people without peopleAdmin");
        Assert.assertEquals(ids(on(SHARED, () -> as(manager).getTripsForUserInPeopleReach(subject))),
                List.of(managedTrip.getId()));
    }

    @Test
    public void anotherTenantsNewerTripsDoNotCrowdOutTheCallersOwn() throws Exception {
        // The other org's trip and the Acme trip are the two most recent on the site: a limit-1 window of the
        // site's trips would hold neither own trip.
        Assert.assertEquals(ids(on(SHARED, () -> as(viewer).getRecentTripsInPeopleReach(1))),
                List.of(ownNewer.getId()), "the limit keeps the caller's own most recent trip");
    }

    @Test
    public void theSiteGateStillHolds() throws Exception {
        final TripCommands mine = as(viewer);
        Assert.assertFalse(ids(on(SHARED, () -> mine.getRecentTripsInPeopleReach(0))).contains(acmeTrip.getId()),
                "a hosted org's trip stays off the shared site, people admin or not");
        Assert.assertTrue(ids(on(ACME_SITE, () -> mine.getRecentTripsInPeopleReach(0))).contains(acmeTrip.getId()),
                "and its own site lists it");
        Assert.assertFalse(ids(on(ACME_SITE, () -> mine.getRecentTripsInPeopleReach(0))).contains(ownNewer.getId()),
                "an org site lists only its own trips");
    }

    @Test
    public void aSiteAdminSeesTheSitesRecentTrips() throws Exception {
        final TripCommands admin = as(TestCallers.siteAdmin());
        final List<String> all = ids(on(SHARED, () -> admin.getRecentTripsInPeopleReach(0)));
        Assert.assertEquals(all, ids(on(SHARED, () -> admin.getRecentTrips(0))), "the whole site, unfiltered");
        Assert.assertTrue(all.contains(otherOrgTrip.getId()) && all.contains(ownNewer.getId()));
    }

    @Test
    public void nobodyAnUnknownPersonOrAPersonWithNoOrganizationSeesNothing() throws Exception {
        Assert.assertEquals(on(SHARED, () -> as(nobody()).getRecentTripsInPeopleReach(0)), List.of());
        Assert.assertEquals(on(SHARED, () -> as(Person.Id.newInstance()).getRecentTripsInPeopleReach(0)), List.of(),
                "no such person");
        final Person.Id loner = savePerson("Loner");
        Assert.assertEquals(on(SHARED, () -> as(loner).getRecentTripsInPeopleReach(0)), List.of(),
                "in no organization");
    }

    @Test
    public void aSubjectsTripsListShowsOnlyTheViewersReach() throws Exception {
        Assert.assertEquals(ids(on(SHARED, () -> as(viewer).getTripsForUserInPeopleReach(subject))),
                List.of(ownNewer.getId()), "the subject's trip in the viewer's org, not the other tenant's");
        Assert.assertEquals(ids(on(ACME_SITE, () -> as(viewer).getTripsForUserInPeopleReach(subject))),
                List.of(acmeTrip.getId()), "an org site: its own trip, which the viewer's Acme peopleAdmin admits");

        final TripCommands siteAdmin = as(TestCallers.siteAdmin());
        final List<String> admin = ids(on(SHARED, () -> siteAdmin.getTripsForUserInPeopleReach(subject)));
        Assert.assertEquals(admin, ids(on(SHARED, () -> siteAdmin.getTripsForUser(subject))));
        Assert.assertTrue(admin.contains(otherOrgTrip.getId()) && admin.contains(ownNewer.getId()),
                "a site admin sees every trip the subject is on: " + admin);

        Assert.assertEquals(on(SHARED, () -> as(nobody()).getTripsForUserInPeopleReach(subject)), List.of());
    }

    /** The admin person page's transaction count and balance: the same reach, and org-less rows hidden. */
    @Test
    public void theLedgerRowsFollowTheSameReach() throws Exception {
        final Person.Id payer = savePerson("Payer");
        final Transaction own = ledgerRow(payer, ownNewer.getOrgId());
        final Transaction otherRow = ledgerRow(payer, otherOrgTrip.getOrgId());
        final Transaction legacy = ledgerRow(payer, null);

        final OrgCommands viewerOrgs = new OrgCommands(() -> TestCallers.person(viewer));
        Assert.assertEquals(txIds(new TransactionsCommands(() -> viewerOrgs).getTransactionsInPeopleReach(payer)),
                List.of(own.getTxId()), "the own org's row: not the other org's, nor an org-less one");

        final List<String> all = txIds(new TransactionsCommands(() -> SITE_ADMIN_ORGS)
                .getTransactionsInPeopleReach(payer));
        Assert.assertTrue(all.containsAll(List.of(own.getTxId(), otherRow.getTxId(), legacy.getTxId())),
                "a site admin sees every row: " + all);
        final OrgCommands nobodyOrgs = new OrgCommands(TripsInPeopleReachTest::nobody);
        Assert.assertEquals(new TransactionsCommands(() -> nobodyOrgs).getTransactionsInPeopleReach(payer),
                List.of(), "nobody signed in");
    }

    private static Transaction ledgerRow(final Person.Id payer, final String orgId) throws IOException {
        final Transaction tx = new Transaction(RandomData.genAlpha(8), payer, null, Transaction.Type.Tx,
                Transaction.TransactionType.Payment, LocalDateTime.of(2026, 8, 1, 12, 0), 10f, "cat", "note");
        tx.setOrgId(orgId);
        Assert.assertTrue(DAO.getInstance().saveTransaction(tx));
        return tx;
    }

    private static List<String> txIds(final List<Transaction> rows) {
        return rows.stream().map(Transaction::getTxId).toList();
    }

    @Test
    public void anOrgsTripsPageKeepsItsLatestEndingTripsInDisplayOrder() throws Exception {
        // Long starts first and ends last; Early starts second and ends before Late. The two that END last are
        // Long and Late, shown by start. The cap used to keep the first two by START: Long and Early.
        final Organization org = saveOrg("Reach cap");
        final String orgId = org.getId().getValue();
        final Trip longest = saveTrip("Cap long", orgId, LocalDateTime.of(2800, 1, 1, 9, 0),
                LocalDateTime.of(2800, 12, 20, 17, 0));
        final Trip early = saveTrip("Cap early", orgId, LocalDateTime.of(2800, 2, 1, 9, 0),
                LocalDateTime.of(2800, 3, 1, 17, 0));
        final Trip late = saveTrip("Cap late", orgId, LocalDateTime.of(2800, 6, 1, 9, 0),
                LocalDateTime.of(2800, 7, 1, 17, 0));
        final TripCommands admin = new TripCommands(() -> SITE_ADMIN_ORGS, TestCallers::siteAdmin);
        Assert.assertEquals(ids(on(SHARED, () -> admin.getTripsForOrg(orgId, 2))),
                List.of(longest.getId(), late.getId()));
        Assert.assertEquals(ids(on(SHARED, () -> admin.getTripsForOrg(orgId, 3))),
                List.of(longest.getId(), early.getId(), late.getId()), "under the cap: all, by start");
    }

    /**
     * A trip with no end date is ongoing: it ranks as ending LAST, as the recency index scores it, so the cap never
     * drops it first. (Not saved: an undated trip would lead every recent-trips window of the rest of the suite.)
     */
    @Test
    public void theCapKeepsAnUndatedTrip() {
        final Trip early = Trip.builder().startDate(LocalDateTime.of(2800, 2, 1, 9, 0))
                .endDate(LocalDateTime.of(2800, 3, 1, 17, 0)).build();
        final Trip undated = Trip.builder().startDate(LocalDateTime.of(2800, 4, 1, 9, 0)).build();
        undated.setEndDate(null);       // the builder defaults one; a stored trip may still have none
        final Trip late = Trip.builder().startDate(LocalDateTime.of(2800, 6, 1, 9, 0))
                .endDate(LocalDateTime.of(2800, 7, 1, 17, 0)).build();
        final List<Trip> byStart = List.of(early, undated, late);
        Assert.assertEquals(ids(TripCommands.latestEnding(byStart, 1)), List.of(undated.getId()),
                "the undated trip outranks every dated one");
        Assert.assertEquals(ids(TripCommands.latestEnding(byStart, 2)), List.of(undated.getId(), late.getId()),
                "then the latest-ending, in display order");
        Assert.assertEquals(TripCommands.latestEnding(byStart, 0), byStart, "no cap: all of them");
    }
}
