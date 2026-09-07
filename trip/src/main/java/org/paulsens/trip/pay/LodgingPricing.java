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
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Room;
import org.paulsens.trip.model.RoomType;

/**
 * The lodging money rules, pure and deterministic ({@code MoneyMath} style) -- {@code LodgingPricingTest}
 * is the worked-example table. Everything is long cents.
 *
 * <p><b>Night.</b> A calendar date {@code d} with {@code start.date <= d < end.date}
 * ({@link Reservation#nightsBetween}); times are informational.
 *
 * <p><b>Occupancy.</b> For each night, the distinct occupants across every ACTIVE reservation on the same
 * room (an unassigned reservation counts only its own people). A late arriver on a shared room is her own
 * reservation, so the group's nights before she lands price as if she were absent -- because she was.
 *
 * <p><b>PER_PERSON.</b> The nightly price per occupant; plus the offer's single supplement on a night the
 * person is the ONLY occupant of an ASSIGNED room and the reservation has not waived it. Room type never
 * matters, only the occupancy count.
 *
 * <p><b>PER_ROOM.</b> The nightly price split equally among that night's occupants, remainder cents to the
 * lowest person ids ({@link MoneyMath#splitEvenly} on the id-sorted list) so a recompute is idempotent. The
 * sole occupant already pays the whole room, so the supplement is ignored.
 *
 * <p>Only the reservation being priced gets lines; its co-occupants are re-priced by their own recompute.
 */
public final class LodgingPricing {
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d");
    private static final DateTimeFormatter MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy");

    private LodgingPricing() {
    }

    /** One occupant's bill for one reservation: what the ledger row says and how it was arrived at. */
    public record Line(Reservation.Id reservationId, Person.Id personId, long amountCents, int nights,
            int supplementNights, String description) {
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
     * The lines for one reservation.
     *
     * @param activeOnSameRoom every ACTIVE reservation on the reservation's room (including this one when it
     *                         is assigned); ignored when the reservation has no room.
     * @param names            person id to display name, for the "split with" wording.
     */
    public static List<Line> price(final Reservation reservation, final ReservationOffer offer,
            final Accommodation accommodation, final List<Reservation> activeOnSameRoom,
            final Function<Person.Id, String> names) {
        final List<Line> lines = new ArrayList<>();
        if (reservation == null || offer == null || !reservation.isActive()) {
            return lines;
        }
        final List<Reservation> relevant = reservation.isAssigned()
                ? withSelf(activeOnSameRoom, reservation) : List.of(reservation);
        final Map<LocalDate, List<Person.Id>> occupancy = occupancyByNight(relevant);
        final List<LocalDate> nights = nightsOf(reservation.getStart(), reservation.getEnd());
        for (final Person.Id person : reservation.sortedOccupants()) {
            lines.add(lineFor(person, reservation, offer, accommodation, nights, occupancy, names));
        }
        return lines;
    }

    private static Line lineFor(final Person.Id person, final Reservation reservation,
            final ReservationOffer offer, final Accommodation accommodation, final List<LocalDate> nights,
            final Map<LocalDate, List<Person.Id>> occupancy, final Function<Person.Id, String> names) {
        long amount = 0L;
        int supplementNights = 0;
        final Set<Person.Id> sharers = new LinkedHashSet<>();
        for (final LocalDate night : nights) {
            final List<Person.Id> present = occupancy.getOrDefault(night, List.of(person));
            final long price = offer.nightlyPriceCents(night);
            if (offer.isPerPerson()) {
                amount += price;
                if (reservation.isAssigned() && present.size() == 1 && !reservation.isSupplementWaived()
                        && offer.getSingleSupplementCents() > 0) {
                    amount += offer.getSingleSupplementCents();
                    supplementNights++;
                }
            } else {
                final long[] shares = MoneyMath.splitEvenly(price, present.size());
                amount += shares[Math.max(0, present.indexOf(person))];
                sharers.addAll(present);
            }
        }
        sharers.remove(person);
        return new Line(reservation.getId(), person, amount, nights.size(), supplementNights,
                describe(reservation, offer, accommodation, nights, supplementNights, sharers, names));
    }

    /**
     * The ledger row's description. Deterministic on purpose: equality with the stored note is what lets a
     * recompute leave an unchanged row alone.
     */
    static String describe(final Reservation reservation, final ReservationOffer offer,
            final Accommodation accommodation, final List<LocalDate> nights, final int supplementNights,
            final Set<Person.Id> sharers, final Function<Person.Id, String> names) {
        final StringBuilder sb = new StringBuilder("Lodging: ").append(offer.getName());
        if (accommodation != null) {
            sb.append(" — ").append(accommodation.getName());
            final RoomType type = accommodation.roomType(offer.getRoomTypeId());
            if (type != null) {
                sb.append(", ").append(type.getName());
            }
            final Room room = accommodation.room(reservation.getRoomId());
            if (room != null) {
                sb.append(", Room ").append(room.getRoomNumber());
            }
        }
        sb.append(": ").append(nights.size()).append(nights.size() == 1 ? " night " : " nights ")
                .append(dateRange(nights, reservation)).append(", ");
        final String rate = offer.getNightlyPriceOverrides().isEmpty()
                ? MoneyMath.formatCents(offer.getNightlyPriceCents()) + "/night" : "nightly rates";
        if (offer.isPerPerson()) {
            sb.append(rate).append(" per person");
            if (supplementNights > 0) {
                sb.append(", + single supplement ").append(MoneyMath.formatCents(offer.getSingleSupplementCents()))
                        .append(" × ").append(supplementNights)
                        .append(supplementNights == 1 ? " night" : " nights");
            }
        } else {
            sb.append("room rate ").append(rate);
            if (!sharers.isEmpty()) {
                sb.append(" split with ").append(joinNames(sortedIds(sharers), names));
            }
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
    static String joinNames(final List<Person.Id> ids, final Function<Person.Id, String> names) {
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
