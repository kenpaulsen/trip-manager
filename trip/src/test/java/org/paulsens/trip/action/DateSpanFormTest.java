package org.paulsens.trip.action;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The range-and-times buffer behind the trip editors' date row and the trip-event dialog: the pickers write a
 * day range and two times, the commands read two date-times, and each side's setters keep the other current.
 */
public class DateSpanFormTest {

    private static final LocalDate MAY_3 = LocalDate.of(2028, 5, 3);
    private static final LocalDate MAY_5 = LocalDate.of(2028, 5, 5);
    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime FIVE_PM = LocalTime.of(17, 0);

    @Test
    public void thePickersFoldIntoTheDateTimesInDecodeOrder() {
        final DateSpanForm span = new DateSpanForm();
        Assert.assertTrue(span.getRange().isEmpty(), "never null, even before anything set it");
        Assert.assertNull(span.getStart());
        span.setRange(List.of(MAY_3, MAY_5));
        span.setStartTime(NINE);
        span.setEndTime(FIVE_PM);
        Assert.assertEquals(span.getStart(), MAY_3.atTime(NINE));
        Assert.assertEquals(span.getEnd(), MAY_5.atTime(FIVE_PM));
    }

    @Test
    public void blankTimesMeanMidnightAndTheStartsTime() {
        final DateSpanForm span = new DateSpanForm();
        span.setRange(List.of(MAY_3, MAY_5));
        Assert.assertEquals(span.getStart(), MAY_3.atStartOfDay(), "no start time: midnight");
        Assert.assertEquals(span.getEnd(), MAY_5.atStartOfDay(), "no end time: the start's time, last day");
        span.setStartTime(NINE);
        Assert.assertEquals(span.getEnd(), MAY_5.atTime(NINE));
        span.setEndTime(FIVE_PM);
        Assert.assertEquals(span.getEnd(), MAY_5.atTime(FIVE_PM));
    }

    @Test
    public void aRangeWithOneDayOrAReversedOneIsThatDay() {
        final DateSpanForm span = new DateSpanForm();
        span.setStartTime(NINE);
        span.setEndTime(FIVE_PM);
        span.setRange(List.of(MAY_3));
        Assert.assertEquals(span.getEnd(), MAY_3.atTime(FIVE_PM), "a single day is first and last");
        span.setRange(Arrays.asList(MAY_3, null));
        Assert.assertEquals(span.getEnd(), MAY_3.atTime(FIVE_PM), "a half-typed range is its first day");
        span.setRange(List.of(MAY_5, MAY_3));
        Assert.assertEquals(span.getStart(), MAY_5.atTime(NINE));
        Assert.assertEquals(span.getEnd(), MAY_5.atTime(FIVE_PM), "a last day before the first is the first");
    }

    @Test
    public void clearingTheRangeClearsTheDateTimes() {
        final DateSpanForm span = new DateSpanForm();
        span.setStart(MAY_3.atTime(NINE));
        span.setEnd(MAY_5.atTime(FIVE_PM));
        span.setRange(new ArrayList<>());
        Assert.assertNull(span.getStart());
        Assert.assertNull(span.getEnd());
        span.setRange(null);
        Assert.assertTrue(span.getRange().isEmpty());
        span.setRange(Arrays.asList((LocalDate) null));
        Assert.assertNull(span.getStart(), "a range whose first day is missing is no range");
        Assert.assertEquals(span.getStartTime(), NINE, "the times are kept for the next pick");
    }

    @Test
    public void theDateTimesFillThePickers() {
        final DateSpanForm span = new DateSpanForm();
        span.setStart(MAY_3.atTime(NINE));
        Assert.assertEquals(span.getRange(), List.of(MAY_3, MAY_3), "no end yet: a one-day range");
        Assert.assertEquals(span.getStartTime(), NINE);
        span.setEnd(MAY_5.atTime(FIVE_PM));
        Assert.assertEquals(span.getRange(), List.of(MAY_3, MAY_5));
        Assert.assertEquals(span.getEndTime(), FIVE_PM);
        span.setEnd(MAY_3.atTime(LocalTime.of(8, 0)));
        Assert.assertEquals(span.getRange(), List.of(MAY_3, MAY_3), "an end before the start shows the start's day");
        span.setEnd(null);
        Assert.assertEquals(span.getRange(), List.of(MAY_3, MAY_3));
        Assert.assertEquals(span.getEndTime(), LocalTime.of(8, 0), "a null end keeps the last time typed");
        span.setStart(null);
        Assert.assertTrue(span.getRange().isEmpty());
        Assert.assertEquals(span.getStartTime(), NINE);
    }

    @Test
    public void theTripEditorsSeedFromTheTripAndFoldBackIntoIt() {
        final TripCommands trip = new TripCommands();
        final Trip theTrip = Trip.builder().title("Span").build();
        theTrip.setStartDate(MAY_3.atTime(NINE));
        theTrip.setEndDate(MAY_5.atTime(FIVE_PM));
        final DateSpanForm span = trip.dateSpanOf(theTrip);
        Assert.assertEquals(span.getRange(), List.of(MAY_3, MAY_5));
        Assert.assertEquals(span.getStartTime(), NINE);
        Assert.assertEquals(span.getEndTime(), FIVE_PM);
        Assert.assertTrue(trip.dateSpanOf(null).getRange().isEmpty(), "no trip: an empty row, not a throw");

        span.setRange(List.of(MAY_5, MAY_5.plusDays(2)));
        trip.applyDateSpan(theTrip, span);
        Assert.assertEquals(theTrip.getStartDate(), MAY_5.atTime(NINE));
        Assert.assertEquals(theTrip.getEndDate(), MAY_5.plusDays(2).atTime(FIVE_PM));

        final LocalDateTime kept = theTrip.getStartDate();
        trip.applyDateSpan(theTrip, new DateSpanForm());
        Assert.assertEquals(theTrip.getStartDate(), kept, "a never-written row changes nothing");
        trip.applyDateSpan(theTrip, null);
        trip.applyDateSpan(null, span);
        Assert.assertEquals(theTrip.getStartDate(), kept);
    }
}
