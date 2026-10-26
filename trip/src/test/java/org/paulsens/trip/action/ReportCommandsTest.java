package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.paulsens.trip.action.LodgingViews.AccommodationForm;
import org.paulsens.trip.action.LodgingViews.OfferCostForm;
import org.paulsens.trip.action.LodgingViews.OfferForm;
import org.paulsens.trip.action.LodgingViews.ReservationForm;
import org.paulsens.trip.action.LodgingViews.RoomTypeForm;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The Ground Transportation report's rows: which events it takes, the order it puts them in, what a leg written
 * by the bespoke editor carries, and how a leg that predates the editor degrades. The names are the report's
 * whole point, so their format and their order are pinned here rather than in the page. Also the Room
 * Invoice's readiness question ({@code invoiceReady} / {@code invoiceBlockedMessage}) over each caller's view.
 */
public class ReportCommandsTest {

    private static final LocalDateTime NOON = LocalDateTime.of(2028, 5, 3, 12, 0);

    private final ReportCommands reports = new ReportCommands(TripCommands::new, LodgingCommands::new);

    @BeforeClass
    void beforeClass() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    private static Person.Id person(final int index) {
        return FakeData.getFakePeople().get(index).getId();
    }

    private static TripEvent leg(final String id, final LocalDateTime start, final LocalDateTime end,
            final Person.Id... people) {
        return new TripEvent(id, TripEvent.Type.GROUND, "A -> B", "notes", start, end, Arrays.asList(people), null);
    }

    private static TripEvent composedLeg(final String id, final LocalDateTime start, final LocalDateTime end,
            final Person.Id... people) {
        final TripEvent event = leg(id, start, end, people);
        event.setDetails(java.util.Map.of(TripEvent.Detail.FROM.key(), "Split", TripEvent.Detail.TO.key(),
                "Medjugorje", TripEvent.Detail.CARRIER.key(), "Globtour bus"));
        return event;
    }

    private static Trip tripWith(final TripEvent... events) {
        return Trip.builder().title("Ground Report Test").tripEvents(List.of(events)).build();
    }

    @Test
    public void legsComeBackInDepartureOrderWithTheUndatedOnesLast() {
        final TripEvent late = leg("late", NOON.plusDays(2), NOON.plusDays(2).plusHours(1));
        final TripEvent early = leg("early", NOON, NOON.plusHours(1));
        final TripEvent undated = leg("undated", NOON, NOON.plusHours(1));
        undated.setStart(null);
        final List<ReportCommands.GroundRow> rows = reports.groundRows(tripWith(late, undated, early));
        Assert.assertEquals(rows.stream().map(ReportCommands.GroundRow::getId).toList(),
                List.of("early", "late", "undated"));
    }

    @Test
    public void onlyGroundEventsAreReported() {
        final TripEvent flight = new TripEvent("f", TripEvent.Type.FLIGHT, "PDX -> FCO", "AS 1", NOON, null, null,
                null);
        final TripEvent lodging = new TripEvent("l", TripEvent.Type.LODGING, "Hotel", "", NOON, null, null, null);
        final TripEvent plain = new TripEvent("e", TripEvent.Type.EVENT, "Mass", "", NOON, null, null, null);
        final TripEvent untyped = new TripEvent("u", null, "Legacy", "", NOON, null, null, null);
        final Trip trip = tripWith(flight, lodging, plain, untyped, leg("g", NOON, NOON.plusHours(1)));
        final List<ReportCommands.GroundRow> rows = reports.groundRows(trip);
        Assert.assertEquals(rows.size(), 1, "only the GROUND leg belongs on this report");
        Assert.assertEquals(rows.get(0).getId(), "g");
    }

