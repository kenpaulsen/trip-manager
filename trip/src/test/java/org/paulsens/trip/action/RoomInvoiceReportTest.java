package org.paulsens.trip.action;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Concession;
import org.paulsens.trip.model.CostExtra;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PricingModel;
import org.paulsens.trip.model.RateSchedule;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Room;
import org.paulsens.trip.model.RoomType;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.pay.LodgingBiller;
import org.paulsens.trip.pay.LodgingPricing;
import org.paulsens.trip.pay.LodgingPricing.Side;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The room invoice: a group per lodging option, lines whose {@code quantity x nights x rate} IS the amount, the
 * single supplement on lines of its own, and a total that equals the hotel's charge for the same stays. Since
 * the price/cost split the invoice is the COST build, read STRICTLY: the fixtures here state a cost equal to
 * the price (as the seeded demo offer does), so every pre-split scenario still holds with its numbers
 * unchanged; an option WITHOUT a cost refuses the whole invoice ({@code blocked}), which the scenarios after
 * them cover together with a cost of its own, extras and concessions.
 */
public class RoomInvoiceReportTest {

    private static final LocalDateTime ARRIVE = LocalDateTime.of(2028, 9, 21, 15, 0);
    private static final LocalDateTime DEPART = ARRIVE.plusDays(12).withHour(10);

    private final ReportCommands reports = new ReportCommands(TripCommands::new, LodgingCommands::new);

    private final RoomType single = RoomType.builder().id("sgl").name("Single").minPeople(1).maxPeople(1).build();
    private final RoomType dbl = RoomType.builder().id("dbl").name("Double").minPeople(1).maxPeople(2).build();
    private final RoomType triple = RoomType.builder().id("tri").name("Triple").minPeople(1).maxPeople(3).build();
    private final Accommodation hotel = Accommodation.builder().name("Pansion Ana")
            .roomTypes(List.of(single, dbl, triple))
            .rooms(List.of(room("001", "sgl"), room("003", "dbl"), room("004", "dbl"), room("005", "dbl"),
                    room("114", "tri")))
            .build();
    private final Accommodation second = Accommodation.builder().name("Hotel Split")
            .roomTypes(List.of(dbl)).rooms(List.of(room("s1", "dbl"))).build();

    @BeforeClass
    void beforeClass() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    private static Room room(final String id, final String typeId) {
        return Room.builder().id(id).roomTypeId(typeId).roomNumber(id).build();
    }

    /** A per-person option whose hotel cost EQUALS its price (the strict invoice needs a cost to read). */
    private static ReservationOffer perPerson(final Accommodation acc, final long cents, final long supplement) {
        return ReservationOffer.builder().name("Any room, per person").accommodationId(acc.getId())
                .roomTypeIds(List.of("dbl", "sgl")).pricingModel(PricingModel.PER_PERSON)
                .nightlyPriceCents(cents).singleSupplementCents(supplement).minNights(1)
                .cost(new RateSchedule(PricingModel.PER_PERSON, cents, Map.of(), supplement)).build();
    }

    /** A per-room option whose hotel cost EQUALS its price. */
    private static ReservationOffer perRoom(final Accommodation acc, final long cents) {
        return ReservationOffer.builder().name("Double room, shared").accommodationId(acc.getId())
                .roomTypeIds(List.of("tri")).pricingModel(PricingModel.PER_ROOM)
                .nightlyPriceCents(cents).minNights(1)
                .cost(new RateSchedule(PricingModel.PER_ROOM, cents, Map.of(), 0L)).build();
    }

    /** The same per-night overrides on both sides, so a scenario reads alike at price and at cost. */
    private static void overrideNights(final ReservationOffer offer, final Map<String, Long> nights) {
        offer.setNightlyPriceOverrides(nights);
        final RateSchedule cost = offer.getCost();
        offer.setCost(new RateSchedule(cost.getPricingModel(), cost.getNightlyCents(), nights,
                cost.getSingleSupplementCents()));
    }

    private static Reservation stay(final ReservationOffer offer, final String roomId, final LocalDateTime start,
            final LocalDateTime end, final String... people) {
        return Reservation.builder().offerId(offer.getId()).accommodationId(offer.getAccommodationId())
                .roomId(roomId).start(start).end(end)
                .occupants(Arrays.stream(people).map(Person.Id::from).toList()).build();
    }

    /**
     * Every invoice built here also proves the owner's rule: per person and per option add up the same, the
     * hotel's extras (never attributed to a person) being the one documented difference.
     */
    private ReportCommands.RoomInvoice invoice(final List<ReservationOffer> offers, final Reservation... stays) {
        return invoice(Side.COST, offers, stays);
    }

    private ReportCommands.RoomInvoice invoice(final Side side, final List<ReservationOffer> offers,
            final Reservation... stays) {
        final ReportCommands.RoomInvoice invoice = reports.roomInvoice(side, List.of(stays), id -> find(offers, id),
                this::findHotel, Person.Id::getValue);
        for (final ReportCommands.InvoiceSection section : invoice.getSections()) {
            Assert.assertEquals(section.getPeopleCents() + section.getExtrasCents(), section.getCents(),
                    section.getAccommodation() + ": the per-person table plus the extras must total the per-option "
                            + "table");
            if (section.getExtrasCents() == 0L) {
                Assert.assertEquals(section.getPeopleAmount(), section.getAmount());
            }
        }
        return invoice;
    }

    private static ReportCommands.InvoicePersonLine person(final ReportCommands.InvoiceSection section,
            final String name) {
        return section.getPeople().stream().filter(line -> line.getName().equals(name)).findFirst().orElseThrow();
    }

    private static ReservationOffer find(final List<ReservationOffer> offers, final String id) {
        return offers.stream().filter(o -> o.getId().getValue().equals(id)).findFirst().orElse(null);
    }

    private Accommodation findHotel(final String id) {
        return hotel.getId().getValue().equals(id) ? hotel : second.getId().getValue().equals(id) ? second : null;
    }

    /** What the pricing engine charges on this side for the same stays: the invoice must add up to exactly this. */
    private long charged(final Side side, final List<ReservationOffer> offers, final Reservation... stays) {
        long total = 0L;
        for (final Reservation res : stays) {
            final List<Reservation> onRoom = Arrays.stream(stays)
                    .filter(o -> res.getRoomId() != null && res.getRoomId().equals(o.getRoomId())).toList();
            total += LodgingPricing.lines(side, res, find(offers, res.getOfferId().getValue()), hotel, onRoom, null)
                    .stream().mapToLong(LodgingPricing.Line::amountCents).sum();
        }
        return total;
    }

    /** With no cost stated the hotel's charge IS the participant price, so the pre-split equality still holds. */
    private long billed(final List<ReservationOffer> offers, final Reservation... stays) {
        Assert.assertEquals(charged(Side.COST, offers, stays), charged(Side.PRICE, offers, stays),
                "cost assumed from the price: both sides charge the same");
        return charged(Side.COST, offers, stays);
    }

    private static CostExtra extra(final String label, final CostExtra.Basis basis, final long cents) {
        return CostExtra.builder().label(label).basis(basis).amountCents(cents).build();
    }

