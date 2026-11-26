package org.paulsens.trip.action;

import static org.paulsens.trip.action.TenancyFixtures.addMember;
import static org.paulsens.trip.action.TenancyFixtures.callerFor;
import static org.paulsens.trip.action.TenancyFixtures.grantPeopleAdmin;
import static org.paulsens.trip.action.TenancyFixtures.orgWithAdmin;
import static org.paulsens.trip.action.TenancyFixtures.savedPerson;
import static org.paulsens.trip.action.TenancyFixtures.siteAdminCaller;
import static org.paulsens.trip.action.TenancyFixtures.unique;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The trip editor's staff pickers take People Admin for the trip's organization (owner, 2026-11-26): the holder
 * searches that org's members and nobody else, an org admin without the privilege gets nothing, and the add and
 * remove commands refuse whoever the search would refuse.
 */
public class TripStaffCommandsTest {

    private Person orgAdmin;
    private Person holder;
    private Person member;
    private Person outsider;
    private Person otherOrgHolder;
    private Organization org;
    private Trip trip;

    @BeforeClass
    public void anOrgWithAPeopleAdminWhoIsNotItsAdmin() throws IOException {
        orgAdmin = savedPerson("StaffAdmin");
        holder = savedPerson("StaffHolder");
        member = savedPerson("StaffMember");
        outsider = savedPerson("StaffOutsider");
        otherOrgHolder = savedPerson("StaffOtherHolder");
        org = orgWithAdmin(orgAdmin);
        addMember(org, orgAdmin);
        addMember(org, holder);
        addMember(org, member);
        grantPeopleAdmin(org, holder);
        final Organization otherOrg = orgWithAdmin(savedPerson("StaffOtherAdmin"));
        addMember(otherOrg, otherOrgHolder);
        grantPeopleAdmin(otherOrg, otherOrgHolder);
        trip = savedTrip(org.getId().getValue());
    }

    @Test
    public void aPeopleAdminSearchesTheTripsOrgMembersOnly() {
        final TripStaffCommands staff = onEditor(callerFor(holder), trip);
        assertTrue(staff.mayPick(trip));
        assertTrue(staff.mayPickOnEditor());
        final List<Person> everyone = staff.complete("");
        assertTrue(listed(everyone, member), "An empty query offers the org's members");
        assertTrue(listed(everyone, holder));
        assertFalse(listed(everyone, outsider), "People outside the org are NEVER offered");
        final List<Person> narrowed = staff.complete(member.getLast().toLowerCase(Locale.ROOT));
        assertTrue(listed(narrowed, member), "The query filters by name");
        assertFalse(listed(narrowed, holder));
    }

    @Test
    public void theOrgsAdminWithoutPeopleAdminGetsNothing() {
        final TripStaffCommands staff = onEditor(callerFor(orgAdmin), trip);
        assertFalse(staff.mayPick(trip), "Administering the org is not People Admin");
        assertFalse(staff.mayPickOnEditor());
        assertEquals(staff.complete("").size(), 0);
        assertEquals(onEditor(callerFor(member), trip).complete("").size(), 0, "A plain member gets nothing");
    }

    @Test
    public void aPeopleAdminOfAnotherOrgGetsNothing() {
        final TripStaffCommands staff = onEditor(callerFor(otherOrgHolder), trip);
        assertFalse(staff.mayPick(trip));
        assertEquals(staff.complete("").size(), 0);
    }

    @Test
    public void noTripNoPicking() {
        final TripStaffCommands staff = onEditor(callerFor(holder), null);
        assertFalse(staff.mayPick(null));
        assertFalse(staff.mayPickOnEditor());
        assertEquals(staff.complete("").size(), 0);
    }

    @Test
    public void onlyASiteAdminPicksForATripWithNoOrganization() throws IOException {
        final Trip orgless = savedTrip(null);
        final TripStaffCommands admin = onEditor(siteAdminCaller(), orgless);
        assertTrue(admin.mayPick(orgless));
        assertTrue(listed(admin.complete(outsider.getLast()), outsider),
                "A site admin on an org-less trip falls back to the global search");
        final Trip working = Trip.builder().title("Orgless working copy").build();
        assertTrue(admin.add(working, TripCommands.STAFF_DIRECTOR, outsider.getId()), "No org, no membership rule");
        assertTrue(working.getDirectorIds().contains(outsider.getId()));
        assertFalse(onEditor(callerFor(holder), orgless).mayPick(orgless), "No org, no org-scoped grant counts");
        assertTrue(onEditor(siteAdminCaller(), trip).mayPick(trip), "A site admin holds People Admin everywhere");
    }

