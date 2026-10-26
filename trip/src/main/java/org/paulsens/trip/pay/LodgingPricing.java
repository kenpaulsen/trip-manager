package org.paulsens.trip.pay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Concession;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.RateSchedule;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Room;
import org.paulsens.trip.model.RoomType;

/**
 * The lodging money rules, pure and deterministic ({@code MoneyMath} style) -- {@code LodgingPricingTest}
 * is the worked-example table. Everything is long cents.
 *
 * <p><b>Two sides.</b> The same rules run on either {@link Side}: {@link #price} reads the offer's
 * participant schedule ({@code offer.getPrice()}) and the reservation's {@code priceConcession}, and feeds the
 * bills; {@link #cost} reads {@code offer.getCost()} (the hotel's schedule; none means NO cost lines, never the
 * price) and {@code costConcession}, and feeds the Room Invoice. A side with no schedule prices to nothing
 * (an empty list, the same contract as a null offer). The single-supplement WAIVER is a price-side concession:
 * the hotel still charges it.
 *
 * <p><b>Night.</b> A calendar date {@code d} with {@code start.date <= d < end.date}
 * ({@link Reservation#nightsBetween}); times are informational.
 *
 * <p><b>Occupancy.</b> For each night, the distinct occupants across every ACTIVE reservation on the same
 * room UNDER THE SAME LODGING OPTION ({@link #underOption}; an unassigned reservation counts only its own
 * people). A late arriver on a shared room is her own reservation, so the group's nights before she lands price
 * as if she were absent -- because she was. A room's nights are priced under one option (every write refuses a
 * second since 2026-10-26), so a stay under another option in the same room -- old data -- neither shares the
 * rate nor counts as company: each option is charged its whole room, as the Room Invoice's option table does.
 *
 * <p><b>PER_PERSON.</b> The nightly rate per occupant; plus the schedule's single supplement on a night the
 * person is the ONLY occupant of an ASSIGNED room and the reservation has not waived it. Room type never
 * matters, only the occupancy count.
 *
 * <p><b>PER_ROOM.</b> The nightly rate split equally among that night's occupants, remainder cents to the
 * lowest person ids ({@link MoneyMath#splitEvenly} on the id-sorted list) so a recompute is idempotent. The
 * sole occupant already pays the whole room, so the supplement is ignored.
 *
 * <p><b>Concession.</b> Applied after the per-night sum, to the side it sits on. PERCENT and COMPLIMENTARY
 * apply to each person's line ({@link Concession#netCents}); PER_NIGHT and TOTAL are amounts off the
 * RESERVATION, split evenly over its id-sorted occupants and taken off each line, floor zero. The line keeps
 * its gross and the amount forgiven. A zero net on the price side writes no bill row (the biller's rule), which
 * is how "complimentary" means "no row".
 *
 * <p>Only the reservation being priced gets lines; its co-occupants are re-priced by their own recompute.
 */
public final class LodgingPricing {
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy");

    private LodgingPricing() {
    }

    /** Which of an offer's two schedules (and a reservation's two concessions) a computation reads. */
    public enum Side {
        /** What the participant pays: {@code offer.price}, {@code reservation.priceConcession}, the bills. */
        PRICE,
        /** What the hotel charges: {@code offer.getCost()}, {@code reservation.costConcession}, the invoice. */
        COST
    }

    /**
     * One occupant's share of one reservation on one side: what the ledger row (or invoice line) says and how
     * it was arrived at. {@code amountCents} is the NET; {@code grossCents} is before the concession and
     * {@code concessionCents} the difference (0 when there is none).
     */
    public record Line(Reservation.Id reservationId, Person.Id personId, long amountCents, int nights,
            int supplementNights, String description, long grossCents, long concessionCents) {

        /** A line with no concession: gross = amount. */
        public Line(final Reservation.Id reservationId, final Person.Id personId, final long amountCents,
                final int nights, final int supplementNights, final String description) {
            this(reservationId, personId, amountCents, nights, supplementNights, description, amountCents, 0L);
        }
    }

    /** @see Reservation#nightsBetween(LocalDateTime, LocalDateTime) */
    public static int nights(final LocalDateTime start, final LocalDateTime end) {
        return Reservation.nightsBetween(start, end);
    }

