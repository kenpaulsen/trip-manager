package org.paulsens.trip.action;

import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * What {@code PersonCommands.getPerson} does when there is no such person.
 *
 * <p>It does <b>not</b> return null. It returns {@code new Person()}, and that constructor mints a fresh random
 * id. For a JSF page binding a form to "the person being edited" that is a convenient default. For a REST
 * resource it is a trap with two distinct failure modes, both of which shipped briefly during Phase 2 and were
 * caught only by running the container:
 *
 * <ul>
 *   <li>A {@code GET} whose {@code person == null} check never fires answers 200 with a blank stranger carrying
 *       an invented id, instead of 404.</li>
 *   <li>A {@code PUT} loads that blank object, applies the request body to it and <b>saves</b> it -- writing a
 *       junk person row for every write aimed at an id that does not exist.</li>
 * </ul>
 *
 * <p>{@code BaseResource.findPerson} exists solely to close this, and it discriminates on exactly the property
 * pinned here: a real record's id equals the id that was asked for, and the miss placeholder's does not. If
 * this contract ever changes -- if {@code getPerson} starts returning null, or starts echoing the requested id
 * on a miss -- {@code findPerson} needs revisiting, and this test is what says so.
 */
public class PersonLookupMissContractTest {

    @Test
    public void aMissYieldsAPlaceholderWhoseIdIsNotTheIdThatWasAskedFor() {
        final Person.Id absent = Person.Id.from("no-such-person-" + System.nanoTime());

        final Person found = new PersonCommands().getPerson(absent);

        Assert.assertNotNull(found, "getPerson answers a blank Person on a miss; it has never answered null.");
        Assert.assertNotEquals(found.getId(), absent,
                "The placeholder must keep its own minted id -- that difference is the only way a resource can "
                        + "tell a miss from a hit, and BaseResource.findPerson depends on it.");
    }

    @Test
    public void theMissPlaceholderCarriesNoIdentifyingData() {
        // Belt and braces: even if a caller forgets the id comparison, the object it would serve is empty --
        // so the bug is a confusing 200, not a disclosure of somebody else's record.
        final Person found = new PersonCommands().getPerson(Person.Id.from("absent-" + System.nanoTime()));

        Assert.assertNull(found.getFirst());
        Assert.assertNull(found.getLast());
        Assert.assertNull(found.getEmail());
    }
}
