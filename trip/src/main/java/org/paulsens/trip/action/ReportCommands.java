package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
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

    private final Supplier<TripCommands> tripSource;
    private final Supplier<LodgingCommands> lodgingSource;

    public ReportCommands() {
        this(() -> org.paulsens.trip.api.Beans.get(TripCommands.class),
                () -> org.paulsens.trip.api.Beans.get(LodgingCommands.class));
    }

    /** Explicit-collaborator constructor: the test seam (Beans.get needs a container). */
    public ReportCommands(final Supplier<TripCommands> tripSource, final Supplier<LodgingCommands> lodgingSource) {
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
     * The trip's ROOM INVOICE: what the hotels CHARGE, one section per accommodation, one group per lodging
     * option, and lines whose arithmetic a reader can check: {@code quantity x nights x rate == amount}
     * ({@link RoomInvoiceBuilder}, on the COST side). Since the 2026-10-25 price/cost split this is the
     * hotel's bill, not the participants': the COST side is read strictly ({@code offer.getCost()}, never the
     * price), its cost extras (taxes, fees, coupons) are lines of their own, and a cost concession on a stay is
     * a negative line. When ANY active-stay option in scope has no cost, the invoice is REFUSED ENTIRELY
     * ({@code blocked}, {@code missingCost} naming the options; empty sections, zero totals) rather than drawn
     * with a guess -- owner decision 2026-10-26. The participants' side is the {@link #billedVsCost Billed vs
     * Cost} report.
     */
    public ReportCommands.RoomInvoice roomInvoice(final String tripId) {
        if (!knownTrip(tripId)) {
            return new ReportCommands.RoomInvoice(); // Unknown here, or a trip this site does not serve
        }
        final LodgingCommands lodging = lodgingSource.get();
        return roomInvoice(LodgingPricing.Side.COST, DAO.getInstance().getReservations(tripId, Cached.NO),
                offerId -> lodging.findOffer(tripId, offerId), lodging::findAccommodation, lodging::displayName);
    }

    /**
     * The invoice as ONE caller may see it. Finance viewers ({@code finance}: site admin, the global
     * {@code viewFinances}, {@code tripFinView@trip}) and anyone internal on the trip's lodging get every
     * section. A Hotel Operator gets the invoice REBUILT over the stays at the hotels they operate on this
     * trip, so its counts, its totals and its {@code missingCost} refusal are theirs alone. Anyone else gets an
     * empty invoice.
     *
     * <p>The caller passes {@code finance} rather than this method deciding it: the finance privileges are
     * not lodging privileges, and {@link LodgingAccess} deliberately knows nothing about them. The page
     * passes its {@code requestScope.invoiceInternal}; the PDF servlet passes the same check it admits on.
     */
    public ReportCommands.RoomInvoice roomInvoiceFor(final String tripId, final LodgingAccess access,
            final boolean finance) {
        if (!knownTrip(tripId)) {
            return new ReportCommands.RoomInvoice();
        }
        if (finance || (access != null && access.isInternal())) {
            return roomInvoice(tripId);
        }
        if (access == null || !access.isOperatorHere()) {
            return new ReportCommands.RoomInvoice();
        }
        final LodgingCommands lodging = lodgingSource.get();
        final Function<String, ReservationOffer> offers = offerId -> lodging.findOffer(tripId, offerId);
        final List<Reservation> theirs = staysAt(DAO.getInstance().getReservations(tripId, Cached.NO),
                access.getOperatorAccIds(), offers);
        return roomInvoice(LodgingPricing.Side.COST, theirs, offers, lodging::findAccommodation, lodging::displayName);
    }

    /**
     * Whether the Room Invoice renders for this caller's view of the trip: every active-stay option in scope
     * has a hotel cost. Scope: a Hotel Operator who is not internal on the trip sees their hotels' options; every
     * other caller (internal, finance) the whole trip. A trip with no stays is ready (nothing to refuse).
     */
    public boolean invoiceReady(final String tripId, final LodgingAccess access) {
        return !invoiceInView(tripId, access).isBlocked();
    }

    /**
     * Why the Room Invoice is refused, for this caller's view and in their words ({@link #blockedMessage}); ""
     * when it is ready. The page shows it in place of the invoice, the PDF servlet answers it as a 409.
     */
    public String invoiceBlockedMessage(final String tripId, final LodgingAccess access) {
        final ReportCommands.RoomInvoice invoice = invoiceInView(tripId, access);
        return invoice.isBlocked() ? blockedMessage(invoice.getMissingCost(), operatorOnly(access)) : "";
    }

    /** The invoice over the caller's scope: an operator's hotels, else the whole trip. */
    private ReportCommands.RoomInvoice invoiceInView(final String tripId, final LodgingAccess access) {
        return operatorOnly(access) ? roomInvoiceFor(tripId, access, false) : roomInvoice(tripId);
    }

    /** A Hotel Operator on the trip who is NOT one of its internal people: their view is their hotels. */
    private static boolean operatorOnly(final LodgingAccess access) {
        return access != null && !access.isInternal() && access.isOperatorHere();
    }

    /**
     * The refusal, naming the options without a cost ("Double room (Pansion) and Single room (Berulia)") and
     * how it gets fixed: the trip's Offers tab for an internal reader; for the operator, who may not set an
     * option's cost (2026-10-26), the trip's lodging admins or one of the hotel's rate plans.
     */
    public static String blockedMessage(final List<String> missingCost, final boolean operator) {
        final String where = operator ? "Ask the trip's lodging admin to set it, or to use one of your hotel's"
                + " rate plans for that option."
                : "Set the cost on the trip's Offers tab (Edit the option, tick 'Hotel cost is known'), or use a"
                        + " hotel rate plan for that option.";
        return "No hotel cost is set for " + joinNames(missingCost) + ". " + where;
    }

    /** "A", "A and B", "A, B and C". */
    static String joinNames(final List<String> names) {
        if (names.size() <= 1) {
            return names.isEmpty() ? "" : names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    /** The stays at the given hotels: the stay's own hotel, else its option's; a stay with neither is nobody's. */
    static List<Reservation> staysAt(final List<Reservation> reservations, final Set<String> accIds,
            final Function<String, ReservationOffer> offers) {
        final List<Reservation> theirs = new ArrayList<>();
        for (final Reservation res : reservations) {
            final ReservationOffer offer = offers.apply(LodgingCommands.idValue(res.getOfferId()));
            final String accId = (res.getAccommodationId() != null) ? res.getAccommodationId().getValue()
                    : (offer == null ? null : LodgingCommands.idValue(offer.getAccommodationId()));
            if (accId != null && accIds.contains(accId)) {
                theirs.add(res);
            }
        }
        return theirs;
    }

    /** The same invoice from reservations already in hand, at COST, naming each person by their id. */
    ReportCommands.RoomInvoice roomInvoice(final List<Reservation> reservations,
            final Function<String, ReservationOffer> offers, final Function<String, Accommodation> accommodations) {
        return roomInvoice(LodgingPricing.Side.COST, reservations, offers, accommodations, Person.Id::getValue);
    }

    /** The same invoice from reservations already in hand, at COST; the lookups take an id's string value. */
    ReportCommands.RoomInvoice roomInvoice(final List<Reservation> reservations,
            final Function<String, ReservationOffer> offers, final Function<String, Accommodation> accommodations,
            final Function<Person.Id, String> names) {
        return roomInvoice(LodgingPricing.Side.COST, reservations, offers, accommodations, names);
    }

    /** The invoice on either side from reservations already in hand: what {@code RoomInvoiceReportTest} drives. */
    ReportCommands.RoomInvoice roomInvoice(final LodgingPricing.Side side, final List<Reservation> reservations,
            final Function<String, ReservationOffer> offers, final Function<String, Accommodation> accommodations,
            final Function<Person.Id, String> names) {
        return RoomInvoiceBuilder.build(side, reservations, offers, accommodations, names);
    }

    /**
     * BILLED VS COST: the participants' lodging bills (the PRICE build) beside what the hotels charge (the
     * COST build) for the same stays, per hotel, per lodging option and per person, with the difference. The
     * option rows carry the cost extras and cost concessions in their cost column and price concessions in
     * their billed column; the person rows are net on both sides. Unlike the invoice, an option WITHOUT a cost
     * does not block the report: its row shows cost "not set" ({@code costAssumed}) and contributes 0, and the
     * report counts and names them ({@code missingCost}, {@code missingCostNames}). Internal only (money on
     * both sides).
     */
    public ReportCommands.BilledVsCost billedVsCost(final String tripId) {
        if (!knownTrip(tripId)) {
            return new ReportCommands.BilledVsCost();
        }
        final LodgingCommands lodging = lodgingSource.get();
        return billedVsCost(DAO.getInstance().getReservations(tripId, Cached.NO),
                offerId -> lodging.findOffer(tripId, offerId), lodging::findAccommodation, lodging::displayName);
    }

    /** The same comparison from reservations already in hand. */
    ReportCommands.BilledVsCost billedVsCost(final List<Reservation> reservations,
            final Function<String, ReservationOffer> offers, final Function<String, Accommodation> accommodations,
            final Function<Person.Id, String> names) {
        return RoomInvoiceBuilder.compare(
                RoomInvoiceBuilder.build(LodgingPricing.Side.PRICE, reservations, offers, accommodations, names),
                RoomInvoiceBuilder.build(LodgingPricing.Side.COST, reservations, offers, accommodations, names,
                        false), offers);
    }

    /** getTrip answers a blank trip for an unknown id or one this site does not serve. */
    private boolean knownTrip(final String tripId) {
        return tripId != null && tripId.equals(tripSource.get().getTrip(tripId).getId());
    }

    /** What a line counts and bills; the order is the order lines take within an option. */
    public enum LineKind {
        GUESTS("Lodging", "guest", "guests"),
        SUPPLEMENT("Single supplement", "guest", "guests"),
        ROOMS("Lodging", "room", "rooms"),
        /** A cost extra (tax, fee, coupon): the item is its label; COST side only. */
        EXTRA("Extra", "", ""),
        /** One stay's discount on this side: a negative amount outside the arithmetic. */
        CONCESSION("Concession", "", "");

        private final String item;
        private final String one;
        private final String many;

        LineKind(final String item, final String one, final String many) {
            this.item = item;
            this.one = one;
            this.many = many;
        }

        String item() {
            return item;
        }

        String unit(final int quantity) {
            return quantity == 1 ? one : many;
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
        /** Active reservations whose lodging option no longer exists (or has no schedule on this side). */
        private int unpriced;
        /**
         * COST build: REFUSED, because an active-stay option in scope has no hotel cost. Sections are empty and
         * the totals zero; {@link #missingCost} says which options, the page and the PDF servlet say so.
         */
        private boolean blocked;
        /** The options without a cost, hotel-qualified ("Double room (Pansion)"), by name. */
        private List<String> missingCost = new ArrayList<>();
    }

    /** One hotel's part of the invoice, with its own subtotal. */
    @Data
    @NoArgsConstructor
    public static final class InvoiceSection implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String accommodation;
        /** The hotel's id, or "" for a hotel that no longer exists; what an operator's filter matches on. */
        private String accommodationId = "";
        private List<ReportCommands.InvoiceOffer> offers = new ArrayList<>();
        private long cents;
        private String amount;
        /** The same money charged per person: each row is that person's line for one stay, net of concessions. */
        private List<ReportCommands.InvoicePersonLine> people = new ArrayList<>();
        /** More than one lodging option here, so each person row names the one its stay is under. */
        private boolean severalOptions;
        /** {@link #cents} less {@link #extrasCents}: the extras are the one thing not attributed to a person. */
        private long peopleCents;
        private String peopleAmount;
        /** The cost extras (taxes, fees, coupons) in this hotel's groups; signed, 0 on the PRICE side. */
        private long extrasCents;
        private String extrasAmount = MoneyMath.formatCents(0L);
    }

    /** One lodging option's lines, under a heading that says how it prices and which rooms it fills. */
    @Data
    @NoArgsConstructor
    public static final class InvoiceOffer implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String offerId;
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
        private String reservationId;
        private String personId;
        /** "Preferred Last". */
        private String name;
        /** The lodging option the stay is under. */
        private String option;
        private LocalDate firstNight;
        private String dates;
        private int nights;
        /** What one night costs THIS person, supplement or room share included, "(10% discount)" when one applies. */
        private String rate;
        /** NET of any concession on this side. */
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
        /** "Lodging", "Single supplement", an extra's label, or "Concession". */
        private String item;
        private LocalDate firstNight;
        /** Arrival to departure, {@code "Sep 21 - Sep 26, 2026"}. */
        private String dates;
        private int quantity;
        /** "guest", "guests", "room", "rooms", an extra's "guest-nights"; empty for a concession. */
        private String unit;
        /** 1 for an extra, 0 for a concession (which has no rate either). */
        private int nights;
        private long rateCents;
        private String rate;
        /** Signed: a coupon or a concession is negative. */
        private long cents;
        private String amount;
        /** A per-room line's guest count, or a concession's "Ada, Bob: 10% discount -- reason"; empty otherwise. */
        private String note = "";

        public boolean isExtra() {
            return kind == ReportCommands.LineKind.EXTRA;
        }

        public boolean isConcession() {
            return kind == ReportCommands.LineKind.CONCESSION;
        }
    }

    /** Billed vs Cost: the whole report, a section per hotel, then the trip's totals and the difference. */
    @Data
    @NoArgsConstructor
    public static final class BilledVsCost implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private List<ReportCommands.BilledVsCostSection> sections = new ArrayList<>();
        private long billedCents;
        private long costCents;
        /** Billed less cost: positive when the participants cover the hotel. */
        private long differenceCents;
        private String billed = MoneyMath.formatCents(0L);
        private String cost = MoneyMath.formatCents(0L);
        private String difference = MoneyMath.formatCents(0L);
        /** How many options with active stays have no hotel cost (their rows read "not set" and count 0). */
        private int missingCost;
        /** Those options, hotel-qualified ("Double room (Pansion)"), by name. */
        private List<String> missingCostNames = new ArrayList<>();
    }

    /** One hotel's part of Billed vs Cost: its options, its people, and its own three totals. */
    @Data
    @NoArgsConstructor
    public static final class BilledVsCostSection implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String accommodation;
        private String accommodationId = "";
        private List<ReportCommands.BilledVsCostOption> options = new ArrayList<>();
        private List<ReportCommands.BilledVsCostPerson> people = new ArrayList<>();
        private long billedCents;
        private long costCents;
        private long differenceCents;
        private String billed;
        private String cost;
        private String difference;
    }

    /** One lodging option: its group total on each side (extras and concessions included) and the difference. */
    @Data
    @NoArgsConstructor
    public static final class BilledVsCostOption implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String offerId;
        private String name;
        private long billedCents;
        private long costCents;
        private long differenceCents;
        private String billed;
        private String cost;
        private String difference;
        /** No hotel cost is on file for the option: {@link #cost} reads "not set" and {@link #costCents} is 0. */
        private boolean costAssumed;
    }

    /** One person's stay: their net line on each side and the difference. */
    @Data
    @NoArgsConstructor
    public static final class BilledVsCostPerson implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private String reservationId;
        private String personId;
        private String name;
        private String option;
        private LocalDate firstNight;
        private String dates;
        private int nights;
        private long billedCents;
        private long costCents;
        private long differenceCents;
        private String billed;
        private String cost;
        private String difference;
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
