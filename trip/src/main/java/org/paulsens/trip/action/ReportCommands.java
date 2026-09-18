package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.paulsens.trip.action.LodgingViews.RoomingRow;
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

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ROOT);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ROOT);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMM d h:mm a", Locale.ROOT);
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ROOT);
    /** The report writes a leg as "here to there"; composed in Java so the page never builds it from parts. */
    private static final String ARROW = " \u2192 ";

    private static final Comparator<LocalDateTime> START_ORDER = Comparator.nullsLast(Comparator.naturalOrder());
    private static final Comparator<TripEvent> BY_START =
            Comparator.comparing(TripEvent::getStart, Comparator.nullsLast(Comparator.naturalOrder()));
    private static final Comparator<Person> BY_NAME = Comparator
            .comparing(Person::getLast, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(Person::getPreferredName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

    private final Supplier<TripCommands> tripSource;
    private final Supplier<LodgingCommands> lodgingSource;

    public ReportCommands() {
        this(() -> org.paulsens.trip.api.Beans.get(TripCommands.class),
                () -> org.paulsens.trip.api.Beans.get(LodgingCommands.class));
    }

    /** Explicit-collaborator constructor: the test seam (Beans.get needs a container). */
    ReportCommands(final Supplier<TripCommands> tripSource, final Supplier<LodgingCommands> lodgingSource) {
        this.tripSource = tripSource;
        this.lodgingSource = lodgingSource;
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
        row.setDates(datesOf(event.getStart(), event.getEnd()));
        row.setTimes(timesOf(event.getStart(), event.getEnd()));
        row.setRoute(routeOf(event));
        row.setElapsed(TripEventComposer.elapsed(event.getStart(), event.getEnd()));
        row.setCount(event.getParticipants().size());
        row.setNames(namesOf(event.getParticipants()));
        return row;
    }

    private static boolean isOvernight(final LocalDateTime start, final LocalDateTime end) {
        return start != null && end != null && end.toLocalDate().isAfter(start.toLocalDate());
    }

    /**
     * The day a leg runs on: {@code "Sep 19, 2026"}, or {@code "Sep 21, 2026 -> Sep 22, 2026"} when it is still
     * going after midnight. The report shows the span itself rather than a "next day" marker beside the arrival,
     * so a reader sees at a glance which legs cost them a night.
     */
    private static String datesOf(final LocalDateTime start, final LocalDateTime end) {
        if (start == null) {
            return "";
        }
        final String first = DAY.format(start);
        return isOvernight(start, end) ? first + ARROW + DAY.format(end) : first;
    }

    /** {@code "12:30 PM -> 4:40 PM"}, or the departure alone when no arrival was ever recorded. */
    private static String timesOf(final LocalDateTime start, final LocalDateTime end) {
        if (start == null) {
            return "";
        }
        return end == null ? CLOCK.format(start) : CLOCK.format(start) + ARROW + CLOCK.format(end);
    }

    /** {@code "Split -> Dubrovnik"} from the stored parts, or the composed title a legacy leg carries instead. */
    private static String routeOf(final TripEvent event) {
        if (!event.hasDetails()) {
            return event.getTitle();
        }
        return trimmed(event.detail(TripEvent.Detail.FROM)) + ARROW + trimmed(event.detail(TripEvent.Detail.TO));
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
     * The rooming list as the printed report wants it: ONE PAGE PER ACCOMMODATION, in the order the trip reaches
     * them, with everyone still waiting for a reservation on a last page of their own.
     *
     * <p>The flat list this builds on emits a row per occupant PER STAY, so a trip using two hotels, or one
     * where somebody changes rooms part-way, has more rows than travellers. Counting rows therefore overstated
     * the party on the old single-page report: 58 rows for 46 people. Every count here is of PEOPLE.
     */
    public List<ReportCommands.RoomingPage> roomingPages(final String tripId) {
        return roomingPages(lodgingSource.get().roomingList(tripId));
    }

    /** The same pages from a rooming list already in hand. */
    public List<ReportCommands.RoomingPage> roomingPages(final List<RoomingRow> rows) {
        final Map<String, List<RoomingRow>> byPlace = new LinkedHashMap<>();
        for (final RoomingRow row : rows) {
            byPlace.computeIfAbsent(keyOf(row), place -> new ArrayList<>()).add(row);
        }
        final List<ReportCommands.RoomingPage> pages = new ArrayList<>();
        for (final Map.Entry<String, List<RoomingRow>> entry : byPlace.entrySet()) {
            pages.add(pageFor(entry.getKey(), entry.getValue()));
        }
        pages.sort(ReportCommands::byArrival);
        return pages;
    }

    /** How many DISTINCT people the trip has beds for, across every hotel and stay. */
    public int roomedPeopleCount(final String tripId) {
        final Set<String> people = new LinkedHashSet<>();
        for (final RoomingRow row : lodgingSource.get().roomingList(tripId)) {
            if (row.isReserved()) {
                people.add(row.getPersonId());
            }
        }
        return people.size();
    }

    private static String keyOf(final RoomingRow row) {
        return row.isReserved() ? trimmed(row.getAccommodation()) : "";
    }

    /** Unreserved last, then earliest arrival first, so the pages print in the order the trip reaches them. */
    private static int byArrival(final ReportCommands.RoomingPage a, final ReportCommands.RoomingPage b) {
        if (a.isReserved() != b.isReserved()) {
            return a.isReserved() ? -1 : 1;
        }
        final int byStart = START_ORDER.compare(a.getFirstStart(), b.getFirstStart());
        return (byStart != 0) ? byStart
                : trimmed(a.getAccommodation()).compareToIgnoreCase(trimmed(b.getAccommodation()));
    }

    private static ReportCommands.RoomingPage pageFor(final String place, final List<RoomingRow> rows) {
        final ReportCommands.RoomingPage page = new ReportCommands.RoomingPage();
        page.setReserved(!place.isEmpty());
        page.setAccommodation(place.isEmpty() ? "Not yet reserved" : place);
        final Set<String> people = new LinkedHashSet<>();
        LocalDateTime first = null;
        LocalDateTime last = null;
        String lastRoom = null;
        boolean stripe = false;
        for (final RoomingRow row : rows) {
            people.add(row.getPersonId());
            first = earlier(first, row.getStart());
            last = later(last, row.getEnd());
            if (!trimmed(row.getRoom()).equalsIgnoreCase(trimmed(lastRoom))) {
                stripe = !stripe;
            }
            lastRoom = row.getRoom();
            page.getLines().add(lineFor(row, stripe));
        }
        page.setPeople(people.size());
        page.setStays(rows.size());
        page.setFirstStart(first);
        page.setDates(windowOf(first, last));
        page.setSummary(summaryOf(page.getDates(), people.size(), rows.size()));
        return page;
    }

    private static ReportCommands.RoomingLine lineFor(final RoomingRow row, final boolean stripe) {
        final ReportCommands.RoomingLine line = new ReportCommands.RoomingLine();
        line.setName(row.getName());
        line.setCell(row.getCell());
        line.setRoom(trimmed(row.getRoom()));
        line.setRoomType(trimmed(row.getRoomType()));
        line.setReserved(row.isReserved());
        line.setStripe(stripe);
        line.setDates(stayOf(row.getStart(), row.getEnd()));
        return line;
    }

    /**
     * The line under a hotel's name: what it covers, and how many stays when that is not simply how many
     * people, so a reader who counts the rows and gets a different number sees why before they wonder.
     */
    private static String summaryOf(final String dates, final int people, final int stays) {
        return (stays == people) ? dates : (dates.isEmpty() ? "" : dates + ", ") + stays + " stays";
    }

    /** {@code "Sep 21 - Oct 1"}: what the whole page covers, for the heading under the hotel's name. */
    private static String windowOf(final LocalDateTime first, final LocalDateTime last) {
        if (first == null) {
            return "";
        }
        final String from = SHORT_DAY.format(first);
        return (last == null || last.toLocalDate().equals(first.toLocalDate())) ? from
                : from + " - " + SHORT_DAY.format(last);
    }

    /** {@code "Sep 21 11:00 PM to Oct 1 5:00 AM"}: one person's stay, under their name. */
    private static String stayOf(final LocalDateTime start, final LocalDateTime end) {
        if (start == null) {
            return "";
        }
        return end == null ? STAMP.format(start) : STAMP.format(start) + " to " + STAMP.format(end);
    }

    private static LocalDateTime earlier(final LocalDateTime held, final LocalDateTime seen) {
        if (seen == null) {
            return held;
        }
        return (held == null || seen.isBefore(held)) ? seen : held;
    }

    private static LocalDateTime later(final LocalDateTime held, final LocalDateTime seen) {
        if (seen == null) {
            return held;
        }
        return (held == null || seen.isAfter(held)) ? seen : held;
    }

    /** One accommodation's page of the rooming report: its own heading, its own table, its own sheet of paper. */
    @Data
    @NoArgsConstructor
    public static final class RoomingPage implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** The hotel's name, or "Not yet reserved" for the people who have no bed yet. */
        private String accommodation;
        private boolean reserved;
        /** DISTINCT people, which is not the number of lines: one person can hold several stays here. */
        private int people;
        private int stays;
        /** What the page covers, {@code "Sep 21 - Oct 1"}. */
        private String dates;
        /** {@link #dates}, plus the stay count when it differs from the head count. */
        private String summary;
        /** The earliest arrival, which is the order the pages print in. */
        private LocalDateTime firstStart;
        private List<ReportCommands.RoomingLine> lines = new ArrayList<>();
    }

    /** One line of a rooming page: a person in a room for a stay. */
    @Data
    @NoArgsConstructor
    public static final class RoomingLine implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String name;
        private String cell;
        private String room;
        private String roomType;
        private String dates;
        private boolean reserved;
        /** Alternating per room, so a shared room reads as one block on paper. */
        private boolean stripe;
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
        /** The leg arrives on a later calendar day than it left, which is what makes {@code dates} a span. */
        private boolean overnight;
        /** The day, or the span of days, the leg runs on. */
        private String dates;
        /** Departure and arrival clock times. */
        private String times;
        /** Where it goes: the stored endpoints, or a legacy leg's own title. */
        private String route;
        /** {@code "2h 30m"}, empty when either end is missing or they are out of order. */
        private String elapsed;
        private int count;
        private String names;
    }
}
