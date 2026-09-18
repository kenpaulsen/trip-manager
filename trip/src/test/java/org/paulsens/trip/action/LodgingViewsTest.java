package org.paulsens.trip.action;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.paulsens.trip.action.LodgingViews.BlockForm;
import org.paulsens.trip.action.LodgingViews.DayCell;
import org.paulsens.trip.action.LodgingViews.OfferForm;
import org.paulsens.trip.action.LodgingViews.ItineraryRow;
import org.paulsens.trip.action.LodgingViews.PlacementForm;
import org.paulsens.trip.action.LodgingViews.ReservationForm;
import org.paulsens.trip.action.LodgingViews.SplitForm;
import org.paulsens.trip.action.LodgingViews.StayWindowForm;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The stay forms hold ONE stay in two shapes: a range picker plus arrival and departure times (what the
 * dialogs write, in whatever order the components decode) and a pair of date-times (what the commands, the
 * REST door and the tests write). Every setter keeps the other shape in step, because the explicit "resolve"
 * step callers used to need is exactly the step callers forgot.
 */
public class LodgingViewsTest {

    private static final LocalDate DAY_1 = LocalDate.of(2026, 9, 21);
    private static final LocalDate DAY_4 = LocalDate.of(2026, 9, 24);

    @Test
    public void anOfferFoldsItsPickersAndItsDateTimesTogether() {
        final OfferForm form = new OfferForm();
        form.setDefaultRange(List.of(DAY_1, DAY_4));
        assertEquals(form.getDefaultStart(), DAY_1.atTime(OfferForm.DEFAULT_ARRIVAL), "Default arrival time");
        assertEquals(form.getDefaultEnd(), DAY_4.atTime(OfferForm.DEFAULT_DEPARTURE));

        form.setArrivalTime(LocalTime.of(16, 30));
        form.setDepartureTime(LocalTime.of(9, 0));
        assertEquals(form.getDefaultStart(), DAY_1.atTime(16, 30), "A time change re-folds the range");
        assertEquals(form.getDefaultEnd(), DAY_4.atTime(9, 0));

        form.setDefaultRange(null);
        assertTrue(form.getDefaultRange().isEmpty(), "A cleared picker is empty, never null");
        assertEquals(form.getDefaultStart(), DAY_1.atTime(16, 30), "...and leaves the last stay alone");

        // The other direction: writing the date-times fills the picker the dialog renders.
        form.setDefaultStart(DAY_1.atTime(15, 0));
        form.setDefaultEnd(DAY_4.atTime(10, 0));
        assertEquals(form.getDefaultRange(), List.of(DAY_1, DAY_4));
        assertEquals(form.getArrivalTime(), LocalTime.of(15, 0));
        assertEquals(form.getDepartureTime(), LocalTime.of(10, 0));
        form.setDefaultStart(null);
        assertEquals(form.getArrivalTime(), LocalTime.of(15, 0), "A null date keeps the time already picked");
        form.setDefaultEnd(null);
        assertEquals(form.getDepartureTime(), LocalTime.of(10, 0));
        assertTrue(form.getDefaultRange().isEmpty(), "...but there is no range left to render");

        form.setValidRange(List.of(DAY_1, DAY_4));
        assertEquals(form.getValidFrom(), DAY_1.atStartOfDay(), "Validity is whole days");
        assertEquals(form.getValidUntil(), DAY_4.atTime(23, 59));
        form.setValidRange(null);
        assertTrue(form.getValidRange().isEmpty());
        form.setValidFrom(DAY_1.atStartOfDay());
        form.setValidUntil(DAY_4.atTime(23, 59));
        assertEquals(form.getValidRange(), List.of(DAY_1, DAY_4));

        form.resolveDates();
        form.fillRanges();
        assertEquals(form.getArrivalTime(), LocalTime.of(15, 0), "Already folded: nothing moves");
        final OfferForm blank = new OfferForm();
        blank.fillRanges();
        assertEquals(blank.getArrivalTime(), OfferForm.DEFAULT_ARRIVAL, "An empty form still offers times");
        assertEquals(blank.getDepartureTime(), OfferForm.DEFAULT_DEPARTURE);
    }

