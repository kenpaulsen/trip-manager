package org.paulsens.trip.action;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * The two org-side halves of the people pages: Org People's member search ({@link OrgCommands#searchMembers}) and
 * the admin People page's read-only Privileges column ({@link OrgCommands#privilegeSummaries}), whose tenancy rule
 * is the point -- an org people admin must never see another tenant's name or grants through it.
 */
public class PeopleSearchOrgTest {
    private final DAO dao = DAO.getInstance();

    @Test
    public void memberSearchListsTheOrgInNameOrderAndNarrowsByContains() throws IOException {
        final Person orgAdmin = savedPerson("Olga", "Zadmin");
        final Organization acme = orgWithAdmin(orgAdmin);
        final String orgId = acme.getId().getValue();
        final Person bo = savedPerson("Bo", "Membrook");
        final Person al = savedPerson("Al", "Membrook");
        assertTrue(admin().addMember(orgId, bo.getId()));
        assertTrue(admin().addMember(orgId, al.getId()));

        final PeopleResult all = commandsFor(orgAdmin).searchMembers(orgId, null);
        assertEquals(ids(all.rows()), List.of(al.getId(), bo.getId(), orgAdmin.getId()), "last, first order");
        assertFalse(all.needsQuery());

        assertEquals(ids(commandsFor(orgAdmin).searchMembers(orgId, "brook").rows()),
                List.of(al.getId(), bo.getId()), "contains, inside the last name");
        assertEquals(ids(commandsFor(orgAdmin).searchMembers(orgId, "brook bo").rows()), List.of(bo.getId()));
    }

    @Test
    public void memberSearchIsEmptyForAnyoneWhoMayNotViewTheOrgsPeople() throws IOException {
        final Organization acme = orgWithAdmin(savedPerson("Ann", "Admin"));
        final String orgId = acme.getId().getValue();
        assertSame(commandsFor(savedPerson("Out", "Sider")).searchMembers(orgId, null), PeopleResult.NONE);
        assertSame(admin().searchMembers(null, null), PeopleResult.NONE);
    }

    @Test
    public void aSiteAdminSeesSiteGrantsAndEveryOrg() throws IOException {
        final Person member = savedPerson("Gina", "Granted");
        final Organization acme = orgWithAdmin(member);
        final String acmeId = acme.getId().getValue();
        final Organization beta = orgWithAdmin(savedPerson("Bea", "Beta"));
        final String betaId = beta.getId().getValue();
        assertTrue(admin().addMember(betaId, member.getId()));
        assertTrue(admin().grantOrgPrivilege(betaId, member.getId(), PrivilegeCommands.EMAIL_ADMIN));
        grantGlobal(PrivilegeCommands.CONFIG_ADMIN, member.getId());

        final PrivilegeSummaries.PrivSummary summary = summaryFor(admin(), member);
        assertTrue(summary.site().contains("Settings Admin"), summary.toString());
        final PrivilegeSummaries.OrgPrivs atAcme = orgNamed(summary, acme.getName());
        assertTrue(atAcme.orgAdmin());
        final PrivilegeSummaries.OrgPrivs atBeta = orgNamed(summary, beta.getName());
        assertFalse(atBeta.orgAdmin());
        assertEquals(atBeta.privs(), List.of("Email Admin"));
        assertFalse(summary.isEmpty());
        assertNotNull(acmeId);
    }

    @Test
    public void anOrgPeopleAdminSeesOnlyTheirOwnOrgAndNoSiteGrants() throws IOException {
        final Person member = savedPerson("Max", "Multi");
        final Organization acme = orgWithAdmin(savedPerson("Ace", "Acme"));
        final String acmeId = acme.getId().getValue();
        final Organization beta = orgWithAdmin(savedPerson("Bix", "Beta"));
        final String betaId = beta.getId().getValue();
        assertTrue(admin().addMember(acmeId, member.getId()));
        assertTrue(admin().addMember(betaId, member.getId()));
        assertTrue(admin().grantOrgPrivilege(betaId, member.getId(), PrivilegeCommands.PAYMENTS_ADMIN));
        grantGlobal(PrivilegeCommands.AUDIT_ADMIN, member.getId());

        final Person acmePeopleAdmin = savedPerson("Pia", "Peopleadmin");
        assertTrue(admin().addMember(acmeId, acmePeopleAdmin.getId()));
        assertTrue(admin().grantOrgPrivilege(acmeId, acmePeopleAdmin.getId(), PrivilegeCommands.PEOPLE_ADMIN));

        final PrivilegeSummaries.PrivSummary seen = summaryFor(realPrivs(acmePeopleAdmin), member);
        assertTrue(seen.site().isEmpty(), "site grants are for site admins' eyes");
        assertEquals(seen.orgs().stream().map(PrivilegeSummaries.OrgPrivs::orgName).toList(),
                List.of(acme.getName()), "never the other tenant's name or grants");
        assertTrue(seen.isEmpty(), "a plain member of Acme: nothing to show");
    }

