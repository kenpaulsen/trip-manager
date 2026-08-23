package org.paulsens.trip.action;

import java.io.IOException;
import java.util.List;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.assertTrue;

/**
 * The tenancy contract of {@link PersonCommands#adminSearchPeople}: the org filter runs in the data source,
 * so a page using it cannot leak another tenant's people no matter what it renders.
 */
public class PersonAdminSearchTest {
    private final DAO dao = DAO.getInstance();

    @Test
    public void adminSearchIsBoundedByThePeopleAdminOrgs() throws IOException {
        final String marker = "Zzq" + RandomData.genAlpha(6);
        final Person deputy = savedPerson(marker);
        final Person inOrg = savedPerson(marker);
        final Person outside = savedPerson(marker);
        final OrgCommands siteAdmin = new OrgCommands(() -> new Caller(Person.Id.from("admin"), true,
                new AuditActor("admin@test", "admin"), new PrivilegeCommands()));
        final Organization acme = siteAdmin.createOrganization("Search " + RandomData.genAlpha(8), null, null);
        final Organization other = siteAdmin.createOrganization("Other " + RandomData.genAlpha(8), null, null);
        assertTrue(siteAdmin.setOrgAdmin(acme.getId().getValue(), deputy.getId(), true));
        assertTrue(siteAdmin.addMember(acme.getId().getValue(), inOrg.getId()));
        assertTrue(siteAdmin.addMember(other.getId().getValue(), outside.getId()));
        assertTrue(new OrgCommands(() -> callerFor(deputy))
                .grantOrgPrivilege(acme.getId().getValue(), deputy.getId(), PrivilegeCommands.PEOPLE_ADMIN));

        final PersonCommands people = new PersonCommands(() -> new OrgCommands(() -> callerFor(deputy)));
        // Membership writes changed orgIds since the local objects were built, so compare by id, not equals.
        final List<Person.Id> hits = people.adminSearchPeople(marker, 25).stream().map(Person::getId).toList();
        assertTrue(hits.contains(inOrg.getId()), "A shared-org person is searchable");
        assertTrue(hits.contains(deputy.getId()), "Self is in the org too");
        assertTrue(hits.stream().noneMatch(id -> id.equals(outside.getId())),
                "A disjoint-org person never surfaces");

        final PersonCommands asSiteAdmin = new PersonCommands(() -> siteAdmin);
        assertTrue(asSiteAdmin.adminSearchPeople(marker, 25).stream().map(Person::getId).toList()
                .contains(outside.getId()), "Site admins search everyone");
    }

    private static Caller callerFor(final Person person) {
        return new Caller(person.getId(), false,
                new AuditActor(person.getEmail(), person.getId().getValue()), new PrivilegeCommands());
    }

    private Person savedPerson(final String lastName) throws IOException {
        final Person person = Person.builder()
                .first(RandomData.genAlpha(6)).last(lastName)
                .email("search." + RandomData.genAlpha(10) + "@example.com")
                .build();
        assertTrue(dao.savePerson(person));
        return person;
    }
}