    @Test
    public void aComposedLegCarriesItsPartsItsDurationAndItsPeople() {
        final TripEvent event = composedLeg("in", NOON, NOON.plusHours(2).plusMinutes(30),
                person(2), person(3), person(4));
        final ReportCommands.GroundRow row = reports.groundRows(tripWith(event)).get(0);
        Assert.assertTrue(row.isComposed());
        Assert.assertEquals(row.getFrom(), "Split");
        Assert.assertEquals(row.getTo(), "Medjugorje");
        Assert.assertEquals(row.getCarrier(), "Globtour bus");
        Assert.assertEquals(row.getElapsed(), "2h 30m");
        Assert.assertFalse(row.isOvernight());
        Assert.assertEquals(row.getCount(), 3);
        Assert.assertEquals(row.getNames(), "Ken Paulsen, Kevin Paulsen, Trinity Paulsen",
                "preferred name and last name, ordered by last then preferred");
        Assert.assertEquals(row.getStart(), NOON);
        Assert.assertEquals(row.getEnd(), NOON.plusHours(2).plusMinutes(30));
        Assert.assertEquals(row.getRoute(), "Split \u2192 Medjugorje");
        Assert.assertEquals(row.getDates(), "May 3, 2028", "one day, named once");
        Assert.assertEquals(row.getTimes(), "12:00 PM \u2192 2:30 PM");
    }

    @Test
    public void aLegRunningPastMidnightNamesBothDays() {
        final LocalDateTime lateNight = LocalDateTime.of(2028, 5, 3, 20, 15);
        final ReportCommands.GroundRow row = reports
                .groundRows(tripWith(leg("n", lateNight, lateNight.plusHours(4).plusMinutes(45)))).get(0);
        Assert.assertTrue(row.isOvernight());
        Assert.assertEquals(row.getDates(), "May 3, 2028 \u2192 May 4, 2028",
                "the span itself says the leg costs a night; there is no separate marker");
        Assert.assertEquals(row.getTimes(), "8:15 PM \u2192 1:00 AM");
        Assert.assertEquals(row.getElapsed(), "4h 45m");
    }

    @Test
    public void aLegWithNoArrivalShowsOnlyItsDeparture() {
        final TripEvent open = leg("o", NOON, NOON.plusHours(1));
        open.setEnd(null);
        final ReportCommands.GroundRow row = reports.groundRows(tripWith(open)).get(0);
        Assert.assertEquals(row.getDates(), "May 3, 2028");
        Assert.assertEquals(row.getTimes(), "12:00 PM", "no arrival, no arrow");
    }

    @Test
    public void anUndatedLegFormatsToNothingRatherThanThrowing() {
        final TripEvent undated = leg("u", NOON, NOON.plusHours(1));
        undated.setStart(null);
        final ReportCommands.GroundRow row = reports.groundRows(tripWith(undated)).get(0);
        Assert.assertEquals(row.getDates(), "");
        Assert.assertEquals(row.getTimes(), "");
    }

    @Test
    public void aLegacyLegFallsBackToItsTitleAndNotes() {
        final ReportCommands.GroundRow row = reports.groundRows(tripWith(leg("old", NOON, NOON.plusHours(1)))).get(0);
        Assert.assertFalse(row.isComposed(), "an event written before the editor stores no parts");
        Assert.assertNull(row.getFrom());
        Assert.assertNull(row.getTo());
        Assert.assertNull(row.getCarrier());
        Assert.assertEquals(row.getTitle(), "A -> B");
        Assert.assertEquals(row.getRoute(), "A -> B", "with no stored endpoints the title IS the route");
        Assert.assertEquals(row.getNotes(), "notes");
        Assert.assertEquals(row.getElapsed(), "1h", "the duration is still derived from the times");
    }

    @Test
    public void anUnknownRiderIsCountedButNotNamed() {
        final Person.Id ghost = Person.Id.from("3fbd4e0a-6f5d-4c66-8b02-9a5c1e2d3f44");
        final ReportCommands.GroundRow row = reports
                .groundRows(tripWith(leg("g", NOON, NOON.plusHours(1), person(5), person(2), ghost))).get(0);
        Assert.assertEquals(row.getNames(), "Ken Paulsen, Dave Robinson");
        Assert.assertEquals(row.getCount(), 3, "the head count follows the event, not what still resolves");
    }