    private static Concession percentOff(final int bps, final String reason) {
        return Concession.builder().kind(Concession.Kind.PERCENT).percentBps(bps).reason(reason).build();
    }

    private static ReportCommands.InvoiceLine only(final ReportCommands.InvoiceOffer group,
            final ReportCommands.LineKind kind) {
        final List<ReportCommands.InvoiceLine> lines =
                group.getLines().stream().filter(line -> line.getKind() == kind).toList();
        Assert.assertEquals(lines.size(), 1, "one " + kind + " line expected in " + group.getLines());
        return lines.get(0);
    }

    private static void assertLine(final ReportCommands.InvoiceLine line, final int quantity, final String unit,
            final int nights, final long rateCents) {
        Assert.assertEquals(line.getQuantity(), quantity, line.toString());
        Assert.assertEquals(line.getUnit(), unit, line.toString());
        Assert.assertEquals(line.getNights(), nights, line.toString());
        Assert.assertEquals(line.getRateCents(), rateCents, line.toString());
        Assert.assertEquals(line.getCents(), quantity * nights * rateCents, "the amount IS the arithmetic");
    }

    /**
     * The owner's own example (2026-09-29): five guests on a $50 per-person option, three of them alone and
     * paying the $10 supplement, and a $60 per-room option with a late arriver sharing one room.
     */
    @Test
    public void perPersonAndSupplementAreSeparateLinesThatMultiplyOut() {
        final ReservationOffer person = perPerson(hotel, 5_000L, 1_000L);
        final ReservationOffer roomRate = perRoom(hotel, 6_000L);
        final List<ReservationOffer> offers = List.of(person, roomRate);
        final Reservation[] stays = {
            stay(person, "001", ARRIVE, DEPART, "admin"),
            stay(person, "004", ARRIVE, DEPART, "joe"),
            stay(person, "003", ARRIVE, DEPART, "ken"),
            stay(person, "005", ARRIVE, DEPART, "kevin"),
            stay(person, "005", ARRIVE, DEPART, "trinity"),
            stay(roomRate, "114", ARRIVE, DEPART, "dave"),
            stay(roomRate, "114", ARRIVE.plusDays(4), DEPART, "matt"),
        };
        final ReportCommands.RoomInvoice invoice = invoice(offers, stays);
        final List<ReportCommands.InvoiceOffer> groups = invoice.getSections().get(0).getOffers();
        Assert.assertEquals(groups.stream().map(ReportCommands.InvoiceOffer::getName).toList(),
                List.of("Any room, per person", "Double room, shared"));

        final ReportCommands.InvoiceOffer perGuest = groups.get(0);
        Assert.assertEquals(perGuest.getLines().size(), 2);
        assertLine(only(perGuest, ReportCommands.LineKind.GUESTS), 5, "guests", 12, 5_000L);
        final ReportCommands.InvoiceLine supplement = only(perGuest, ReportCommands.LineKind.SUPPLEMENT);
        assertLine(supplement, 3, "guests", 12, 1_000L);
        Assert.assertEquals(supplement.getItem(), "Single supplement");
        Assert.assertEquals(perGuest.getLines().get(1), supplement, "the supplement follows the lodging line");
        Assert.assertEquals(perGuest.getRoomMix(), "3 Double rooms, 1 Single room");
        Assert.assertEquals(perGuest.getPricing(),
                "$50.00 per person per night, single supplement $10.00 per night");
        Assert.assertEquals(perGuest.getAmount(), "$3,360.00");

        final ReportCommands.InvoiceLine rooms = only(groups.get(1), ReportCommands.LineKind.ROOMS);
        assertLine(rooms, 1, "room", 12, 6_000L);
        Assert.assertEquals(rooms.getNote(), "2 guests");
        Assert.assertEquals(rooms.getItem(), "Lodging");
        Assert.assertEquals(rooms.getDates(), "Sep 21 - Oct 3, 2028");
        Assert.assertEquals(groups.get(1).getRoomMix(), "1 Triple room");

        Assert.assertEquals(invoice.getGuests(), 7);
        Assert.assertEquals(invoice.getRooms(), 5);
        Assert.assertEquals(invoice.getAmount(), "$4,080.00");
        Assert.assertEquals(invoice.getCents(), billed(offers, stays), "the invoice is the sum of the bills");

        final ReportCommands.InvoiceSection section = invoice.getSections().get(0);
        Assert.assertEquals(section.getPeople().stream().map(ReportCommands.InvoicePersonLine::getName).toList(),
                List.of("admin", "dave", "joe", "ken", "kevin", "matt", "trinity"), "by name");
        final ReportCommands.InvoicePersonLine alone = person(section, "joe");
        Assert.assertEquals(alone.getRate(), "$50.00 + $10.00 single supplement");
        Assert.assertEquals(alone.getNights(), 12);
        Assert.assertEquals(alone.getAmount(), "$720.00");
        Assert.assertEquals(alone.getDates(), "Sep 21 - Oct 3, 2028");
        Assert.assertEquals(alone.getOption(), "Any room, per person");
        Assert.assertEquals(person(section, "kevin").getRate(), "$50.00");
        Assert.assertEquals(person(section, "kevin").getAmount(), "$600.00");
        final ReportCommands.InvoicePersonLine early = person(section, "dave");
        Assert.assertEquals(early.getRate(), "Share of a $60.00 room, varies by night",
                "alone for four nights, then sharing with the late arriver");
        Assert.assertEquals(early.getAmount(), "$480.00");
        final ReportCommands.InvoicePersonLine late = person(section, "matt");
        Assert.assertEquals(late.getRate(), "$30.00 (share of a $60.00 room)");
        Assert.assertEquals(late.getNights(), 8);
        Assert.assertEquals(late.getAmount(), "$240.00");
        Assert.assertTrue(section.isSeveralOptions(), "two options here, so each row names its own");
        Assert.assertEquals(section.getPeopleAmount(), "$4,080.00");
    }

    @Test
    public void aSupplementForSomeNightsSaysHowMany() {
        final ReservationOffer person = perPerson(hotel, 5_000L, 1_000L);
        final ReportCommands.InvoiceSection section = invoice(List.of(person),
                stay(person, "005", ARRIVE, DEPART, "early"),
                stay(person, "005", ARRIVE.plusDays(3), DEPART, "late")).getSections().get(0);
        Assert.assertEquals(person(section, "early").getRate(), "$50.00 + $10.00 single supplement (3 nights)");
        Assert.assertEquals(person(section, "early").getCents(), 12 * 5_000L + 3 * 1_000L);
        Assert.assertEquals(person(section, "late").getRate(), "$50.00");
        Assert.assertFalse(section.isSeveralOptions());
    }

    @Test
    public void aOneNightSupplementIsSingular() {
        final ReservationOffer person = perPerson(hotel, 5_000L, 1_000L);
        final ReportCommands.InvoiceSection section = invoice(List.of(person),
                stay(person, "005", ARRIVE, DEPART, "early"),
                stay(person, "005", ARRIVE.plusDays(1), DEPART, "late")).getSections().get(0);
        Assert.assertEquals(person(section, "early").getRate(), "$50.00 + $10.00 single supplement (1 night)");
    }