    /** The People page's Trips column used to list a person's trips in EVERY org to an org people admin. */
    @Test
    public void anOrgPeopleAdminSeesOnlyTheirOwnOrgsTripsOfAPerson() throws IOException {
        final Person traveler = savedPerson("Tia", "Traveler");
        final Organization acme = orgWithAdmin(savedPerson("Ama", "Acme"));
        final Organization beta = orgWithAdmin(savedPerson("Ben", "Beta"));
        final String acmeTrip = savedTrip(acme, traveler);
        final String betaTrip = savedTrip(beta, traveler);
        final String noOrgTrip = savedTrip(null, traveler);
        final Person acmePeopleAdmin = savedPerson("Pam", "Peopleadmin");
        assertTrue(admin().addMember(acme.getId().getValue(), acmePeopleAdmin.getId()));
        assertTrue(admin().grantOrgPrivilege(acme.getId().getValue(), acmePeopleAdmin.getId(),
                PrivilegeCommands.PEOPLE_ADMIN));

        final List<String> seen = realPrivs(acmePeopleAdmin).visibleTripsOf(traveler).stream()
                .map(Trip::getId).toList();
        assertEquals(seen, List.of(acmeTrip), "never Beta's trip, nor a trip with no org");
        assertTrue(admin().visibleTripsOf(traveler).stream().map(Trip::getId).toList()
                .containsAll(List.of(acmeTrip, betaTrip, noOrgTrip)), "a site admin sees them all");
        assertTrue(admin().visibleTripsOf(null).isEmpty());
    }

    private static String savedTrip(final Organization org, final Person traveler) throws IOException {
        final Trip trip = Trip.builder()
                .id(java.util.UUID.randomUUID().toString())
                .title("Pso trip " + RandomData.genAlpha(6))
                .openToPublic(false)
                .startDate(java.time.LocalDateTime.now())
                .endDate(java.time.LocalDateTime.now().plusDays(3))
                .people(new java.util.ArrayList<>(List.of(traveler.getId())))
                .build();
        if (org != null) {
            trip.setOrgId(org.getId().getValue());
        }
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip.getId();
    }

    /**
     * The admin People search for an org people admin scans their own orgs' members, so a site full of other
     * tenants' matches cannot crowd them out (it read a site-wide index window and filtered it until 2026-11-12).
     */
    @Test
    public void anOrgPeopleAdminFindsEveryOwnMatchHoweverManyOthersMatch() throws IOException {
        final String fragment = "Zqcrowd" + RandomData.genAlpha(5);
        final Organization acme = orgWithAdmin(savedPerson("Ada", "Acme"));
        final String acmeId = acme.getId().getValue();
        for (int i = 0; i < 12; i++) {
            savedPerson("Aaa" + i, fragment + "a");
        }
        final Person zed = savedPerson("Zed", fragment + "z");
        final Person yan = savedPerson("Yan", fragment + "y");
        assertTrue(admin().addMember(acmeId, zed.getId()));
        assertTrue(admin().addMember(acmeId, yan.getId()));
        final Person pam = savedPerson("Pam", "Peopleadmin");
        assertTrue(admin().addMember(acmeId, pam.getId()));
        assertTrue(admin().grantOrgPrivilege(acmeId, pam.getId(), PrivilegeCommands.PEOPLE_ADMIN));

        final OrgCommands asPam = realPrivs(pam);
        final PersonCommands people = new PersonCommands(() -> asPam);
        final PeopleResult found = people.adminSearch(fragment.toLowerCase(), 2);
        assertEquals(ids(found.rows()), List.of(yan.getId(), zed.getId()), "both members, nobody else");
        assertFalse(found.truncated());
        assertEquals(ids(people.adminSearchPeople(fragment, 1)), List.of(yan.getId()));
        assertTrue(people.adminSearch(fragment, 1).truncated(), "a third member would not fit");
        assertTrue(asPam.peopleAdminScope().stream().anyMatch(member -> member.getId().equals(zed.getId())));
        assertTrue(commandsFor(pam).peopleAdminScope().isEmpty(), "no privilege, no scope");
    }

