package org.paulsens.trip.pay;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PricingModel;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Room;
import org.paulsens.trip.model.RoomType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** The lodging money rules as a worked-example table (the {@code PaymentRecorderTest} idea). */
public class LodgingPricingTest {
    private static final Person.Id ADA = Person.Id.from("a-ada");
    private static final Person.Id BOB = Person.Id.from("b-bob");
    private static final Person.Id CY = Person.Id.from("c-cy");
    private static final LocalDateTime CHECK_IN = LocalDateTime.of(2026, 9, 21, 15, 0);
    private static final Map<Person.Id, String> NAMES = Map.of(ADA, "Ada", BOB, "Bob", CY, "Cy");
    private static final Accommodation PANSION = Accommodation.builder().name("Pansion")
            .roomTypes(List.of(RoomType.builder().id("rt-d").name("Double").maxPeople(2).build()))
            .rooms(List.of(Room.builder().id("r-114").roomNumber("114").roomTypeId("rt-d").build())).build();

    @Test
    public void nightsAndNightListFollowTheCalendarRule() {
        assertEquals(LodgingPricing.nights(CHECK_IN, CHECK_IN.plusDays(4).withHour(2)), 4);
        assertEquals(LodgingPricing.nightsOf(CHECK_IN, CHECK_IN.plusDays(2)),
                List.of(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 22)));
        assertTrue(LodgingPricing.nightsOf(CHECK_IN, CHECK_IN).isEmpty());
        assertTrue(LodgingPricing.nightsOf(CHECK_IN.plusDays(1), CHECK_IN).isEmpty());
    }

    @Test
    public void perPersonFlatRateChargesEveryOccupantEveryNight() {
        final ReservationOffer offer = perPerson(5500, 2000);
        final Reservation res = reservation(List.of(ADA, BOB), 0, 4, "r-114");
        final List<LodgingPricing.Line> lines = LodgingPricing.price(res, offer, PANSION, List.of(res), NAMES::get);
        assertEquals(lines.size(), 2);
        assertEquals(lines.get(0).personId(), ADA, "Id-sorted");
        assertEquals(lines.get(0).amountCents(), 4 * 5500L);
        assertEquals(lines.get(1).amountCents(), 4 * 5500L);
        assertEquals(lines.get(0).supplementNights(), 0, "Two in the room: no supplement");
        assertEquals(lines.get(0).description(), "Lodging: Double room — Pansion, Double, Room 114: 4 nights "
                + "Sep 21–Sep 25, 2026, $55.00/night per person");
    }

    @Test
    public void perPersonOverridesAndTheSupplementOnlyOnSoloNights() {
        final ReservationOffer offer = perPerson(5500, 2000);
        offer.getNightlyPriceOverrides().put("2026-09-22", 7000L);
        // Ada stays 5 nights alone in 114; Bob joins her for nights 3-5 (his own reservation, same room).
        final Reservation ada = reservation(List.of(ADA), 0, 5, "r-114");
        final Reservation bob = reservation(List.of(BOB), 2, 5, "r-114");
        final List<LodgingPricing.Line> lines = LodgingPricing.price(ada, offer, PANSION, List.of(ada, bob),
                NAMES::get);
        assertEquals(lines.size(), 1, "Only the priced reservation's occupants get lines");
        final LodgingPricing.Line line = lines.get(0);
        assertEquals(line.nights(), 5);
        assertEquals(line.supplementNights(), 2, "Nights 1 and 2 are solo");
        assertEquals(line.amountCents(), 4 * 5500L + 7000L + 2 * 2000L);
        assertTrue(line.description().contains("nightly rates per person, + single supplement $20.00 × 2 nights"),
                line.description());

        assertEquals(LodgingPricing.price(bob, offer, PANSION, List.of(ada, bob), NAMES::get).get(0)
                .supplementNights(), 0, "Bob is never alone");
    }

    @Test
    public void supplementIsNeverChargedWhenWaivedUnassignedOrPerRoom() {
        final ReservationOffer offer = perPerson(5500, 2000);
        final Reservation waived = reservation(List.of(ADA), 0, 3, "r-114");
        waived.setWaiveSingleSupplement(true);
        assertEquals(LodgingPricing.price(waived, offer, PANSION, List.of(waived), NAMES::get).get(0)
                .amountCents(), 3 * 5500L, "Waived: the plain per-person rate");
        final Reservation unassigned = reservation(List.of(ADA), 0, 3, null);
        final LodgingPricing.Line line = LodgingPricing.price(unassigned, offer, PANSION, null, NAMES::get).get(0);
        assertEquals(line.amountCents(), 3 * 5500L, "No room yet: no supplement");
        assertEquals(line.description(), "Lodging: Double room — Pansion, Double: 3 nights Sep 21–Sep 24, 2026, "
                + "$55.00/night per person");
        final ReservationOffer perRoom = perRoom(12000);
        perRoom.setSingleSupplementCents(2000);
        final Reservation solo = reservation(List.of(ADA), 0, 1, "r-114");
        assertEquals(LodgingPricing.price(solo, perRoom, PANSION, List.of(solo), NAMES::get).get(0).amountCents(),
                12000L, "PER_ROOM: the sole occupant already pays the whole room");
        assertEquals(LodgingPricing.price(solo, ReservationOffer.builder().nightlyPriceCents(0).build(), PANSION,
                List.of(solo), NAMES::get).get(0).amountCents(), 0L, "A $0 offer prices to zero");
    }

    @Test
    public void perRoomSplitsEachNightAmongThatNightsOccupants() {
        final ReservationOffer offer = perRoom(12000);
        final Reservation pair = reservation(List.of(ADA, BOB), 0, 5, "r-114");
        // Cy, the late arriver, shares the same room for nights 3-5 under her own reservation.
        final Reservation cy = reservation(List.of(CY), 2, 5, "r-114");
        final List<LodgingPricing.Line> pairLines = LodgingPricing.price(pair, offer, PANSION, List.of(pair, cy),
                NAMES::get);
        // Nights 1-2: 12000/2 = 6000 each. Nights 3-5: 12000/3 = 4000 each.
        assertEquals(pairLines.get(0).amountCents(), 2 * 6000L + 3 * 4000L);
        assertEquals(pairLines.get(1).amountCents(), 2 * 6000L + 3 * 4000L);
        assertEquals(pairLines.get(0).description(), "Lodging: Double room — Pansion, Double, Room 114: 5 nights "
                + "Sep 21–Sep 26, 2026, room rate $120.00/night split with Bob and Cy");
        final LodgingPricing.Line cyLine = LodgingPricing.price(cy, offer, PANSION, List.of(pair, cy), NAMES::get)
                .get(0);
        assertEquals(cyLine.amountCents(), 3 * 4000L);
        assertTrue(cyLine.description().endsWith("split with Ada and Bob"), cyLine.description());
        // The room-total invariant, night by night: everyone's share for one night sums to the room price.
        final long allNight3 = 4000L * 3;
        assertEquals(allNight3, 12000L);
    }

    @Test
    public void perRoomOddCentsGoToTheLowestIdsAndUnassignedSplitsAmongOwnOccupants() {
        final ReservationOffer offer = perRoom(10001);
        final Reservation trio = reservation(List.of(CY, BOB, ADA), 0, 1, "r-114");
        final List<LodgingPricing.Line> lines = LodgingPricing.price(trio, offer, PANSION, List.of(trio), NAMES::get);
        assertEquals(lines.get(0).personId(), ADA);
        assertEquals(lines.get(0).amountCents(), 3334L, "The remainder cent lands on the lowest id");
        assertEquals(lines.get(1).amountCents(), 3334L);
        assertEquals(lines.get(2).amountCents(), 3333L);
        assertEquals(lines.stream().mapToLong(LodgingPricing.Line::amountCents).sum(), 10001L);

        final Reservation unassigned = reservation(List.of(ADA, BOB), 0, 2, null);
        final List<LodgingPricing.Line> split = LodgingPricing.price(unassigned, offer, PANSION, List.of(trio),
                NAMES::get);
        assertEquals(split.get(0).amountCents(), 2 * 5001L, "Others on some room are irrelevant when unassigned");
        assertEquals(split.get(1).amountCents(), 2 * 5000L);
    }

    @Test
    public void cancelledAndInactiveReservationsNeverCount() {
        final ReservationOffer offer = perRoom(12000);
        final Reservation pair = reservation(List.of(ADA, BOB), 0, 2, "r-114");
        final Reservation gone = reservation(List.of(CY), 0, 2, "r-114");
        gone.setStatus(Reservation.Status.CANCELLED);
        assertEquals(LodgingPricing.price(pair, offer, PANSION, List.of(pair, gone), NAMES::get).get(0)
                .amountCents(), 2 * 6000L, "A cancelled roommate does not share the cost");
        assertTrue(LodgingPricing.price(gone, offer, PANSION, List.of(pair, gone), NAMES::get).isEmpty(),
                "A cancelled reservation prices to nothing");
        assertTrue(LodgingPricing.price(null, offer, PANSION, List.of(), NAMES::get).isEmpty());
        assertTrue(LodgingPricing.price(pair, null, PANSION, List.of(), NAMES::get).isEmpty());
        final Reservation zeroNights = reservation(List.of(ADA), 0, 0, "r-114");
        final LodgingPricing.Line line = LodgingPricing.price(zeroNights, offer, PANSION, List.of(zeroNights),
                NAMES::get).get(0);
        assertEquals(line.amountCents(), 0L);
        assertTrue(line.description().contains("0 nights Sep 21, 2026"), line.description());
    }

    @Test
    public void occupancyByNightIsIdSortedAndSkipsInactive() {
        final Reservation pair = reservation(List.of(BOB, ADA), 0, 2, "r-114");
        final Reservation cancelled = reservation(List.of(CY), 0, 2, "r-114");
        cancelled.setStatus(Reservation.Status.CANCELLED);
        final Map<LocalDate, List<Person.Id>> occupancy = LodgingPricing.occupancyByNight(List.of(pair, cancelled));
        assertEquals(occupancy.get(LocalDate.of(2026, 9, 21)), List.of(ADA, BOB));
        assertEquals(occupancy.size(), 2);
    }

    @Test
    public void cancellationFeeFixedPercentBothCappedAndShared() {
        assertEquals(LodgingPricing.cancellationFee(ReservationOffer.builder().build(), 10000), 0L, "No fee");
        assertEquals(LodgingPricing.cancellationFee(ReservationOffer.builder().cancelFeeFixedCents(1500L).build(),
                10000), 1500L);
        assertEquals(LodgingPricing.cancellationFee(ReservationOffer.builder().cancelFeeBps(1000).build(), 10001),
                1000L, "10% of $100.01 rounds half-up to $10.00");
        assertEquals(LodgingPricing.cancellationFee(ReservationOffer.builder().cancelFeeBps(2500).build(), 10002),
                2501L, "25% of $100.02 = $25.005 rounds up");
        assertEquals(LodgingPricing.cancellationFee(ReservationOffer.builder().cancelFeeFixedCents(1500L)
                .cancelFeeBps(1000).build(), 10000), 2500L, "Fixed plus percent");
        assertEquals(LodgingPricing.cancellationFee(ReservationOffer.builder().cancelFeeFixedCents(99999L).build(),
                10000), 10000L, "Never more than what was billed");
        assertEquals(LodgingPricing.cancellationFee(ReservationOffer.builder().cancelFeeFixedCents(-5L).build(),
                10000), 0L);
        assertEquals(LodgingPricing.cancellationFee(null, 10000), 0L);
        assertEquals(LodgingPricing.cancellationFee(ReservationOffer.builder().cancelFeeBps(1000).build(), 0), 0L);
        assertEquals(LodgingPricing.feeShares(1001, 2), new long[] {501L, 500L});
        assertEquals(LodgingPricing.feeShares(-5, 2), new long[] {0L, 0L});
    }

    @Test
    public void namesJoinAlphabeticallyAndFallBackToIds() {
        assertEquals(LodgingPricing.joinNames(List.of(CY, ADA, BOB), NAMES::get), "Ada, Bob and Cy");
        assertEquals(LodgingPricing.joinNames(List.of(BOB), NAMES::get), "Bob");
        assertEquals(LodgingPricing.joinNames(List.of(BOB, ADA), id -> null), "a-ada and b-bob");
        assertEquals(LodgingPricing.joinNames(List.of(ADA), null), "a-ada");
    }

    private static ReservationOffer perPerson(final long nightly, final long supplement) {
        return ReservationOffer.builder().name("Double room").roomTypeId("rt-d").pricingModel(PricingModel.PER_PERSON)
                .nightlyPriceCents(nightly).singleSupplementCents(supplement).build();
    }

    private static ReservationOffer perRoom(final long nightly) {
        return ReservationOffer.builder().name("Double room").roomTypeId("rt-d").pricingModel(PricingModel.PER_ROOM)
                .nightlyPriceCents(nightly).build();
    }

    private static Reservation reservation(final List<Person.Id> who, final int fromNight, final int toNight,
            final String roomId) {
        return Reservation.builder().occupants(who).start(CHECK_IN.plusDays(fromNight))
                .end(CHECK_IN.plusDays(toNight).withHour(10)).roomId(roomId).build();
    }
}
