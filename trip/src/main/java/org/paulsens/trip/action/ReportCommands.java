package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;

/**
 * The rows behind the trip's report pages (the Reports dashboard at {@code admin/reports/index.xhtml} and the
 * pages it links). Every method answers SCALARS built fresh for one request: a report page holds its rows in
 * {@code requestScope} and nothing binds back into them, so there is no row identity to protect.
 *
 * <p>Ground transportation is {@link TripEvent.Type#GROUND}. A leg the bespoke editor wrote carries its parts in
 * {@code TripEvent.details} (from, to, carrier), which is the only reliable source for them as separate columns:
 * the event's title and notes are the COMPOSED rendering, and parsing them back apart was rejected because notes
 * get hand-edited. A leg saved before that editor has no details at all, so a row says so ({@code composed}) and
 * the page falls back to the title and notes it does have.
 */
@Named("reports")
@ApplicationScoped
public class ReportCommands {

    private static final Comparator<TripEvent> BY_START =
            Comparator.comparing(TripEvent::getStart, Comparator.nullsLast(Comparator.naturalOrder()));
    private static final Comparator<Person> BY_NAME = Comparator
            .comparing(Person::getLast, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(Person::getPreferredName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

    private final Supplier<TripCommands> tripSource;

    public ReportCommands() {
        this(() -> org.paulsens.trip.api.Beans.get(TripCommands.class));
    }

    /** Explicit-collaborator constructor: the {@link TripCommands} test seam (Beans.get needs a container). */
    ReportCommands(final Supplier<TripCommands> tripSource) {
        this.tripSource = tripSource;
    }

    /**
     * Every ground leg on the trip, in departure order. The page entry point; the trip is read through
     * {@link TripCommands#getTrip(String)}, so it is site-gated like every other trip read.
     */
    public List<ReportCommands.GroundRow> groundRows(final String tripId) {
        return groundRows(tripSource.get().getTrip(tripId));
    }

    /** The same rows from a trip already in hand. Mutable: a {@code p:dataTable} sorts its value in place. */
    public List<ReportCommands.GroundRow> groundRows(final Trip trip) {
        final List<ReportCommands.GroundRow> rows = new ArrayList<>();
        for (final TripEvent event : groundEvents(trip)) {
            rows.add(rowFor(event));
        }
        return rows;
    }

    /** The dashboard card's metric. Counts legs only, so it never resolves a person. */
    public int groundLegCount(final String tripId) {
        return groundEvents(tripSource.get().getTrip(tripId)).size();
    }

    /** Chronological by start, undated last; a stable sort, so same-minute legs keep the trip's own order. */
    private static List<TripEvent> groundEvents(final Trip trip) {
        if (trip == null) {
            return List.of();
        }
        return trip.getTripEvents().stream()
                .filter(ReportCommands::isGround)
                .sorted(BY_START)
                .toList();
    }

    private static boolean isGround(final TripEvent event) {
        return event != null && event.getType() == TripEvent.Type.GROUND;
    }

    private static ReportCommands.GroundRow rowFor(final TripEvent event) {
        final ReportCommands.GroundRow row = new ReportCommands.GroundRow();
        row.setId(event.getId());
        row.setComposed(event.hasDetails());
        row.setFrom(event.detail(TripEvent.Detail.FROM));
        row.setTo(event.detail(TripEvent.Detail.TO));
        row.setCarrier(event.detail(TripEvent.Detail.CARRIER));
        row.setTitle(event.getTitle());
        row.setNotes(event.getNotes());
        row.setStart(event.getStart());
        row.setEnd(event.getEnd());
        row.setOvernight(isOvernight(event.getStart(), event.getEnd()));
        row.setElapsed(TripEventComposer.elapsed(event.getStart(), event.getEnd()));
        row.setCount(event.getParticipants().size());
        row.setNames(namesOf(event.getParticipants()));
        return row;
    }

    private static boolean isOvernight(final LocalDateTime start, final LocalDateTime end) {
        return start != null && end != null && end.toLocalDate().isAfter(start.toLocalDate());
    }

    /**
     * {@code "Ken Paulsen, Kevin Paulsen, Trinity Paulsen"}: preferred name and last name, ordered by last name
     * then preferred. An id that no longer resolves is left out of the list but still counted, so the head count
     * keeps matching the event's own participant list.
     */
    private static String namesOf(final List<Person.Id> ids) {
        return ids.stream()
                .map(ReportCommands::resolve)
                .flatMap(Optional::stream)
                .sorted(BY_NAME)
                .map(ReportCommands::displayName)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.joining(", "));
    }

    /** The {@code Trip.resolveNames} idiom: the DAO's Optional, so a missing person drops out rather than blanks. */
    private static Optional<Person> resolve(final Person.Id id) {
        return DAO.getInstance().getPerson(id, Cached.YES);
    }

    private static String displayName(final Person person) {
        return (trimmed(person.getPreferredName()) + " " + trimmed(person.getLast())).trim();
    }

    private static String trimmed(final String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * One ground leg, as the report renders it. A no-arg POJO of scalars (the {@code LodgingViews.ItineraryRow}
     * shape): it lives in {@code requestScope} only, but every {@code Serializable} in this package carries a
     * pinned id, and {@code ModelSerializationTest} enforces that.
     */
    @Data
    @NoArgsConstructor
    public static final class GroundRow implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String id;
        /** True when the editor stored the leg's parts; false for a legacy leg, whose title and notes are all. */
        private boolean composed;
        private String from;
        private String to;
        private String carrier;
        private String title;
        private String notes;
        private LocalDateTime start;
        private LocalDateTime end;
        /** The leg arrives on a later calendar day than it left. */
        private boolean overnight;
        /** {@code "2h 30m"}, empty when either end is missing or they are out of order. */
        private String elapsed;
        private int count;
        private String names;
    }
}