    @Test
    public void overnightIsTheDateChangingAndNothingElse() {
        final LocalDateTime lateNight = LocalDateTime.of(2028, 5, 3, 23, 0);
        Assert.assertTrue(reports.groundRows(tripWith(leg("n", lateNight, lateNight.plusHours(2)))).get(0)
                .isOvernight());
        Assert.assertFalse(reports.groundRows(tripWith(leg("d", NOON, NOON.plusHours(2)))).get(0).isOvernight());
        final TripEvent open = leg("o", NOON, NOON.plusHours(1));
        open.setEnd(null);
        final ReportCommands.GroundRow row = reports.groundRows(tripWith(open)).get(0);
        Assert.assertFalse(row.isOvernight());
        Assert.assertEquals(row.getElapsed(), "", "no arrival, no duration");
    }

    @Test
    public void aTripWithNoGroundLegsReportsNothing() {
        Assert.assertTrue(reports.groundRows((Trip) null).isEmpty());
        Assert.assertTrue(reports.groundRows(Trip.builder().title("Empty").build()).isEmpty());
        Assert.assertEquals(reports.groundLegCount("dfd0a6f2-0b47-4a2e-9a0f-2f7e2a5b6c31"), 0,
                "an id that names no trip answers a blank one, which has no events");
    }

    @Test
    public void theSeededDemoTripListsItsTwoTransfers() {
        final List<ReportCommands.GroundRow> rows = reports.groundRows(FakeData.FAKE_TRIP_ID);
        Assert.assertEquals(rows.size(), 3, "the fixture seeds a composed leg, an overnight leg and a legacy one");
        Assert.assertEquals(reports.groundLegCount(FakeData.FAKE_TRIP_ID), 3);
        final ReportCommands.GroundRow nightRide = rows.get(0);
        Assert.assertTrue(nightRide.isOvernight(), "the night ride sorts first and spans two days");
        Assert.assertTrue(nightRide.getDates().contains("\u2192"), nightRide.getDates());
        final ReportCommands.GroundRow inbound = rows.get(1);
        Assert.assertTrue(inbound.isComposed());
        Assert.assertEquals(inbound.getFrom(), "Split");
        Assert.assertEquals(inbound.getTo(), "Medjugorje");
        Assert.assertEquals(inbound.getCarrier(), "Globtour bus");
        Assert.assertEquals(inbound.getCount(), 3);
        Assert.assertTrue(inbound.getNames().contains("Ken Paulsen"), inbound.getNames());
        final ReportCommands.GroundRow outbound = rows.get(2);
        Assert.assertFalse(outbound.isComposed());
        Assert.assertEquals(outbound.getTitle(), "Medjugorje -> Split");
        Assert.assertEquals(outbound.getCount(), 2);
    }

    @Test
    public void theDefaultConstructorDoesNotNeedAContainerUntilItIsAsked() {
        final List<ReportCommands.GroundRow> rows = new ReportCommands()
                .groundRows(tripWith(leg("g", NOON, NOON.plusHours(1), person(2))));
        Assert.assertEquals(rows.size(), 1, "a trip in hand never reaches the CDI lookup");
        Assert.assertEquals(rows.get(0).getNames(), "Ken Paulsen");
    }

    // --- the rooming report: one page per accommodation ---

    private static LodgingViews.RoomingRow stay(final String personId, final String name, final String place,
            final String room, final String type, final LocalDateTime start, final LocalDateTime end) {
        return new LodgingViews.RoomingRow(personId, name, "555-1212", place, "1", room, type, start, end, null,
                null, true);
    }

    private static LodgingViews.RoomingRow waiting(final String personId, final String name) {
        return new LodgingViews.RoomingRow(personId, name, "555-1212", "", "", "", "", null, null, null, null,
                false);
    }

