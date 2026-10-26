package org.paulsens.trip.pay;

import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.model.BudgetCategory;
import org.paulsens.trip.model.BudgetLine;
import org.paulsens.trip.model.BudgetLineKind;
import org.paulsens.trip.model.BudgetLodgingLine;
import org.paulsens.trip.model.CostExtra;
import org.paulsens.trip.model.PricingModel;
import org.paulsens.trip.model.RateSchedule;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.TripBudget;
import org.paulsens.trip.pay.BudgetMath.LodgingFigures;
import org.paulsens.trip.pay.BudgetMath.OtherItem;
import org.paulsens.trip.pay.BudgetMath.PayeeTotal;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The budget arithmetic against its two oracles: the Holy Angels "Budget" sheet the feature replaced, and one
 * lodging line on the seeded rate plan's shape (PER_PERSON $60 + $20 single price; PER_ROOM $50 + FIXED $12
 * city tax cost). See docs/budget.md.
 */
public class BudgetMathTest {

    /** The sheet's shared lines, inputs and payees: 46 people, 10 nights, $400 budget, $1,000 to bill. */
    static TripBudget sheet() {
        final List<BudgetLine> lines = new ArrayList<>();
        lines.add(shared(BudgetCategory.GENERAL, "Van", 0, null));
        lines.add(shared(BudgetCategory.GENERAL, "Badges + Printing", 300_00, null));
        lines.add(shared(BudgetCategory.GENERAL, "Guide", 1_440_00, "Guide"));
        lines.add(shared(BudgetCategory.GENERAL, "Van PDX-SEA", 0, null));
        lines.add(shared(BudgetCategory.GENERAL, "Buses + Transfers", 3_000_00, null));
        lines.add(shared(BudgetCategory.GENERAL, "Tips", 700_00, "Tips"));
        lines.add(shared(BudgetCategory.GENERAL, "Misc", 500_00, "Other"));
        lines.add(shared(BudgetCategory.SCHOLARSHIP, "Sr. Angelicah", 1_000_00, null));
        lines.add(shared(BudgetCategory.SCHOLARSHIP, "Sr. Dorothy", 1_848_53, null));
        lines.add(shared(BudgetCategory.SCHOLARSHIP, "Unnamed", 1_000_00, null));
        lines.add(shared(BudgetCategory.LEADER, "Audie", 2_000_00, null));
        lines.add(shared(BudgetCategory.LEADER, "Ken Air + Room", 1_806_03, null));
        lines.add(shared(BudgetCategory.LEADER, "Fr John Air + Room", 2_307_63, null));
        return TripBudget.builder().tripId("t").people(46).tripNights(10).perPersonBudgetCents(400_00)
                .toBillCents(1_000_00).lines(lines).build();
    }

    static BudgetLine shared(final BudgetCategory category, final String name, final long cents,
            final String payee) {
        return BudgetLine.builder().kind(BudgetLineKind.SHARED).category(category).name(name).amountCents(cents)
                .payee(payee).build();
    }

    /** The lodging oracle's option: price PER_PERSON $60 + $20 single; cost PER_ROOM $50 + FIXED $12 city tax. */
    static ReservationOffer seededShape() {
        return ReservationOffer.builder().id(ReservationOffer.Id.from("offer-1")).name("Double room")
                .price(schedule(PricingModel.PER_PERSON, 60_00, 20_00))
                .cost(schedule(PricingModel.PER_ROOM, 50_00, 0))
                .costExtras(List.of(extra(CostExtra.Basis.FIXED, 12_00))).build();
    }

    static RateSchedule schedule(final PricingModel model, final long nightly, final long supplement) {
        return RateSchedule.builder().pricingModel(model).nightlyCents(nightly).singleSupplementCents(supplement)
                .build();
    }

    static CostExtra extra(final CostExtra.Basis basis, final long cents) {
        return CostExtra.builder().label("City tax").basis(basis).amountCents(cents).build();
    }

