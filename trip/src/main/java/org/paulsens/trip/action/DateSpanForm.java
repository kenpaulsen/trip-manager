package org.paulsens.trip.action;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The dates-and-times half of an edit buffer, shaped for the pages' pickers: a {@code p:datePicker} in range
 * mode hands back the first and last DAY as a list and carries no time of day, so the start and end times ride
 * in two {@code timeOnly} pickers beside it (the Lodging tab's stay row, 2026-09-07; the trip dates and the
 * trip-event dialog since 2026-09-17). The commands, the openers and the tests speak in the two
 * {@link LocalDateTime}s instead. The two shapes stay in sync through the setters, in whatever order they are
 * called -- JSF decodes the pickers in tree order, range then the times -- so no caller has to fold them.
 *
 * <p>The rules: a blank start time means midnight; a blank end time means the start's time on the last day; a
 * last day before the first, or missing, means the first; clearing the range clears both date-times. A
 * viewScope buffer, so nothing is established in a constructor: the session's serializer runs none, and a
 * stream written before a field existed reads it back null, which every accessor tolerates.
 */
@Data
@NoArgsConstructor
public class DateSpanForm implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private LocalDateTime start;
    private LocalDateTime end;
    /** [first day, last day] as the range picker holds them; empty while there is no start. */
    private List<LocalDate> range;
    private LocalTime startTime;
    private LocalTime endTime;

    public List<LocalDate> getRange() {
        if (range == null) {
            range = new ArrayList<>();
        }
        return range;
    }

    public void setRange(final List<LocalDate> picked) {
        range = (picked == null) ? new ArrayList<>() : new ArrayList<>(picked);
        syncFromPickers();
    }

    public void setStartTime(final LocalTime time) {
        startTime = time;
        syncFromPickers();
    }

    public void setEndTime(final LocalTime time) {
        endTime = time;
        syncFromPickers();
    }

    public void setStart(final LocalDateTime when) {
        start = when;
        if (when != null) {
            startTime = when.toLocalTime();
        }
        range = rangeOf(start, end);
    }

    public void setEnd(final LocalDateTime when) {
        end = when;
        if (when != null) {
            endTime = when.toLocalTime();
        }
        range = rangeOf(start, end);
    }

    /** The pickers were written: the date-times follow them. */
    private void syncFromPickers() {
        final List<LocalDate> days = getRange();
        final LocalDate first = days.isEmpty() ? null : days.get(0);
        if (first == null) {
            start = null;
            end = null;
            return;
        }
        final LocalDate lastPicked = days.get(days.size() - 1);
        final LocalDate last = (lastPicked == null || lastPicked.isBefore(first)) ? first : lastPicked;
        final LocalTime from = (startTime == null) ? LocalTime.MIDNIGHT : startTime;
        start = first.atTime(from);
        end = last.atTime(endTime == null ? from : endTime);
    }

    private static List<LocalDate> rangeOf(final LocalDateTime from, final LocalDateTime to) {
        final List<LocalDate> days = new ArrayList<>();
        if (from != null) {
            days.add(from.toLocalDate());
            days.add((to == null || to.isBefore(from)) ? from.toLocalDate() : to.toLocalDate());
        }
        return days;
    }
}
