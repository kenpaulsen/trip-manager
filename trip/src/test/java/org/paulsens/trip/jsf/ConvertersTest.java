package org.paulsens.trip.jsf;

import java.time.LocalDateTime;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.TripEvent;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The JSF converters.
 *
 * <p>None of them touch the {@code FacesContext} they are handed, so they test without a container. What they
 * DO touch is the DAO, which is what makes them worth testing at all: a converter is the boundary where a form
 * value becomes a model object, and a silent null there surfaces much later as an unexplained empty field.
 */
public class ConvertersTest {

    private static Person savedPerson() throws java.io.IOException {
        final Person person = new Person();
        person.setFirst("Converted");
        person.setLast("Person");
        Assert.assertTrue(DAO.getInstance().savePerson(person).join());
        return person;
    }

    // --- PersonConverter ---

    @Test
    public void aPersonRoundTripsThroughItsId() throws Exception {
        final Person person = savedPerson();
        final PersonConverter converter = new PersonConverter();

        final String asString = converter.getAsString(null, null, person);

        Assert.assertEquals(asString, person.getId().getValue());
        final Object back = converter.getAsObject(null, null, asString);
        Assert.assertTrue(back instanceof Person);
        Assert.assertEquals(((Person) back).getId(), person.getId());
    }

    @Test
    public void anUnknownPersonConvertsToNullRatherThanABlankPerson() {
        Assert.assertNull(new PersonConverter().getAsObject(null, null, "no-such-person-id"),
                "a miss must be null here, not the blank Person the DAO would invent");
    }

    @Test
    public void thePersonConverterAcceptsEitherFormOnTheWayOut() throws Exception {
        final PersonConverter converter = new PersonConverter();
        final Person person = savedPerson();

        Assert.assertEquals(converter.getAsString(null, null, person.getId()), person.getId().getValue());
        Assert.assertEquals(converter.getAsString(null, null, "already-a-string"), "already-a-string");
        Assert.assertNull(converter.getAsString(null, null, null));
        Assert.assertNull(converter.getAsString(null, null, 42), "an unexpected type converts to null");
    }

    // --- PersonIdConverter ---

    @Test
    public void thePersonIdConverterRoundTripsWithoutTouchingTheStore() {
        final PersonIdConverter converter = new PersonIdConverter();
        final Person.Id id = Person.Id.from("p-123");

        Assert.assertEquals(converter.getAsObject(null, null, "p-123"), id);
        Assert.assertEquals(converter.getAsString(null, null, id), "p-123");
    }

    @Test
    public void thePersonIdConverterAlsoAcceptsAPersonOrAString() throws Exception {
        final PersonIdConverter converter = new PersonIdConverter();
        final Person person = savedPerson();

        Assert.assertEquals(converter.getAsString(null, null, person), person.getId().getValue());
        Assert.assertEquals(converter.getAsString(null, null, "raw"), "raw");
        Assert.assertNull(converter.getAsString(null, null, 42));
        Assert.assertNull(converter.getAsString(null, null, null));
    }

    // --- StringTrimConverter ---

    /**
     * Applied to every String input on every form ({@code forClass = String.class}), so its behaviour is
     * site-wide: a field left with only spaces becomes NULL, not " ", which is what keeps blank-vs-absent from
     * meaning two different things throughout the model.
     */
    @Test
    public void theTrimConverterTurnsBlankIntoNull() {
        final StringTrimConverter converter = new StringTrimConverter();

        Assert.assertEquals(converter.getAsObject(null, null, "  padded  "), "padded");
        Assert.assertNull(converter.getAsObject(null, null, "   "));
        Assert.assertNull(converter.getAsObject(null, null, ""));
        Assert.assertNull(converter.getAsObject(null, null, null));
    }

    @Test
    public void theTrimConverterDoesNotTouchTheWayOut() {
        Assert.assertEquals(new StringTrimConverter().getAsString(null, null, "  as stored  "),
                "  as stored  ", "rendering must not silently rewrite what was stored");
        Assert.assertNull(new StringTrimConverter().getAsString(null, null, null));
    }

    // --- TripEventConverter ---

    @Test
    public void aTripEventRoundTripsThroughItsId() throws Exception {
        final TripEvent event = new TripEvent("converter-evt-" + System.nanoTime(), TripEvent.Type.EVENT,
                "Concert", "notes", LocalDateTime.now(), null, null, null);
        Assert.assertTrue(DAO.getInstance().saveTripEvent(event).join());
        final TripEventConverter converter = new TripEventConverter();

        Assert.assertEquals(converter.getAsString(null, null, event), event.getId());
        Assert.assertEquals(converter.getAsObject(null, null, event.getId()), event);
    }

    @Test
    public void anUnknownTripEventConvertsToNull() {
        Assert.assertNull(new TripEventConverter().getAsObject(null, null, "no-such-event"));
    }
}