    static BudgetLodgingLine oracleLine() {
        return BudgetLodgingLine.builder().id("room").offerId("offer-1").nights(10).people(46).rooms(28).singles(10)
                .payee("Huanita").build();
    }

    @Test
    public void theSheetsSharedFigures() {
        final TripBudget budget = sheet();
        assertEquals(BudgetMath.sharedTotal(budget, BudgetCategory.GENERAL), 5_940_00L);
        assertEquals(BudgetMath.sharedTotal(budget, BudgetCategory.SCHOLARSHIP), 3_848_53L);
        assertEquals(BudgetMath.sharedTotal(budget, BudgetCategory.LEADER), 6_113_66L);
        assertEquals(BudgetMath.sharedTotal(budget), 15_902_19L, "Ground cost (excluding lodging)");
        assertEquals(BudgetMath.estimatedPerPerson(budget), 345_70L, "15,902.19 / 46, HALF_UP");
        assertEquals(BudgetMath.individualExtras(budget), 0L);
        assertEquals(BudgetMath.budgetLeftOver(budget), 2_497_81L, "400 x 46 - 15,902.19");
    }

    @Test
    public void theLodgingOracle() {
        final TripBudget budget = sheet();
        final LodgingFigures figures = BudgetMath.lodging(oracleLine(), seededShape());
        assertEquals(figures.revenue(), 29_600_00L, "46 x 10 x $60 + 10 x 10 x $20");
        assertEquals(figures.hotelCost(), 14_012_00L, "28 x 10 x $50 + $12");
        assertEquals(figures.margin(), 15_588_00L);
        assertEquals(figures.perPersonPrice(), 600_00L);
        assertEquals(figures.singleAddOn(), 200_00L);
        assertTrue(figures.singleAddOnApplies());
        assertFalse(figures.priceMissing());
        assertFalse(figures.costMissing());
        assertEquals(figures.perPersonCost(), 304_61L, "14,012.00 / 46 = 304.608..., HALF_UP");
        assertEquals(BudgetMath.totalPerPersonGround(budget, figures), 650_31L,
                "345.70 shared + 304.61 hotel cost: the participant price never enters a cost");
        assertEquals(BudgetMath.toBillReference(budget, figures), 1_000_00L);
        assertEquals(BudgetMath.overall(budget, figures.margin()), 18_085_81L);
    }

    @Test
    public void aPerRoomPriceSharesTheRoomAndHasNoSingleAddOn() {
        final ReservationOffer offer = ReservationOffer.builder().price(schedule(PricingModel.PER_ROOM, 100_00, 0))
                .cost(schedule(PricingModel.PER_PERSON, 30_00, 5_00)).build();
        final BudgetLodgingLine line = BudgetLodgingLine.builder().nights(3).people(7).rooms(4).singles(1).build();
        final LodgingFigures figures = BudgetMath.lodging(line, offer);
        assertEquals(figures.revenue(), 1_200_00L, "4 rooms x 3 nights x $100");
        assertEquals(figures.perPersonPrice(), 171_43L, "1,200 / 7 = 171.428..., HALF_UP");
        assertFalse(figures.singleAddOnApplies(), "A single occupant pays the whole room");
        assertEquals(figures.singleAddOn(), 0L);
        assertEquals(figures.hotelCost(), 645_00L, "7 x 3 x $30 + 1 x 3 x $5");
        assertEquals(figures.perPersonCost(), 92_14L, "645 / 7 = 92.142..., the single supplement spread over all");
        assertEquals(BudgetMath.lodging(BudgetLodgingLine.builder().nights(3).build(), offer).perPersonPrice(), 0L,
                "Nobody to share it: 0, not a division by zero");
    }

