package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The event dialog's form and the commands behind its openers and Add/Apply: what each opener hands the dialog,
 * what a confirmed section composes and stores, and every refusal -- each of which must be a growl, never a
 * throw out of the command (a throw redirects home and destroys the whole edit draft; see
 * {@link TripAddEventTest}).
 */
public class TripEventFormTest {

    private static final LocalDateTime DEPART = LocalDateTime.of(2028, 5, 1, 8, 15);
    private static final LocalDateTime ARRIVE = LocalDateTime.of(2028, 5, 1, 17, 25);

    private final TripCommands trip = new TripCommands();

    @BeforeClass
    void beforeClass() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    private static Trip workingCopy() {
        return Trip.builder().title("Event Form Test").build();
    }

    private TripEventForm flightForm(final String from, final String to) {
        final TripEventForm form = trip.eventFormFor(workingCopy(), "FLIGHT", null);
        form.setFrom(from);
        form.setTo(to);
        form.setFlightNumber("Alaska Airlines AS 123");
        form.setDuration("9h 10m");
        form.setStart(DEPART);
        form.setEnd(ARRIVE);
        return form;
    }

    // --- openers ---

    @Test
    public void anAddFormCarriesItsTypeAndSeedsTheDates() {
        final TripEventForm form = trip.eventFormFor(workingCopy(), "GROUND", null);
        Assert.assertEquals(form.getMode(), TripEventForm.ADD);
        Assert.assertFalse(form.isEditing());
        Assert.assertEquals(form.getType(), "GROUND");
        Assert.assertTrue(form.isGround());
        Assert.assertTrue(form.isRoute());
        Assert.assertNotNull(form.getStart());
        Assert.assertEquals(form.getHeading(), "Add Ground Transport");
        Assert.assertEquals(trip.eventFormFor(workingCopy(), "  ", null).getType(), "EVENT", "blank is an event");
        Assert.assertEquals(trip.eventFormFor(workingCopy(), null, "").getType(), "EVENT");
    }

    @Test
    public void theLodgingOpenerIsAHandOffNotAnEditor() {
        final TripEventForm form = trip.eventFormFor(workingCopy(), "LODGING", null);
        Assert.assertTrue(form.isLodgingCreate());
        Assert.assertTrue(form.isLodging());
        Assert.assertFalse(form.isGenericSection());
        Assert.assertEquals(form.getHeading(), "Add Lodging");
        Assert.assertFalse(trip.saveEventForm(workingCopy(), form), "lodging is created on the Lodging tab");
    }

    @Test
    public void anEditFormCopiesTheEventAndItsComponents() {
        final Trip theTrip = workingCopy();
        Assert.assertTrue(trip.saveEventForm(theTrip, flightForm("pdx", "fco")));
        final TripEvent flight = theTrip.getTripEvents().get(0);
        flight.joinTripEvent(Person.Id.from("ada"));

        final TripEventForm edit = trip.eventFormFor(theTrip, null, flight.getId());
        Assert.assertTrue(edit.isEditing());
        Assert.assertEquals(edit.getEventId(), flight.getId());
        Assert.assertTrue(edit.isFlight(), "components present: the bespoke editor");
        Assert.assertFalse(edit.isGeneric());
        Assert.assertEquals(edit.getFrom(), "pdx");
        Assert.assertEquals(edit.getTo(), "fco");
        Assert.assertEquals(edit.getFlightNumber(), "Alaska Airlines AS 123");
        Assert.assertEquals(edit.getDuration(), "9h 10m");
        Assert.assertEquals(edit.getStart(), DEPART);
        Assert.assertEquals(edit.getParticipants(), List.of(Person.Id.from("ada")));
        Assert.assertEquals(edit.getHeading(), "Edit Flight");
        edit.getParticipants().clear();
        Assert.assertEquals(flight.getParticipants().size(), 1, "the form holds a copy, not the event's list");
    }