    @Test
    public void eachAccommodationGetsItsOwnPageInTheOrderTheTripReachesThem() {
        final LocalDateTime sep21 = LocalDateTime.of(2026, 9, 21, 23, 0);
        final LocalDateTime oct1 = LocalDateTime.of(2026, 10, 1, 5, 0);
        final List<ReportCommands.RoomingPage> pages = reports.roomingPages(List.of(
                stay("p3", "Cal Jean-Pierre", "Berulia", "3", "Double", oct1.plusHours(6), oct1.plusDays(2)),
                stay("p1", "Angie Briguglio", "Pansion Dragicevic", "001", "Double", sep21, oct1),
                waiting("p9", "Nobody Yet")));
        Assert.assertEquals(pages.size(), 3);
        Assert.assertEquals(pages.get(0).getAccommodation(), "Pansion Dragicevic", "earliest arrival prints first");
        Assert.assertEquals(pages.get(1).getAccommodation(), "Berulia");
        Assert.assertEquals(pages.get(2).getAccommodation(), "Not yet reserved", "and they always come last");
        Assert.assertFalse(pages.get(2).isReserved());
    }

    @Test
    public void aPageCountsPeopleNotStays() {
        final LocalDateTime sep21 = LocalDateTime.of(2026, 9, 21, 23, 0);
        final List<ReportCommands.RoomingPage> pages = reports.roomingPages(List.of(
                stay("p1", "Angie Briguglio", "Pansion", "001", "Double", sep21, sep21.plusDays(3)),
                stay("p1", "Angie Briguglio", "Pansion", "007", "Double", sep21.plusDays(3), sep21.plusDays(6)),
                stay("p2", "Cathy Kennedy", "Pansion", "003", "Double", sep21, sep21.plusDays(6))));
        final ReportCommands.RoomingPage page = pages.get(0);
        Assert.assertEquals(page.getPeople(), 2, "someone who changes rooms is still one person");
        Assert.assertEquals(page.getStays(), 3);
        Assert.assertEquals(page.getLines().size(), 3, "but every stay is a line, because each is a bed");
    }

    @Test
    public void aPageSaysWhatItCoversAndEachLineSaysItsOwnStay() {
        final LocalDateTime sep21 = LocalDateTime.of(2026, 9, 21, 23, 0);
        final LocalDateTime oct1 = LocalDateTime.of(2026, 10, 1, 5, 0);
        final ReportCommands.RoomingPage page = reports.roomingPages(List.of(
                stay("p1", "Angie Briguglio", "Pansion", "001", "Double", sep21, oct1))).get(0);
        Assert.assertEquals(page.getDates(), "Sep 21 - Oct 1");
        Assert.assertEquals(page.getLines().get(0).getDates(), "Sep 21 11:00 PM to Oct 1 5:00 AM");
        Assert.assertEquals(page.getLines().get(0).getRoom(), "001");
        Assert.assertEquals(page.getLines().get(0).getRoomType(), "Double");
    }

    @Test
    public void theBandingChangesWithTheRoomSoASharedRoomReadsAsOneBlock() {
        final LocalDateTime sep21 = LocalDateTime.of(2026, 9, 21, 23, 0);
        final ReportCommands.RoomingPage page = reports.roomingPages(List.of(
                stay("p1", "Angie", "Pansion", "001", "Double", sep21, sep21.plusDays(1)),
                stay("p2", "Nicolina", "Pansion", "001", "Double", sep21, sep21.plusDays(1)),
                stay("p3", "Cal", "Pansion", "002", "Triple", sep21, sep21.plusDays(1)))).get(0);
        Assert.assertEquals(page.getLines().get(0).isStripe(), page.getLines().get(1).isStripe(),
                "two people in room 001 share a band");
        Assert.assertNotEquals(page.getLines().get(2).isStripe(), page.getLines().get(1).isStripe(),
                "and the next room starts a new one");
    }

    @Test
    public void aTripWithNoLodgingHasNoPages() {
        Assert.assertTrue(reports.roomingPages(List.<LodgingViews.RoomingRow>of()).isEmpty());
    }