    @Test
    public void missingSchedulesCountZeroAndSayWhy() {
        final BudgetLodgingLine line = oracleLine();
        final ReservationOffer unpriced = ReservationOffer.builder().cost(schedule(PricingModel.PER_ROOM, 50_00, 0))
                .costExtras(List.of(extra(CostExtra.Basis.FIXED, 12_00))).build();
        final LodgingFigures noPrice = BudgetMath.lodging(line, unpriced);
        assertTrue(noPrice.priceMissing());
        assertFalse(noPrice.costMissing());
        assertEquals(noPrice.revenue(), 0L);
        assertEquals(noPrice.perPersonPrice(), 0L);
        assertEquals(noPrice.margin(), -14_012_00L, "Negative margin is reported, not clamped");

        final ReservationOffer costless = ReservationOffer.builder().nightlyPriceCents(60_00)
                .costExtras(List.of(extra(CostExtra.Basis.FIXED, 12_00))).build();
        final LodgingFigures noCost = BudgetMath.lodging(line, costless);
        assertTrue(noCost.costMissing());
        assertEquals(noCost.hotelCost(), 0L, "No cost: the extras do not count either");

        final LodgingFigures gone = BudgetMath.lodging(line, null);
        assertTrue(gone.priceMissing());
        assertTrue(gone.costMissing());
        assertEquals(gone.revenue() + gone.hotelCost() + gone.perPersonPrice() + gone.perPersonCost(), 0L);
        assertEquals(BudgetMath.lodging(null, seededShape()), gone);
    }

    @Test
    public void extrasMultiplyByTheirBasis() {
        final BudgetLodgingLine line = BudgetLodgingLine.builder().nights(2).people(5).rooms(3).build();
        assertEquals(costWith(line, CostExtra.Basis.PER_PERSON_NIGHT, 1_00), 10_00L, "5 x 2 guest-nights");
        assertEquals(costWith(line, CostExtra.Basis.PER_ROOM_NIGHT, 1_00), 6_00L, "3 x 2 room-nights");
        assertEquals(costWith(line, CostExtra.Basis.PER_STAY, 1_00), 3_00L, "one stay per room");
        assertEquals(costWith(line, CostExtra.Basis.FIXED, 1_00), 1_00L);
        assertEquals(costWith(line, CostExtra.Basis.FIXED, -4_00), -4_00L, "A coupon is a negative extra");
    }

    private static long costWith(final BudgetLodgingLine line, final CostExtra.Basis basis, final long cents) {
        final ReservationOffer offer = ReservationOffer.builder().cost(schedule(PricingModel.PER_ROOM, 0, 0))
                .costExtras(List.of(extra(basis, cents))).build();
        return BudgetMath.lodging(line, offer).hotelCost();
    }

    @Test
    public void whichFieldsAnOptionNeeds() {
        assertTrue(BudgetMath.needsRooms(seededShape()), "A per-room cost needs rooms");
        assertTrue(BudgetMath.needsSingles(seededShape()), "A per-person price with a supplement needs singles");
        final ReservationOffer perPerson = ReservationOffer.builder()
                .price(schedule(PricingModel.PER_PERSON, 60_00, 0)).cost(schedule(PricingModel.PER_PERSON, 40_00, 0))
                .build();
        assertFalse(BudgetMath.needsRooms(perPerson));
        assertFalse(BudgetMath.needsSingles(perPerson), "No supplement, no singles");
        perPerson.setCostExtras(List.of(extra(CostExtra.Basis.PER_STAY, 1_00)));
        assertTrue(BudgetMath.needsRooms(perPerson), "A per-stay extra counts rooms");
        perPerson.setCostExtras(List.of(extra(CostExtra.Basis.PER_ROOM_NIGHT, 1_00)));
        assertTrue(BudgetMath.needsRooms(perPerson));
        perPerson.setCostExtras(List.of(extra(CostExtra.Basis.PER_PERSON_NIGHT, 1_00)));
        assertFalse(BudgetMath.needsRooms(perPerson));
        perPerson.setCost(null);
        perPerson.setCostExtras(List.of(extra(CostExtra.Basis.PER_STAY, 1_00)));
        assertFalse(BudgetMath.needsRooms(perPerson), "Extras without a cost count nothing");
        assertFalse(BudgetMath.needsRooms(null));
        assertFalse(BudgetMath.needsSingles(null));
        assertTrue(BudgetMath.needsSingles(ReservationOffer.builder()
                .cost(schedule(PricingModel.PER_PERSON, 1, 1)).build()), "A cost-side supplement counts too");
    }

