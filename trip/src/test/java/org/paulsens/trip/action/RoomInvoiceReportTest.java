package org.paulsens.trip.action;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PricingModel;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Room;
import org.paulsens.trip.model.RoomType;
import org.paulsens.trip.pay.LodgingPricing;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The room invoice: a group per lodging option, lines whose {@code quantity x nights x rate} IS the amount, the
 * single supplement on lines of its own, and a total that equals the lodging bills for the same stays.
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

    private static ReservationOffer perPerson(final Accommodation acc, final long cents, final long supplement) {
        return ReservationOffer.builder().name("Any room, per person").accommodationId(acc.getId())
                .roomTypeIds(List.of("dbl", "sgl")).pricingModel(PricingModel.PER_PERSON)
                .nightlyPriceCents(cents).singleSupplementCents(supplement).minNights(1).build();
    }

    private static ReservationOffer perRoom(final Accommodation acc, final long cents) {
        return ReservationOffer.builder().name("Double room, shared").accommodationId(acc.getId())
                .roomTypeIds(List.of("tri")).pricingModel(PricingModel.PER_ROOM)
                .nightlyPriceCents(cents).minNights(1).build();
    }

    private static Reservation stay(final ReservationOffer offer, final String roomId, final LocalDateTime start,
            final LocalDateTime end, final String... people) {
        return Reservation.builder().offerId(offer.getId()).accommodationId(offer.getAccommodationId())
                .roomId(roomId).start(start).end(end)
                .occupants(Arrays.stream(people).map(Person.Id::from).toList()).build();
    }

    private ReportCommands.RoomInvoice invoice(final List<ReservationOffer> offers, final Reservation... stays) {
        return reports.roomInvoice(List.of(stays), id -> find(offers, id), this::findHotel);
    }

    private static ReservationOffer find(final List<ReservationOffer> offers, final String id) {
        return offers.stream().filter(o -> o.getId().getValue().equals(id)).findFirst().orElse(null);
    }

    private Accommodation findHotel(final String id) {
        return hotel.getId().getValue().equals(id) ? hotel : second.getId().getValue().equals(id) ? second : null;
    }

    /** What the ledger bills for the same stays: the invoice must add up to exactly this. */
    private long billed(final List<ReservationOffer> offers, final Reservation... stays) {
        long total = 0L;
        for (final Reservation res : stays) {
            final List<Reservation> onRoom = Arrays.stream(stays)
                    .filter(o -> res.getRoomId() != null && res.getRoomId().equals(o.getRoomId())).toList();
            total += LodgingPricing.price(res, find(offers, res.getOfferId().getValue()), hotel, onRoom, null)
                    .stream().mapToLong(LodgingPricing.Line::amountCents).sum();
        }
        return total;
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

    @Test
    public void aWaivedSupplementIsNotCharged() {
        final ReservationOffer person = perPerson(hotel, 5_000L, 1_000L);
        final Reservation waived = stay(person, "003", ARRIVE, DEPART, "a");
        waived.setWaiveSingleSupplement(Boolean.TRUE);
        final ReportCommands.InvoiceOffer group = invoice(List.of(person), waived,
                stay(person, "004", ARRIVE, DEPART, "b")).getSections().get(0).getOffers().get(0);
        assertLine(only(group, ReportCommands.LineKind.SUPPLEMENT), 1, "guest", 12, 1_000L);
        assertLine(only(group, ReportCommands.LineKind.GUESTS), 2, "guests", 12, 5_000L);
    }

    @Test
    public void nightsAtADifferentPriceAreTheirOwnLine() {
        final ReservationOffer roomRate = perRoom(hotel, 6_000L);
        roomRate.setNightlyPriceOverrides(Map.of("2028-09-22", 9_000L, "2028-09-23", 9_000L));
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
        Assert.assertEquals(ReportCommands.roomMixOf(Map.of("Single", 1, "Double", 3, "Anex", 1), 2),
                "3 Double rooms, 1 Anex room, 1 Single room, 2 reservations not yet placed in a room");
    }

    @Test
    public void theRateReadsTheWayTheOptionEditorWritesIt() {
        Assert.assertEquals(ReportCommands.rateOf(perPerson(hotel, 5_000L, 0L)), "$50.00 per person per night");
        Assert.assertEquals(ReportCommands.rateOf(perRoom(hotel, 6_000L)), "$60.00 per room per night");
    }

    @Test
    public void aWindowAcrossNewYearNamesBothYears() {
        Assert.assertEquals(ReportCommands.stayWindowOf(LocalDate.of(2028, 12, 30), LocalDate.of(2029, 1, 2)),
                "Dec 30, 2028 - Jan 2, 2029");
    }

    @Test
    public void theSeededTripsInvoiceMatchesItsLodgingBills() {
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
        final ReportCommands.InvoiceLine line =
                invoice.getSections().get(0).getOffers().get(0).getLines().get(0);
        Assert.assertEquals(line.getQuantity(), 1);
        Assert.assertEquals(invoice.getCents(), rate.getNightlyPriceCents() * line.getNights(),
                "a PER_ROOM room costs the room rate each night");
        // Counted from the rows, not hard-coded: each initFakeData re-seeds the stays under fresh person ids.
        final long occupants = active.stream().flatMap(res -> res.getOccupants().stream()).distinct().count();
        Assert.assertEquals(line.getNote(), occupants + " guests");
    }

    @Test
    public void anUnknownTripHasAnEmptyInvoice() {
        Assert.assertTrue(reports.roomInvoice("no-such-trip").getSections().isEmpty());
        Assert.assertTrue(reports.roomInvoice(null).getSections().isEmpty());
    }
}