    @Test
    public void perRoomRatesNameTheShareEachPersonPays() {
        final ReservationOffer roomRate = perRoom(hotel, 6_000L);
        final ReportCommands.InvoiceSection section = invoice(List.of(roomRate),
                stay(roomRate, "114", ARRIVE, DEPART, "solo"),
                stay(roomRate, "003", ARRIVE, DEPART, "pair1", "pair2")).getSections().get(0);
        Assert.assertEquals(person(section, "solo").getRate(), "$60.00 room, alone");
        Assert.assertEquals(person(section, "pair1").getRate(), "$30.00 (share of a $60.00 room)");
        Assert.assertEquals(person(section, "pair2").getAmount(), "$360.00");
    }

    /**
     * A room holding stays under two DIFFERENT options on one night is old data (every write refuses it since
     * 2026-10-26), and both tables now charge each option its whole room, so they agree -- the "known edge" where
     * the per-person table split the rate across the options is gone.
     */
    @Test
    public void aRoomHoldingTwoOptionsChargesEachItsWholeRoomInBothTables() {
        final ReservationOffer shared = perRoom(hotel, 6_000L);
        final ReservationOffer other = ReservationOffer.builder().name("Triple, per room")
                .accommodationId(hotel.getId()).roomTypeIds(List.of("tri")).pricingModel(PricingModel.PER_ROOM)
                .nightlyPriceCents(4_500L).minNights(1)
                .cost(new RateSchedule(PricingModel.PER_ROOM, 4_500L, Map.of(), 0L)).build();
        final Reservation pair = stay(shared, "003", ARRIVE, DEPART, "a", "b");
        final Reservation third = stay(other, "003", ARRIVE, DEPART, "c");
        final ReportCommands.InvoiceSection section = invoice(List.of(shared, other), pair, third).getSections()
                .get(0);
        Assert.assertEquals(section.getCents(), 12 * (6_000L + 4_500L), "each option its whole room, 12 nights");
        Assert.assertEquals(section.getPeopleCents(), section.getCents(), "the per-person table says the same");
        Assert.assertEquals(person(section, "a").getRate(), "$30.00 (share of a $60.00 room)");
        Assert.assertEquals(person(section, "c").getRate(), "$45.00 room, alone", "not a third of anything");
        Assert.assertEquals(person(section, "c").getAmount(), "$540.00");
        Assert.assertEquals(charged(Side.COST, List.of(shared, other), pair, third), section.getCents(),
                "and the pricing engine, priced against the same room, says the same");
    }

    @Test
    public void nightlyOverridesReadAsVaryingRates() {
        final ReservationOffer roomRate = perRoom(hotel, 6_000L);
        overrideNights(roomRate, Map.of("2028-09-22", 9_000L));
        final ReservationOffer person = perPerson(second, 5_000L, 0L);
        overrideNights(person, Map.of("2028-09-22", 9_000L));
        final ReportCommands.RoomInvoice invoice = invoice(List.of(roomRate, person),
                stay(roomRate, "114", ARRIVE, DEPART, "solo"),
                stay(roomRate, "003", ARRIVE, DEPART, "pair1", "pair2"),
                stay(person, "s1", ARRIVE, DEPART, "guest"));
        final ReportCommands.InvoiceSection ana = invoice.getSections().stream()
                .filter(s -> s.getAccommodation().equals("Pansion Ana")).findFirst().orElseThrow();
        Assert.assertEquals(person(ana, "solo").getRate(), "Room alone, priced by night");
        Assert.assertEquals(person(ana, "pair1").getRate(), "Share of a room priced by night, varies by night");
        final ReportCommands.InvoiceSection split = invoice.getSections().stream()
                .filter(s -> s.getAccommodation().equals("Hotel Split")).findFirst().orElseThrow();
        Assert.assertEquals(person(split, "guest").getRate(), "Varies by night");
    }

    @Test
    public void aPersonWithTwoStaysHasARowForEach() {
        final ReservationOffer person = perPerson(hotel, 5_000L, 0L);
        final ReportCommands.InvoiceSection section = invoice(List.of(person),
                stay(person, "004", ARRIVE.plusDays(6), DEPART, "mover"),
                stay(person, "003", ARRIVE, ARRIVE.plusDays(6), "mover")).getSections().get(0);
        Assert.assertEquals(section.getPeople().stream().map(ReportCommands.InvoicePersonLine::getDates).toList(),
                List.of("Sep 21 - Sep 27, 2028", "Sep 27 - Oct 3, 2028"), "in date order");
    }

    @Test
    public void theRealTripNamesPeopleByTheirPreferredAndLastNames() {
        final ReportCommands.InvoiceSection section =
                reports.roomInvoice(FakeData.FAKE_TRIP_ID).getSections().get(0);
        Assert.assertTrue(section.getPeople().stream().anyMatch(line -> line.getName().equals("Dave Robinson")),
                section.getPeople().toString());
        Assert.assertEquals(section.getPeopleCents(), section.getCents());
    }

    @Test
    public void aNamelessPersonFallsBackToTheirId() {
        final ReservationOffer roomRate = perRoom(hotel, 6_000L);
        final ReportCommands.RoomInvoice invoice = reports.roomInvoice(
                List.of(stay(roomRate, "114", ARRIVE, DEPART, "who")), id -> roomRate, id -> hotel, id -> " ");
        Assert.assertEquals(invoice.getSections().get(0).getPeople().get(0).getName(), "who");
    }

    @Test
    public void guestsWithDifferentStaysAreDifferentLines() {
        final ReservationOffer person = perPerson(hotel, 5_000L, 0L);
        final ReportCommands.InvoiceOffer group = invoice(List.of(person),
                stay(person, "005", ARRIVE, DEPART, "a", "b"),
                stay(person, "003", ARRIVE.plusDays(4), DEPART, "c")).getSections().get(0).getOffers().get(0);
        Assert.assertEquals(group.getLines().size(), 2);
        assertLine(group.getLines().get(0), 2, "guests", 12, 5_000L);
        assertLine(group.getLines().get(1), 1, "guest", 8, 5_000L);
        Assert.assertEquals(group.getLines().get(1).getDates(), "Sep 25 - Oct 3, 2028");
    }

    @Test
    public void theSupplementCoversOnlyTheNightsAGuestWasAlone() {
        final ReservationOffer person = perPerson(hotel, 5_000L, 1_000L);
        final List<ReservationOffer> offers = List.of(person);
        final Reservation[] stays = {
            stay(person, "005", ARRIVE, DEPART, "early"),
            stay(person, "005", ARRIVE.plusDays(3), DEPART, "late"),
        };
        final ReportCommands.RoomInvoice invoice = invoice(offers, stays);
        final ReportCommands.InvoiceOffer group = invoice.getSections().get(0).getOffers().get(0);
        final ReportCommands.InvoiceLine supplement = only(group, ReportCommands.LineKind.SUPPLEMENT);
        assertLine(supplement, 1, "guest", 3, 1_000L);
        Assert.assertEquals(supplement.getDates(), "Sep 21 - Sep 24, 2028");
        Assert.assertEquals(invoice.getCents(), billed(offers, stays));
    }