    /** The calendar dates of a stay's nights, in order: {@code [start.date, end.date)}. */
    public static List<LocalDate> nightsOf(final LocalDateTime start, final LocalDateTime end) {
        final List<LocalDate> nights = new ArrayList<>();
        final int count = nights(start, end);
        for (int i = 0; i < count; i++) {
            nights.add(start.toLocalDate().plusDays(i));
        }
        return nights;
    }

    /** Who sleeps in the room each night, across every ACTIVE reservation given (id-sorted per night). */
    public static Map<LocalDate, List<Person.Id>> occupancyByNight(final List<Reservation> activeOnRoom) {
        final Map<LocalDate, Set<Person.Id>> occupants = new LinkedHashMap<>();
        for (final Reservation res : activeOnRoom) {
            if (!res.isActive()) {
                continue;
            }
            for (final LocalDate night : nightsOf(res.getStart(), res.getEnd())) {
                occupants.computeIfAbsent(night, n -> new LinkedHashSet<>()).addAll(res.getOccupants());
            }
        }
        final Map<LocalDate, List<Person.Id>> sorted = new LinkedHashMap<>();
        for (final Map.Entry<LocalDate, Set<Person.Id>> entry : occupants.entrySet()) {
            sorted.put(entry.getKey(), sortedIds(entry.getValue()));
        }
        return sorted;
    }

    /**
     * The stays on {@code self}'s room that are priced WITH it: the ACTIVE ones under the same lodging option,
     * {@code self} included (added when the list lacks it), in the order given. A PER_ROOM rate is split among
     * these, and a PER_PERSON supplement is judged against them; a stay under another option is neither.
     */
    public static List<Reservation> underOption(final List<Reservation> activeOnRoom, final Reservation self) {
        final List<Reservation> mine = new ArrayList<>();
        for (final Reservation res : withSelf(activeOnRoom, self)) {
            if (res.isActive() && Objects.equals(res.getOfferId(), self.getOfferId())) {
                mine.add(res);
            }
        }
        return mine;
    }

    /**
     * The PRICE lines for one reservation: what its occupants are billed.
     *
     * @param activeOnSameRoom every ACTIVE reservation on the reservation's room (including this one when it
     *                         is assigned); ignored when the reservation has no room.
     * @param names            person id to display name, for the "split with" wording.
     */
    public static List<Line> price(final Reservation reservation, final ReservationOffer offer,
            final Accommodation accommodation, final List<Reservation> activeOnSameRoom,
            final Function<Person.Id, String> names) {
        return lines(Side.PRICE, reservation, offer, accommodation, activeOnSameRoom, names);
    }

    /** The COST lines for one reservation: what the hotel charges us for its occupants. Same parameters. */
    public static List<Line> cost(final Reservation reservation, final ReservationOffer offer,
            final Accommodation accommodation, final List<Reservation> activeOnSameRoom,
            final Function<Person.Id, String> names) {
        return lines(Side.COST, reservation, offer, accommodation, activeOnSameRoom, names);
    }

    /** The lines on one side; empty for a null or inactive reservation, a null offer, or a side with no schedule. */
    public static List<Line> lines(final Side side, final Reservation reservation, final ReservationOffer offer,
            final Accommodation accommodation, final List<Reservation> activeOnSameRoom,
            final Function<Person.Id, String> names) {
        final List<Line> lines = new ArrayList<>();
        if (reservation == null || offer == null || !reservation.isActive()) {
            return lines;
        }
        // Strict on both sides: a cost-less option prices NO cost lines (the invoice refuses it first).
        final RateSchedule schedule = (side == Side.PRICE) ? offer.getPrice() : offer.getCost();
        if (schedule == null) {
            return lines;
        }
        final Concession concession = (side == Side.PRICE) ? reservation.getPriceConcession()
                : reservation.getCostConcession();
        final List<Reservation> relevant = reservation.isAssigned()
                ? underOption(activeOnSameRoom, reservation) : List.of(reservation);
        final Map<LocalDate, List<Person.Id>> occupancy = occupancyByNight(relevant);
        final List<LocalDate> nights = nightsOf(reservation.getStart(), reservation.getEnd());
        final List<Person.Id> occupants = reservation.sortedOccupants();
        final long[] shares = reservationShares(concession, nights.size(), occupants.size());
        for (int i = 0; i < occupants.size(); i++) {
            final Line gross = lineFor(side, occupants.get(i), reservation, offer, schedule, accommodation, nights,
                    occupancy, concession, names);
            lines.add(discounted(gross, concession, (shares == null) ? 0L : shares[i]));
        }
        return lines;
    }