    /**
     * A trip with two hotels, one option without a cost: the whole-trip view (internal, finance, nobody) is
     * refused in the internal wording; the operator of the costed hotel is ready while the operator of the
     * other is refused in the operator's wording and for their hotel alone; stating the cost readies everyone,
     * and the message is "" once ready. The same answers reach a page through {@code lodging.invoiceReady}.
     */
    @Test
    public void invoiceReadinessFollowsTheCallersViewOfTheTrip() throws IOException {
        final LodgingCommands admin = new LodgingCommands(TestCallers::siteAdmin);
        final Organization org = new OrgCommands(TestCallers::siteAdmin)
                .createOrganization("Ready " + RandomData.genAlpha(8), "RDY", null);
        Assert.assertNotNull(org);
        final Person guestA = savedPerson("Ana");
        final Person guestB = savedPerson("Bo");
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Readiness trip")
                .startDate(CHECK_IN).endDate(CHECK_OUT.plusDays(2))
                .people(new ArrayList<>(List.of(guestA.getId(), guestB.getId()))).build();
        trip.setOrgId(org.getId().getValue());
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        final String hotelA = hotel(admin, org.getId().getValue(), "Costed House");
        final String hotelB = hotel(admin, org.getId().getValue(), "Quiet Inn");
        reserve(admin, trip.getId(), offerAt(admin, trip.getId(), hotelA, "Double at A", true), guestA);
        final String offerB = offerAt(admin, trip.getId(), hotelB, "Single at B", false);
        reserve(admin, trip.getId(), offerB, guestB);
        final String nameOfB = "Single at B (" + admin.findAccommodation(hotelB).getName() + ")";

        final LodgingAccess internal = new LodgingAccess(trip.getId(), true, Set.of(), Set.of(hotelA, hotelB));
        final LodgingAccess operatorA = new LodgingAccess(trip.getId(), false, Set.of(hotelA), Set.of());
        final LodgingAccess operatorB = new LodgingAccess(trip.getId(), false, Set.of(hotelB), Set.of());
        final LodgingAccess both = new LodgingAccess(trip.getId(), false, Set.of(hotelA, hotelB), Set.of());
        Assert.assertFalse(reports.invoiceReady(trip.getId(), internal));
        Assert.assertEquals(reports.invoiceBlockedMessage(trip.getId(), internal),
                "No hotel cost is set for " + nameOfB + ". Set the cost on the trip's Offers tab (Edit the option, "
                        + "tick 'Hotel cost is known') or ask the hotel's operator to set it on the hotel's Trips "
                        + "tab.");
        Assert.assertFalse(reports.invoiceReady(trip.getId(), null), "no access object: the whole trip");
        Assert.assertFalse(reports.invoiceReady(trip.getId(), LodgingAccess.none(trip.getId())));
        Assert.assertTrue(reports.invoiceReady(trip.getId(), operatorA), "A's options all have a cost");
        Assert.assertEquals(reports.invoiceBlockedMessage(trip.getId(), operatorA), "");
        Assert.assertFalse(reports.invoiceReady(trip.getId(), operatorB));
        Assert.assertEquals(reports.invoiceBlockedMessage(trip.getId(), operatorB),
                "No hotel cost is set for " + nameOfB + ". Set the cost on this trip's options on your hotel's "
                        + "Trips tab.");
        Assert.assertFalse(reports.invoiceReady(trip.getId(), both));
        Assert.assertTrue(reports.invoiceReady("no-such-trip", internal), "nothing to refuse");
        Assert.assertEquals(reports.invoiceBlockedMessage("no-such-trip", internal), "");
        Assert.assertFalse(admin.invoiceReady(trip.getId()), "the bean's one-liner: a site admin is internal");
        Assert.assertTrue(admin.invoiceBlockedMessage(trip.getId()).contains("Offers tab"));

        final OfferCostForm cost = admin.offerCostFormFor(trip.getId(), offerB);
        cost.setCostKnown(true);
        cost.setCostNightlyPrice(70.0);
        Assert.assertTrue(admin.setOfferCost(cost));
        Assert.assertTrue(reports.invoiceReady(trip.getId(), internal));
        Assert.assertTrue(reports.invoiceReady(trip.getId(), operatorB));
        Assert.assertEquals(reports.invoiceBlockedMessage(trip.getId(), operatorB), "");
        Assert.assertTrue(admin.invoiceReady(trip.getId()));
        Assert.assertEquals(admin.invoiceBlockedMessage(trip.getId()), "");
        Assert.assertEquals(reports.roomInvoice(trip.getId()).getSections().size(), 2);
    }