    /** Every event that predates the editor is free text: it opens generic, whatever its type says. */
    @Test
    public void aLegacyEventOpensTheGenericEditorAndCanOptIn() {
        final Trip theTrip = workingCopy();
        Assert.assertTrue(trip.addTripEvent(theTrip, TripEvent.Type.FLIGHT, "SEA -> AMS", "DL 142", DEPART, ARRIVE));
        final TripEventForm edit = trip.eventFormFor(theTrip, null, theTrip.getTripEvents().get(0).getId());
        Assert.assertTrue(edit.isGeneric());
        Assert.assertTrue(edit.isGenericSection());
        Assert.assertFalse(edit.isFlight());
        Assert.assertEquals(edit.getTitle(), "SEA -> AMS");
        Assert.assertEquals(edit.getHeading(), "Edit Flight", "it is still a flight, just without parts");

        trip.retypeEventForm(edit);
        Assert.assertTrue(edit.isFlight(), "picking a type in the menu opts into the bespoke editor");
        trip.retypeEventForm(null);
    }

    /** The heading never throws: a form with no type, or a forged one, still reads as an event. */
    @Test
    public void theHeadingDegradesForAMissingOrUnknownType() {
        final TripEventForm blank = new TripEventForm();
        Assert.assertEquals(blank.getHeading(), "Add Event");
        Assert.assertTrue(blank.isGenericSection(), "no type means the generic form");
        Assert.assertFalse(blank.isEditing());
        Assert.assertTrue(blank.getParticipants().isEmpty(), "never null, even before anything set it");
        blank.setType("BLIMP");
        blank.setMode(TripEventForm.EDIT);
        Assert.assertEquals(blank.getHeading(), "Edit Event");
        Assert.assertFalse(blank.isLodging());
    }

    @Test
    public void aVanishedEventRefusesToOpen() {
        Assert.assertNull(trip.eventFormFor(workingCopy(), null, "no-such-event"));
        Assert.assertNull(trip.eventFormFor(null, null, "no-such-event"));
    }

    @Test
    public void theEndDefaultsThreeHoursAfterTheStartButIsNeverClobbered() {
        final TripEventForm form = trip.eventFormFor(workingCopy(), "EVENT", null);
        form.setStart(DEPART);
        form.setEnd(null);
        trip.defaultEventEnd(form);
        Assert.assertEquals(form.getEnd(), DEPART.plusHours(3));

        form.setEnd(DEPART.minusHours(1));
        trip.defaultEventEnd(form);
        Assert.assertEquals(form.getEnd(), DEPART.plusHours(3), "an end before the start is replaced");

        form.setEnd(ARRIVE);
        trip.defaultEventEnd(form);
        Assert.assertEquals(form.getEnd(), ARRIVE, "a real end survives a new start pick");

        form.setStart(null);
        trip.defaultEventEnd(form);
        trip.defaultEventEnd(null);
    }

    // --- add ---

    @Test
    public void aFlightComposesItsTitleAndNotesAndKeepsItsParts() {
        final Trip theTrip = workingCopy();
        Assert.assertTrue(trip.saveEventForm(theTrip, flightForm(" pdx", "fco ")));
        final TripEvent added = theTrip.getTripEvents().get(0);
        Assert.assertEquals(added.getType(), TripEvent.Type.FLIGHT);
        Assert.assertEquals(added.getTitle(), "PDX -> FCO");
        Assert.assertEquals(added.getNotes(), "Alaska Airlines AS 123: 8:15am -> 5:25pm (9h 10m)");
        Assert.assertEquals(added.detail(TripEvent.Detail.FROM), "pdx");
        Assert.assertEquals(added.detail(TripEvent.Detail.DURATION), "9h 10m");
        Assert.assertNull(added.detail(TripEvent.Detail.CARRIER));
    }