    /**
     * The per-person shares of a RESERVATION-level concession (PER_NIGHT, TOTAL), id-sorted like the lines;
     * null for the per-line kinds and for no concession at all.
     */
    private static long[] reservationShares(final Concession concession, final int nights, final int ways) {
        if (concession == null || ways <= 0) {
            return null;
        }
        switch (concession.getKind()) {
            case PER_NIGHT:
                return MoneyMath.splitEvenly(concession.getAmountCents() * nights, ways);
            case TOTAL:
                return MoneyMath.splitEvenly(concession.getAmountCents(), ways);
            default:
                return null;
        }
    }

    /** The gross line with the concession taken off: per line for PERCENT/COMPLIMENTARY, {@code share} otherwise. */
    private static Line discounted(final Line gross, final Concession concession, final long share) {
        if (concession == null) {
            return gross;
        }
        final long net;
        switch (concession.getKind()) {
            case PERCENT:
            case COMPLIMENTARY:
                net = concession.netCents(gross.amountCents(), gross.nights());
                break;
            default:
                net = Math.max(0L, gross.amountCents() - share);
                break;
        }
        return new Line(gross.reservationId(), gross.personId(), net, gross.nights(), gross.supplementNights(),
                gross.description(), gross.amountCents(), gross.amountCents() - net);
    }

    private static Line lineFor(final Side side, final Person.Id person, final Reservation reservation,
            final ReservationOffer offer, final RateSchedule schedule, final Accommodation accommodation,
            final List<LocalDate> nights, final Map<LocalDate, List<Person.Id>> occupancy,
            final Concession concession, final Function<Person.Id, String> names) {
        long amount = 0L;
        int supplementNights = 0;
        // The waiver is a PRICE-side concession: the hotel still charges the supplement.
        final boolean waived = (side == Side.PRICE) && reservation.isSupplementWaived();
        final Set<Person.Id> sharers = new LinkedHashSet<>();
        for (final LocalDate night : nights) {
            final List<Person.Id> present = occupancy.getOrDefault(night, List.of(person));
            final long rate = schedule.nightlyCents(night);
            if (schedule.isPerPerson()) {
                amount += rate;
                // Alone that night: the sole occupant of their room, or a one-person reservation not yet
                // placed (its own occupants are all that is known; placing them with someone recomputes).
                if (present.size() == 1 && !waived && schedule.getSingleSupplementCents() > 0) {
                    amount += schedule.getSingleSupplementCents();
                    supplementNights++;
                }
            } else {
                final long[] shares = MoneyMath.splitEvenly(rate, present.size());
                amount += shares[Math.max(0, present.indexOf(person))];
                sharers.addAll(present);
            }
        }
        sharers.remove(person);
        return new Line(reservation.getId(), person, amount, nights.size(), supplementNights,
                describe(reservation, offer, schedule, accommodation, nights, supplementNights, sharers, concession,
                        names));
    }