    /** The waiver is a PRICE-side concession: the participant is spared the supplement, the hotel still bills it. */
    @Test
    public void aWaivedSupplementComesOffThePriceButNotTheCost() {
        final ReservationOffer person = perPerson(hotel, 5_000L, 1_000L);
        final Reservation waived = stay(person, "003", ARRIVE, DEPART, "a");
        waived.setWaiveSingleSupplement(Boolean.TRUE);
        final Reservation other = stay(person, "004", ARRIVE, DEPART, "b");
        final ReportCommands.InvoiceOffer priced = invoice(Side.PRICE, List.of(person), waived, other)
                .getSections().get(0).getOffers().get(0);
        assertLine(only(priced, ReportCommands.LineKind.SUPPLEMENT), 1, "guest", 12, 1_000L);
        assertLine(only(priced, ReportCommands.LineKind.GUESTS), 2, "guests", 12, 5_000L);
        final ReportCommands.InvoiceOffer cost = invoice(Side.COST, List.of(person), waived, other)
                .getSections().get(0).getOffers().get(0);
        assertLine(only(cost, ReportCommands.LineKind.SUPPLEMENT), 2, "guests", 12, 1_000L);
        Assert.assertEquals(cost.getCents(), charged(Side.COST, List.of(person), waived, other));
    }

    @Test
    public void nightsAtADifferentPriceAreTheirOwnLine() {
        final ReservationOffer roomRate = perRoom(hotel, 6_000L);
        overrideNights(roomRate, Map.of("2028-09-22", 9_000L, "2028-09-23", 9_000L));
        final List<ReservationOffer> offers = List.of(roomRate);
        final Reservation[] stays = {stay(roomRate, "114", ARRIVE, DEPART, "a", "b")};
        final ReportCommands.RoomInvoice invoice = invoice(offers, stays);
        final ReportCommands.InvoiceOffer group = invoice.getSections().get(0).getOffers().get(0);
        Assert.assertEquals(group.getLines().size(), 2);
        assertLine(group.getLines().get(0), 1, "room", 10, 6_000L);
        assertLine(group.getLines().get(1), 1, "room", 2, 9_000L);
        Assert.assertEquals(group.getLines().get(1).getDates(), "Sep 22 - Sep 24, 2028");
        Assert.assertEquals(group.getPricing(), "Varies by night per room per night");
        Assert.assertEquals(invoice.getCents(), billed(offers, stays));
    }

    @Test
    public void perRoomLinesCountRoomsAndTheGuestsInThoseRooms() {
        final ReservationOffer roomRate = perRoom(hotel, 6_000L);
        final ReportCommands.InvoiceOffer group = invoice(List.of(roomRate),
                stay(roomRate, "114", ARRIVE, DEPART, "a", "b"),
                stay(roomRate, "003", ARRIVE, DEPART, "c"),
                stay(roomRate, "004", ARRIVE, DEPART.minusDays(2), "d", "e")).getSections().get(0).getOffers()
                .get(0);
        Assert.assertEquals(group.getLines().size(), 2);
        assertLine(group.getLines().get(0), 2, "rooms", 12, 6_000L);
        Assert.assertEquals(group.getLines().get(0).getNote(), "3 guests");
        assertLine(group.getLines().get(1), 1, "room", 10, 6_000L);
        Assert.assertEquals(group.getLines().get(1).getNote(), "2 guests");
        Assert.assertEquals(group.getRoomMix(), "2 Double rooms, 1 Triple room");
    }

    @Test
    public void aReservationNotYetPlacedIsBilledAsARoomOfItsOwn() {
        final ReservationOffer roomRate = perRoom(hotel, 6_000L);
        final ReservationOffer person = perPerson(hotel, 5_000L, 1_000L);
        final List<ReservationOffer> offers = List.of(roomRate, person);
        final Reservation[] stays = {
            stay(roomRate, null, ARRIVE, DEPART, "a", "b"),
            stay(roomRate, "114", ARRIVE, DEPART, "c"),
            stay(person, null, ARRIVE, DEPART, "d"),
        };
        final ReportCommands.RoomInvoice invoice = invoice(offers, stays);
        final List<ReportCommands.InvoiceOffer> groups = invoice.getSections().get(0).getOffers();
        final ReportCommands.InvoiceOffer shared = groups.get(groups.get(0).getName().startsWith("Double") ? 0 : 1);
        assertLine(only(shared, ReportCommands.LineKind.ROOMS), 2, "rooms", 12, 6_000L);
        Assert.assertEquals(shared.getRoomMix(), "1 Triple room, 1 reservation not yet placed in a room");
        final ReportCommands.InvoiceOffer alone = groups.get(groups.indexOf(shared) == 0 ? 1 : 0);
        Assert.assertEquals(alone.getRoomMix(), "1 reservation not yet placed in a room");
        assertLine(only(alone, ReportCommands.LineKind.SUPPLEMENT), 1, "guest", 12, 1_000L);
        Assert.assertEquals(invoice.getRooms(), 3);
        Assert.assertEquals(invoice.getCents(), billed(offers, stays));
    }

    @Test
    public void cancelledZeroNightAndUnpricedStaysAddNothing() {
        final ReservationOffer roomRate = perRoom(hotel, 6_000L);
        final ReservationOffer gone = perRoom(hotel, 6_000L);
        final Reservation cancelled = stay(roomRate, "003", ARRIVE, DEPART, "b");
        cancelled.setStatus(Reservation.Status.CANCELLED);
        final ReportCommands.RoomInvoice invoice = invoice(List.of(roomRate),
                stay(roomRate, "114", ARRIVE, DEPART, "a"),
                cancelled,
                stay(roomRate, "004", ARRIVE, ARRIVE.plusHours(3), "c"),
                stay(gone, "005", ARRIVE, DEPART, "d"));
        Assert.assertEquals(invoice.getRooms(), 1);
        Assert.assertEquals(invoice.getGuests(), 1, "a zero-night stay is not a guest");
        Assert.assertEquals(invoice.getCents(), 72_000L);
        Assert.assertEquals(invoice.getUnpriced(), 1, "a stay whose option is gone is counted, not priced");
    }

    @Test
    public void anOptionWithOnlyZeroNightStaysIsLeftOut() {
        final ReservationOffer roomRate = perRoom(hotel, 6_000L);
        Assert.assertTrue(invoice(List.of(roomRate), stay(roomRate, "114", ARRIVE, ARRIVE.plusHours(2), "a"))
                .getSections().isEmpty());
    }