    @Test
    public void aGroundLegComposesFromItsRouteAndCarrier() {
        final Trip theTrip = workingCopy();
        final TripEventForm form = trip.eventFormFor(theTrip, "GROUND", null);
        form.setFrom("Medjugorje");
        form.setTo("Split");
        form.setCarrier("Globtour bus");
        form.setStart(LocalDateTime.of(2028, 5, 3, 9, 0));
        form.setEnd(LocalDateTime.of(2028, 5, 3, 11, 30));
        Assert.assertTrue(trip.saveEventForm(theTrip, form));
        final TripEvent added = theTrip.getTripEvents().get(0);
        Assert.assertEquals(added.getType(), TripEvent.Type.GROUND);
        Assert.assertEquals(added.getTitle(), "Medjugorje -> Split");
        Assert.assertEquals(added.getNotes(), "Globtour bus: 9:00am -> 11:30am (2h 30m)");
        Assert.assertEquals(added.detail(TripEvent.Detail.CARRIER), "Globtour bus");
        Assert.assertNull(added.detail(TripEvent.Detail.FLIGHT_NUMBER));

        form.setCarrier(" ");
        form.setStart(form.getStart().plusDays(1));
        Assert.assertTrue(trip.saveEventForm(theTrip, form), "a carrier is optional");
        Assert.assertNull(theTrip.getTripEvents().get(1).detail(TripEvent.Detail.CARRIER));
    }

    @Test
    public void aPlainEventStoresNoComponents() {
        final Trip theTrip = workingCopy();
        final TripEventForm form = trip.eventFormFor(theTrip, "EVENT", null);
        form.setTitle("  Mass at St. James  ");
        form.setNotes("<p>Bring a rosary</p>");
        form.setStart(DEPART);
        Assert.assertTrue(trip.saveEventForm(theTrip, form));
        final TripEvent added = theTrip.getTripEvents().get(0);
        Assert.assertEquals(added.getTitle(), "Mass at St. James");
        Assert.assertEquals(added.getNotes(), "<p>Bring a rosary</p>");
        Assert.assertFalse(added.hasDetails());
    }

    @Test
    public void incompleteFormsAreRefusedNotThrown() {
        final Trip theTrip = workingCopy();
        Assert.assertFalse(trip.saveEventForm(null, flightForm("pdx", "fco")));
        Assert.assertFalse(trip.saveEventForm(theTrip, null));

        final TripEventForm noType = trip.eventFormFor(theTrip, "EVENT", null);
        noType.setType("BLIMP");
        noType.setTitle("x");
        Assert.assertFalse(trip.saveEventForm(theTrip, noType), "an unknown type is a forged submit");
        noType.setType(null);
        Assert.assertFalse(trip.saveEventForm(theTrip, noType));

        final TripEventForm noStart = flightForm("pdx", "fco");
        noStart.setStart(null);
        Assert.assertFalse(trip.saveEventForm(theTrip, noStart));

        Assert.assertFalse(trip.saveEventForm(theTrip, flightForm("", "fco")), "a flight needs both airports");
        final TripEventForm noNumber = flightForm("pdx", "fco");
        noNumber.setFlightNumber(" ");
        Assert.assertFalse(trip.saveEventForm(theTrip, noNumber), "and its flight number");

        final TripEventForm ground = trip.eventFormFor(theTrip, "GROUND", null);
        ground.setStart(DEPART);
        ground.setFrom("Split");
        Assert.assertFalse(trip.saveEventForm(theTrip, ground), "ground transport needs both ends");

        final TripEventForm untitled = trip.eventFormFor(theTrip, "EVENT", null);
        untitled.setStart(DEPART);
        untitled.setTitle("  ");
        Assert.assertFalse(trip.saveEventForm(theTrip, untitled));
        Assert.assertTrue(theTrip.getTripEvents().isEmpty(), "nothing was added by a refusal");
    }

    @Test
    public void addingTheSameFlightTwiceIsRefused() {
        final Trip theTrip = workingCopy();
        Assert.assertTrue(trip.saveEventForm(theTrip, flightForm("pdx", "fco")));
        Assert.assertFalse(trip.saveEventForm(theTrip, flightForm("PDX", "FCO")), "same title, same start");
        Assert.assertEquals(theTrip.getTripEvents().size(), 1);
    }

    // --- edit ---