    @Test
    public void aReservationFoldsItsPickersAndItsDateTimesTogether() {
        final ReservationForm form = new ReservationForm();
        form.setRange(List.of(DAY_1, DAY_4));
        assertEquals(form.getStart(), DAY_1.atTime(OfferForm.DEFAULT_ARRIVAL));
        assertEquals(form.getEnd(), DAY_4.atTime(OfferForm.DEFAULT_DEPARTURE));

        form.setArrivalTime(LocalTime.of(23, 0));
        form.setDepartureTime(LocalTime.of(6, 15));
        assertEquals(form.getStart(), DAY_1.atTime(23, 0), "The late arriver's own hour");
        assertEquals(form.getEnd(), DAY_4.atTime(6, 15));
        form.setRange(null);
        assertTrue(form.getRange().isEmpty());

        form.setStart(DAY_1.atTime(14, 0));
        form.setEnd(DAY_4.atTime(11, 0));
        assertEquals(form.getRange(), List.of(DAY_1, DAY_4));
        assertEquals(form.getArrivalTime(), LocalTime.of(14, 0));
        assertEquals(form.getDepartureTime(), LocalTime.of(11, 0));
        form.setStart(null);
        form.setEnd(null);
        assertEquals(form.getArrivalTime(), LocalTime.of(14, 0), "Clearing a date keeps the picked time");
        assertEquals(form.getDepartureTime(), LocalTime.of(11, 0));

        form.resolveDates();
        final ReservationForm blank = new ReservationForm();
        blank.fillRanges();
        assertEquals(blank.getArrivalTime(), OfferForm.DEFAULT_ARRIVAL);
        assertEquals(blank.getDepartureTime(), OfferForm.DEFAULT_DEPARTURE);
        blank.setStart(DAY_1.atTime(13, 0));
        blank.setEnd(DAY_4.atTime(8, 0));
        blank.fillRanges();
        assertEquals(blank.getArrivalTime(), LocalTime.of(13, 0), "Times already set are not overwritten");
    }

    @Test
    public void aPlacementCarriesTheStayItIsAboutToCreate() {
        final PlacementForm form = new PlacementForm();
        assertNull(form.start(), "No range picked yet: there is no stay to report");
        assertNull(form.end());

        form.setRange(List.of(DAY_1, DAY_4));
        assertEquals(form.start(), DAY_1.atTime(OfferForm.DEFAULT_ARRIVAL));
        assertEquals(form.end(), DAY_4.atTime(OfferForm.DEFAULT_DEPARTURE));
        form.setArrivalTime(LocalTime.of(22, 45));
        form.setDepartureTime(LocalTime.of(5, 30));
        assertEquals(form.start(), DAY_1.atTime(22, 45));
        assertEquals(form.end(), DAY_4.atTime(5, 30));
        form.setRange(null);
        assertTrue(form.getRange().isEmpty());
        assertNull(form.start());

        // Opening the dialog, and every later option change, fills the stay from that option's default.
        form.applyDefaults(DAY_1.atTime(15, 0), DAY_4.atTime(10, 0));
        assertEquals(form.getRange(), List.of(DAY_1, DAY_4));
        assertEquals(form.start(), DAY_1.atTime(15, 0));
        assertEquals(form.end(), DAY_4.atTime(10, 0));
        form.applyDefaults(null, null);
        assertTrue(form.getRange().isEmpty(), "An option with no default stay leaves the picker empty");
        assertEquals(form.getArrivalTime(), OfferForm.DEFAULT_ARRIVAL);
        assertEquals(form.getDepartureTime(), OfferForm.DEFAULT_DEPARTURE);
    }

    @Test
    public void theBoardWindowIsAWholeDayUnlessTimesAreGiven() {
        final StayWindowForm form = new StayWindowForm();
        assertNull(form.start(), "No window picked");
        assertNull(form.end());

        form.setRange(List.of(DAY_1, DAY_4));
        assertEquals(form.start(), DAY_1.atStartOfDay(), "A window with no times covers the whole days");
        assertEquals(form.end(), DAY_4.atTime(23, 59));
        form.setArrivalTime(LocalTime.of(15, 0));
        form.setDepartureTime(LocalTime.of(10, 0));
        assertEquals(form.start(), DAY_1.atTime(15, 0));
        assertEquals(form.end(), DAY_4.atTime(10, 0));
        form.setRange(null);
        assertTrue(form.getRange().isEmpty());
        assertNull(form.start());
    }