    @Test
    public void aGrantForAnOrgThatDoesNotResolveSearchesAndAddsNobody() throws IOException {
        // A real org whose row fails to read looks the same here: findOrganization answers null.
        final String unresolvedOrg = UUID.randomUUID().toString();
        final Trip unresolved = savedTrip(unresolvedOrg);
        final Caller holderThere = TestCallers.person(TestCallers.grant(PrivilegeCommands.PEOPLE_ADMIN, unresolvedOrg));
        final TripStaffCommands staff = onEditor(holderThere, unresolved);
        assertTrue(staff.mayPick(unresolved), "The scoped grant alone passes the gate");
        assertEquals(staff.complete(outsider.getLast()).size(), 0, "Never the global search for a non-site-admin");
        final Trip working = Trip.builder().title("Unresolved working copy").build();
        assertFalse(staff.add(working, TripCommands.STAFF_DIRECTOR, outsider.getId()),
                "No resolved org, no membership rule: refused, not waved through");
        assertTrue(working.getDirectorIds() == null || !working.getDirectorIds().contains(outsider.getId()),
                "Nobody was added");

        final TripStaffCommands admin = onEditor(siteAdminCaller(), unresolved);
        assertTrue(listed(admin.complete(outsider.getLast()), outsider), "A site admin still searches everyone");
        assertTrue(admin.add(working, TripCommands.STAFF_DIRECTOR, outsider.getId()));
    }

    @Test
    public void aNewTripsOwnDraftAnswersBeforeItsFirstSave() {
        final Trip draft = Trip.builder().title("New Trip " + unique()).build();
        draft.setOrgId(org.getId().getValue());
        final TripEditDrafts drafts = new TripEditDrafts();
        final String token = drafts.start(draft, holder.getId());
        final TripStaffCommands creating = creating(callerFor(holder), token, drafts);
        assertTrue(creating.mayPickOnEditor());
        assertTrue(listed(creating.complete(member.getLast()), member),
                "The new trip's org members are offered before it is ever saved");
        assertTrue(creating.add(draft, TripCommands.STAFF_COORDINATOR, member.getId()));
        assertTrue(draft.getFacilitatorIds().contains(member.getId()));
        assertFalse(creating(callerFor(otherOrgHolder), token, drafts).mayPickOnEditor(),
                "Another user's draft token answers nothing");
    }

    @Test
    public void addAndRemoveRefuseWhoeverTheSearchRefuses() {
        final Trip working = Trip.builder().title("Working copy").build();
        final TripStaffCommands staff = onEditor(callerFor(holder), trip);
        assertTrue(staff.add(working, TripCommands.STAFF_DIRECTOR, member.getId()));
        assertTrue(working.getDirectorIds().contains(member.getId()));
        assertFalse(staff.add(working, TripCommands.STAFF_DIRECTOR, outsider.getId()),
                "Only the org's members, whatever id the converter was sent");
        assertFalse(working.getDirectorIds().contains(outsider.getId()));
        assertFalse(staff.add(null, TripCommands.STAFF_DIRECTOR, member.getId()));
        assertFalse(staff.add(working, TripCommands.STAFF_DIRECTOR, null));

        final TripStaffCommands notAllowed = onEditor(callerFor(orgAdmin), trip);
        assertFalse(notAllowed.add(working, TripCommands.STAFF_DIRECTOR, holder.getId()));
        assertFalse(working.getDirectorIds().contains(holder.getId()));
        assertFalse(notAllowed.remove(working, TripCommands.STAFF_DIRECTOR, member.getId()));
        assertTrue(working.getDirectorIds().contains(member.getId()), "A refused remove leaves the staff as it was");
        assertFalse(staff.remove(null, TripCommands.STAFF_DIRECTOR, member.getId()));

        assertTrue(staff.remove(working, TripCommands.STAFF_DIRECTOR, member.getId()));
        assertFalse(working.getDirectorIds().contains(member.getId()));
    }

    @Test
    public void theBeanConstructorWiresWithoutAContainer() {
        // CDI's constructor; its suppliers are only read on use, which needs the container.
        assertFalse(new TripStaffCommands().mayPick(null));
    }

    private static Trip savedTrip(final String orgId) throws IOException {
        final Trip saved = Trip.builder().title("Staff Trip " + unique()).build();
        saved.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(saved));
        return saved;
    }

    /** The bean for {@code caller} on the editor of {@code onPage} (null: off the editor). */
    private static TripStaffCommands onEditor(final Caller caller, final Trip onPage) {
        return new TripStaffCommands(() -> caller, () -> new OrgCommands(() -> caller),
                () -> new TripCommands(() -> new OrgCommands(() -> caller), () -> caller)) {
            @Override
            protected Trip tripFromView() {
                return onPage;
            }
        };
    }

    /** The bean on the editor in CREATE mode: no pinned id, only the draft token, as the real view resolves it. */
    private static TripStaffCommands creating(final Caller caller, final String token, final TripEditDrafts drafts) {
        return new TripStaffCommands(() -> caller, () -> new OrgCommands(() -> caller),
                () -> new TripCommands(() -> new OrgCommands(() -> caller), () -> caller)) {
            @Override
            protected Trip tripFromView() {
                return drafts.editorTrip(null, token, caller.personId());
            }
        };
    }

    private static boolean listed(final List<Person> people, final Person who) {
        return people.stream().anyMatch(person -> person.getId().equals(who.getId()));
    }
}
