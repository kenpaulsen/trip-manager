package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
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
        assertEquals(ids(people.searchCandidates(null, outsider.getLast())), List.of(), "nor by their name");
        assertEquals(ids(people.searchCandidates(null, insider.getLast())), List.of(insider.getId()));
        assertEquals(ids(people.searchCandidates(List.of(outsider.getId()), insider.getLast())),
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

    /**
     * The index the site-wide search reads is one window for every tenant, in token order. However many other
     * organizations' people match the same word, the caller's own colleague is still found: their members are
     * searched directly, not filtered out of that window.
     */
    @Test
    public void aColleagueIsFoundHoweverManyOtherTenantsMatchTheSameWord() {
        final String word = "Zyx" + TenancyFixtures.unique().replaceAll("[^A-Za-z0-9]", "");
        final Person financeAdmin = TenancyFixtures.savedPerson("Fin");
        final Person colleague = named(word + "zz", "Colleague");
        TenancyFixtures.org("PickCrowdA", financeAdmin, colleague);
        final Person[] crowd = new Person[130];
        for (int i = 0; i < crowd.length; i++) {
            crowd[i] = named(word + "aa" + i, "Elsewhere");
        }
        TenancyFixtures.org("PickCrowdB", crowd);
        final PersonCommands people = new PersonCommands(() -> TenancyFixtures.orgsFor(financeAdmin));

        assertEquals(ids(people.searchCandidates(null, word)), List.of(colleague.getId()));
    }

    private static Person named(final String first, final String last) {
        final Person person = TenancyFixtures.savedPerson(last);
        person.setFirst(first);
        return TenancyFixtures.saved(person);
    }

    /**
     * On a page that belongs to one trip, a caller with a role on it searches that trip's organization: a Finance
     * Admin of an org A trip who also belongs to org B is not offered B's people there.
     */
    @Test
    public void onATripsPageThePickerSearchesTheTripsOrganization() {
        final Person financeAdmin = TenancyFixtures.savedPerson("Fin");
        final Person inA = TenancyFixtures.savedPerson("InA");
        final Person inB = TenancyFixtures.savedPerson("InB");
        final Organization orgA = TenancyFixtures.org("PickTripA", inA);
        TenancyFixtures.org("PickTripB", financeAdmin, inB);
        final Trip trip = TenancyFixtures.tripOwnedBy(orgA);
        final PrivilegeCommands privileges = new PrivilegeCommands();
        assertTrue(privileges.savePrivilege(privileges.getOrCreate(PrivilegeCommands.TRIP_FIN_ADMIN, trip.getId(),
                "Finance Admin").withNewPerson(financeAdmin.getId())));
        final PersonCommands people = new PersonCommands(() -> TenancyFixtures.orgsFor(financeAdmin));

        assertEquals(ids(people.searchCandidatesOnTrip(null, inA.getLast(), trip.getId())), List.of(inA.getId()));
        assertEquals(ids(people.searchCandidatesOnTrip(null, inB.getLast(), trip.getId())), List.of(),
                "their own other organization is not this trip's");
        assertEquals(ids(people.searchCandidatesOnTrip(null, inB.getLast(), null)), List.of(inB.getId()),
                "off a trip page it is the caller's own organizations");

        // A trip id alone is not a key to its organization's people: no role there, no wider scope.
        final Person bystander = TenancyFixtures.savedPerson("Bystander");
        TenancyFixtures.org("PickTripC", bystander);
        final PersonCommands asBystander = new PersonCommands(() -> TenancyFixtures.orgsFor(bystander));
        assertEquals(ids(asBystander.searchCandidatesOnTrip(null, inA.getLast(), trip.getId())), List.of());
    }

    @Test
    public void thePickerScopeIsAboutTheSignedInCaller() {
        final Person member = TenancyFixtures.savedPerson("Member");
        TenancyFixtures.org("PickD", member);
        final OrgCommands anonymous = new OrgCommands(() -> Caller.of((jakarta.servlet.http.HttpSession) null));
        assertTrue(anonymous.pickerScope(null).isEmpty());
        assertTrue(OrgCommands.fellowMembersOf(org.paulsens.trip.dynamo.DAO.getInstance(), null).isEmpty());
        assertEquals(ids(TenancyFixtures.orgsFor(member).pickerScope(null)), List.of(member.getId()));
    }
}
