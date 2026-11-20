package org.paulsens.trip.action;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * {@code viewFinances} is scoped to ONE organization (2026-11). It was a global grant, so its holder opened every
 * tenant's trip finance pages; now a holder reads the money of the trips of the organization the grant names, a
 * leftover global row reads nothing, and a site admin still reads everything. Also the split this change made
 * public on {@link TripFinanceGate}: {@code seesMoney} (the money pages) is narrower than {@code canView} (the
 * planning money), so a trip manager keeps the budget without gaining the travelers' payments.
 *
 * <p>Real rows through the real commands ({@link TenancyFixtures}): these rules are about which privilege rows
 * exist, so a stubbed check would prove nothing.
 */
public class OrgFinanceViewerTest {

    private static final String BASE = PrivilegeCommands.VIEW_FINANCES;

    private Organization ours;
    private Organization theirs;
    private Trip ourTrip;
    private Trip theirTrip;
    /** A member of BOTH organizations who holds the grant in {@link #ours} only. */
    private Person viewer;

    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
        viewer = TenancyFixtures.savedPerson("FinView");
        ours = TenancyFixtures.org("Fin ours", viewer);
        theirs = TenancyFixtures.org("Fin theirs", viewer);
        ourTrip = TenancyFixtures.tripOwnedBy(ours);
        theirTrip = TenancyFixtures.tripOwnedBy(theirs);
        assertTrue(TenancyFixtures.asSiteAdmin().grantOrgPrivilege(id(ours), viewer.getId(), BASE));
    }

    private static String id(final Organization org) {
        return org.getId().getValue();
    }

    private static TripFinanceGate gate(final Person person) {
        return TripFinanceGate.forCaller(() -> TenancyFixtures.callerFor(person));
    }

    private static TripFinanceGate siteAdmin() {
        return TripFinanceGate.forCaller(TenancyFixtures::siteAdminCaller);
    }

    /** A person holding one privilege row, written directly: the trip roles need no membership to be read. */
    private static Person holding(final String base, final String scopeId) {
        final Person person = TenancyFixtures.savedPerson("Holds" + base);
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(base, scopeId, base).withNewPerson(person.getId())));
        return person;
    }

    @Test
    public void aHolderSeesTheMoneyOfTheirOwnOrganizationsTripsAndNoOthers() {
        final TripFinanceGate mine = gate(viewer);
        assertTrue(mine.seesMoney(ourTrip.getId()), "the grant names this trip's organization");
        assertTrue(mine.canView(ourTrip.getId()), "and the planning money follows");
        assertFalse(mine.canEdit(ourTrip.getId()), "a viewer edits nothing");
        assertFalse(mine.canSettle(ourTrip.getId()));

        // A MEMBER of the other organization too, with no grant there: membership is not the grant.
        assertFalse(mine.seesMoney(theirTrip.getId()), "another organization's trip");
        assertFalse(mine.canView(theirTrip.getId()));

        assertTrue(siteAdmin().seesMoney(ourTrip.getId()), "a site admin still sees everything");
        assertTrue(siteAdmin().seesMoney(theirTrip.getId()));
        assertFalse(gate(TenancyFixtures.savedPerson("Stranger")).seesMoney(ourTrip.getId()));
    }

    @Test
    public void aLeftoverGlobalRowGrantsNothingAnywhere() throws Exception {
        final Person global = holding(BASE, null);
        assertTrue(new PrivilegeCommands().check(BASE, null, global.getId()), "the inert row really is stored");
        final TripFinanceGate gate = gate(global);
        for (final Trip trip : List.of(ourTrip, theirTrip)) {
            assertFalse(gate.seesMoney(trip.getId()), "the global form is gone");
            assertFalse(gate.canView(trip.getId()));
            assertFalse(new CheckinCommands(() -> TenancyFixtures.callerFor(global), () -> null,
                    java.time.LocalDateTime::now).canSeeMoney(trip.getId()));
        }
        // Nor is it a key to a trip picker: it is no longer a global base, so its holder "sees every trip" nowhere.
        final TripCommands trips = new TripCommands(() -> null, () -> TenancyFixtures.callerFor(global));
        assertEquals(trips.getRecentTripsFor(BASE, 0), List.of());
        assertFalse(ourTrip.getId().equals(trips.getPickableTrip(BASE, ourTrip.getId()).getId()));
        assertFalse(new OrgCommands(() -> TenancyFixtures.callerFor(global)).canViewOrgHub(id(ours)));
    }

    @Test
    public void aTripManagerKeepsTheBudgetAndGainsNoMoneyPage() {
        final String tripId = ourTrip.getId();
        final TripFinanceGate manager = gate(holding(PrivilegeCommands.TRIP_MGR, tripId));
        assertTrue(manager.canView(tripId), "an editor reads what they edit");
        assertFalse(manager.seesMoney(tripId), "but a trip manager never read the travelers' payments");

        final TripFinanceGate finAdmin = gate(holding(PrivilegeCommands.TRIP_FIN_ADMIN, tripId));
        assertTrue(finAdmin.canView(tripId));
        assertFalse(finAdmin.seesMoney(tripId), "nor did a finance admin, by that role alone");

        final TripFinanceGate finView = gate(holding(PrivilegeCommands.TRIP_FIN_VIEW, tripId));
        assertTrue(finView.seesMoney(tripId), "the trip's own finance viewer");
        assertTrue(finView.canView(tripId));
        assertFalse(finView.seesMoney(theirTrip.getId()), "on that one trip");

        assertFalse(gate(holding(PrivilegeCommands.TRIP_VIEW, tripId)).seesMoney(tripId), "roster access is not money");
    }

    @Test
    public void unknownTripsVisitorsAndTripsWithNoOrganization() throws IOException {
        final String unknown = UUID.randomUUID().toString();
        assertFalse(siteAdmin().seesMoney(unknown), "an unknown trip, whoever asks");
        assertFalse(siteAdmin().seesMoney(null));
        assertFalse(siteAdmin().seesMoney(" "));
        assertFalse(gate(viewer).seesMoney(unknown));

        final TripFinanceGate visitor = TripFinanceGate.forCaller(
                () -> new Caller(null, false, AuditActor.from(null), new PrivilegeCommands()));
        assertFalse(visitor.seesMoney(ourTrip.getId()), "nobody signed in");
        assertFalse(visitor.canView(ourTrip.getId()));
        // The page's spelling of the same rule, with no Faces request behind it: nobody is signed in.
        assertFalse(new TransactionsCommands(() -> null).seesMoney(ourTrip.getId()));

        final Trip orgless = Trip.builder().id(UUID.randomUUID().toString()).title("No org").build();
        assertTrue(DAO.getInstance().saveTrip(orgless));
        assertFalse(gate(viewer).seesMoney(orgless.getId()), "no organization, no organization's finance viewers");
        assertTrue(gate(holding(PrivilegeCommands.TRIP_FIN_VIEW, orgless.getId())).seesMoney(orgless.getId()),
                "its own finance viewer is a role on the trip, which needs no organization");
        assertTrue(siteAdmin().seesMoney(orgless.getId()));

        final Caller mine = TenancyFixtures.callerFor(viewer);
        assertFalse(TripFinanceGate.seesMoney(null, ourTrip));
        assertFalse(TripFinanceGate.seesMoney(mine, null));
        assertTrue(TripFinanceGate.seesMoney(mine, ourTrip), "the rule over a trip the caller already resolved");
        assertFalse(TripFinanceGate.seesMoney(mine, theirTrip));
    }

    @Test
    public void theGrantIsBoundedByMembershipAndTheAllowList() {
        final Person admin = TenancyFixtures.savedPerson("FinOrgAdmin");
        final Person member = TenancyFixtures.savedPerson("FinMember");
        final Person outsider = TenancyFixtures.savedPerson("FinOutsider");
        final Organization org = TenancyFixtures.org("Fin allow", admin, member);
        TenancyFixtures.makeOrgAdmin(org, admin);
        final OrgCommands asAdmin = TenancyFixtures.orgsFor(admin);
        final OrgCommands site = TenancyFixtures.asSiteAdmin();

        // Never restricted (null): every org-scoped base is grantable, this one included.
        assertTrue(asAdmin.grantableOrgPrivileges(id(org)).contains(BASE));
        assertFalse(asAdmin.grantOrgPrivilege(id(org), outsider.getId(), BASE), "grantees are members");
        assertFalse(site.grantOrgPrivilege(id(org), outsider.getId(), BASE), "for a site admin too");

        // An allow-list written before the base existed does not hold it: the org cannot grant it until a site
        // admin adds it (the go-live step), though the site admin may grant it meanwhile.
        assertTrue(site.setGrantablePrivileges(id(org), List.of(PrivilegeCommands.PEOPLE_ADMIN)));
        assertFalse(asAdmin.grantableOrgPrivileges(id(org)).contains(BASE));
        assertFalse(asAdmin.grantOrgPrivilege(id(org), member.getId(), BASE), "not on the allow-list");
        assertFalse(new PrivilegeCommands().check(BASE, id(org), member.getId()));
        assertTrue(site.setGrantablePrivileges(id(org), List.of(PrivilegeCommands.PEOPLE_ADMIN, BASE)),
                "an allow-list may name it: it is a grantable base");
        assertTrue(asAdmin.grantOrgPrivilege(id(org), member.getId(), BASE));

        final Trip trip = TenancyFixtures.tripOwnedBy(org);
        assertTrue(gate(member).seesMoney(trip.getId()));
        final List<Map<String, String>> held = asAdmin.heldOrgPrivs(id(org), member.getId());
        assertEquals(held.stream().map(def -> def.get("name")).toList(), List.of("Finance Viewer"));
        assertTrue(asAdmin.grantableDescription(BASE).startsWith("Finance Viewer (organization-wide): "));
        assertEquals(PrivilegeSummaries.siteName(BASE), "Finance Viewer", "one name for the inert global row too");

        // The grant is no key to the org dashboard: nothing on it is a finance viewer's.
        assertFalse(TenancyFixtures.orgsFor(member).canViewOrgHub(id(org)));
        TenancyFixtures.grantPeopleAdmin(org, outsiderJoined(org, outsider));
        assertTrue(TenancyFixtures.orgsFor(outsider).canViewOrgHub(id(org)), "every other org grant still opens it");

        assertTrue(asAdmin.revokeOrgPrivilege(id(org), member.getId(), BASE));
        assertFalse(gate(member).seesMoney(trip.getId()), "revoked");
    }

    private static Trip orgTrip(final String orgId) throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Fin seeded org trip").build();
        trip.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static Person outsiderJoined(final Organization org, final Person outsider) {
        TenancyFixtures.addMember(org, outsider);
        return outsider;
    }

    @Test
    public void leavingTheOrganizationRevokesTheGrant() {
        final Person leaver = TenancyFixtures.savedPerson("FinLeaver");
        // Two organizations: nobody can be removed from their LAST one.
        final Organization org = TenancyFixtures.org("Fin leave", leaver);
        TenancyFixtures.org("Fin stay", leaver);
        final Trip trip = TenancyFixtures.tripOwnedBy(org);
        assertTrue(TenancyFixtures.asSiteAdmin().grantOrgPrivilege(id(org), leaver.getId(), BASE));
        assertTrue(gate(leaver).seesMoney(trip.getId()));

        assertTrue(TenancyFixtures.asSiteAdmin().removeMember(id(org), leaver.getId()));
        assertFalse(new PrivilegeCommands().check(BASE, id(org), leaver.getId()),
                "a grant must not outlive the membership it was bounded by");
        assertFalse(gate(leaver).seesMoney(trip.getId()));
    }

    /**
     * Local mode's fixture ({@code FakeData}): ONE Acme member (Matt) reads Acme's trip finances and nobody
     * else's. The holder is read from the row, not looked up by persona: every class that calls
     * {@code initFakeData} mints the fake people new ids, and the seeded grant keeps the id it was made for.
     */
    @Test
    public void localModeSeedsOneAcmeFinanceViewer() throws IOException {
        final List<Person.Id> holders = new PrivilegeCommands().getPrivilege(BASE, FakeData.ACME_ORG_ID).getPeople();
        assertEquals(holders.size(), 1, "the seed, and no test's leftovers");
        // Trips of this test's own in the two seeded organizations: other classes move and delete the seeded ones.
        final Trip acmeTrip = orgTrip(FakeData.ACME_ORG_ID);
        final Trip cfpwTrip = orgTrip(FakeData.CFPW_ORG_ID);
        final TripFinanceGate seeded = TripFinanceGate.forCaller(() -> TestCallers.person(holders.get(0)));
        assertTrue(seeded.seesMoney(acmeTrip.getId()));
        assertFalse(seeded.seesMoney(cfpwTrip.getId()), "the demo organization's trips are not Acme's");
        assertFalse(new PrivilegeCommands().check(BASE, null, holders.get(0)), "org-scoped only");
        final Organization acme = DAO.getInstance()
                .getOrganization(Organization.Id.from(FakeData.ACME_ORG_ID), Cached.NO).orElseThrow();
        assertTrue(acme.mayGrant(BASE), "Acme's seeded allow-list offers it");
        assertFalse(acme.isAdmin(holders.get(0)), "a plain member: an org admin would prove nothing");
    }
}
