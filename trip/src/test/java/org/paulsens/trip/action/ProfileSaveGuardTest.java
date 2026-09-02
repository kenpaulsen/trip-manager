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
        Assert.assertTrue(new PersonCommands().saveProfile(edit));
        Assert.assertEquals(DAO.getInstance().getPerson(stored.getId(), Cached.NO).orElseThrow().getNickname(),
                "Guardy");
    }

    @Test
    public void aProfileNobodyStoredIsRefusedAndNotCreated() {
        // What an unknown ?id= hands the page: a blank Person carrying a freshly minted id.
        final Person miss = new PersonCommands().getPersonForEdit(Person.Id.from("psg-absent-" + System.nanoTime()));
        miss.setFirst("Junk");
        miss.setLast("Row");
        Assert.assertFalse(new PersonCommands().saveProfile(miss), "creation is sign-up's job");
        Assert.assertTrue(DAO.getInstance().getPerson(miss.getId(), Cached.NO).isEmpty(), "nothing was written");
        Assert.assertFalse(new PersonCommands().saveProfile(null));
    }
}