    /** The stay line's tail: the room type is parenthetical only when it qualifies a shown room number. */
    @Test
    public void theStayTailFollowsWhateverTheRowIsAllowedToShow() {
        final ItineraryRow row = new ItineraryRow();
        row.setNights(3);
        row.setRoomLabel("101");
        row.setRoomTypeName("Double");
        assertEquals(row.getStayTail(), " (Double), 3 nights", "after a shown room the type qualifies it");
        row.setRoomLabel(" ");
        assertEquals(row.getStayTail(), "Double, 3 nights", "a withheld number leaves the type on its own");
        row.setRoomTypeName(null);
        assertEquals(row.getStayTail(), "3 nights", "nothing else known, just the stay");
        row.setRoomLabel("7");
        row.setNights(1);
        assertEquals(row.getStayTail(), ", 1 night", "a room without a type, and the singular");
    }

    /** An EXTRA stay keeps the option's TIMES and drops its dates: those are the nights already taken. */
    @Test
    public void anotherStayStartsWithNoDatesOfItsOwn() {
        final PlacementForm first = new PlacementForm();
        first.applyDefaults(DAY_1.atTime(15, 0), DAY_4.atTime(10, 0));
        assertEquals(first.getRange(), List.of(DAY_1, DAY_4));
        assertEquals(first.start(), DAY_1.atTime(15, 0));

        final PlacementForm second = new PlacementForm();
        second.setAnotherStay(true);
        second.applyDefaults(DAY_1.atTime(15, 0), DAY_4.atTime(10, 0));
        assertTrue(second.getRange().isEmpty(), "the dialog makes you pick nights they do not already hold");
        assertNull(second.start());
        assertEquals(second.getArrivalTime(), LocalTime.of(15, 0), "the times still come from the option");
        assertEquals(second.getDepartureTime(), LocalTime.of(10, 0));
    }

    /** The changeover picker is bounded so both halves of a split keep at least one night. */
    @Test
    public void aSplitOffersOnlyTheNightsInsideTheStay() {
        final SplitForm form = new SplitForm();
        assertNull(form.getMinDate(), "no stay, no bounds");
        assertNull(form.getMaxDate());
        form.setStart(DAY_1.atTime(15, 0));
        form.setEnd(DAY_4.atTime(10, 0));
        assertEquals(form.getMinDate(), DAY_1.plusDays(1));
        assertEquals(form.getMaxDate(), DAY_4.minusDays(1));
    }

    @Test
    public void aBlockFormCopiesItsListsAndReadsItsRange() {
        final BlockForm form = new BlockForm();
        assertTrue(form.getRoomIds().isEmpty());
        assertNull(form.start());
        assertNull(form.end());
        final List<String> rooms = new java.util.ArrayList<>(List.of("r-1"));
        form.setRoomIds(rooms);
        rooms.add("r-2");
        assertEquals(form.getRoomIds(), List.of("r-1"), "copied, so the caller's list cannot drift into it");
        form.setRoomIds(null);
        assertTrue(form.getRoomIds().isEmpty(), "a cleared multi-select is empty, never null");
        form.setRange(List.of(DAY_1, DAY_4));
        assertEquals(form.start(), DAY_1);
        assertEquals(form.end(), DAY_4);
        form.setRange(null);
        assertNull(form.start());
    }

    /** A day's pill says the numbers, because a colour band on its own tells a hotel nothing. */
    @Test
    public void aDayCellSpellsOutItsOccupancy() {
        final DayCell full = new DayCell(DAY_1, 23, 28, 2, 33, 0, 0, "cal-most");
        assertEquals(full.getSummary(), "23/28 rooms · 33 people");
        final DayCell one = new DayCell(DAY_1, 1, 28, 0, 1, 0, 0, "cal-some");
        assertEquals(one.getSummary(), "1/28 rooms · 1 person");
        assertEquals(new DayCell().getSummary(), "0/0 rooms · 0 people");
    }

    /** A plain row is a first row: whatever the event says renders on it, and a plain row is all there is. */
    @Test
    public void anItineraryRowIsAFirstStayUntilToldOtherwise() {
        assertTrue(new ItineraryRow().isFirstStay());
        final ItineraryRow second = new ItineraryRow();
        second.setFirstStay(false);
        second.setStayIndex(2);
        second.setStayCount(2);
        assertFalse(second.isFirstStay());
        assertEquals(second.getStayIndex(), 2);
    }

}