    // ------------------------------------------------------------------ lodging fixtures

    private static final LocalDateTime CHECK_IN = LocalDateTime.of(2027, 11, 2, 15, 0);
    private static final LocalDateTime CHECK_OUT = LocalDateTime.of(2027, 11, 5, 10, 0);

    private static Person savedPerson(final String first) throws IOException {
        final Person person = Person.builder().first(first).last(RandomData.genAlpha(8))
                .email(first.toLowerCase(Locale.ROOT) + "." + RandomData.genAlpha(8).toLowerCase(Locale.ROOT)
                        + "@example.com").build();
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }

    private static String hotel(final LodgingCommands admin, final String orgId, final String name) {
        final AccommodationForm form = new AccommodationForm();
        final String tag = RandomData.genAlpha(8);
        form.setName(name + " " + tag);
        form.setEmail(tag.toLowerCase(Locale.ROOT) + "@ready.example");
        // Discovery compares phone DIGITS only, so the random part must be digits.
        form.setPhone("+1 503 " + (1_000_000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(9_000_000)));
        form.setStreet(tag + " Way");
        form.setCity("Portland");
        final String accId = admin.saveAccommodation(form, orgId);
        Assert.assertTrue(accId != null && !accId.isBlank(), "the hotel fixture was refused");
        final RoomTypeForm type = new RoomTypeForm();
        type.setName("Double");
        type.setMinPeople(1);
        type.setMaxPeople(2);
        Assert.assertTrue(admin.saveRoomType(accId, type));
        return accId;
    }

    private static String offerAt(final LodgingCommands admin, final String tripId, final String accId,
            final String name, final boolean costed) {
        final OfferForm form = new OfferForm();
        form.setName(name);
        form.setAccommodationId(accId);
        form.setRoomTypeIds(new ArrayList<>(List.of(admin.roomTypeRows(accId).get(0).getId())));
        form.setTripEventId(OfferForm.NEW_EVENT);
        form.setPricingModel("PER_ROOM");
        form.setNightlyPrice(100.0);
        form.setCostKnown(costed);
        form.setCostNightlyPrice(90.0);
        form.setDefaultStart(CHECK_IN);
        form.setDefaultEnd(CHECK_OUT);
        Assert.assertTrue(admin.saveOffer(tripId, form), form.getProblem());
        return DAO.getInstance().getReservationOffers(tripId, Cached.NO).stream()
                .filter(offer -> name.equals(offer.getName())).findFirst().orElseThrow().getId().getValue();
    }

    private static void reserve(final LodgingCommands admin, final String tripId, final String offerId,
            final Person who) {
        final ReservationForm form = new ReservationForm();
        form.setOfferId(offerId);
        form.setPersonIds(List.of(who.getId().getValue()));
        form.setStart(CHECK_IN);
        form.setEnd(CHECK_OUT);
        Assert.assertEquals(admin.createReservations(tripId, form), 1, "the stay fixture was refused");
    }

    @Test
    public void theSeededTripRoomsItsGuestsUnderOneRoof() {
        final List<ReportCommands.RoomingPage> pages = reports.roomingPages(FakeData.FAKE_TRIP_ID);
        Assert.assertFalse(pages.isEmpty(), "the local fixture reserves rooms for two of its people");
        final ReportCommands.RoomingPage hotel = pages.get(0);
        Assert.assertTrue(hotel.isReserved(), hotel.getAccommodation());
        Assert.assertEquals(hotel.getPeople(), reports.roomedPeopleCount(FakeData.FAKE_TRIP_ID),
                "one hotel, so its people ARE the trip's roomed people");
        Assert.assertEquals(pages.get(pages.size() - 1).getAccommodation(), "Not yet reserved",
                "everyone else is still waiting, on a page of their own");
    }
}
