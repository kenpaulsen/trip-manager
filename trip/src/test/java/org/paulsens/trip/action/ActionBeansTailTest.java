package org.paulsens.trip.action;

import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.Registration;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The action beans' remaining branches, against the fake store: registration reads that invent a transient
 * NOT_REGISTERED record, the room save going through ONE object (the two-lookup bug that reverted rooms on
 * reload), person search plumbing, and the JSON helper.
 */
public class ActionBeansTailTest {

    private final RegistrationCommands registrations = new RegistrationCommands();
    private final PersonCommands people = new PersonCommands();

    private Person savedPerson(final String first) {
        final Person person = people.createPerson();
        person.setFirst(first);
        person.setLast("Tailer");
        person.setEmail(first.toLowerCase() + "@tail.example");
        Assert.assertTrue(people.savePerson(person));
        return person;
    }

    // --- PersonCommands.pickerLabel ---

    /**
     * The null case is the one that matters: an autocomplete renders itemLabel for its CURRENT value, so a
     * label that is not empty when nothing is picked pre-fills the search box with junk.
     */
    @Test
    public void pickerLabelIsEmptyWhenNothingIsPicked() {
        Assert.assertEquals(people.pickerLabel(null), "");
    }

    @Test
    public void pickerLabelCarriesTheAddressOnlyWhenItIsMailable() {
        final Person mailable = savedPerson("Pickable");
        Assert.assertEquals(people.pickerLabel(mailable), "Pickable Tailer (pickable@tail.example)");

        mailable.setEmail("u1");
        Assert.assertEquals(people.pickerLabel(mailable), "Pickable Tailer",
                "a bare persona is not an address, so it should not be shown as one");
    }

    // --- RegistrationCommands ---

    @Test
    public void aMissingRegistrationIsATransientNotRegisteredRecordNeverNull() {
        final Person who = savedPerson("Reggie");

        final Registration reg = registrations.getRegistration("no-such-trip", who.getId());

        Assert.assertNotNull(reg);
        Assert.assertEquals(reg.getStatus(), Registration.Status.NOT_REGISTERED,
                "A client rendering a Register button needs the state, not a null");
    }

    @Test
    public void registrationReadsGuardNullInputs() {
        Assert.assertNull(registrations.getRegistration(null, Person.Id.from("x")));
        Assert.assertNull(registrations.getRegistration("t", null));
        Assert.assertNull(registrations.getRoomPDV(null, Person.Id.from("x")));
        Assert.assertNull(registrations.getRoomPDV("t", null));
        Assert.assertFalse(registrations.saveRoom(null, Person.Id.from("x"), "101"));
        Assert.assertFalse(registrations.saveRoom("t", null, "101"));
    }

    @Test
    public void aRegistrationRoundTripsAndCountsAsPending() {
        final Person who = savedPerson("Penny");
        final Registration reg = registrations.createRegistration("tail-trip-1", who.getId())
                .withStatus(Registration.Status.PENDING);

        Assert.assertTrue(registrations.saveRegistration(reg));

        Assert.assertTrue(registrations.getNumPending("tail-trip-1") >= 1);
        Assert.assertEquals(registrations.getRegistration("tail-trip-1", who.getId()).getStatus(),
                Registration.Status.PENDING);
    }

    /**
     * The room save writes through ONE object. The old page bound an input to one lookup's content and saved a
     * SECOND lookup, which -- since reads deserialize fresh copies -- stored an object nobody had typed into.
     */
    @Test
    public void theRoomSaveSurvivesAReload() {
        final Person who = savedPerson("Roomer");

        Assert.assertTrue(registrations.saveRoom("tail-trip-2", who.getId(), "Room 12"));

        final PersonDataValue reread = registrations.getRoomPDV("tail-trip-2", who.getId());
        Assert.assertEquals(reread.getContent(), "Room 12");

        // A null room stores an empty string rather than nulling the row.
        Assert.assertTrue(registrations.saveRoom("tail-trip-2", who.getId(), null));
        Assert.assertEquals(registrations.getRoomPDV("tail-trip-2", who.getId()).getContent(), "");
    }

    @Test
    public void aFirstRoomReadCreatesTheValueLazily() {
        final Person who = savedPerson("Firstie");

        final PersonDataValue pdv = registrations.getRoomPDV("tail-trip-3", who.getId());

        Assert.assertNotNull(pdv, "The first read creates and stores an empty room");
        Assert.assertEquals(pdv.getContent(), "");
    }

    // --- PersonDataValueCommands ---