    @Test
    public void eachHotelIsASectionWithASubtotalInTheOrderTheTripReachesThem() {
        final ReservationOffer here = perRoom(hotel, 10_000L);
        final ReservationOffer there = perRoom(second, 8_000L);
        final ReportCommands.RoomInvoice invoice = invoice(List.of(here, there),
                stay(here, "114", DEPART, DEPART.plusDays(2), "a"),
                stay(there, "s1", ARRIVE, DEPART, "a"));
        Assert.assertEquals(invoice.getSections().stream().map(ReportCommands.InvoiceSection::getAccommodation)
                .toList(), List.of("Hotel Split", "Pansion Ana"));
        Assert.assertEquals(invoice.getSections().get(0).getCents(), 96_000L);
        Assert.assertEquals(invoice.getSections().get(1).getAmount(), "$200.00");
        Assert.assertEquals(invoice.getCents(), 116_000L);
        Assert.assertEquals(invoice.getGuests(), 1, "guests are counted once across hotels");
        Assert.assertEquals(invoice.getRooms(), 2);
    }

    @Test
    public void hotelsReachedTheSameDayAreOrderedByName() {
        final ReservationOffer here = perRoom(hotel, 10_000L);
        final ReservationOffer there = perRoom(second, 8_000L);
        final ReportCommands.RoomInvoice invoice = invoice(List.of(here, there),
                stay(here, "114", ARRIVE, DEPART, "a"),
                stay(there, "s1", ARRIVE, DEPART, "b"));
        Assert.assertEquals(invoice.getSections().stream().map(ReportCommands.InvoiceSection::getAccommodation)
                .toList(), List.of("Hotel Split", "Pansion Ana"));
    }

    @Test
    public void optionsInOneHotelAreOrderedByArrivalThenName() {
        final ReservationOffer later = perRoom(hotel, 6_000L);
        final ReservationOffer earlier = perPerson(hotel, 5_000L, 0L);
        final List<ReportCommands.InvoiceOffer> groups = invoice(List.of(later, earlier),
                stay(later, "114", ARRIVE.plusDays(1), DEPART, "a"),
                stay(earlier, "003", ARRIVE, DEPART, "b")).getSections().get(0).getOffers();
        Assert.assertEquals(groups.get(0).getName(), "Any room, per person");
    }

    @Test
    public void aStayAtAnUnknownHotelIsStillChargedUnderItsOwnHeading() {
        final Accommodation lost = Accommodation.builder().name("Lost").build();
        final ReservationOffer rate = perRoom(lost, 10_000L);
        final ReportCommands.RoomInvoice invoice = invoice(List.of(rate), stay(rate, "114", ARRIVE, DEPART, "a"));
        Assert.assertEquals(invoice.getSections().get(0).getAccommodation(), "Unknown accommodation");
        Assert.assertEquals(invoice.getSections().get(0).getOffers().get(0).getRoomMix(),
                "1 reservation not yet placed in a room");
        Assert.assertEquals(invoice.getCents(), 120_000L);
    }

    @Test
    public void aRoomWhoseTypeIsGoneIsStillCounted() {
        final Accommodation odd = Accommodation.builder().name("Odd").rooms(List.of(room("x", "gone"))).build();
        final ReservationOffer rate = perRoom(odd, 10_000L);
        final ReportCommands.RoomInvoice invoice = reports.roomInvoice(
                List.of(stay(rate, "x", ARRIVE, DEPART, "a")), id -> rate, id -> odd);
        Assert.assertEquals(invoice.getSections().get(0).getOffers().get(0).getRoomMix(), "1 room");
    }

    @Test
    public void noReservationsIsAnEmptyInvoiceAtZero() {
        final ReportCommands.RoomInvoice invoice = invoice(List.of());
        Assert.assertTrue(invoice.getSections().isEmpty());
        Assert.assertEquals(invoice.getAmount(), "$0.00");
    }

    @Test
    public void theRoomMixListsTheMostUsedTypeFirst() {
        Assert.assertEquals(RoomInvoiceBuilder.roomMixOf(Map.of("Single", 1, "Double", 3, "Anex", 1), 2),
                "3 Double rooms, 1 Anex room, 1 Single room, 2 reservations not yet placed in a room");
    }

    @Test
    public void theRateReadsTheWayTheOptionEditorWritesIt() {
        Assert.assertEquals(RoomInvoiceBuilder.rateOf(perPerson(hotel, 5_000L, 0L).getPrice()),
                "$50.00 per person per night");
        Assert.assertEquals(RoomInvoiceBuilder.rateOf(perRoom(hotel, 6_000L).getPrice()), "$60.00 per room per night");
    }

    @Test
    public void aWindowAcrossNewYearNamesBothYears() {
        Assert.assertEquals(RoomInvoiceBuilder.stayWindowOf(LocalDate.of(2028, 12, 30), LocalDate.of(2029, 1, 2)),
                "Dec 30, 2028 - Jan 2, 2029");
    }

    // ------------------------------------------------------------------ price vs cost

    /** A hotel that charges per room while the participants pay per person: the invoice is the hotel's number. */
    @Test
    public void anOptionWithItsOwnCostIsInvoicedAtCostNotPrice() {
        final ReservationOffer person = perPerson(hotel, 5_000L, 1_000L);
        person.setCost(RateSchedule.builder().pricingModel(PricingModel.PER_ROOM).nightlyCents(7_000L).build());
        final List<ReservationOffer> offers = List.of(person);
        final Reservation[] stays = {
            stay(person, "003", ARRIVE, DEPART, "a", "b"),
            stay(person, "001", ARRIVE, DEPART, "solo"),
        };
        final ReportCommands.RoomInvoice invoice = invoice(offers, stays);
        final ReportCommands.InvoiceOffer group = invoice.getSections().get(0).getOffers().get(0);
        assertLine(only(group, ReportCommands.LineKind.ROOMS), 2, "rooms", 12, 7_000L);
        Assert.assertTrue(group.getLines().stream().noneMatch(l -> l.getKind() == ReportCommands.LineKind.SUPPLEMENT),
                "a per-room cost has no supplement, whatever the price side charges");
        Assert.assertEquals(group.getPricing(), "$70.00 per room per night");
        Assert.assertFalse(invoice.isBlocked());
        Assert.assertEquals(invoice.getCents(), 2 * 12 * 7_000L);
        Assert.assertEquals(invoice.getCents(), charged(Side.COST, offers, stays));
        final ReportCommands.InvoiceSection section = invoice.getSections().get(0);
        Assert.assertEquals(person(section, "a").getRate(), "$35.00 (share of a $70.00 room)");
        Assert.assertEquals(person(section, "solo").getRate(), "$70.00 room, alone");
        // The participants' side is untouched by the cost.
        final ReportCommands.RoomInvoice priced = invoice(Side.PRICE, offers, stays);
        Assert.assertEquals(priced.getCents(), 3 * 12 * 5_000L + 12 * 1_000L);
        Assert.assertEquals(priced.getCents(), charged(Side.PRICE, offers, stays));
    }