    @Test
    public void individualLinesAddPerPersonAndPerNight() {
        final TripBudget budget = sheet();
        budget.getLines().add(BudgetLine.builder().kind(BudgetLineKind.INDIVIDUAL).name("Snacks").amountCents(5_00)
                .perNight(true).payee("Market").build());
        budget.getLines().add(BudgetLine.builder().kind(BudgetLineKind.INDIVIDUAL).name("Rosary").amountCents(3_00)
                .build());
        assertEquals(BudgetMath.individualExtras(budget), 53_00L, "$5 x 10 nights + $3");
        final LodgingFigures figures = BudgetMath.lodging(oracleLine(), seededShape());
        assertEquals(BudgetMath.totalPerPersonGround(budget, figures), 703_31L);
        assertEquals(BudgetMath.toBillReference(budget, figures), 1_053_00L);
        assertEquals(BudgetMath.sharedTotal(budget), 15_902_19L, "Individual lines are not shared costs");
    }

    @Test
    public void halfUpAndNobodyCounted() {
        assertEquals(BudgetMath.divide(5, 2), 3L, "2.5 rounds up");
        assertEquals(BudgetMath.divide(-5, 2), -3L, "HALF_UP rounds away from zero");
        assertEquals(BudgetMath.divide(100, 0), 0L);
        final TripBudget nobody = sheet();
        nobody.setPeople(0);
        assertEquals(BudgetMath.estimatedPerPerson(nobody), 0L);
        assertEquals(BudgetMath.budgetLeftOver(nobody), -15_902_19L, "Signed: a deficit");
        assertEquals(BudgetMath.overall(nobody, -1_00L), -15_903_19L);
    }

    @Test
    public void payeesGroupCaseInsensitivelyAndLodgingDefaultsToTheHotel() {
        final TripBudget budget = TripBudget.builder().people(46).tripNights(10).build();
        budget.getLines().add(shared(BudgetCategory.GUIDE, "Guide", 1_440_00, "Guide"));
        budget.getLines().add(shared(BudgetCategory.TIPS, "Tips", 700_00, "Tips"));
        budget.getLines().add(shared(BudgetCategory.TIPS, "More tips", 50_00, "  tips "));
        budget.getLines().add(shared(BudgetCategory.SUPPLIES, "Badges", 300_00, null));
        budget.getLines().add(BudgetLine.builder().kind(BudgetLineKind.INDIVIDUAL).name("Snacks").amountCents(1_00)
                .perNight(true).payee("Market").build());
        budget.getLodging().add(oracleLine());
        final BudgetLodgingLine unnamed = BudgetLodgingLine.builder().id("other").offerId("offer-1").nights(1)
                .people(2).rooms(1).build();
        budget.getLodging().add(unnamed);
        final List<PayeeTotal> payees = BudgetMath.payeeTotals(budget,
                line -> BudgetMath.lodging(line, seededShape()), line -> "Pansion Ivan");
        assertEquals(payees, List.of(
                new PayeeTotal("Huanita", 1, 14_012_00L, "Lodging"),
                new PayeeTotal("Pansion Ivan", 1, 62_00L, "Lodging"),
                new PayeeTotal("Guide", 1, 1_440_00L, "Guide"),
                new PayeeTotal("Tips", 2, 750_00L, "Tips"),
                new PayeeTotal("[Badges]", 1, 300_00L, "Supplies"),
                new PayeeTotal("Market", 1, 460_00L, "Per-person extra")));
        assertEquals(BudgetMath.grandTotal(payees), 17_024_00L);
        assertEquals(BudgetMath.grandTotal(List.of()), 0L);
        final List<PayeeTotal> unknownHotel = BudgetMath.payeeTotals(TripBudget.builder()
                .lodging(List.of(BudgetLodgingLine.builder().label("Gone hotel").build())).build(),
                line -> BudgetMath.lodging(line, null), line -> null);
        assertEquals(unknownHotel.get(0).name(), "[Gone hotel]", "no payee and no hotel: the line's own name");
        final List<PayeeTotal> unlabelled = BudgetMath.payeeTotals(TripBudget.builder()
                .lines(List.of(BudgetLine.builder().build())).build(), line -> null, line -> null);
        assertEquals(unlabelled.get(0).name(), "[unnamed]");
    }