    /**
     * The ledger row's description. Deterministic on purpose: equality with the stored note is what lets a
     * recompute leave an unchanged row alone -- so the text is byte-identical to the pre-split wording unless a
     * concession is present, in which case its {@link Concession#label() label} (never its reason) is appended.
     */
    static String describe(final Reservation reservation, final ReservationOffer offer, final RateSchedule schedule,
            final Accommodation accommodation, final List<LocalDate> nights, final int supplementNights,
            final Set<Person.Id> sharers, final Concession concession, final Function<Person.Id, String> names) {
        final StringBuilder sb = new StringBuilder("Lodging: ").append(offer.getName());
        if (accommodation != null) {
            sb.append(" — ").append(accommodation.getName());
            // The ASSIGNED room's type when there is one (an option may sell several), else the option's first.
            final Room room = accommodation.room(reservation.getRoomId());
            final RoomType type = accommodation.roomType(room != null ? room.getRoomTypeId()
                    : offer.firstRoomTypeId());
            if (type != null) {
                sb.append(", ").append(type.getName());
            }
            if (room != null) {
                sb.append(", Room ").append(room.getRoomNumber());
            }
        }
        sb.append(": ").append(nights.size()).append(nights.size() == 1 ? " night " : " nights ")
                .append(dateRange(nights, reservation)).append(", ");
        final String rate = schedule.hasOverrides()
                ? "nightly rates" : MoneyMath.formatCents(schedule.getNightlyCents()) + "/night";
        if (schedule.isPerPerson()) {
            sb.append(rate).append(" per person");
            if (supplementNights > 0) {
                sb.append(", + single supplement ").append(MoneyMath.formatCents(schedule.getSingleSupplementCents()))
                        .append(" × ").append(supplementNights)
                        .append(supplementNights == 1 ? " night" : " nights");
            }
        } else {
            sb.append("room rate ").append(rate);
            if (!sharers.isEmpty()) {
                sb.append(" split with ").append(joinNames(sortedIds(sharers), names));
            }
        }
        if (concession != null) {
            sb.append(", ").append(concession.label());
        }
        return sb.toString();
    }

    /** "Sep 21–Oct 1, 2026" (or "Sep 21, 2026" for a zero-night stay). */
    static String dateRange(final List<LocalDate> nights, final Reservation reservation) {
        if (nights.isEmpty()) {
            return reservation.getStart() == null ? "" : reservation.getStart().format(MONTH_DAY_YEAR);
        }
        final LocalDate first = nights.get(0);
        final LocalDate last = nights.get(nights.size() - 1).plusDays(1);
        return first.format(MONTH_DAY) + "–" + last.format(MONTH_DAY_YEAR);
    }

    /** "Ada", "Ada and Bob", "Ada, Bob and Cy" -- alphabetical, the payment recorder's wording. */
    public static String joinNames(final List<Person.Id> ids, final Function<Person.Id, String> names) {
        final List<String> list = ids.stream().map(id -> displayName(id, names)).sorted().toList();
        if (list.size() == 1) {
            return list.get(0);
        }
        return String.join(", ", list.subList(0, list.size() - 1)) + " and " + list.get(list.size() - 1);
    }

    private static String displayName(final Person.Id id, final Function<Person.Id, String> names) {
        final String name = (names == null) ? null : names.apply(id);
        return (name == null || name.isBlank()) ? id.getValue() : name;
    }

    /**
     * The cancellation fee for a reservation billed at {@code totalCents}: the offer's fixed fee plus its
     * percentage (basis points, half-up), never more than what was billed and never negative.
     */
    public static long cancellationFee(final ReservationOffer offer, final long totalCents) {
        if (offer == null || totalCents <= 0) {
            return 0L;
        }
        long fee = (offer.getCancelFeeFixedCents() == null) ? 0L : Math.max(0L, offer.getCancelFeeFixedCents());
        if (offer.getCancelFeeBps() != null && offer.getCancelFeeBps() > 0) {
            fee += BigDecimal.valueOf(totalCents).multiply(BigDecimal.valueOf(offer.getCancelFeeBps()))
                    .divide(BigDecimal.valueOf(10_000), 0, RoundingMode.HALF_UP).longValueExact();
        }
        return Math.min(fee, totalCents);
    }

    /** The fee divided among {@code ways} occupants, remainder to the first (id-sorted) positions. */
    public static long[] feeShares(final long feeCents, final int ways) {
        return MoneyMath.splitEvenly(Math.max(0L, feeCents), ways);
    }

    private static List<Reservation> withSelf(final List<Reservation> others, final Reservation self) {
        final List<Reservation> all = new ArrayList<>();
        if (others != null) {
            all.addAll(others);
        }
        if (all.stream().noneMatch(res -> res.getId().equals(self.getId()))) {
            all.add(self);
        }
        return all;
    }

    private static List<Person.Id> sortedIds(final Set<Person.Id> ids) {
        return ids.stream().sorted((a, b) -> a.getValue().compareTo(b.getValue())).collect(Collectors.toList());
    }
}