    /**
     * No cost is ever assumed from a price: ONE active-stay option without a cost refuses the whole invoice,
     * naming the options (hotel-qualified, by name) and drawing nothing -- not even the hotel whose costs are
     * all stated. The PRICE side is untouched, and a cancelled stay on the costless option does not count.
     */
    @Test
    public void anOptionWithoutACostBlocksTheWholeInvoice() {
        final ReservationOffer unstated = perRoom(hotel, 6_000L);
        unstated.setCost(null);
        final ReservationOffer stated = perRoom(second, 8_000L);
        stated.setCost(RateSchedule.builder().nightlyCents(5_000L).build());
        final ReservationOffer alsoUnstated = ReservationOffer.builder().name("Anex room").accommodationId(
                second.getId()).roomTypeIds(List.of("dbl")).pricingModel(PricingModel.PER_ROOM)
                .nightlyPriceCents(4_000L).build();
        final List<ReservationOffer> offers = List.of(unstated, stated, alsoUnstated);
        final Reservation[] stays = {
            stay(unstated, "114", ARRIVE, DEPART, "a"),
            stay(unstated, "003", ARRIVE, DEPART, "a2"),
            stay(stated, "s1", ARRIVE, DEPART, "b"),
            stay(alsoUnstated, null, ARRIVE, DEPART, "c"),
        };
        final ReportCommands.RoomInvoice invoice = invoice(offers, stays);
        Assert.assertTrue(invoice.isBlocked());
        Assert.assertEquals(invoice.getMissingCost(), List.of("Anex room (Hotel Split)", "Double room, shared "
                + "(Pansion Ana)"), "each option once, hotel-qualified, by name");
        Assert.assertTrue(invoice.getSections().isEmpty(), "nothing is drawn, not even the costed hotel");
        Assert.assertEquals(invoice.getCents(), 0L);
        Assert.assertEquals(invoice.getAmount(), "$0.00");
        Assert.assertEquals(invoice.getGuests(), 0);
        Assert.assertEquals(invoice.getUnpriced(), 0, "a missing cost is not an unpriced stay");
        final ReportCommands.RoomInvoice priced = invoice(Side.PRICE, offers, stays);
        Assert.assertFalse(priced.isBlocked(), "the participants' side never blocks on a hotel's cost");
        Assert.assertTrue(priced.getMissingCost().isEmpty());
        Assert.assertEquals(priced.getCents(), 2 * 12 * 6_000L + 12 * 8_000L + 12 * 4_000L);

        final Reservation cancelled = stay(unstated, "114", ARRIVE, DEPART, "z");
        cancelled.setStatus(Reservation.Status.CANCELLED);
        final ReportCommands.RoomInvoice fine = invoice(List.of(unstated, stated), cancelled,
                stay(stated, "s1", ARRIVE, DEPART, "b"));
        Assert.assertFalse(fine.isBlocked(), "a cancelled stay has no cost to miss");
        Assert.assertEquals(fine.getCents(), 12 * 5_000L);
        Assert.assertEquals(ReportCommands.blockedMessage(invoice.getMissingCost(), false),
                "No hotel cost is set for Anex room (Hotel Split) and Double room, shared (Pansion Ana). Set the "
                        + "cost on the trip's Offers tab (Edit the option, tick 'Hotel cost is known') or ask the "
                        + "hotel's operator to set it on the hotel's Trips tab.");
        Assert.assertEquals(ReportCommands.blockedMessage(List.of("Double room (Pansion)"), true),
                "No hotel cost is set for Double room (Pansion). Set the cost on this trip's options on your "
                        + "hotel's Trips tab.");
        Assert.assertEquals(ReportCommands.joinNames(List.of("A", "B", "C")), "A, B and C");
        Assert.assertEquals(ReportCommands.joinNames(List.of()), "");
    }

    /**
     * Every basis multiplies out of the option's counts: 58 guest-nights (2 x 12 in a room, 2 x 12 not yet
     * placed, 1 x 10), 34 room-nights (the unplaced stay is a room of its own), 3 stays; FIXED is once, and a
     * coupon is a negative extra. The extras are the hotel's, so they are not on the people table.
     */
    @Test
    public void costExtrasMultiplyOutByTheirBasis() {
        final ReservationOffer roomRate = perRoom(hotel, 6_000L);
        roomRate.setCostExtras(List.of(
                extra("City tax", CostExtra.Basis.PER_PERSON_NIGHT, 250L),
                extra("Resort fee", CostExtra.Basis.PER_ROOM_NIGHT, 500L),
                extra("Cleaning", CostExtra.Basis.PER_STAY, 3_000L),
                extra("Booking fee", CostExtra.Basis.FIXED, 1_500L),
                extra("Coupon", CostExtra.Basis.FIXED, -2_000L)));
        final List<ReservationOffer> offers = List.of(roomRate);
        final Reservation[] stays = {
            stay(roomRate, "114", ARRIVE, DEPART, "a", "b"),
            stay(roomRate, null, ARRIVE, DEPART, "c", "d"),
            stay(roomRate, "003", ARRIVE, DEPART.minusDays(2), "e"),
        };
        final ReportCommands.RoomInvoice invoice = invoice(offers, stays);
        final ReportCommands.InvoiceOffer group = invoice.getSections().get(0).getOffers().get(0);
        final List<ReportCommands.InvoiceLine> extras =
                group.getLines().stream().filter(ReportCommands.InvoiceLine::isExtra).toList();
        Assert.assertEquals(extras.stream().map(ReportCommands.InvoiceLine::getItem).toList(),
                List.of("City tax", "Resort fee", "Cleaning", "Booking fee", "Coupon"),
                "extras keep the order they were entered in");
        final ReportCommands.InvoiceLine tax = extras.get(0);
        assertLine(tax, 58, "guest-nights", 1, 250L);
        Assert.assertEquals(tax.getAmount(), "$145.00");
        Assert.assertEquals(tax.getDates(), "Sep 21 - Oct 3, 2028", "the option's whole window");
        assertLine(extras.get(1), 34, "room-nights", 1, 500L);
        assertLine(extras.get(2), 3, "stays", 1, 3_000L);
        assertLine(extras.get(3), 1, "", 1, 1_500L);
        final ReportCommands.InvoiceLine coupon = extras.get(4);
        assertLine(coupon, 1, "", 1, -2_000L);
        Assert.assertEquals(coupon.getAmount(), "-$20.00");
        Assert.assertEquals(coupon.getRate(), "-$20.00");
        final int lastLodging = group.getLines().indexOf(group.getLines().stream()
                .filter(l -> l.getKind() == ReportCommands.LineKind.ROOMS).reduce((x, y) -> y).orElseThrow());
        Assert.assertTrue(group.getLines().indexOf(coupon) > lastLodging, "extras follow the lodging lines");
        final long lodging = 2 * 12 * 6_000L + 10 * 6_000L;
        final long extrasTotal = 14_500L + 17_000L + 9_000L + 1_500L - 2_000L;
        Assert.assertEquals(group.getCents(), lodging + extrasTotal);
        final ReportCommands.InvoiceSection section = invoice.getSections().get(0);
        Assert.assertEquals(section.getExtrasCents(), extrasTotal);
        Assert.assertEquals(section.getExtrasAmount(), "$400.00");
        Assert.assertEquals(section.getPeopleCents(), lodging, "nobody is charged an extra");
        Assert.assertEquals(invoice.getCents(), lodging + extrasTotal);
        Assert.assertEquals(invoice.getRooms(), 3);
        Assert.assertTrue(invoice(Side.PRICE, offers, stays).getSections().get(0).getOffers().get(0).getLines()
                .stream().noneMatch(ReportCommands.InvoiceLine::isExtra), "extras never reach the price side");
    }