    @Test
    public void offSiteLinesLeaveThePayeesForTheOtherItems() {
        final TripBudget budget = TripBudget.builder().people(10).tripNights(5).build();
        budget.getLines().add(shared(BudgetCategory.TRANSPORTATION, "Flights", 9_000_00, "Airline"));
        budget.getLines().get(0).setOffSite(true);
        budget.getLines().add(shared(BudgetCategory.GUIDE, "Guide", 500_00, "Guide"));
        budget.getLines().add(BudgetLine.builder().kind(BudgetLineKind.INDIVIDUAL).name("Insurance")
                .amountCents(20_00).offSite(true).build());
        final BudgetLodgingLine hotel = BudgetLodgingLine.builder().id("h").offerId("offer-1").nights(1).people(10)
                .rooms(5).offSite(true).build();
        budget.getLodging().add(hotel);
        final List<PayeeTotal> payees = BudgetMath.payeeTotals(budget,
                line -> BudgetMath.lodging(line, seededShape()), line -> "Pansion");
        assertEquals(payees, List.of(new PayeeTotal("Guide", 1, 500_00L, "Guide")),
                "only the line paid during the trip");
        final List<OtherItem> others = BudgetMath.otherItems(budget,
                line -> BudgetMath.lodging(line, seededShape()), line -> "Pansion · Double", line -> "Pansion");
        assertEquals(others, List.of(
                new OtherItem("Pansion · Double", "Lodging", "Pansion", 262_00L),
                new OtherItem("Flights", "Transportation", "Airline", 9_000_00L),
                new OtherItem("Insurance", "Per-person extra", null, 200_00L)));
        assertEquals(BudgetMath.otherTotal(others), 9_462_00L);
        assertEquals(BudgetMath.otherTotal(List.of()), 0L);
    }

    @Test
    public void aPayeeListsTheDistinctCategoriesOfItsLines() {
        final TripBudget budget = TripBudget.builder().people(1).build();
        budget.getLines().add(shared(BudgetCategory.GUIDE, "Guide", 100_00, "Ivan"));
        budget.getLines().add(shared(BudgetCategory.TIPS, "Guide tip", 10_00, "Ivan"));
        budget.getLines().add(shared(BudgetCategory.GUIDE, "Extra day", 50_00, "ivan"));
        final List<PayeeTotal> payees = BudgetMath.payeeTotals(budget, line -> null, line -> null);
        assertEquals(payees, List.of(new PayeeTotal("Ivan", 3, 160_00L, "Guide, Tips")));
    }

    @Test
    public void moneyToCollectIsTheSharedBudgetPlusExtrasPlusWhatLodgingCharges() {
        final TripBudget budget = sheet();
        final LodgingFigures figures = BudgetMath.lodging(oracleLine(), seededShape());
        assertEquals(BudgetMath.moneyToCollect(budget, figures.revenue()), 48_000_00L,
                "$400 × 46 + 29,600.00 (46 × 10 × $60 + 10 singles × 10 × $20)");
        assertEquals(BudgetMath.moneyToCollect(budget, 0L), 18_400_00L, "no lodging: the shared budget alone");
        budget.getLines().add(BudgetLine.builder().kind(BudgetLineKind.INDIVIDUAL).name("Snacks").amountCents(5_00)
                .perNight(true).build());
        budget.getLines().add(BudgetLine.builder().kind(BudgetLineKind.INDIVIDUAL).name("Insurance")
                .amountCents(80_00).offSite(true).build());
        assertEquals(BudgetMath.moneyToCollect(budget, figures.revenue()), 53_980_00L,
                "+ ($5 × 10 nights + $80) × 46: extras paid during the trip or before it");
    }