    @Test
    public void personDataValuesRoundTripAndListByPerson() {
        final Person who = savedPerson("Data");
        final PersonDataValue pdv = PersonDataValueCommands.createPersonDataValue(
                who.getId(), DataId.from("tail-pdv-1"), "text");
        pdv.setContent("hello");

        Assert.assertTrue(PersonDataValueCommands.savePersonDataValue(pdv));

        final Map<DataId, PersonDataValue> all = PersonDataValueCommands.getPersonDataValues(who.getId());
        Assert.assertEquals(all.get(DataId.from("tail-pdv-1")).getContent(), "hello");
    }

    @Test
    public void personDataValueReadsGuardNulls() {
        Assert.assertNull(PersonDataValueCommands.getPersonDataValue(null, DataId.from("d")));
        Assert.assertNull(PersonDataValueCommands.getPersonDataValue(Person.Id.from("p"), null));
        Assert.assertNull(PersonDataValueCommands.getPersonDataValue(Person.Id.from("no-such"), DataId.from("d")));
    }

    // --- PersonCommands ---

    @Test
    public void searchFindsByPrefixAndCapsResults() {
        savedPerson("Searchable");

        final List<Person> found = people.searchPeople("searchable");

        Assert.assertTrue(found.stream().anyMatch(p -> "Searchable".equals(p.getFirst())));
        Assert.assertEquals(people.searchPeople("x", 0).size(), 0);
    }

    @Test
    public void searchCandidatesPutSelectionsFirstWithoutDuplicates() {
        final Person selected = savedPerson("Selected");
        final Person other = savedPerson("Otherone");

        // Selections may arrive as a List of ids, an array of ids, or their string values.
        final List<Person> fromList = people.searchCandidates(List.of(selected.getId()), "otherone");
        Assert.assertEquals(fromList.get(0).getId(), selected.getId(), "Selections come first");
        Assert.assertTrue(fromList.stream().anyMatch(p -> p.getId().equals(other.getId())));

        final List<Person> fromArray = people.searchCandidates(
                new Object[] {selected.getId().getValue()}, null);
        Assert.assertEquals(fromArray.get(0).getId(), selected.getId());

        final List<Person> fromNothing = people.searchCandidates(null, "otherone");
        Assert.assertTrue(fromNothing.stream().anyMatch(p -> p.getId().equals(other.getId())));
    }

    @Test
    public void getPeopleByIdsResolvesEachId() {
        final Person a = savedPerson("Ida");
        final Person b = savedPerson("Idb");

        final List<Person> found = people.getPeopleByIds(List.of(a.getId(), b.getId()));

        Assert.assertEquals(found.size(), 2);
        Assert.assertEquals(found.get(0).getId(), a.getId());
    }

    @Test
    public void getPersonByEmailFindsThePersonOrNull() {
        final Person who = savedPerson("Mailfind");

        Assert.assertEquals(people.getPersonByEmail("mailfind@tail.example").getId(), who.getId());
        Assert.assertNull(people.getPersonByEmail("nobody@tail.example"));
    }

    @Test
    public void canAccessCoversSelfManagedAndStrangers() {
        final Person manager = savedPerson("Manager");
        final Person ward = savedPerson("Ward");
        manager.setManagedUsers(List.of(ward.getId()));

        Assert.assertTrue(people.canAccessUserId(manager, manager.getId()));
        Assert.assertTrue(people.canAccessUserId(manager, ward.getId()));
        Assert.assertFalse(people.canAccessUserId(ward, manager.getId()));
        Assert.assertFalse(people.canAccessUserId(null, manager.getId()));
        Assert.assertFalse(people.canAccessUserId(manager, null));
    }

    @Test
    public void theInstanceRoleCheckFailsClosedOffAFacesThread() {
        Assert.assertFalse(people.hasRole("admin"), "No FacesContext: must fail closed, not blow up");
        Assert.assertFalse(people.hasRole("  "));
        Assert.assertFalse(people.hasRole(null));
    }

    @Test
    public void theFactoryAnswersAFreshBeanOffAFacesThread() {
        Assert.assertNotNull(PersonCommands.getPersonCommands());
    }

    @Test
    public void idWrapsTheString() {
        Assert.assertEquals(people.id("p-9"), Person.Id.from("p-9"));
    }

    // --- JsonCommands ---

    @Test
    public void toJsonSerializesThroughTheDaosMapper() {
        final String json = new JsonCommands().toJson(Map.of("a", 1));

        Assert.assertEquals(json, "{\"a\":1}");
        Assert.assertNotNull(new JsonCommands().toJson(new Object()),
                "An unserializable object answers the error text rather than throwing");
    }
}
