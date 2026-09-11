package org.paulsens.trip.action;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * How a flight or a ground leg becomes an event's title and notes: {@code "PDX -> FCO"} and
 * {@code "Alaska Airlines AS 123: 8:15am -> 11:45am +1 (9h 10m)"}. The components themselves stay on the event
 * ({@code TripEvent.details}); this is only the rendering, so the editor can show a leg's parts again while the
 * itinerary shows one line.
 *
 * <p>Composing used to be a JSFT script inline in {@code trip/edit.xhtml}, where nothing could unit-test it and a
 * null field aborted the whole block. Two bugs came out of the move to Java and are pinned in the tests: the
 * departure time was formatted with {@code K} (hour 0-11), so a 12:15pm departure read {@code 0:15pm}; and the
 * overnight marker compared {@code dayOfYear}, which goes negative across New Year, so a 31-Dec flight lost its
 * {@code +1}.
 */
public final class TripEventComposer {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mma", Locale.ROOT);

    private TripEventComposer() {
    }

    /**
     * {@code "PDX -> FCO"} for a flight, {@code "Medjugorje -> Split"} for a bus: airport codes are uppercased
     * and trimmed so the title does not depend on how the manager typed them; place names keep their case.
     */
    public static String composeRouteTitle(final String from, final String to, final boolean airportCodes) {
        return endpoint(from, airportCodes) + " -> " + endpoint(to, airportCodes);
    }

    /**
     * A flight's notes, with the duration as the manager typed it -- it cannot be derived, because departure and
     * arrival are local times in different zones -- and a {@code +1} when it lands on a later calendar day.
     */
    public static String composeFlightNotes(final String flightNumber, final LocalDateTime start,
            final LocalDateTime end, final String duration) {
        return composeLegNotes(flightNumber, start, end, duration);
    }

    /**
     * A ground leg's notes. Both ends are in one time zone in practice, so the elapsed time is derived from
     * them rather than asked for.
     */
    public static String composeGroundNotes(final String carrier, final LocalDateTime start,
            final LocalDateTime end) {
        return composeLegNotes(carrier, start, end, elapsed(start, end));
    }

    /**
     * {@code "<label>: 8:15am -> 11:45am +1 (9h 10m)"}, dropping each piece that is missing: no label means no
     * colon, no duration means no parentheses, no times means an empty clock -- degrading, never throwing.
     */
    private static String composeLegNotes(final String label, final LocalDateTime start, final LocalDateTime end,
            final String duration) {
        final String head = trimmed(label).isEmpty() ? "" : trimmed(label) + ": ";
        final String overnight = (start != null && end != null && end.toLocalDate().isAfter(start.toLocalDate()))
                ? " +1" : "";
        final String tail = trimmed(duration).isEmpty() ? "" : " (" + trimmed(duration) + ")";
        return head + clock(start) + " -> " + clock(end) + overnight + tail;
    }

    /** {@code "2h 30m"}, {@code "45m"}, {@code "3h"}; empty when either time is missing or the order is wrong. */
    static String elapsed(final LocalDateTime start, final LocalDateTime end) {
        if (start == null || end == null || !end.isAfter(start)) {
            return "";
        }
        final Duration span = Duration.between(start, end);
        final long hours = span.toHours();
        final long minutes = span.toMinutesPart();
        if (hours == 0) {
            return minutes + "m";
        }
        return minutes == 0 ? hours + "h" : hours + "h " + minutes + "m";
    }

    private static String endpoint(final String place, final boolean airportCode) {
        return airportCode ? trimmed(place).toUpperCase(Locale.ROOT) : trimmed(place);
    }

    /** Wall-clock half of a time, lowercased: {@code 8:15am}. */
    private static String clock(final LocalDateTime when) {
        return when == null ? "" : CLOCK.format(when).toLowerCase(Locale.ROOT);
    }

    private static String trimmed(final String value) {
        return value == null ? "" : value.trim();
    }
}