    /** A site admin searches the index; when its window fills, the page is told the list may be partial. */
    @Test
    public void aSiteAdminSearchSaysWhenItWasCut() throws IOException {
        final String fragment = "Zqcut" + RandomData.genAlpha(5);
        for (int i = 0; i < 12; i++) {
            savedPerson("Cut" + i, fragment);
        }
        final PersonCommands people = new PersonCommands(this::admin);
        final PeopleResult few = people.adminSearch(fragment, 3);
        assertEquals(few.rows().size(), 3);
        assertTrue(few.truncated());
        assertFalse(people.adminSearch(fragment, 20).truncated(), "all twelve fit");
        assertTrue(admin().isSiteAdmin());
    }

    @Test
    public void noRowsNoSummaries() {
        assertTrue(admin().privilegeSummaries(null).isEmpty());
        assertTrue(admin().privilegeSummaries(PeopleResult.NONE).isEmpty());
    }

    private PrivilegeSummaries.PrivSummary summaryFor(final OrgCommands viewer, final Person subject) {
        final Person stored = dao.getPerson(subject.getId(), Cached.NO).orElseThrow();
        final Map<String, PrivilegeSummaries.PrivSummary> byId =
                viewer.privilegeSummaries(new PeopleResult(List.of(stored), false, false));
        final PrivilegeSummaries.PrivSummary summary = byId.get(subject.getId().getValue());
        assertNotNull(summary);
        return summary;
    }

    private static PrivilegeSummaries.OrgPrivs orgNamed(final PrivilegeSummaries.PrivSummary summary,
            final String name) {
        final PrivilegeSummaries.OrgPrivs found = summary.orgs().stream()
                .filter(org -> org.orgName().equals(name)).findFirst().orElse(null);
        assertNotNull(found, name + " in " + summary);
        return found;
    }

    private static void grantGlobal(final String base, final Person.Id who) {
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(base, null, base).withNewPerson(who)));
    }

    private static List<Person.Id> ids(final List<Person> people) {
        return people.stream().map(Person::getId).toList();
    }

    private Person savedPerson(final String first, final String last) throws IOException {
        final Person person = Person.builder().first(first).last(last)
                .email("pso." + RandomData.genAlpha(10) + "@example.com").build();
        assertTrue(dao.savePerson(person));
        return person;
    }

    private Organization orgWithAdmin(final Person orgAdmin) {
        final Organization org = admin().createOrganization("Pso " + RandomData.genAlpha(10), null, null);
        assertNotNull(org);
        assertTrue(admin().setOrgAdmin(org.getId().getValue(), orgAdmin.getId(), true));
        return dao.getOrganization(org.getId(), Cached.NO).orElseThrow();
    }

    private OrgCommands admin() {
        return new OrgCommands(() -> new Caller(Person.Id.from("admin-" + RandomData.genAlpha(8)), true,
                new AuditActor("admin@test", "admin"), grantsNothing()));
    }

    private OrgCommands commandsFor(final Person person) {
        return new OrgCommands(() -> new Caller(person.getId(), false,
                new AuditActor(person.getEmail(), person.getId().getValue()), grantsNothing()));
    }

    private OrgCommands realPrivs(final Person person) {
        return new OrgCommands(() -> new Caller(person.getId(), false,
                new AuditActor(person.getEmail(), person.getId().getValue()), new PrivilegeCommands()));
    }

    private static PrivilegeCommands grantsNothing() {
        final PrivilegeCommands none = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(none.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        return none;
    }

    @Test
    public void anUnknownOrgIdIsSkipped() throws IOException {
        final Person stray = savedPerson("Sy", "Stray");
        stray.setOrgIds(List.of(Organization.Id.from(java.util.UUID.randomUUID().toString())));
        final Map<String, PrivilegeSummaries.PrivSummary> byId =
                admin().privilegeSummaries(new PeopleResult(List.of(stray), false, false));
        assertTrue(byId.get(stray.getId().getValue()).orgs().isEmpty());
        assertNull(byId.get("nobody"));
    }
}