    @Test
    public void aCostConcessionIsANegativeLineAndANetPersonRowAndTheTotalsAgree() {
        final ReservationOffer roomRate = perRoom(hotel, 6_000L);
        final Reservation comped = stay(roomRate, "003", ARRIVE, DEPART, "a", "b");
        comped.setCostConcession(percentOff(1_000, "Hotel comped the guide"));
        final List<ReservationOffer> offers = List.of(roomRate);
        final Reservation[] stays = {comped, stay(roomRate, "114", ARRIVE, DEPART, "c")};
        final ReportCommands.RoomInvoice invoice = invoice(offers, stays);
        final ReportCommands.InvoiceOffer group = invoice.getSections().get(0).getOffers().get(0);
        final ReportCommands.InvoiceLine concession = only(group, ReportCommands.LineKind.CONCESSION);
        Assert.assertTrue(concession.isConcession());
        Assert.assertEquals(concession.getItem(), "Concession");
        Assert.assertEquals(concession.getQuantity(), 1);
        Assert.assertEquals(concession.getNights(), 0);
        Assert.assertEquals(concession.getRate(), "");
        Assert.assertEquals(concession.getUnit(), "");
        Assert.assertEquals(concession.getCents(), -7_200L, "10% of a $720.00 room");
        Assert.assertEquals(concession.getAmount(), "-$72.00");
        Assert.assertEquals(concession.getNote(), "a, b: 10% discount -- Hotel comped the guide");
        Assert.assertEquals(group.getLines().get(group.getLines().size() - 1), concession, "last in the group");
        assertLine(only(group, ReportCommands.LineKind.ROOMS), 2, "rooms", 12, 6_000L);
        Assert.assertEquals(group.getCents(), 2 * 72_000L - 7_200L);
        final ReportCommands.InvoiceSection section = invoice.getSections().get(0);
        Assert.assertEquals(person(section, "a").getRate(), "$30.00 (share of a $60.00 room) (10% discount)");
        Assert.assertEquals(person(section, "a").getCents(), 32_400L);
        Assert.assertEquals(person(section, "c").getRate(), "$60.00 room, alone", "no suffix without a concession");
        Assert.assertEquals(section.getPeopleCents(), 2 * 32_400L + 72_000L);
        Assert.assertEquals(invoice.getCents(), charged(Side.COST, offers, stays), "the engine's own net");
    }

    @Test
    public void aConcessionWithoutAReasonNamesOnlyTheGuestsAndTheLabel() {
        final ReservationOffer roomRate = perRoom(hotel, 6_000L);
        final Reservation comped = stay(roomRate, "114", ARRIVE, DEPART, "zed", "amy");
        comped.setCostConcession(Concession.builder().kind(Concession.Kind.COMPLIMENTARY).reason("  ").build());
        final ReportCommands.InvoiceOffer group =
                invoice(List.of(roomRate), comped).getSections().get(0).getOffers().get(0);
        final ReportCommands.InvoiceLine concession = only(group, ReportCommands.LineKind.CONCESSION);
        Assert.assertEquals(concession.getNote(), "amy, zed: complimentary");
        Assert.assertEquals(group.getCents(), 0L, "the room is free");
    }

    @Test
    public void aPriceConcessionIsInvisibleOnTheCostSide() {
        final ReservationOffer roomRate = perRoom(hotel, 6_000L);
        final Reservation discounted = stay(roomRate, "003", ARRIVE, DEPART, "a", "b");
        discounted.setPriceConcession(percentOff(5_000, "Scholarship"));
        final ReportCommands.InvoiceOffer cost = invoice(Side.COST, List.of(roomRate), discounted)
                .getSections().get(0).getOffers().get(0);
        Assert.assertTrue(cost.getLines().stream().noneMatch(ReportCommands.InvoiceLine::isConcession));
        Assert.assertEquals(cost.getCents(), 72_000L, "the hotel charges the full room");
        final ReportCommands.RoomInvoice priced = invoice(Side.PRICE, List.of(roomRate), discounted);
        final ReportCommands.InvoiceOffer billed = priced.getSections().get(0).getOffers().get(0);
        Assert.assertEquals(only(billed, ReportCommands.LineKind.CONCESSION).getNote(),
                "a, b: 50% discount -- Scholarship", "the price side is internal, so its reason shows");
        Assert.assertEquals(billed.getCents(), 36_000L);
        Assert.assertEquals(person(priced.getSections().get(0), "a").getCents(), 18_000L);
    }

    /**
     * The two absences on the COST side: an option with no cost (priced or not) BLOCKS; a stay whose option is
     * gone is counted as unpriced, as it always was. On the PRICE side an unpriced option is simply unpriced.
     */
    @Test
    public void anOptionWithNoScheduleOnThisSideIsUnpricedOrBlocking() {
        final ReservationOffer neither = ReservationOffer.builder().name("Needs pricing")
                .accommodationId(hotel.getId()).roomTypeIds(List.of("dbl")).build();
        Assert.assertFalse(neither.isPriced());
        final ReservationOffer costOnly = ReservationOffer.builder().name("Hotel-stated only")
                .accommodationId(hotel.getId()).roomTypeIds(List.of("dbl"))
                .cost(RateSchedule.builder().nightlyCents(4_000L).build()).build();
        final Reservation orphan = stay(costOnly, "005", ARRIVE, DEPART, "c");
        orphan.setOfferId(ReservationOffer.Id.from("gone"));
        final Reservation[] stays = {
            stay(neither, "003", ARRIVE, DEPART, "a"),
            stay(costOnly, "004", ARRIVE, DEPART, "b"),
            orphan,
        };
        final ReportCommands.RoomInvoice cost = invoice(Side.COST, List.of(neither, costOnly), stays);
        Assert.assertTrue(cost.isBlocked(), "no cost, whatever the price side says");
        Assert.assertEquals(cost.getMissingCost(), List.of("Needs pricing (Pansion Ana)"));
        final ReportCommands.RoomInvoice costed = invoice(Side.COST, List.of(costOnly), stays[1], orphan);
        Assert.assertFalse(costed.isBlocked());
        Assert.assertEquals(costed.getUnpriced(), 1, "a stay whose option is gone is counted, not priced");
        Assert.assertEquals(costed.getCents(), 12 * 4_000L);
        final ReportCommands.RoomInvoice price = invoice(Side.PRICE, List.of(neither, costOnly), stays);
        Assert.assertEquals(price.getUnpriced(), 3, "neither has a participant price, and one option is gone");
        Assert.assertTrue(price.getSections().isEmpty());
        Assert.assertFalse(price.isBlocked());
    }

    // ------------------------------------------------------------------ who sees what