    @Test
    public void editingRecomposesAndRewritesTheComponents() {
        final Trip theTrip = workingCopy();
        Assert.assertTrue(trip.saveEventForm(theTrip, flightForm("pdx", "fco")));
        final TripEvent flight = theTrip.getTripEvents().get(0);
        flight.joinTripEvent(Person.Id.from("ada"));

        final TripEventForm edit = trip.eventFormFor(theTrip, null, flight.getId());
        edit.setTo("lhr");
        edit.setDuration("");
        edit.getParticipants().add(Person.Id.from("bob"));
        Assert.assertTrue(trip.saveEventForm(theTrip, edit));
        Assert.assertSame(theTrip.getTripEvent(flight.getId()), flight, "edited in place");
        Assert.assertEquals(flight.getTitle(), "PDX -> LHR");
        Assert.assertEquals(flight.getNotes(), "Alaska Airlines AS 123: 8:15am -> 5:25pm", "no duration now");
        Assert.assertEquals(flight.detail(TripEvent.Detail.TO), "lhr");
        Assert.assertNull(flight.detail(TripEvent.Detail.DURATION));
        Assert.assertEquals(flight.getParticipants(), List.of(Person.Id.from("ada"), Person.Id.from("bob")));
    }

    @Test
    public void editingAnEventIntoAnotherEventsTitleAndStartIsRefused() {
        final Trip theTrip = workingCopy();
        Assert.assertTrue(trip.saveEventForm(theTrip, flightForm("pdx", "fco")));
        final TripEventForm second = trip.eventFormFor(theTrip, "EVENT", null);
        second.setTitle("Free afternoon");
        second.setStart(DEPART);
        Assert.assertTrue(trip.saveEventForm(theTrip, second));
        final TripEvent other = theTrip.getTripEvents().get(1);

        final TripEventForm edit = trip.eventFormFor(theTrip, null, other.getId());
        edit.setTitle("PDX -> FCO");
        Assert.assertFalse(trip.saveEventForm(theTrip, edit), "would duplicate the flight");
        Assert.assertEquals(other.getTitle(), "Free afternoon", "a refusal changes nothing");

        edit.setTitle("Free afternoon");
        edit.setNotes("Rest");
        Assert.assertTrue(trip.saveEventForm(theTrip, edit), "an event is never a duplicate of itself");
        Assert.assertEquals(other.getNotes(), "Rest");
    }

    @Test
    public void editingAVanishedEventIsRefused() {
        final Trip theTrip = workingCopy();
        Assert.assertTrue(trip.saveEventForm(theTrip, flightForm("pdx", "fco")));
        final TripEvent flight = theTrip.getTripEvents().get(0);
        final TripEventForm edit = trip.eventFormFor(theTrip, null, flight.getId());
        theTrip.deleteTripEvent(flight);
        Assert.assertFalse(trip.saveEventForm(theTrip, edit));
        Assert.assertFalse(trip.editTripEvent(theTrip, null, TripEvent.Type.EVENT, "x", null, DEPART, null,
                null, null));
        Assert.assertFalse(trip.editTripEvent(theTrip, flight.getId(), null, "x", null, DEPART, null, null, null));
    }

    /** A lodging event edits as a plain event: its dates and rooms belong to the offer, not to this dialog. */
    @Test
    public void aLodgingEventEditsGenerically() {
        final Trip theTrip = workingCopy();
        Assert.assertTrue(trip.addTripEvent(theTrip, TripEvent.Type.LODGING, "Pansion", "Podbrdo 25", DEPART,
                ARRIVE.plusDays(9)));
        final TripEventForm edit = trip.eventFormFor(theTrip, null, theTrip.getTripEvents().get(0).getId());
        Assert.assertTrue(edit.isLodging());
        Assert.assertTrue(edit.isGenericSection());
        Assert.assertFalse(edit.isLodgingCreate());
        Assert.assertEquals(edit.getHeading(), "Edit Lodging");
        edit.setTitle("Pansion Dragicevic");
        Assert.assertTrue(trip.saveEventForm(theTrip, edit));
        Assert.assertEquals(theTrip.getTripEvents().get(0).getTitle(), "Pansion Dragicevic");
    }
}
