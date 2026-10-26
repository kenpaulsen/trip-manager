package org.paulsens.trip.pay;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Concession;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PricingModel;
import org.paulsens.trip.model.RateSchedule;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Room;
import org.paulsens.trip.model.RoomType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
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
    public void supplementFollowsBeingAloneAndIsNeverChargedWhenWaivedOrPerRoom() {
        final ReservationOffer offer = perPerson(5500, 2000);
        final Reservation waived = reservation(List.of(ADA), 0, 3, "r-114");
        waived.setWaiveSingleSupplement(true);
        assertEquals(LodgingPricing.price(waived, offer, PANSION, List.of(waived), NAMES::get).get(0)
                .amountCents(), 3 * 5500L, "Waived: the plain per-person rate");
        // Not placed yet, and alone on the reservation: alone every night until someone joins the room.
        final Reservation unassigned = reservation(List.of(ADA), 0, 3, null);
        final LodgingPricing.Line line = LodgingPricing.price(unassigned, offer, PANSION, null, NAMES::get).get(0);
        assertEquals(line.amountCents(), 3 * 7500L, "No room yet, one person: the supplement applies");
        assertEquals(line.description(), "Lodging: Double room — Pansion, Double: 3 nights Sep 21–Sep 24, 2026, "
                + "$55.00/night per person, + single supplement $20.00 × 3 nights");
        unassigned.setWaiveSingleSupplement(true);
        assertEquals(LodgingPricing.price(unassigned, offer, PANSION, null, NAMES::get).get(0).amountCents(),
                3 * 5500L, "...and the waiver removes it again");
        final Reservation pair = reservation(List.of(ADA, BOB), 0, 3, null);
        assertEquals(LodgingPricing.price(pair, offer, PANSION, null, NAMES::get).get(0).amountCents(),
                3 * 5500L, "Two on one unplaced reservation are not alone");
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

    /**
     * Owner decision 2026-10-26: a room's nights are priced under ONE option. Every write refuses a second, so a
     * mixed room is old data -- and each stay in it then pays its own option's whole room, which is what the Room
     * Invoice's option table charges, so the two tables agree.
     */
    @Test
    public void aRoomHoldingTwoOptionsChargesEachStayItsOwnWholeRoom() {
        final ReservationOffer shared = perRoom(12000);
        final ReservationOffer other = perRoom(8000);
        final Reservation ada = under(shared, List.of(ADA), 0, 2);
        final Reservation bob = under(other, List.of(BOB), 0, 2);
        final Reservation cy = under(shared, List.of(CY), 0, 2);
        final List<Reservation> room = List.of(ada, bob, cy);
        assertEquals(LodgingPricing.underOption(room, ada), List.of(ada, cy));
        assertEquals(LodgingPricing.underOption(List.of(bob), ada), List.of(ada), "self is added when absent");
        final LodgingPricing.Line adaLine = LodgingPricing.price(ada, shared, PANSION, room, NAMES::get).get(0);
        assertEquals(adaLine.amountCents(), 2 * 6000L, "split with Cy under the same option, never with Bob");
        assertEquals(adaLine.description(), "Lodging: Double room — Pansion, Double, Room 114: 2 nights "
                + "Sep 21–Sep 23, 2026, room rate $120.00/night split with Cy");
        final LodgingPricing.Line bobLine = LodgingPricing.price(bob, other, PANSION, room, NAMES::get).get(0);
        assertEquals(bobLine.amountCents(), 2 * 8000L, "Bob pays his own option's whole room");
        assertFalse(bobLine.description().contains("split with"), bobLine.description());
        final ReservationOffer perPerson = perPerson(5500, 2000);
        final Reservation solo = under(perPerson, List.of(ADA), 0, 2);
        assertEquals(LodgingPricing.price(solo, perPerson, PANSION, List.of(solo, bob), NAMES::get).get(0)
                .supplementNights(), 2, "somebody under another option is not company either");
        assertEquals(LodgingPricing.price(solo, perPerson, PANSION, List.of(solo, under(perPerson, List.of(CY), 0, 2)),
                NAMES::get).get(0).supplementNights(), 0, "...where a roommate under the same option is");
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


    // ------------------------------------------------------------------ price vs cost, concessions

    @Test
    public void linesWithoutAConcessionCarryTheirGrossAndNothingForgiven() {
        final ReservationOffer offer = perRoom(12000);
        final Reservation pair = reservation(List.of(ADA, BOB), 0, 2, "r-114");
        final LodgingPricing.Line line = LodgingPricing.price(pair, offer, PANSION, List.of(pair), NAMES::get).get(0);
        assertEquals(line.grossCents(), line.amountCents());
        assertEquals(line.concessionCents(), 0L);
        final LodgingPricing.Line legacy = new LodgingPricing.Line(pair.getId(), ADA, 500L, 2, 0, "Lodging: x");
        assertEquals(legacy.grossCents(), 500L, "The 6-arg constructor is a line with no concession");
        assertEquals(legacy.concessionCents(), 0L);
    }

    @Test
    public void costSideReadsItsOwnScheduleAndModel() {
        final ReservationOffer offer = perPerson(5500, 2000);
        offer.setCost(RateSchedule.builder().pricingModel(PricingModel.PER_ROOM).nightlyCents(8000).build());
        final Reservation pair = reservation(List.of(ADA, BOB), 0, 4, "r-114");
        final List<LodgingPricing.Line> price = LodgingPricing.price(pair, offer, PANSION, List.of(pair), NAMES::get);
        final List<LodgingPricing.Line> cost = LodgingPricing.cost(pair, offer, PANSION, List.of(pair), NAMES::get);
        assertEquals(price.get(0).amountCents(), 4 * 5500L, "Participants pay per person");
        assertEquals(cost.get(0).amountCents(), 4 * 4000L, "The hotel charges the room, split two ways");
        assertEquals(cost.get(1).amountCents(), 4 * 4000L);
        assertEquals(cost.get(0).description(), "Lodging: Double room — Pansion, Double, Room 114: 4 nights "
                + "Sep 21–Sep 25, 2026, room rate $80.00/night split with Bob");
        assertEquals(LodgingPricing.lines(LodgingPricing.Side.COST, pair, offer, PANSION, List.of(pair), NAMES::get),
                cost, "cost(...) is lines(COST, ...)");
        assertEquals(LodgingPricing.lines(LodgingPricing.Side.PRICE, pair, offer, PANSION, List.of(pair), NAMES::get),
                price);
    }

    @Test
    public void noCostScheduleMeansNoCostLinesNeverThePrice() {
        final ReservationOffer offer = perRoom(12000);
        offer.setCost(null);
        final Reservation pair = reservation(List.of(ADA, BOB), 0, 5, "r-114");
        final Reservation cy = reservation(List.of(CY), 2, 5, "r-114");
        assertTrue(LodgingPricing.cost(pair, offer, PANSION, List.of(pair, cy), NAMES::get).isEmpty(),
                "No cost schedule: nothing prices the hotel side; the invoice refuses the trip instead");
        assertEquals(LodgingPricing.price(pair, offer, PANSION, List.of(pair, cy), NAMES::get).size(), 2,
                "the bills are untouched by a missing cost");
    }

    @Test
    public void theSupplementWaiverIsAPriceSideConcessionOnly() {
        final ReservationOffer offer = perPerson(5500, 2000);
        final Reservation solo = reservation(List.of(ADA), 0, 3, "r-114");
        solo.setWaiveSingleSupplement(true);
        final LodgingPricing.Line price = LodgingPricing.price(solo, offer, PANSION, List.of(solo), NAMES::get).get(0);
        final LodgingPricing.Line cost = LodgingPricing.cost(solo, offer, PANSION, List.of(solo), NAMES::get).get(0);
        assertEquals(price.amountCents(), 3 * 5500L, "Waived for the participant");
        assertEquals(price.supplementNights(), 0);
        assertEquals(cost.amountCents(), 3 * 7500L, "The hotel still charges it");
        assertEquals(cost.supplementNights(), 3);
        assertTrue(cost.description().endsWith("+ single supplement $20.00 × 3 nights"), cost.description());
    }

    @Test
    public void anUnpricedOfferPricesToNothingAndACostOnlyOfferInvoicesOnly() {
        final Reservation pair = reservation(List.of(ADA, BOB), 0, 2, "r-114");
        final ReservationOffer unpriced = ReservationOffer.builder().name("Double room").roomTypeId("rt-d").build();
        assertFalse(unpriced.isPriced());
        assertTrue(LodgingPricing.price(pair, unpriced, PANSION, List.of(pair), NAMES::get).isEmpty());
        assertTrue(LodgingPricing.cost(pair, unpriced, PANSION, List.of(pair), NAMES::get).isEmpty(),
                "Nothing to assume a cost from either");
        unpriced.setCost(RateSchedule.builder().nightlyCents(8000).build());
        assertTrue(LodgingPricing.price(pair, unpriced, PANSION, List.of(pair), NAMES::get).isEmpty(),
                "Still nothing to bill");
        assertEquals(LodgingPricing.cost(pair, unpriced, PANSION, List.of(pair), NAMES::get).get(0).amountCents(),
                2 * 4000L, "...but the hotel's side is known");
    }

    @Test
    public void percentConcessionAppliesPerLineOnItsOwnSideAndNamesItself() {
        final ReservationOffer offer = perRoom(10001);
        final Reservation trio = reservation(List.of(CY, BOB, ADA), 0, 1, "r-114");
        trio.setPriceConcession(Concession.builder().kind(Concession.Kind.PERCENT).percentBps(1000)
                .reason("Loyal pilgrims").build());
        final List<LodgingPricing.Line> price = LodgingPricing.price(trio, offer, PANSION, List.of(trio), NAMES::get);
        // Gross 3334 / 3334 / 3333 (the odd cent to the lowest id); 10% off each, half-up.
        assertEquals(price.get(0).amountCents(), 3001L, "3000.6 rounds up");
        assertEquals(price.get(0).grossCents(), 3334L);
        assertEquals(price.get(0).concessionCents(), 333L);
        assertEquals(price.get(1).amountCents(), 3001L);
        assertEquals(price.get(2).amountCents(), 3000L, "2999.7 rounds up");
        assertEquals(price.get(0).description(), "Lodging: Double room — Pansion, Double, Room 114: 1 night "
                + "Sep 21–Sep 22, 2026, room rate $100.01/night split with Bob and Cy, 10% discount");
        assertFalse(price.get(0).description().contains("Loyal"), "The reason never reaches a bill note");
        final List<LodgingPricing.Line> cost = LodgingPricing.cost(trio, offer, PANSION, List.of(trio), NAMES::get);
        assertEquals(cost.get(0).amountCents(), 3334L, "A price concession does not touch the hotel's side");
        assertFalse(cost.get(0).description().contains("discount"), cost.get(0).description());
        trio.setCostConcession(Concession.builder().kind(Concession.Kind.PERCENT).percentBps(5000).build());
        final List<LodgingPricing.Line> halved = LodgingPricing.cost(trio, offer, PANSION, List.of(trio), NAMES::get);
        assertEquals(halved.get(0).amountCents(), 1667L);
        assertEquals(halved.get(2).amountCents(), 1667L, "1666.5 rounds half-up");
        assertTrue(halved.get(0).description().endsWith(", 50% discount"), halved.get(0).description());
        assertEquals(LodgingPricing.price(trio, offer, PANSION, List.of(trio), NAMES::get).get(0).amountCents(), 3001L,
                "...and the cost concession does not touch the bills");
    }

    @Test
    public void perNightConcessionIsAReservationAmountSplitAcrossTheOccupants() {
        final ReservationOffer offer = perPerson(5500, 0);
        final Reservation pair = reservation(List.of(ADA, BOB), 0, 4, "r-114");
        pair.setPriceConcession(Concession.builder().kind(Concession.Kind.PER_NIGHT).amountCents(500).build());
        final List<LodgingPricing.Line> price = LodgingPricing.price(pair, offer, PANSION, List.of(pair), NAMES::get);
        // $5.00 x 4 nights = $20.00 off the RESERVATION, $10.00 off each of the two lines.
        assertEquals(price.get(0).amountCents(), 4 * 5500L - 1000L);
        assertEquals(price.get(1).amountCents(), 4 * 5500L - 1000L);
        assertEquals(price.get(0).grossCents(), 4 * 5500L);
        assertEquals(price.get(0).concessionCents(), 1000L);
        assertTrue(price.get(0).description().endsWith(", $5.00/night discount"), price.get(0).description());
        // An odd split hands the remainder cent to the lowest id, like every other split here.
        final Reservation odd = reservation(List.of(BOB, ADA), 0, 3, "r-114");
        odd.setPriceConcession(Concession.builder().kind(Concession.Kind.PER_NIGHT).amountCents(1).build());
        final List<LodgingPricing.Line> lines = LodgingPricing.price(odd, offer, PANSION, List.of(odd), NAMES::get);
        assertEquals(lines.get(0).personId(), ADA);
        assertEquals(lines.get(0).concessionCents(), 2L);
        assertEquals(lines.get(1).concessionCents(), 1L);
        assertEquals(lines.stream().mapToLong(LodgingPricing.Line::concessionCents).sum(), 3L);
        // On the COST side the same kind reads the hotel's schedule and the cost concession.
        odd.setCostConcession(Concession.builder().kind(Concession.Kind.PER_NIGHT).amountCents(2000).build());
        final List<LodgingPricing.Line> cost = LodgingPricing.cost(odd, offer, PANSION, List.of(odd), NAMES::get);
        assertEquals(cost.get(0).amountCents(), 3 * 5500L - 3000L, "$20.00 x 3 nights, half each");
        assertEquals(cost.get(1).amountCents(), 3 * 5500L - 3000L);
    }

    @Test
    public void totalConcessionSplitsOnceAndFloorsEachLineAtZero() {
        final ReservationOffer offer = perPerson(5500, 0);
        final Reservation pair = reservation(List.of(ADA, BOB), 0, 2, "r-114");
        pair.setPriceConcession(Concession.builder().kind(Concession.Kind.TOTAL).amountCents(3001).build());
        final List<LodgingPricing.Line> price = LodgingPricing.price(pair, offer, PANSION, List.of(pair), NAMES::get);
        assertEquals(price.get(0).amountCents(), 11000L - 1501L, "The odd cent of the discount goes to Ada");
        assertEquals(price.get(1).amountCents(), 11000L - 1500L);
        assertTrue(price.get(0).description().endsWith(", $30.01 discount"), price.get(0).description());
        final Reservation solo = reservation(List.of(ADA), 0, 1, "r-114");
        solo.setCostConcession(Concession.builder().kind(Concession.Kind.TOTAL).amountCents(20000).build());
        final LodgingPricing.Line cost = LodgingPricing.cost(solo, perRoom(12000), PANSION, List.of(solo), NAMES::get)
                .get(0);
        assertEquals(cost.amountCents(), 0L, "Never below zero");
        assertEquals(cost.grossCents(), 12000L);
        assertEquals(cost.concessionCents(), 12000L, "Only what was there is forgiven");
        assertTrue(cost.description().endsWith(", $200.00 discount"), cost.description());
        assertEquals(LodgingPricing.price(solo, perRoom(12000), PANSION, List.of(solo), NAMES::get).get(0)
                .amountCents(), 12000L, "The participant still pays: the hotel's gift was to us");
    }

    @Test
    public void complimentaryZeroesTheLineSupplementIncludedOnEitherSide() {
        final ReservationOffer offer = perPerson(5500, 2000);
        final Reservation solo = reservation(List.of(ADA), 0, 2, "r-114");
        solo.setPriceConcession(Concession.builder().kind(Concession.Kind.COMPLIMENTARY).reason("Guide").build());
        final LodgingPricing.Line price = LodgingPricing.price(solo, offer, PANSION, List.of(solo), NAMES::get).get(0);
        assertEquals(price.amountCents(), 0L, "Zero net: the biller writes no row");
        assertEquals(price.grossCents(), 2 * 7500L, "What it would have been, supplement included");
        assertEquals(price.concessionCents(), 2 * 7500L);
        assertEquals(price.description(), "Lodging: Double room — Pansion, Double, Room 114: 2 nights "
                + "Sep 21–Sep 23, 2026, $55.00/night per person, + single supplement $20.00 × 2 nights, "
                + "complimentary");
        final LodgingPricing.Line cost = LodgingPricing.cost(solo, offer, PANSION, List.of(solo), NAMES::get).get(0);
        assertEquals(cost.amountCents(), 2 * 7500L, "The hotel did not comp it");
        solo.setCostConcession(Concession.builder().kind(Concession.Kind.COMPLIMENTARY).build());
        assertEquals(LodgingPricing.cost(solo, offer, PANSION, List.of(solo), NAMES::get).get(0).amountCents(), 0L);
    }

    @Test
    public void everyUnconcessionedDescriptionIsByteIdenticalToThePreSplitWording() {
        // The recompute compares amount AND note; a changed note would rewrite every bill row on deploy.
        final ReservationOffer offer = perPerson(5500, 2000);
        final Reservation res = reservation(List.of(ADA, BOB), 0, 4, "r-114");
        res.setPriceConcession(null);
        final String note = LodgingPricing.price(res, offer, PANSION, List.of(res), NAMES::get).get(0).description();
        assertEquals(note, "Lodging: Double room — Pansion, Double, Room 114: 4 nights Sep 21–Sep 25, 2026, "
                + "$55.00/night per person");
        assertFalse(note.contains("discount") || note.contains("complimentary"));
    }

    /** Priced AND costed at the same numbers: the cost-side cases read a stated cost, never a fallback. */
    private static ReservationOffer perPerson(final long nightly, final long supplement) {
        final ReservationOffer offer = ReservationOffer.builder().name("Double room").roomTypeId("rt-d")
                .pricingModel(PricingModel.PER_PERSON).nightlyPriceCents(nightly).singleSupplementCents(supplement)
                .build();
        offer.setCost(new RateSchedule(PricingModel.PER_PERSON, nightly, null, supplement));
        return offer;
    }

    private static ReservationOffer perRoom(final long nightly) {
        final ReservationOffer offer = ReservationOffer.builder().name("Double room").roomTypeId("rt-d")
                .pricingModel(PricingModel.PER_ROOM).nightlyPriceCents(nightly).build();
        offer.setCost(new RateSchedule(PricingModel.PER_ROOM, nightly, null, 0L));
        return offer;
    }

    /** A stay in 114 under a NAMED option: the one-option-per-room rule compares offer ids. */
    private static Reservation under(final ReservationOffer offer, final List<Person.Id> who, final int fromNight,
            final int toNight) {
        final Reservation res = reservation(who, fromNight, toNight, "r-114");
        res.setOfferId(offer.getId());
        return res;
    }

    private static Reservation reservation(final List<Person.Id> who, final int fromNight, final int toNight,
            final String roomId) {
        return Reservation.builder().occupants(who).start(CHECK_IN.plusDays(fromNight))
                .end(CHECK_IN.plusDays(toNight).withHour(10)).roomId(roomId).build();
    }
}