    @Test
    public void percentLinesFollowTheLineTheyAreOf() {
        final BudgetLine guide = BudgetLine.builder().id("guide").name("Guide").amountCents(1_440_00).build();
        final BudgetLine tips = BudgetLine.builder().id("tips").name("Tips").percentOf("guide").percentBps(1_000)
                .amountCents(999).build();
        final BudgetLine extra = BudgetLine.builder().id("extra").name("Extra").percentOf("tips").percentBps(3_333)
                .build();
        final BudgetLine orphan = BudgetLine.builder().id("orphan").percentOf("gone").percentBps(5_000).build();
        final TripBudget budget = TripBudget.builder().people(1).lines(List.of(guide, tips, extra, orphan)).build();
        BudgetMath.resolvePercents(budget);
        assertEquals(tips.getAmountCents(), 144_00L, "10% of $1,440.00; the stored 999 was stale");
        assertEquals(extra.getAmountCents(), 48_00L, "33.33% of $144.00 = 47.99..., HALF_UP");
        assertEquals(orphan.getAmountCents(), 0L, "a line whose base is gone counts nothing");
        assertEquals(guide.getAmountCents(), 1_440_00L, "typed amounts are untouched");
        assertEquals(BudgetMath.sharedTotal(budget), 1_632_00L);
        guide.setAmountCents(2_000_00L);
        BudgetMath.resolvePercents(budget);
        assertEquals(extra.getAmountCents(), 66_66L, "a chain follows an edit of its root");

        assertNull(BudgetMath.percentProblem(budget, guide), "a typed line has nothing to check");
        assertNull(BudgetMath.percentProblem(budget, extra));
        assertEquals(BudgetMath.percentProblem(budget, orphan), "Choose another line for the percentage to apply to.");
        final BudgetLine self = BudgetLine.builder().id("self").percentOf("self").percentBps(100).build();
        budget.getLines().add(self);
        assertEquals(BudgetMath.percentProblem(budget, self), "Choose another line for the percentage to apply to.");
        final BudgetLine perPerson = BudgetLine.builder().id("pp").kind(BudgetLineKind.INDIVIDUAL)
                .percentOf("guide").percentBps(100).build();
        budget.getLines().add(perPerson);
        assertEquals(BudgetMath.percentProblem(budget, perPerson),
                "A percentage must be of a line of the same kind (shared or per person).");
        guide.setPercentOf("extra");
        guide.setPercentBps(100);
        assertTrue(BudgetMath.percentProblem(budget, guide)
                .startsWith("That would make the percentages go in a circle"));
        BudgetMath.resolvePercents(budget);
        assertEquals(guide.getAmountCents(), 0L, "a cycle that slipped through resolves to 0, never loops");
    }

    @Test
    public void theBasicGroundBudgetCeilingAndEstimate() {
        final TripBudget budget = sheet();
        assertEquals(BudgetMath.maxSingleAddOn(budget, line -> BudgetMath.lodging(line, seededShape())), 0L);
        budget.getLodging().add(oracleLine());
        budget.getLodging().add(BudgetLodgingLine.builder().nights(3).people(2).singles(1).build());
        assertEquals(BudgetMath.maxSingleAddOn(budget, line -> BudgetMath.lodging(line, seededShape())), 200_00L,
                "the largest add-on across the lines");
        assertEquals(BudgetMath.estimatedGroundPerPerson(budget, 14_012_00L), 650_31L,
                "(15,902.19 + 14,012.00) / 46");
        budget.setPeople(0);
        assertEquals(BudgetMath.estimatedGroundPerPerson(budget, 1L), 0L);
        final BudgetLine perPerson = BudgetLine.builder().kind(BudgetLineKind.INDIVIDUAL).amountCents(5_00)
                .perNight(true).build();
        budget.setPeople(3);
        assertEquals(BudgetMath.tripCents(budget, perPerson), 150_00L, "$5 × 10 nights × 3 people");
    }
}
