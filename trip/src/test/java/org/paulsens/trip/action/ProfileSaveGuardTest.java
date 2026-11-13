package org.paulsens.trip.action;

import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The profile editor saves EXISTING people only: a person is created by signing up (or by a family
 * manager / the REST API), never by an admin opening a profile page. The hole this closes: an admin
 * opening {@code /account/person.jsf?id=<unknown>} got a blank Person under a fresh id and its Save created
 * a junk row -- the old People page "New Person" button was exactly that.
 */
public class ProfileSaveGuardTest {

    @Test
    public void anExistingProfileSaves() throws Exception {
        final Person stored = new Person();
        stored.setFirst("Guard");
        stored.setLast("Existing");
        Assert.assertTrue(DAO.getInstance().savePerson(stored));

        final Person edit = new PersonCommands().getPersonForEdit(stored.getId());
        edit.setNickname("Guardy");
        Assert.assertTrue(new PersonCommands().saveProfile(edit, stored, false), "one's own profile");
        Assert.assertEquals(DAO.getInstance().getPerson(stored.getId(), Cached.NO).orElseThrow().getNickname(),
                "Guardy");
    }

    @Test
    public void aProfileNobodyStoredIsRefusedAndNotCreated() {
        // What an unknown ?id= hands the page: a blank Person carrying a freshly minted id.
        final Person miss = new PersonCommands().getPersonForEdit(Person.Id.from("psg-absent-" + System.nanoTime()));
        miss.setFirst("Junk");
        miss.setLast("Row");
        Assert.assertFalse(new PersonCommands().saveProfile(miss, miss, true), "creation is sign-up's job");
        Assert.assertTrue(DAO.getInstance().getPerson(miss.getId(), Cached.NO).isEmpty(), "nothing was written");
        Assert.assertFalse(new PersonCommands().saveProfile(null, miss, true));
    }

    /**
     * Opening a profile is not permission to change it. The page opens for a trip's managers and finance
     * viewers (a read), and its Save used to check nothing: any trip manager could overwrite anyone's record.
     */
    @Test
    public void onlyThePersonTheirManagerOrAnAdministratorMayChangeAProfile() throws Exception {
        final Person subject = saved("Subject");
        final Person manager = saved("Manager");
        manager.getManagedUsers().add(subject.getId());
        Assert.assertTrue(DAO.getInstance().savePerson(manager));
        final Person stranger = saved("Stranger");
        // A caller with no reach over anybody: what a trip manager is to a traveler's record.
        final PersonCommands people = new PersonCommands(() -> new OrgCommands(() -> TestCallers.person(
                stranger.getId())));

        final Person edit = people.getPersonForEdit(subject.getId());
        edit.setNickname("Changed");
        Assert.assertFalse(people.saveProfile(edit, stranger, false), "a reader may not write");
        Assert.assertNull(DAO.getInstance().getPerson(subject.getId(), Cached.NO).orElseThrow().getNickname());

        Assert.assertTrue(people.saveProfile(edit, manager, false), "their family manager may");
        Assert.assertEquals(DAO.getInstance().getPerson(subject.getId(), Cached.NO).orElseThrow().getNickname(),
                "Changed");
        Assert.assertTrue(people.saveProfile(people.getPersonForEdit(subject.getId()), stranger, true),
                "a site administrator may");
        Assert.assertTrue(people.mayEditProfile(subject, false, subject.getId()));
        Assert.assertFalse(people.mayEditProfile(stranger, false, subject.getId()));
    }

    /** A trip role reaches the people ON that trip, and nobody else. */
    @Test
    public void aTripOpensOnlyTheProfilesOfThePeopleOnIt() throws Exception {
        org.paulsens.trip.dynamo.FakeData.initFakeData();
        org.paulsens.trip.dynamo.FakeData.addFakeData();
        final org.paulsens.trip.model.Trip trip = DAO.getInstance()
                .getTrip(org.paulsens.trip.dynamo.FakeData.FAKE_TRIP_ID, Cached.NO).orElseThrow();
        final Person.Id traveler = trip.getPeople().iterator().next();
        final Person outsider = saved("Outsider");
        final TripCommands trips = new TripCommands();
        Assert.assertTrue(trips.personIsOnTrip(trip.getId(), traveler));
        Assert.assertFalse(trips.personIsOnTrip(trip.getId(), outsider.getId()));
        Assert.assertFalse(trips.personIsOnTrip("no-such-trip", traveler));
        Assert.assertFalse(trips.personIsOnTrip(" ", traveler));
        Assert.assertFalse(trips.personIsOnTrip(trip.getId(), null));
    }

    private static Person saved(final String first) throws Exception {
        final Person person = new Person();
        person.setFirst(first);
        person.setLast("Psg" + System.nanoTime());
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }
}
