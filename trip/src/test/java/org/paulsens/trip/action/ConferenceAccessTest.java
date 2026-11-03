package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripKind;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Who may create a conference (docs/conference.md, "Access"): a separately sold feature. The org's allow-list
 * offers {@code addConference}, a member holds it; org admins are NOT implied; withdrawing the feature from the
 * allow-list takes effect at once; and a trip editor can neither make a trip a conference nor unmake one.
 */
public class ConferenceAccessTest {
    private Organization org;
    private Person.Id admin;
    private Person.Id holder;

    @BeforeMethod
    public void seed() throws IOException {
        admin = Person.Id.newInstance();
        holder = Person.Id.newInstance();
        org = new Organization(null, "Conference Access " + admin.getValue(), null, null, List.of(admin), null,
                null, 0L);
        assertTrue(DAO.getInstance().saveOrganization(org));
        final PrivilegeCommands privileges = new PrivilegeCommands();
        assertTrue(privileges.savePrivilege(privileges.getOrCreate(PrivilegeCommands.ADD_CONFERENCE, orgId(),
                "test").withNewPerson(holder), AuditActor.system()));
    }

    @Test
    public void aMemberHoldingTheGrantMayAndAnOrgAdminMayNot() {
        assertTrue(orgs(holder).canCreateConferenceFor(orgId()));
        assertFalse(orgs(admin).canCreateConferenceFor(orgId()), "org admins are not implied");
        assertTrue(orgs(admin).canCreateTripFor(orgId()), "while trips are theirs as before");
        assertFalse(orgs(holder).canCreateTripFor(orgId()), "and the conference grant is not a trip grant");
        assertTrue(orgs(holder).canViewOrgTrips(orgId()), "the New Conference button lives on that page");
        assertTrue(siteAdmin().canCreateConferenceFor(orgId()));
        assertFalse(orgs(holder).canCreateConferenceFor(null));
        assertFalse(orgs(holder).canCreateConferenceFor(Person.Id.newInstance().getValue()), "an unknown org");
        assertTrue(PrivilegeCommands.ORG_SCOPED_BASES.contains(PrivilegeCommands.ADD_CONFERENCE));
    }

    @Test
    public void withdrawingTheFeatureFromTheAllowListTakesEffectAtOnce() throws IOException {
        org.setGrantablePrivileges(List.of(PrivilegeCommands.ADD_TRIP));
        assertTrue(DAO.getInstance().saveOrganization(org));
        assertFalse(orgs(holder).canCreateConferenceFor(orgId()), "the grant stands, the offer does not");
        org.setGrantablePrivileges(List.of(PrivilegeCommands.ADD_TRIP, PrivilegeCommands.ADD_CONFERENCE));
        assertTrue(DAO.getInstance().saveOrganization(org));
        assertTrue(orgs(holder).canCreateConferenceFor(orgId()));
    }

    @Test
    public void aNewTripOfEachKindHasItsOwnGate() {
        final Trip conference = trips(holder).createTripFor(orgId(), "conference");
        assertNotNull(conference);
        assertEquals(conference.getKind(), TripKind.CONFERENCE);
        assertNull(trips(holder).createTripFor(orgId(), null), "no trip grant");
        assertNull(trips(admin).createTripFor(orgId(), "CONFERENCE"), "no conference grant");
        assertEquals(trips(admin).createTripFor(orgId()).getKind(), TripKind.PILGRIMAGE);
        assertTrue(trips(holder).canCreate(orgId(), "CONFERENCE"));
        assertFalse(trips(holder).canCreate(orgId(), "PILGRIMAGE"));
    }

    @Test
    public void onlyTheGrantMakesOrUnmakesAConference() throws IOException {
        final Trip trip = Trip.builder().title("Access " + orgId()).orgId(orgId())
                .startDate(LocalDateTime.now().plusDays(90)).endDate(LocalDateTime.now().plusDays(92)).build();
        assertTrue(DAO.getInstance().saveTrip(trip));
        assertEquals(trips(admin).tripKindsFor(trip), List.of(TripKind.PILGRIMAGE), "the editor sees its kind only");
        assertEquals(trips(holder).tripKindsFor(trip), List.of(TripKind.values()));
        assertFalse(trips(admin).canChooseKind(trip), "no grant: the editor leaves the Kind row out");
        assertTrue(trips(holder).canChooseKind(trip));
        assertFalse(trips(holder).canChooseKind(null));

        trip.setKind(TripKind.CONFERENCE);
        assertFalse(trips(admin).saveTrip(trip, orgs(admin)), "an org admin may not make one");
        assertEquals(stored(trip).getKind(), TripKind.PILGRIMAGE, "nothing was written");
        assertTrue(trips(holder).saveTrip(trip, orgs(holder)));
        assertEquals(stored(trip).getKind(), TripKind.CONFERENCE);

        trip.setTitle("Renamed by its editor");
        assertTrue(trips(admin).saveTrip(trip, orgs(admin)), "editing a conference needs no grant");
        trip.setKind(TripKind.PILGRIMAGE);
        assertFalse(trips(admin).saveTrip(trip, orgs(admin)), "nor may an editor unmake one");
        assertTrue(trips(admin).saveTrip(trip, siteAdmin()), "a site admin may");
    }

    private String orgId() {
        return org.getId().getValue();
    }

    private static Trip stored(final Trip trip) {
        return DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow();
    }

    private static OrgCommands orgs(final Person.Id who) {
        return new OrgCommands(() -> new Caller(who, false, AuditActor.system(), new PrivilegeCommands()));
    }

    private static OrgCommands siteAdmin() {
        return new OrgCommands(() -> new Caller(Person.Id.newInstance(), true, AuditActor.system(),
                new PrivilegeCommands()));
    }

    private static TripCommands trips(final Person.Id who) {
        return new TripCommands(() -> orgs(who));
    }
}
