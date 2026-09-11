package org.paulsens.trip.action;

import java.time.LocalDateTime;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * The one line an itinerary shows for a flight or a ground leg. The flight cases came over from
 * {@code TripUtilCommandsTest} unchanged: two of them ({@link #aNoonDepartureIsTwelveNotZero},
 * {@link #anOvernightFlightAcrossNewYearIsStillMarked}) are real bugs the move out of the page's inline script
 * turned up, and they stay pinned.
 */
public class TripEventComposerTest {

    @Test
    public void airportCodesAreUppercasedAndTrimmedPlaceNamesKeepTheirCase() {
        assertEquals(TripEventComposer.composeRouteTitle("pdx", "fco", true), "PDX -> FCO");
        assertEquals(TripEventComposer.composeRouteTitle("  pdx ", " Fco", true), "PDX -> FCO",
                "Typed whitespace is not a code");
        assertEquals(TripEventComposer.composeRouteTitle(" Medjugorje", "Split ", false), "Medjugorje -> Split",
                "A bus route is written the way it was typed");
        assertEquals(TripEventComposer.composeRouteTitle(null, null, true), " -> ");
    }

    @Test
    public void flightNotesCarryTheNumberTimesAndDuration() {
        assertEquals(TripEventComposer.composeFlightNotes("Alaska Airlines AS 123",
                        LocalDateTime.of(2028, 5, 1, 8, 15), LocalDateTime.of(2028, 5, 1, 11, 45), "9h 10m"),
                "Alaska Airlines AS 123: 8:15am -> 11:45am (9h 10m)");
    }

    /**
     * The old inline script formatted the departure with {@code K} (hour 0-11), so an afternoon departure read
     * {@code 0:15pm}. Arrival used {@code h} and was correct, which is why nobody noticed.
     */
    @Test
    public void aNoonDepartureIsTwelveNotZero() {
        assertEquals(TripEventComposer.composeFlightNotes("AS 1", LocalDateTime.of(2028, 5, 1, 12, 15),
                LocalDateTime.of(2028, 5, 1, 14, 0), "1h 45m"), "AS 1: 12:15pm -> 2:00pm (1h 45m)");
    }

    @Test
    public void anOvernightFlightIsMarked() {
        assertEquals(TripEventComposer.composeFlightNotes("AS 2", LocalDateTime.of(2028, 5, 1, 22, 30),
                LocalDateTime.of(2028, 5, 2, 6, 5), "7h 35m"), "AS 2: 10:30pm -> 6:05am +1 (7h 35m)");
    }

    /**
     * The marker used to compare {@code dayOfYear}, which goes NEGATIVE across New Year -- a 31-Dec red-eye
     * silently lost its {@code +1}.
     */
    @Test
    public void anOvernightFlightAcrossNewYearIsStillMarked() {
        assertEquals(TripEventComposer.composeFlightNotes("AS 3", LocalDateTime.of(2028, 12, 31, 23, 30),
                LocalDateTime.of(2029, 1, 1, 7, 0), "7h 30m"), "AS 3: 11:30pm -> 7:00am +1 (7h 30m)");
    }

    /** A ground leg's elapsed time is derived from its times: both ends are in one zone. */
    @Test
    public void groundNotesDeriveTheirDuration() {
        assertEquals(TripEventComposer.composeGroundNotes("Globtour bus", LocalDateTime.of(2028, 5, 3, 9, 0),
                LocalDateTime.of(2028, 5, 3, 11, 30)), "Globtour bus: 9:00am -> 11:30am (2h 30m)");
        assertEquals(TripEventComposer.composeGroundNotes("Taxi", LocalDateTime.of(2028, 5, 3, 9, 0),
                LocalDateTime.of(2028, 5, 3, 9, 45)), "Taxi: 9:00am -> 9:45am (45m)");
        assertEquals(TripEventComposer.composeGroundNotes("Night bus", LocalDateTime.of(2028, 5, 3, 23, 0),
                LocalDateTime.of(2028, 5, 4, 2, 0)), "Night bus: 11:00pm -> 2:00am +1 (3h)");
    }

    @Test
    public void elapsedIsEmptyUnlessTheEndFollowsTheStart() {
        assertEquals(TripEventComposer.elapsed(null, LocalDateTime.of(2028, 5, 3, 9, 0)), "");
        assertEquals(TripEventComposer.elapsed(LocalDateTime.of(2028, 5, 3, 9, 0), null), "");
        assertEquals(TripEventComposer.elapsed(LocalDateTime.of(2028, 5, 3, 9, 0),
                LocalDateTime.of(2028, 5, 3, 9, 0)), "", "a zero-length leg has no duration to show");
        assertEquals(TripEventComposer.elapsed(LocalDateTime.of(2028, 5, 3, 9, 0),
                LocalDateTime.of(2028, 5, 3, 8, 0)), "", "arrival before departure is not a negative duration");
    }

    /**
     * A null field used to abort the whole JSFT block; composing must degrade instead. And each missing piece
     * takes its punctuation with it: no label means no colon, no duration means no parentheses.
     */
    @Test
    public void missingPiecesDegradeRatherThanThrow() {
        assertEquals(TripEventComposer.composeFlightNotes(null, null, null, null), " -> ");
        assertEquals(TripEventComposer.composeFlightNotes("AS 4", LocalDateTime.of(2028, 5, 1, 8, 15),
                LocalDateTime.of(2028, 5, 1, 11, 45), "  "), "AS 4: 8:15am -> 11:45am",
                "no duration typed, no empty parentheses");
        assertEquals(TripEventComposer.composeGroundNotes("", LocalDateTime.of(2028, 5, 3, 9, 0),
                LocalDateTime.of(2028, 5, 3, 10, 0)), "9:00am -> 10:00am (1h)", "no carrier, no colon");
    }
}