    @Test
    public void staysAreFilteredToAnOperatorsHotelsByTheStaysOwnHotelElseItsOptions() {
        final ReservationOffer here = perRoom(hotel, 6_000L);
        final ReservationOffer there = perRoom(second, 8_000L);
        final Reservation atHotel = stay(here, "114", ARRIVE, DEPART, "a");
        final Reservation atSecond = stay(there, "s1", ARRIVE, DEPART, "b");
        final Reservation byOffer = stay(there, null, ARRIVE, DEPART, "c");
        byOffer.setAccommodationId(null);
        final Reservation orphan = stay(here, null, ARRIVE, DEPART, "d");
        orphan.setAccommodationId(null);
        orphan.setOfferId(ReservationOffer.Id.from("gone"));
        final List<ReservationOffer> offers = List.of(here, there);
        Assert.assertEquals(ReportCommands.staysAt(List.of(atHotel, atSecond, byOffer, orphan),
                Set.of(second.getId().getValue()), id -> find(offers, id)), List.of(atSecond, byOffer));
        Assert.assertEquals(ReportCommands.staysAt(List.of(atHotel, atSecond, byOffer, orphan),
                Set.of(hotel.getId().getValue()), id -> find(offers, id)), List.of(atHotel));
    }

    @Test
    public void theInvoiceForAnOperatorIsTheirHotelsAlone() {
        final String tripId = FakeData.FAKE_TRIP_ID;
        final ReportCommands.RoomInvoice everything = reports.roomInvoice(tripId);
        Assert.assertFalse(everything.isBlocked(), "the seeded option states its cost");
        Assert.assertEquals(everything.getCents(), 12 * 6_000L, "12 nights at the seeded $60 cost");
        final LodgingAccess finance = LodgingAccess.none(tripId);
        Assert.assertEquals(reports.roomInvoiceFor(tripId, finance, true), everything, "a finance viewer: all of it");
        Assert.assertEquals(reports.roomInvoiceFor(tripId, null, true), everything, "...even with no access object");
        final LodgingAccess internal = new LodgingAccess(tripId, true, Set.of(),
                Set.of(FakeData.CFPW_ACCOMMODATION_ID));
        Assert.assertEquals(reports.roomInvoiceFor(tripId, internal, false), everything, "the trip's own people too");

        final LodgingAccess operator = new LodgingAccess(tripId, false, Set.of(FakeData.CFPW_ACCOMMODATION_ID),
                Set.of(FakeData.CFPW_ACCOMMODATION_ID));
        final ReportCommands.RoomInvoice theirs = reports.roomInvoiceFor(tripId, operator, false);
        Assert.assertEquals(theirs.getSections().size(), 1);
        Assert.assertEquals(theirs.getCents(), everything.getCents(), "the one hotel in play is theirs");
        Assert.assertFalse(theirs.isBlocked());
        Assert.assertTrue(reports.invoiceReady(tripId, operator));
        Assert.assertEquals(reports.invoiceBlockedMessage(tripId, operator), "");
        Assert.assertTrue(reports.invoiceReady(tripId, internal));
        Assert.assertTrue(reports.invoiceReady(tripId, null), "no access object: the whole trip's view");

        final LodgingAccess elsewhere = new LodgingAccess(tripId, false, Set.of("some-other-hotel"), Set.of());
        Assert.assertTrue(reports.roomInvoiceFor(tripId, elsewhere, false).getSections().isEmpty(),
                "an operator of a hotel not in play gets nothing");
        Assert.assertTrue(reports.roomInvoiceFor(tripId, finance, false).getSections().isEmpty(),
                "neither finance nor internal nor operator: nothing");
        Assert.assertTrue(reports.roomInvoiceFor("no-such-trip", internal, true).getSections().isEmpty());
    }

    // ------------------------------------------------------------------ the seeded trip

    /** The local fixture states a hotel cost equal to its price ($60 per room), so the invoice renders at it. */
    @Test
    public void theSeededTripsInvoiceRendersAtTheSeededCost() {
        final ReportCommands.RoomInvoice invoice = reports.roomInvoice(FakeData.FAKE_TRIP_ID);
        Assert.assertEquals(invoice.getSections().size(), 1, "the local fixture reserves one hotel");
        Assert.assertEquals(invoice.getRooms(), 1, "Dave and the late-arriving Matt share one room");
        final List<Reservation> active = new ArrayList<>();
        for (final Reservation res : DAO.getInstance().getReservations(FakeData.FAKE_TRIP_ID, Cached.NO)) {
            if (res.isActive()) {
                active.add(res);
            }
        }
        final ReservationOffer rate = new LodgingCommands().findOffer(FakeData.FAKE_TRIP_ID,
                active.get(0).getOfferId().getValue());
        Assert.assertEquals(rate.getCost().getNightlyCents(), 6_000L, "the seed states the cost");
        Assert.assertFalse(invoice.isBlocked());
        final ReportCommands.InvoiceOffer group = invoice.getSections().get(0).getOffers().get(0);
        final ReportCommands.InvoiceLine line = group.getLines().get(0);
        Assert.assertEquals(line.getQuantity(), 1);
        Assert.assertEquals(invoice.getCents(), rate.getCost().getNightlyCents() * line.getNights(),
                "a PER_ROOM room costs the room rate each night");
        // Counted from the rows, not hard-coded: each initFakeData re-seeds the stays under fresh person ids.
        final long occupants = active.stream().flatMap(res -> res.getOccupants().stream()).distinct().count();
        Assert.assertEquals(line.getNote(), occupants + " guests");
    }

    /** Billed vs Cost over the fixture: its billed column IS the ledger, its cost column IS the invoice. */
    @Test
    public void theSeededTripsBilledColumnMatchesItsLodgingBills() {
        final String tripId = FakeData.FAKE_TRIP_ID;
        // The seed bills on reservation; recomputing here makes the test independent of that setting.
        new LodgingCommands(TestCallers::siteAdmin).recomputeTrip(tripId);
        final ReportCommands.BilledVsCost compared = reports.billedVsCost(tripId);
        // Read the rows the way the biller does (per ACTIVE stay and occupant, by bill id): the roster's ids
        // drift across re-seeds, and bills of cancelled stays stay in the ledger but leave the report.
        long ledger = 0L;
        for (final Reservation res : DAO.getInstance().getReservations(tripId, Cached.NO)) {
            if (!res.isActive()) {
                continue;
            }
            for (final Person.Id person : res.getOccupants()) {
                final Transaction tx = DAO.getInstance()
                        .getTransaction(person, LodgingBiller.billTxId(res.getId(), person), Cached.NO).orElse(null);
                if (tx != null && tx.getAmount() != null) {
                    ledger += Math.round(tx.getAmount() * 100);
                }
            }
        }
        Assert.assertTrue(ledger < 0L, "the seed wrote lodging bills");
        Assert.assertEquals(compared.getBilledCents(), -ledger, "billed == -sum of the Lodging Bill rows");
        Assert.assertEquals(compared.getCostCents(), reports.roomInvoice(tripId).getCents(),
                "cost == the Room Invoice total");
        Assert.assertEquals(compared.getDifferenceCents(), 0L, "the seeded cost equals the seeded price");
        Assert.assertEquals(compared.getMissingCost(), 0);
    }

    @Test
    public void anUnknownTripHasAnEmptyInvoice() {
        Assert.assertTrue(reports.roomInvoice("no-such-trip").getSections().isEmpty());
        Assert.assertTrue(reports.roomInvoice(null).getSections().isEmpty());
    }
}
