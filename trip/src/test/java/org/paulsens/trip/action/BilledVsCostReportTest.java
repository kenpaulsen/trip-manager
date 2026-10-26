package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
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
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Billed vs Cost: the PRICE build and the COST build of the same stays zipped per hotel, per option and per
 * person, with billed - cost as the difference. Extras and cost concessions move the cost column only; a price
 * concession moves the billed column only; a row on one side alone shows zero on the other. Unlike the
 * invoice, an option without a cost does not block the report: its cost reads "not set" at zero.
 */
public class BilledVsCostReportTest {

    private static final LocalDateTime ARRIVE = LocalDateTime.of(2028, 9, 21, 15, 0);
    private static final LocalDateTime DEPART = ARRIVE.plusDays(12).withHour(10);

    private final ReportCommands reports = new ReportCommands(TripCommands::new, LodgingCommands::new);

    private final RoomType single = RoomType.builder().id("sgl").name("Single").minPeople(1).maxPeople(1).build();
    private final RoomType dbl = RoomType.builder().id("dbl").name("Double").minPeople(1).maxPeople(2).build();
    private final Accommodation hotel = Accommodation.builder().name("Pansion Ana").roomTypes(List.of(single, dbl))
            .rooms(List.of(room("001", "sgl"), room("003", "dbl"), room("004", "dbl"))).build();
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
                .roomTypeIds(List.of("dbl")).pricingModel(PricingModel.PER_ROOM).nightlyPriceCents(cents)
                .minNights(1).build();
    }

    private static Reservation stay(final ReservationOffer offer, final String roomId, final String... people) {
        return Reservation.builder().offerId(offer.getId()).accommodationId(offer.getAccommodationId())
                .roomId(roomId).start(ARRIVE).end(DEPART)
                .occupants(Arrays.stream(people).map(Person.Id::from).toList()).build();
    }

    private ReportCommands.BilledVsCost compare(final List<ReservationOffer> offers, final Reservation... stays) {
        final ReportCommands.BilledVsCost report = reports.billedVsCost(List.of(stays),
                id -> offers.stream().filter(o -> o.getId().getValue().equals(id)).findFirst().orElse(null),
                this::findHotel, Person.Id::getValue);
        for (final ReportCommands.BilledVsCostSection section : report.getSections()) {
            Assert.assertEquals(section.getDifferenceCents(), section.getBilledCents() - section.getCostCents());
            for (final ReportCommands.BilledVsCostOption option : section.getOptions()) {
                Assert.assertEquals(option.getDifferenceCents(), option.getBilledCents() - option.getCostCents());
            }
            for (final ReportCommands.BilledVsCostPerson who : section.getPeople()) {
                Assert.assertEquals(who.getDifferenceCents(), who.getBilledCents() - who.getCostCents());
            }
        }
        Assert.assertEquals(report.getBilledCents(),
                report.getSections().stream().mapToLong(ReportCommands.BilledVsCostSection::getBilledCents).sum());
        Assert.assertEquals(report.getCostCents(),
                report.getSections().stream().mapToLong(ReportCommands.BilledVsCostSection::getCostCents).sum());
        Assert.assertEquals(report.getDifferenceCents(), report.getBilledCents() - report.getCostCents());
        return report;
    }

    private Accommodation findHotel(final String id) {
        return hotel.getId().getValue().equals(id) ? hotel : second.getId().getValue().equals(id) ? second : null;
    }

    private static ReportCommands.BilledVsCostPerson person(final ReportCommands.BilledVsCostSection section,
            final String name) {
        return section.getPeople().stream().filter(who -> who.getName().equals(name)).findFirst().orElseThrow();
    }

    /** Participants pay $50 a head plus a $10 supplement when alone; the hotel charges $70 a room. */
    @Test
    public void theDifferenceIsPerOptionAndPerPerson() {
        final ReservationOffer option = perPerson(hotel, 5_000L, 1_000L);
        option.setCost(RateSchedule.builder().pricingModel(PricingModel.PER_ROOM).nightlyCents(7_000L).build());
        final ReportCommands.BilledVsCost report = compare(List.of(option),
                stay(option, "003", "a", "b"), stay(option, "001", "solo"));
        Assert.assertEquals(report.getSections().size(), 1);
        final ReportCommands.BilledVsCostSection section = report.getSections().get(0);
        Assert.assertEquals(section.getAccommodation(), "Pansion Ana");
        Assert.assertEquals(section.getOptions().size(), 1);
        final ReportCommands.BilledVsCostOption row = section.getOptions().get(0);
        Assert.assertEquals(row.getName(), "Any room, per person");
        Assert.assertEquals(row.getOfferId(), option.getId().getValue());
        Assert.assertEquals(row.getBilledCents(), 3 * 12 * 5_000L + 12 * 1_000L);
        Assert.assertEquals(row.getCostCents(), 2 * 12 * 7_000L);
        Assert.assertEquals(row.getBilled(), "$1,920.00");
        Assert.assertEquals(row.getCost(), "$1,680.00");
        Assert.assertEquals(row.getDifference(), "$240.00");
        Assert.assertFalse(row.isCostAssumed());
        Assert.assertEquals(section.getPeople().stream().map(ReportCommands.BilledVsCostPerson::getName).toList(),
                List.of("a", "b", "solo"), "by name");
        final ReportCommands.BilledVsCostPerson a = person(section, "a");
        Assert.assertEquals(a.getBilledCents(), 60_000L);
        Assert.assertEquals(a.getCostCents(), 42_000L, "half of a $70.00 room for 12 nights");
        Assert.assertEquals(a.getDifference(), "$180.00");
        Assert.assertEquals(a.getNights(), 12);
        Assert.assertEquals(a.getDates(), "Sep 21 - Oct 3, 2028");
        Assert.assertEquals(a.getOption(), "Any room, per person");
        final ReportCommands.BilledVsCostPerson solo = person(section, "solo");
        Assert.assertEquals(solo.getBilledCents(), 72_000L, "with the supplement");
        Assert.assertEquals(solo.getCostCents(), 84_000L, "the whole room");
        Assert.assertEquals(solo.getDifference(), "-$120.00", "signed in front");
        Assert.assertEquals(section.getBilled(), "$1,920.00");
        Assert.assertEquals(section.getDifference(), "$240.00");
        Assert.assertEquals(report.getBilled(), "$1,920.00");
        Assert.assertEquals(report.getCost(), "$1,680.00");
        Assert.assertEquals(report.getDifference(), "$240.00");
        Assert.assertEquals(report.getMissingCost(), 0);
        Assert.assertTrue(report.getMissingCostNames().isEmpty());
    }

    @Test
    public void extrasLandInTheCostColumnOnly() {
        final ReservationOffer option = perRoom(hotel, 6_000L);
        option.setCost(RateSchedule.builder().nightlyCents(6_000L).build());
        option.setCostExtras(List.of(CostExtra.builder().label("Booking fee").basis(CostExtra.Basis.FIXED)
                .amountCents(1_500L).build()));
        final ReportCommands.BilledVsCost report = compare(List.of(option), stay(option, "003", "a", "b"));
        final ReportCommands.BilledVsCostOption row = report.getSections().get(0).getOptions().get(0);
        Assert.assertEquals(row.getBilledCents(), 72_000L);
        Assert.assertEquals(row.getCostCents(), 73_500L);
        Assert.assertEquals(row.getDifference(), "-$15.00");
        Assert.assertFalse(row.isCostAssumed());
        Assert.assertEquals(report.getMissingCost(), 0);
        final ReportCommands.BilledVsCostPerson a = person(report.getSections().get(0), "a");
        Assert.assertEquals(a.getBilledCents(), a.getCostCents(), "an extra is never attributed to a person");
        Assert.assertEquals(a.getDifference(), "$0.00");
    }

    /** An option without a cost is not a guess here: it reads "not set", counts 0 and is named on the report. */
    @Test
    public void anOptionWithoutACostReadsNotSetAndCountsNothing() {
        final ReservationOffer unstated = perRoom(hotel, 6_000L);
        final ReportCommands.BilledVsCost report = compare(List.of(unstated), stay(unstated, "003", "a", "b"));
        final ReportCommands.BilledVsCostOption row = report.getSections().get(0).getOptions().get(0);
        Assert.assertEquals(row.getBilledCents(), 72_000L, "the participants are billed as usual");
        Assert.assertEquals(row.getCostCents(), 0L, "nothing is assumed from the price");
        Assert.assertEquals(row.getCost(), "not set");
        Assert.assertTrue(row.isCostAssumed());
        Assert.assertEquals(row.getDifference(), "$720.00");
        Assert.assertEquals(report.getMissingCost(), 1);
        Assert.assertEquals(report.getMissingCostNames(), List.of("Double room, shared (Pansion Ana)"));
        final ReportCommands.BilledVsCostPerson a = person(report.getSections().get(0), "a");
        Assert.assertEquals(a.getBilledCents(), 36_000L);
        Assert.assertEquals(a.getCostCents(), 0L);
    }

    @Test
    public void eachConcessionMovesItsOwnSide() {
        final ReservationOffer option = perRoom(hotel, 6_000L);
        option.setCost(RateSchedule.builder().nightlyCents(6_000L).build());
        final Reservation scholarship = stay(option, "003", "a", "b");
        scholarship.setPriceConcession(Concession.builder().kind(Concession.Kind.PERCENT).percentBps(5_000).build());
        final Reservation comped = stay(option, "004", "c");
        comped.setCostConcession(Concession.builder().kind(Concession.Kind.TOTAL).amountCents(12_000L).build());
        final ReportCommands.BilledVsCost report = compare(List.of(option), scholarship, comped);
        final ReportCommands.BilledVsCostSection section = report.getSections().get(0);
        final ReportCommands.BilledVsCostOption row = section.getOptions().get(0);
        Assert.assertEquals(row.getBilledCents(), 36_000L + 72_000L, "half the first room, all of the second");
        Assert.assertEquals(row.getCostCents(), 72_000L + 60_000L, "all of the first, $120.00 off the second");
        Assert.assertEquals(row.getDifferenceCents(), -24_000L);
        Assert.assertEquals(person(section, "a").getBilledCents(), 18_000L);
        Assert.assertEquals(person(section, "a").getCostCents(), 36_000L);
        Assert.assertEquals(person(section, "c").getBilledCents(), 72_000L);
        Assert.assertEquals(person(section, "c").getCostCents(), 60_000L);
        Assert.assertEquals(person(section, "c").getDifference(), "$120.00");
    }

    @Test
    public void aRowOnOneSideOnlyShowsZeroOnTheOther() {
        final ReservationOffer costOnly = ReservationOffer.builder().name("Hotel-stated only")
                .accommodationId(hotel.getId()).roomTypeIds(List.of("dbl"))
                .cost(RateSchedule.builder().nightlyCents(4_000L).build()).build();
        final ReservationOffer neither = ReservationOffer.builder().name("Needs pricing")
                .accommodationId(second.getId()).roomTypeIds(List.of("dbl")).build();
        final ReportCommands.BilledVsCost report = compare(List.of(costOnly, neither),
                stay(costOnly, "003", "a"), stay(neither, "s1", "b"));
        Assert.assertEquals(report.getSections().size(), 1, "an option with no schedule on either side has no row");
        final ReportCommands.BilledVsCostOption row = report.getSections().get(0).getOptions().get(0);
        Assert.assertEquals(row.getBilled(), "$0.00");
        Assert.assertEquals(row.getCostCents(), 48_000L);
        Assert.assertEquals(row.getDifference(), "-$480.00");
        Assert.assertFalse(row.isCostAssumed());
        Assert.assertEquals(report.getMissingCost(), 1, "the unpriced, uncosted option is still named...");
        Assert.assertEquals(report.getMissingCostNames(), List.of("Needs pricing (Hotel Split)"), "...with no row");
        Assert.assertEquals(person(report.getSections().get(0), "a").getBilledCents(), 0L);
        Assert.assertEquals(person(report.getSections().get(0), "a").getCostCents(), 48_000L);
    }

    @Test
    public void hotelsAreSectionsOfTheirOwn() {
        final ReservationOffer here = perRoom(hotel, 6_000L);
        final ReservationOffer there = perRoom(second, 8_000L);
        there.setCost(RateSchedule.builder().nightlyCents(5_000L).build());
        final ReportCommands.BilledVsCost report = compare(List.of(here, there),
                stay(here, "003", "a"), stay(there, "s1", "b"));
        Assert.assertEquals(report.getSections().stream().map(ReportCommands.BilledVsCostSection::getAccommodation)
                .toList(), List.of("Hotel Split", "Pansion Ana"), "the invoice's order: same arrival, by name");
        Assert.assertEquals(report.getSections().get(0).getDifferenceCents(), 12 * 3_000L);
        Assert.assertEquals(report.getSections().get(1).getDifferenceCents(), 12 * 6_000L, "no cost: billed - 0");
        Assert.assertEquals(report.getBilledCents(), 12 * 6_000L + 12 * 8_000L);
        Assert.assertEquals(report.getCostCents(), 12 * 5_000L, "Pansion Ana's cost is not set, so it counts 0");
        Assert.assertEquals(report.getMissingCost(), 1);
        Assert.assertTrue(report.getSections().get(1).getOptions().get(0).isCostAssumed());
        Assert.assertFalse(report.getSections().get(0).getOptions().get(0).isCostAssumed());
    }

    @Test
    public void nothingReservedIsAnEmptyReportAtZero() {
        final ReportCommands.BilledVsCost report = compare(List.of());
        Assert.assertTrue(report.getSections().isEmpty());
        Assert.assertEquals(report.getBilled(), "$0.00");
        Assert.assertEquals(report.getDifference(), "$0.00");
    }

    @Test
    public void anUnknownTripIsAnEmptyReport() {
        Assert.assertTrue(reports.billedVsCost("no-such-trip").getSections().isEmpty());
        Assert.assertTrue(reports.billedVsCost(null).getSections().isEmpty());
        Assert.assertFalse(reports.billedVsCost(FakeData.FAKE_TRIP_ID).getSections().isEmpty(),
                "the seeded trip has a stay to compare");
    }
}
