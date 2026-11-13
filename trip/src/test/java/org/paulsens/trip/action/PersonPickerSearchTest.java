package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.model.Person;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * The shared people picker ({@code WEB-INF/selectPeople.xhtml}) is on pages a non-site-admin reaches -- a trip's
 * Finance Admin on Batch Transactions -- so its search must never answer with another organization's people.
 */
public class PersonPickerSearchTest {

    private static List<Person.Id> ids(final List<Person> people) {
        return people.stream().map(Person::getId).toList();
    }

    @Test
    public void thePickerSearchStaysInsideTheCallersOrganizations() {
        final Person financeAdmin = TenancyFixtures.savedPerson("Fin");
        final Person insider = TenancyFixtures.savedPerson("Inside");
        final Person outsider = TenancyFixtures.savedPerson("Outside");
        TenancyFixtures.org("PickA", financeAdmin, insider);
        TenancyFixtures.org("PickB", outsider);
        final PersonCommands people = new PersonCommands(() -> TenancyFixtures.orgsFor(financeAdmin));

        assertEquals(ids(people.searchCandidates(null, outsider.getEmail())), List.of(),
                "another tenant's member must not be found by their address");
        assertEquals(ids(people.searchCandidates(null, insider.getEmail())), List.of(insider.getId()));
        assertEquals(ids(people.searchCandidates(List.of(outsider.getId()), insider.getEmail())),
                List.of(outsider.getId(), insider.getId()),
                "what is already selected stays offered: the page's own rule put it there");

        final PersonCommands asSiteAdmin = new PersonCommands(TenancyFixtures::asSiteAdmin);
        assertTrue(ids(asSiteAdmin.searchCandidates(null, outsider.getEmail())).contains(outsider.getId()),
                "a site admin still searches everyone");
    }

    /** No query, no search: the page asks this way to list just the selection, and no reach is consulted. */
    @Test
    public void withoutAQueryOnlyTheSelectionIsOffered() {
        final Person picked = TenancyFixtures.savedPerson("Picked");
        final PersonCommands people = new PersonCommands(PersonPickerSearchTest::neverAsked);
        assertEquals(ids(people.searchCandidates(List.of(picked.getId()), null)), List.of(picked.getId()));
        assertEquals(ids(people.searchCandidates(null, "  ")), List.of());
    }

    private static OrgCommands neverAsked() {
        throw new AssertionError("no query means no search, so nobody's organizations are needed");
    }

    @Test
    public void sharingAnOrganizationIsAboutTheSignedInCaller() {
        final Person member = TenancyFixtures.savedPerson("Member");
        TenancyFixtures.org("PickD", member);
        final OrgCommands anonymous = new OrgCommands(() -> Caller.of((jakarta.servlet.http.HttpSession) null));
        org.testng.Assert.assertFalse(anonymous.sharesOrgWith(TenancyFixtures.reload(member)));
        org.testng.Assert.assertFalse(TenancyFixtures.orgsFor(member).sharesOrgWith(null));
        assertTrue(TenancyFixtures.orgsFor(member).sharesOrgWith(TenancyFixtures.reload(member)));
    }

    @Test
    public void someoneInNoOrganizationFindsNobody() {
        final Person loner = TenancyFixtures.savedPerson("Loner");
        final Person other = TenancyFixtures.savedPerson("Other");
        TenancyFixtures.org("PickC", other);
        final PersonCommands people = new PersonCommands(() -> TenancyFixtures.orgsFor(loner));
        assertEquals(ids(people.searchCandidates(null, other.getEmail())), List.of());
    }
}
