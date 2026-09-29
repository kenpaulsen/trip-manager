package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.paulsens.trip.action.LodgingViews.RoomingRow;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Room;
import org.paulsens.trip.model.RoomType;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.pay.LodgingPricing;
import org.paulsens.trip.pay.MoneyMath;

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

    /** The per-person table: by name, then a person's stays in date order. */
    private static final Comparator<ReportCommands.InvoicePersonLine> PERSON_ORDER = Comparator
            .comparing(ReportCommands.InvoicePersonLine::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ReportCommands.InvoicePersonLine::getFirstNight);

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

    /**
     * The trip's ROOM INVOICE: what the hotels charge, one section per accommodation, one group per lodging
     * option, and lines whose arithmetic a reader can check: {@code quantity x nights x rate == amount}.
     *
     * <p>A per-person option bills GUESTS, and the single supplement is a line of its own (the guests who were
     * alone, for the nights they were alone), so the two rates are never folded into one figure. A per-room
     * option bills ROOMS: a room shared by a late arriver counts every night anybody sleeps in it. Guests or
     * rooms whose nights differ, or nights the option prices differently, go on separate lines, because that
     * is what keeps every line multiplying out.
     *
     * <p>The rules are {@link LodgingPricing}'s (occupancy per room per night, the waiver, the per-night
     * override), so the invoice total is the sum of the ledger's lodging bills. Cancelled stays and their fees
     * are left out.
     */
    public ReportCommands.RoomInvoice roomInvoice(final String tripId) {
        if (tripId == null || !tripId.equals(tripSource.get().getTrip(tripId).getId())) {
            return new ReportCommands.RoomInvoice(); // Unknown here, or a trip this site does not serve
        }
        final LodgingCommands lodging = lodgingSource.get();
        return roomInvoice(DAO.getInstance().getReservations(tripId, Cached.NO),
                offerId -> lodging.findOffer(tripId, offerId), lodging::findAccommodation, lodging::displayName);
    }

    /** The same invoice from reservations already in hand, naming each person by their id. */
    ReportCommands.RoomInvoice roomInvoice(final List<Reservation> reservations,
            final Function<String, ReservationOffer> offers, final Function<String, Accommodation> accommodations) {
        return roomInvoice(reservations, offers, accommodations, Person.Id::getValue);
    }

    /** The same invoice from reservations already in hand; the lookups take an id's string value. */
    ReportCommands.RoomInvoice roomInvoice(final List<Reservation> reservations,
            final Function<String, ReservationOffer> offers, final Function<String, Accommodation> accommodations,
            final Function<Person.Id, String> names) {
        final List<Reservation> active = reservations.stream().filter(Reservation::isActive).toList();
        final Map<String, List<Reservation>> byRoom = active.stream().filter(Reservation::isAssigned)
                .collect(Collectors.groupingBy(Reservation::getRoomId, LinkedHashMap::new, Collectors.toList()));
        final Map<String, ReportCommands.OfferTally> tallies = new LinkedHashMap<>();
        final ReportCommands.RoomInvoice invoice = new ReportCommands.RoomInvoice();
        for (final Reservation res : active) {
            final ReservationOffer offer = offers.apply(LodgingCommands.idValue(res.getOfferId()));
            if (offer == null) {
                invoice.setUnpriced(invoice.getUnpriced() + 1);
                continue;
            }
            final Accommodation acc = accommodations.apply(LodgingCommands.idValue(
                    res.getAccommodationId() != null ? res.getAccommodationId() : offer.getAccommodationId()));
            tallies.computeIfAbsent(offer.getId().getValue(), k -> new ReportCommands.OfferTally(offer, acc, names))
                    .add(res, byRoom.getOrDefault(res.getRoomId(), List.of(res)));
        }
        return invoiceOf(invoice, tallies.values());
    }

    /** Offers gather under their hotel; each hotel and the whole invoice total their lines. */
    private static ReportCommands.RoomInvoice invoiceOf(final ReportCommands.RoomInvoice invoice,
            final Collection<ReportCommands.OfferTally> tallies) {
        final Map<String, ReportCommands.InvoiceSection> sections = new LinkedHashMap<>();
        final Set<Person.Id> guests = new LinkedHashSet<>();
        final Set<String> rooms = new LinkedHashSet<>();
        for (final ReportCommands.OfferTally tally : tallies) {
            final ReportCommands.InvoiceOffer group = tally.toGroup();
            if (group.getLines().isEmpty()) {
                continue; // Zero-night stays only: nobody is charged for a night nobody slept.
            }
            final ReportCommands.InvoiceSection section = sections.computeIfAbsent(tally.accKey(),
                    k -> sectionFor(tally.acc));
            section.getOffers().add(group);
            section.getPeople().addAll(tally.people);
            guests.addAll(tally.guests);
            rooms.addAll(tally.rooms.keySet());
        }
        for (final ReportCommands.InvoiceSection section : sections.values()) {
            section.getOffers().sort(Comparator.comparing(ReportCommands.InvoiceOffer::getFirstNight)
                    .thenComparing(ReportCommands.InvoiceOffer::getName, String.CASE_INSENSITIVE_ORDER));
            section.setCents(section.getOffers().stream().mapToLong(ReportCommands.InvoiceOffer::getCents).sum());
            section.setAmount(MoneyMath.formatCents(section.getCents()));
            section.setSeveralOptions(section.getOffers().size() > 1);
            section.getPeople().sort(PERSON_ORDER);
            section.setPeopleCents(section.getPeople().stream()
                    .mapToLong(ReportCommands.InvoicePersonLine::getCents).sum());
            section.setPeopleAmount(MoneyMath.formatCents(section.getPeopleCents()));
            invoice.getSections().add(section);
        }
        // Same arrival: by name, so the order does not depend on which reservation happened to be read first.
        invoice.getSections().sort(Comparator.comparing(ReportCommands::firstNightOf)
                .thenComparing(ReportCommands.InvoiceSection::getAccommodation, String.CASE_INSENSITIVE_ORDER));
        invoice.setGuests(guests.size());
        invoice.setRooms(rooms.size());
        invoice.setCents(invoice.getSections().stream().mapToLong(ReportCommands.InvoiceSection::getCents).sum());
        invoice.setAmount(MoneyMath.formatCents(invoice.getCents()));
        return invoice;
    }

    private static LocalDate firstNightOf(final ReportCommands.InvoiceSection section) {
        return section.getOffers().get(0).getFirstNight();
    }

    private static ReportCommands.InvoiceSection sectionFor(final Accommodation acc) {
        final ReportCommands.InvoiceSection section = new ReportCommands.InvoiceSection();
        section.setAccommodation(acc == null ? "Unknown accommodation" : trimmed(acc.getName()));
        return section;
    }

    /**
     * Splits one guest's (or room's) nights by what the option charges for each, adding one to the quantity of
     * the line for that price and exactly those nights. Two guests share a line only when they were billed for
     * the same nights at the same price, which is what makes {@code quantity x nights x rate} the amount.
     */
    private static List<ReportCommands.InvoiceLine> countInto(final Map<String, ReportCommands.InvoiceLine> lines,
            final ReportCommands.LineKind kind, final Set<LocalDate> nights, final Function<LocalDate, Long> price) {
        final List<ReportCommands.InvoiceLine> touched = new ArrayList<>();
        final Map<Long, TreeSet<LocalDate>> byPrice = new LinkedHashMap<>();
        for (final LocalDate night : nights) {
            byPrice.computeIfAbsent(price.apply(night), p -> new TreeSet<>()).add(night);
        }
        for (final Map.Entry<Long, TreeSet<LocalDate>> entry : byPrice.entrySet()) {
            final TreeSet<LocalDate> run = entry.getValue();
            final String key = String.join("|", kind.name(), entry.getKey().toString(), run.first().toString(),
                    run.last().toString(), String.valueOf(run.size()));
            final ReportCommands.InvoiceLine line =
                    lines.computeIfAbsent(key, k -> newLine(kind, entry.getKey(), run));
            line.setQuantity(line.getQuantity() + 1);
            touched.add(line);
        }
        return touched;
    }

    private static ReportCommands.InvoiceLine newLine(final ReportCommands.LineKind kind, final long rateCents,
            final TreeSet<LocalDate> nights) {
        final ReportCommands.InvoiceLine line = new ReportCommands.InvoiceLine();
        line.setKind(kind);
        line.setItem(kind.item);
        line.setRateCents(rateCents);
        line.setRate(MoneyMath.formatCents(rateCents));
        line.setFirstNight(nights.first());
        line.setNights(nights.size());
        line.setDates(stayWindowOf(nights.first(), nights.last().plusDays(1)));
        return line;
    }

    /** {@code "Sep 21 - Sep 26, 2026"}: arrival to departure, the year once unless the stay crosses one. */
    static String stayWindowOf(final LocalDate arrive, final LocalDate depart) {
        return (arrive.getYear() == depart.getYear() ? SHORT_DAY.format(arrive) : DAY.format(arrive))
                + " - " + DAY.format(depart);
    }

    /** What one night costs under the option, in the words the option editor uses. */
    static String rateOf(final ReservationOffer offer) {
        final String nightly = offer.getNightlyPriceOverrides().isEmpty()
                ? MoneyMath.formatCents(offer.getNightlyPriceCents()) : "Varies by night";
        if (!offer.isPerPerson()) {
            return nightly + " per room per night";
        }
        return nightly + " per person per night" + (offer.getSingleSupplementCents() > 0
                ? ", single supplement " + MoneyMath.formatCents(offer.getSingleSupplementCents()) + " per night"
                : "");
    }

    /**
     * A per-person rate as one guest pays it: {@code "$50.00"}, plus {@code " + $10.00 single supplement"} when
     * they were alone, and how many nights that was when it was not all of them.
     */
    static String personRateOf(final ReservationOffer offer, final List<LocalDate> nights,
            final int supplementNights) {
        final Set<Long> prices = nights.stream().map(offer::nightlyPriceCents).collect(Collectors.toSet());
        final String base = prices.size() == 1 ? MoneyMath.formatCents(prices.iterator().next()) : "Varies by night";
        if (supplementNights <= 0) {
            return base;
        }
        return base + " + " + MoneyMath.formatCents(offer.getSingleSupplementCents()) + " single supplement"
                + (supplementNights < nights.size() ? " (" + supplementNights
                        + (supplementNights == 1 ? " night)" : " nights)") : "");
    }

    /**
     * A per-room rate as one occupant pays it, which is their SHARE of the room each night (LodgingPricing's
     * even split, remainder cents to the lowest ids): {@code "$60.00 room, alone"}, {@code "$30.00 (share of a
     * $60.00 room)"}, or, when the share changed because someone arrived or left, {@code "Share of a $60.00
     * room, varies by night"}.
     */
    static String roomShareOf(final ReservationOffer offer, final List<LocalDate> nights, final Person.Id person,
            final Map<LocalDate, List<Person.Id>> occupancy) {
        final Set<Long> prices = new LinkedHashSet<>();
        final Set<Long> shares = new LinkedHashSet<>();
        boolean alone = true;
        for (final LocalDate night : nights) {
            final List<Person.Id> present = occupancy.getOrDefault(night, List.of(person));
            final long price = offer.nightlyPriceCents(night);
            prices.add(price);
            shares.add(MoneyMath.splitEvenly(price, present.size())[Math.max(0, present.indexOf(person))]);
            alone = alone && present.size() == 1;
        }
        final String room = prices.size() == 1 ? "a " + MoneyMath.formatCents(prices.iterator().next()) + " room"
                : "a room priced by night";
        if (alone) {
            return prices.size() == 1 ? MoneyMath.formatCents(prices.iterator().next()) + " room, alone"
                    : "Room alone, priced by night";
        }
        return shares.size() == 1 ? MoneyMath.formatCents(shares.iterator().next()) + " (share of " + room + ")"
                : "Share of " + room + ", varies by night";
    }

    /**
     * {@code "3 Double rooms, 1 Single room"}: the rooms an option's guests sleep in, most-used type first,
     * plus how many reservations still wait for a room. Price never depends on the type, so this is a note
     * under the option rather than a split of its lines.
     */
    static String roomMixOf(final Map<String, Integer> byType, final int unplaced) {
        final List<String> parts = byType.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER)))
                .map(entry -> entry.getValue() + (entry.getKey().isEmpty() ? "" : " " + entry.getKey())
                        + (entry.getValue() == 1 ? " room" : " rooms"))
                .collect(Collectors.toCollection(ArrayList::new));
        if (unplaced > 0) {
            parts.add(unplaced + (unplaced == 1 ? " reservation" : " reservations") + " not yet placed in a room");
        }
        return String.join(", ", parts);
    }

    /** What a line counts and bills. */
    public enum LineKind {
        GUESTS("Lodging", "guest", "guests"),
        SUPPLEMENT("Single supplement", "guest", "guests"),
        ROOMS("Lodging", "room", "rooms");

        private final String item;
        private final String one;
        private final String many;

        LineKind(final String item, final String one, final String many) {
            this.item = item;
            this.one = one;
            this.many = many;
        }

        String unit(final int quantity) {
            return quantity == 1 ? one : many;
        }
    }

    /**
     * One lodging option while the invoice is gathered: every guest's nights, every alone night that carries
     * the supplement, every room's nights, and the room-type mix. Never leaves this class.
     */
    private static final class OfferTally {
        private final ReservationOffer offer;
        private final Accommodation acc;
        private final Map<Person.Id, TreeSet<LocalDate>> guestNights = new LinkedHashMap<>();
        private final Map<Person.Id, TreeSet<LocalDate>> aloneNights = new LinkedHashMap<>();
        /** Room id, or {@code res:<id>} for a reservation not placed in a room, which is billed as its own. */
        private final Map<String, TreeSet<LocalDate>> rooms = new LinkedHashMap<>();
        private final Map<String, Set<String>> roomsByType = new LinkedHashMap<>();
        private final Set<Person.Id> guests = new LinkedHashSet<>();
        private final Map<String, Set<Person.Id>> guestsByRoom = new LinkedHashMap<>();
        /** One row per occupant per stay, for the hotel's per-person table. */
        private final List<ReportCommands.InvoicePersonLine> people = new ArrayList<>();
        private final Function<Person.Id, String> names;

        private OfferTally(final ReservationOffer offer, final Accommodation acc,
                final Function<Person.Id, String> names) {
            this.offer = offer;
            this.acc = acc;
            this.names = names;
        }

        private void add(final Reservation res, final List<Reservation> onRoom) {
            final Room room = (acc == null) ? null : acc.room(res.getRoomId());
            final String roomKey = (room == null) ? "res:" + res.getId().getValue() : room.getId();
            final List<LocalDate> nights = LodgingPricing.nightsOf(res.getStart(), res.getEnd());
            if (nights.isEmpty()) {
                return; // A zero-night stay sleeps nobody anywhere, so it is neither a guest nor a room.
            }
            rooms.computeIfAbsent(roomKey, k -> new TreeSet<>()).addAll(nights);
            guestsByRoom.computeIfAbsent(roomKey, k -> new LinkedHashSet<>()).addAll(res.getOccupants());
            if (room != null) {
                roomsByType.computeIfAbsent(typeName(room.getRoomTypeId()), k -> new LinkedHashSet<>()).add(roomKey);
            }
            guests.addAll(res.getOccupants());
            // Each occupant's charge IS their lodging bill: the same call, against the same room.
            final List<Reservation> context = res.isAssigned() ? onRoom : List.of(res);
            for (final LodgingPricing.Line line : LodgingPricing.price(res, offer, acc, context, null)) {
                people.add(personLine(line, nights, context));
            }
            if (!offer.isPerPerson()) {
                return;
            }
            // Alone is LodgingPricing's test: the only occupant of the room that night, across every stay on it.
            final Map<LocalDate, List<Person.Id>> occupancy =
                    LodgingPricing.occupancyByNight(res.isAssigned() ? onRoom : List.of(res));
            final boolean supplemented = offer.getSingleSupplementCents() > 0 && !res.isSupplementWaived();
            for (final Person.Id person : res.getOccupants()) {
                guestNights.computeIfAbsent(person, k -> new TreeSet<>()).addAll(nights);
                for (final LocalDate night : nights) {
                    if (supplemented && occupancy.getOrDefault(night, List.of(person)).size() == 1) {
                        aloneNights.computeIfAbsent(person, k -> new TreeSet<>()).add(night);
                    }
                }
            }
        }

        private ReportCommands.InvoicePersonLine personLine(final LodgingPricing.Line bill,
                final List<LocalDate> nights, final List<Reservation> context) {
            final ReportCommands.InvoicePersonLine line = new ReportCommands.InvoicePersonLine();
            final String name = names.apply(bill.personId());
            line.setName(name == null || name.isBlank() ? bill.personId().getValue() : name);
            line.setOption(trimmed(offer.getName()));
            line.setFirstNight(nights.get(0));
            line.setDates(stayWindowOf(nights.get(0), nights.get(nights.size() - 1).plusDays(1)));
            line.setNights(bill.nights());
            line.setRate(offer.isPerPerson() ? personRateOf(offer, nights, bill.supplementNights())
                    : roomShareOf(offer, nights, bill.personId(), LodgingPricing.occupancyByNight(context)));
            line.setCents(bill.amountCents());
            line.setAmount(MoneyMath.formatCents(bill.amountCents()));
            return line;
        }

        private String typeName(final String typeId) {
            final RoomType type = acc.roomType(typeId);
            return type == null ? "" : trimmed(type.getName());
        }

        private String accKey() {
            return acc == null ? "" : acc.getId().getValue();
        }

        private ReportCommands.InvoiceOffer toGroup() {
            final Map<String, ReportCommands.InvoiceLine> lines = new LinkedHashMap<>();
            // Per-room lines name how many guests slept in THEIR rooms, so remember which rooms landed where.
            final Map<ReportCommands.InvoiceLine, Set<Person.Id>> sleptOn = new IdentityHashMap<>();
            if (offer.isPerPerson()) {
                guestNights.values().forEach(n -> countInto(lines, LineKind.GUESTS, n, offer::nightlyPriceCents));
                aloneNights.values().forEach(n -> countInto(lines, LineKind.SUPPLEMENT, n,
                        night -> offer.getSingleSupplementCents()));
            } else {
                for (final Map.Entry<String, TreeSet<LocalDate>> room : rooms.entrySet()) {
                    for (final ReportCommands.InvoiceLine line
                            : countInto(lines, LineKind.ROOMS, room.getValue(), offer::nightlyPriceCents)) {
                        sleptOn.computeIfAbsent(line, k -> new LinkedHashSet<>())
                                .addAll(guestsByRoom.get(room.getKey()));
                    }
                }
            }
            final ReportCommands.InvoiceOffer group = new ReportCommands.InvoiceOffer();
            group.setName(trimmed(offer.getName()));
            group.setPricing(rateOf(offer));
            group.setRoomMix(roomMixOf(roomsByType.entrySet().stream().collect(Collectors.toMap(
                    Map.Entry::getKey, e -> e.getValue().size())), unplaced()));
            for (final ReportCommands.InvoiceLine line : lines.values()) {
                finish(line, sleptOn.getOrDefault(line, Set.of()).size());
                group.getLines().add(line);
            }
            group.getLines().sort(Comparator.comparing(ReportCommands.InvoiceLine::getKind)
                    .thenComparing(ReportCommands.InvoiceLine::getFirstNight)
                    .thenComparing(ReportCommands.InvoiceLine::getRateCents));
            group.setFirstNight(group.getLines().isEmpty() ? null : group.getLines().stream()
                    .map(ReportCommands.InvoiceLine::getFirstNight).min(Comparator.naturalOrder()).orElseThrow());
            group.setCents(group.getLines().stream().mapToLong(ReportCommands.InvoiceLine::getCents).sum());
            group.setAmount(MoneyMath.formatCents(group.getCents()));
            return group;
        }

        private int unplaced() {
            return (int) rooms.keySet().stream().filter(key -> key.startsWith("res:")).count();
        }

        /** The amount IS the arithmetic; a per-room line also says how many guests slept in those rooms. */
        private static void finish(final ReportCommands.InvoiceLine line, final int slept) {
            line.setUnit(line.getKind().unit(line.getQuantity()));
            line.setCents(line.getQuantity() * line.getNights() * line.getRateCents());
            line.setAmount(MoneyMath.formatCents(line.getCents()));
            if (line.getKind() == LineKind.ROOMS) {
                line.setNote(slept + (slept == 1 ? " guest" : " guests"));
            }
        }
    }

    /** The whole room invoice: a section per hotel, then the trip's total across all of them. */
    @Data
    @NoArgsConstructor
    public static final class RoomInvoice implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private List<ReportCommands.InvoiceSection> sections = new ArrayList<>();
        /** DISTINCT guests and rooms across every hotel; a room is a placed room or an unplaced reservation. */
        private int guests;
        private int rooms;
        private long cents;
        private String amount = MoneyMath.formatCents(0L);
        /** Active reservations whose lodging option no longer exists, so nothing can price them. */
        private int unpriced;
    }

    /** One hotel's part of the invoice, with its own subtotal. */
    @Data
    @NoArgsConstructor
    public static final class InvoiceSection implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String accommodation;
        private List<ReportCommands.InvoiceOffer> offers = new ArrayList<>();
        private long cents;
        private String amount;
        /** The same money charged per person: each row is that person's lodging bill for one stay. */
        private List<ReportCommands.InvoicePersonLine> people = new ArrayList<>();
        /** More than one lodging option here, so each person row names the one its stay is under. */
        private boolean severalOptions;
        /** Equal to {@link #cents}: both tables add up the same bills, by option and by person. */
        private long peopleCents;
        private String peopleAmount;
    }

    /** One lodging option's lines, under a heading that says how it prices and which rooms it fills. */
    @Data
    @NoArgsConstructor
    public static final class InvoiceOffer implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String name;
        /** {@code "$50.00 per person per night, single supplement $10.00 per night"}. */
        private String pricing;
        /** {@code "3 Double rooms, 1 Single room"}. */
        private String roomMix;
        private LocalDate firstNight;
        private List<ReportCommands.InvoiceLine> lines = new ArrayList<>();
        private long cents;
        private String amount;
    }

    /** One person's charge for one stay at the hotel: what their lodging bill for it says. */
    @Data
    @NoArgsConstructor
    public static final class InvoicePersonLine implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** "Preferred Last". */
        private String name;
        /** The lodging option the stay is under. */
        private String option;
        private LocalDate firstNight;
        private String dates;
        private int nights;
        /** What one night costs THIS person, supplement or room share included. */
        private String rate;
        private long cents;
        private String amount;
    }

    /** One line: {@code quantity} guests or rooms, each billed the same {@code nights} at {@code rate}. */
    @Data
    @NoArgsConstructor
    public static final class InvoiceLine implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private ReportCommands.LineKind kind;
        /** "Lodging" or "Single supplement". */
        private String item;
        private LocalDate firstNight;
        /** Arrival to departure, {@code "Sep 21 - Sep 26, 2026"}. */
        private String dates;
        private int quantity;
        /** "guest", "guests", "room" or "rooms", to read after the quantity. */
        private String unit;
        private int nights;
        private long rateCents;
        private String rate;
        private long cents;
        private String amount;
        /** A per-room line's guest count; empty otherwise. */
        private String note = "";
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
