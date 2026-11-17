package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * A trip event's shared notes are cleaned where they are stored and wherever they are read (medjugorje#156): the
 * event dialog's Add and Apply, the editor it reopens, and every page bean that writes them out unescaped. A
 * stored row is never rewritten, so a row saved before the fix (seeded raw here, the way an old row reads back)
 * must be cleaned on every read path.
 */
public class EventNotesCleaningTest {

    private static final LocalDateTime START = LocalDateTime.of(2028, 5, 1, 9, 0);
    private static final String RAW = "<p>Meet at <strong>gate 4</strong></p><img src=x onerror=alert(1)>";
    private static final String CLEAN = "<p>Meet at <strong>gate 4</strong></p>";
    private static final Person.Id TRAVELER = Person.Id.from(FakeData.ADMIN_PERSON_ID);

    private final TripCommands trips = new TripCommands();

    @BeforeClass
    void beforeClass() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    /** A trip holding one EVENT whose notes were stored raw, before notes were cleaned. */
    private static Trip tripWithRawNotes() {
        final Trip trip = Trip.builder().title("Notes " + UUID.randomUUID()).build();
        trip.getTripEvents().add(new TripEvent(UUID.randomUUID().toString(), TripEvent.Type.EVENT, "Welcome", RAW,
                START, START.plusHours(1), List.of(TRAVELER), null));
        return trip;
    }

    private TripEventForm eventForm(final Trip trip, final String notes) {
        final TripEventForm form = trips.eventFormFor(trip, "EVENT", null);
        form.setTitle("Welcome");
        form.setNotes(notes);
        form.setStart(START);
        form.setEnd(START.plusHours(1));
        return form;
    }

    @Test
    public void addStoresCleanNotesAndSaysWhatWasRemoved() {
        final Trip trip = Trip.builder().title("Add").build();
        final List<String> growls = TestFaces.growlsOf(new HashMap<>(),
                () -> Assert.assertTrue(trips.saveEventForm(trip, eventForm(trip, RAW))));
        Assert.assertEquals(trip.getTripEvents().get(0).getNotes(), CLEAN);
        Assert.assertEquals(growls.size(), 1, growls.toString());
        Assert.assertTrue(growls.get(0).contains("some formatting its notes cannot keep"), growls.toString());
    }

    @Test
    public void applyStoresCleanNotesAndCleanNotesSaveQuietly() {
        final Trip trip = tripWithRawNotes();
        final String id = trip.getTripEvents().get(0).getId();
        final TripEventForm edit = trips.eventFormFor(trip, null, id);
        Assert.assertEquals(edit.getNotes(), CLEAN, "the editor reopens on what a save will keep");

        edit.setNotes(RAW + "<p style=\"color: rgb(230, 0, 0)\">red</p>");
        final List<String> warned = TestFaces.growlsOf(new HashMap<>(),
                () -> Assert.assertTrue(trips.saveEventForm(trip, edit)));
        Assert.assertEquals(trip.getTripEvent(id).getNotes(), CLEAN + "<p style=\"color: rgb(230, 0, 0)\">red</p>");
        Assert.assertEquals(warned.size(), 1, warned.toString());

        final TripEventForm again = trips.eventFormFor(trip, null, id);
        final List<String> quiet = TestFaces.growlsOf(new HashMap<>(),
                () -> Assert.assertTrue(trips.saveEventForm(trip, again)));
        Assert.assertEquals(quiet, List.of(), "notes that lose nothing save without a warning");
    }

    /**
     * A pasted picture (Quill inlines it as a data address thousands of characters long) is refused, as a
     * reminder's save refuses it: the cleaner could only write it out as that many characters of visible text.
     */
    @Test
    public void aPastedPictureIsRefusedNotStoredAsText() {
        final String pasted = "<p>Hotel</p><p><img src=\"data:image/png;base64," + "A".repeat(3_000) + "\"></p>";
        // Refused like every other dialog refusal (a growl and keepOpen; TripEventFormTest runs them the same way).
        final Trip trip = Trip.builder().title("Pasted").build();
        Assert.assertFalse(trips.saveEventForm(trip, eventForm(trip, pasted)));
        Assert.assertTrue(trip.getTripEvents().isEmpty(), "nothing was added");

        final Trip existing = tripWithRawNotes();
        final String id = existing.getTripEvents().get(0).getId();
        final TripEventForm edit = trips.eventFormFor(existing, null, id);
        edit.setNotes(pasted);
        Assert.assertFalse(trips.saveEventForm(existing, edit));
        Assert.assertEquals(existing.getTripEvent(id).getNotes(), RAW, "the event is left as it was");

        Assert.assertTrue(TripCommands.eventNotesProblem(pasted).contains("too large to keep"));

        Assert.assertNotNull(TripCommands.eventNotesProblem("<a href=\"https://x/" + "y".repeat(3_000) + "\">z</a>"),
                "an address too long to read is refused too");
        Assert.assertNull(TripCommands.eventNotesProblem(CLEAN));
        Assert.assertNull(TripCommands.eventNotesProblem(null));
    }

    @Test
    public void everyPageReaderWritesOutCleanNotes() {
        final Trip trip = tripWithRawNotes();
        final TripEvent event = trip.getTripEvents().get(0);
        Assert.assertEquals(event.getNotes(), RAW, "the stored row itself is left alone");

        Assert.assertEquals(trips.eventNotesHtml(event), CLEAN, "trip/edit.xhtml and admin/tripEvents.xhtml");
        Assert.assertEquals(trips.eventNotesHtml(null), "");
        Assert.assertEquals(SharedItineraryCommands.SharedItineraryRow.of(event).getNotes(), CLEAN,
                "the shared itinerary, which needs no sign-in");
        final List<TripPageCommands.AgendaDay> agenda =
                new TripPageCommands(() -> null, () -> START).publicAgenda(trip);
        Assert.assertEquals(agenda.get(0).items().get(0).notes(), CLEAN, "the trip page's public schedule");
        final List<LodgingViews.ItineraryRow> rows =
                new LodgingCommands(TestCallers::siteAdmin).itineraryRowsFor(trip, TRAVELER);
        Assert.assertEquals(rows.get(0).getNotes(), CLEAN, "the itinerary, its print and badge views");

        final Trip ground = tripWithRawNotes();
        ground.getTripEvents().get(0).setType(TripEvent.Type.GROUND);
        Assert.assertEquals(new ReportCommands().groundRows(ground).get(0).getNotes(), CLEAN,
                "the ground transport report");
    }
}
