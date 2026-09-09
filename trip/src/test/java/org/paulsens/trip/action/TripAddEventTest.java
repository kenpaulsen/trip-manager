package org.paulsens.trip.action;

import java.time.LocalDateTime;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * {@link TripCommands#addTripEvent} -- adding an event to a trip edit's working copy.
 *
 * <p>The bug these exist for (2026-09-08): the add dialogs called {@code Trip.addTripEvent} straight from a JSFT
 * command, and that method THROWS on a duplicate title+start. The throw unwound into Mojarra's ajax exception
 * handler, which routes to {@code web.xml}'s catch-all error page ({@code /index.jsf}), so the answer to the
 * postback was a redirect home -- and since a fresh GET of the edit page starts a new {@code TripEditDrafts}
 * draft from saved state, every unsaved edit went with it. A trip manager hit it four times in eleven minutes
 * re-adding flights she had already added, losing work each time and reporting only that the site "kicks me
 * out". {@link #duplicateIsRefusedWithoutThrowing} is the whole point: refused, not thrown.
 */
public class TripAddEventTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 13, 18, 55);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 13, 19, 50);

    private final TripCommands trip = new TripCommands();

    @BeforeClass
    void beforeClass() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    /** A fresh trip per test: these mutate the working copy, and a leaked event would fail its neighbours. */
    private static Trip workingCopy() {
        return Trip.builder().title("Add Event Test").build();
    }

    @Test
    public void addsTheEvent() {
        final Trip theTrip = workingCopy();
        Assert.assertTrue(trip.addTripEvent(theTrip, TripEvent.Type.FLIGHT, "IST -> SJJ", "TK1025", START, END));
        Assert.assertEquals(theTrip.getTripEvents().size(), 1);
        final TripEvent added = theTrip.getTripEvents().get(0);
        Assert.assertEquals(added.getTitle(), "IST -> SJJ");
        Assert.assertEquals(added.getStart(), START);
        Assert.assertEquals(added.getType(), TripEvent.Type.FLIGHT);
    }

    @Test
    public void duplicateIsRefusedWithoutThrowing() {
        final Trip theTrip = workingCopy();
        Assert.assertTrue(trip.addTripEvent(theTrip, TripEvent.Type.FLIGHT, "IST -> SJJ", "TK1025", START, END));
        // The model still throws; the command must turn that into a refusal the page can render.
        Assert.assertThrows(IllegalStateException.class,
                () -> theTrip.addTripEvent(TripEvent.Type.FLIGHT, "IST -> SJJ", "TK1025", START, END));
        Assert.assertFalse(trip.addTripEvent(theTrip, TripEvent.Type.FLIGHT, "IST -> SJJ", "TK1025", START, END));
        Assert.assertEquals(theTrip.getTripEvents().size(), 1, "The refused duplicate must not have been added");
    }

    /** A rescheduled segment keeps its title, so only title AND start together may refuse an add. */
    @Test
    public void sameTitleAtADifferentTimeIsAllowed() {
        final Trip theTrip = workingCopy();
        Assert.assertTrue(trip.addTripEvent(theTrip, TripEvent.Type.FLIGHT, "IST -> SJJ", "TK1025", START, END));
        Assert.assertTrue(trip.addTripEvent(theTrip, TripEvent.Type.FLIGHT, "IST -> SJJ", "TK1025",
                START.plusDays(1), END.plusDays(1)));
        Assert.assertEquals(theTrip.getTripEvents().size(), 2);
    }

    @Test
    public void differentTitleAtTheSameTimeIsAllowed() {
        final Trip theTrip = workingCopy();
        Assert.assertTrue(trip.addTripEvent(theTrip, TripEvent.Type.FLIGHT, "IST -> SJJ", "TK1025", START, END));
        Assert.assertTrue(trip.addTripEvent(theTrip, TripEvent.Type.EVENT, "Meet the group", "", START, END));
        Assert.assertEquals(theTrip.getTripEvents().size(), 2);
    }

    /** Missing pieces are refused rather than NPEing out of the command, same redirect-home consequence. */
    @Test
    public void incompleteEventsAreRefused() {
        final Trip theTrip = workingCopy();
        Assert.assertFalse(trip.addTripEvent(null, TripEvent.Type.FLIGHT, "IST -> SJJ", "n", START, END));
        Assert.assertFalse(trip.addTripEvent(theTrip, null, "IST -> SJJ", "n", START, END));
        Assert.assertFalse(trip.addTripEvent(theTrip, TripEvent.Type.FLIGHT, null, "n", START, END));
        Assert.assertFalse(trip.addTripEvent(theTrip, TripEvent.Type.FLIGHT, "  ", "n", START, END));
        Assert.assertFalse(trip.addTripEvent(theTrip, TripEvent.Type.FLIGHT, "IST -> SJJ", "n", null, END));
        Assert.assertTrue(theTrip.getTripEvents().isEmpty());
    }

    /** A null end is a real case (an open-ended event), so it must not be lumped in with the refusals. */
    @Test
    public void aNullEndIsAccepted() {
        final Trip theTrip = workingCopy();
        Assert.assertTrue(trip.addTripEvent(theTrip, TripEvent.Type.EVENT, "Free afternoon", "", START, null));
        Assert.assertEquals(theTrip.getTripEvents().size(), 1);
    }
}
