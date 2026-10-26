package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.paulsens.trip.action.LodgingViews.AccommodationForm;
import org.paulsens.trip.action.LodgingViews.CostExtraForm;
import org.paulsens.trip.action.LodgingViews.OfferForm;
import org.paulsens.trip.action.LodgingViews.OfferRow;
import org.paulsens.trip.action.LodgingViews.ReservationForm;
import org.paulsens.trip.action.LodgingViews.RoomTypeForm;
import org.paulsens.trip.action.RatePlanViews.PlanChoice;
import org.paulsens.trip.action.RatePlanViews.RatePlanForm;
import org.paulsens.trip.action.RatePlanViews.RatePlanRow;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.RatePlan;
import org.paulsens.trip.model.RateSchedule;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.pay.LodgingBiller;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Hotel rate plans end to end against the in-memory store: who sees and sets which side, the attach that
 * creates a linked option, the write-through fan-out (cost moves without touching a bill, a price moves the
 * bills), the pruning of stale links, detach, the un-pricing refusal, and the delete rules.
 */
public class RatePlanCommandsTest {
    private static final LocalDateTime CHECK_IN = LocalDateTime.of(2027, 9, 21, 15, 0);
    private static final LocalDateTime CHECK_OUT = LocalDateTime.of(2027, 9, 24, 10, 0);
    private static final LocalDate SEASON_START = LocalDate.of(2027, 9, 1);
    private static final LocalDate SEASON_END = LocalDate.of(2027, 10, 31);
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final LodgingCommands admin = new LodgingCommands(TestCallers::siteAdmin);
    private final RatePlanCommands plans = new RatePlanCommands(TestCallers::siteAdmin);
    private String orgId;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        final Organization org = new OrgCommands(TestCallers::siteAdmin)
                .createOrganization("Plans " + RandomData.genAlpha(8), "PLN", null);
        assertNotNull(org);
        orgId = org.getId().getValue();
    }

    // ------------------------------------------------------------------ who sees and sets what

    /** The price column is internal: an operator reads cost only, and their price fields are ignored on save. */
    @Test
    public void anOperatorSeesNoPriceAndCannotSetOne() throws IOException {
        final String accId = hotel();
        final Person keeper = savedPerson("Kep");
        makeOperator(keeper, accId);
        final RatePlanCommands asKeeper = new RatePlanCommands(() -> TestCallers.person(keeper.getId()));
        assertTrue(asKeeper.canEditCost(accId));
        assertFalse(asKeeper.canEditPrice(accId), "an operator is never internal");
        assertTrue(plans.canEditPrice(accId));

        final RatePlanForm form = asKeeper.planFormFor(accId, null);
        assertNull(form.getPlanId(), "a new plan");
        assertEquals(form.getAccId(), accId);
        form.setName("Hotel rate");
        form.setRoomTypeIds(List.of(typeId(accId, 0)));
        form.setValidFrom(SEASON_START);
        form.setValidUntil(SEASON_END);
        form.setCostNightlyPrice(50.0);
        form.setCostExtras(List.of(new CostExtraForm("City tax", "FIXED", 12.0)));
        form.setPriceKnown(true);
        form.setNightlyPrice(999.0);
        assertTrue(asKeeper.savePlan(form));
        assertNotNull(form.getPlanId(), "the minted id is written back");
        assertNull(form.getProblem());
        final RatePlan saved = plan(accId, form.getPlanId());
        assertEquals(saved.getCost().getNightlyCents(), 5000L);
        assertEquals(saved.getCostExtras().get(0).getAmountCents(), 1200L);
        assertFalse(saved.isPriced(), "the operator's $999 never reached the plan");
        assertEquals(saved.getVersion(), 1L);

        final List<RatePlanRow> keeperRows = asKeeper.rows(accId);
        assertEquals(keeperRows.size(), 1);
        assertEquals(keeperRows.get(0).getName(), "Hotel rate");
        assertEquals(keeperRows.get(0).getRoomTypes(), "Double");
        assertEquals(keeperRows.get(0).getDates(), "Sep 1 - Oct 31, 2027");
        assertEquals(keeperRows.get(0).getCostSummary(), "$50.00/night per room, +1 extra");
        assertNull(keeperRows.get(0).getPriceSummary(), "no price column for an operator");
        assertTrue(keeperRows.get(0).isNeedsPricing());
        assertEquals(keeperRows.get(0).getLinkedCount(), 0);
        assertEquals(keeperRows.get(0).getLinkedTrips(), "");

        final List<RatePlanRow> adminRows = plans.rows(accId);
        assertEquals(adminRows.get(0).getPriceSummary(), "needs participant pricing", "internal: the badge");

        // The admin prices it; the operator's later cost edit leaves that price alone.
        final RatePlanForm price = plans.planFormFor(accId, form.getPlanId());
        assertEquals(price.getCostNightlyPrice(), 50.0);
        assertEquals(price.getCostExtras().size(), 1);
        assertFalse(price.isPriceKnown());
        price.setPriceKnown(true);
        price.setPricingModel("PER_PERSON");
        price.setNightlyPrice(70.0);
        price.setSingleSupplement(20.0);
        assertTrue(plans.savePlan(price));
        assertEquals(plans.rows(accId).get(0).getPriceSummary(), "$70.00/night per person, +$20.00 single");
        assertFalse(plans.rows(accId).get(0).isNeedsPricing());
        final RatePlanForm costEdit = asKeeper.planFormFor(accId, form.getPlanId());
        costEdit.setCostNightlyPrice(55.0);
        costEdit.setPriceKnown(false);
        costEdit.setNightlyPrice(1.0);
        assertTrue(asKeeper.savePlan(costEdit));
        final RatePlan after = plan(accId, form.getPlanId());
        assertEquals(after.getCost().getNightlyCents(), 5500L, "the cost moved");
        assertEquals(after.getPrice().getNightlyCents(), 7000L, "the price did not: not the operator's to touch");
        assertTrue(after.getPrice().isPerPerson());

        // A stranger reads nothing and writes nothing; an operator of ANOTHER hotel reads the table (they may
        // open the lodging page) but neither prices nor the trips' names.
        final Person nobody = savedPerson("Nob");
        final RatePlanCommands stranger = new RatePlanCommands(() -> TestCallers.person(nobody.getId()));
        assertTrue(stranger.rows(accId).isEmpty());
        assertFalse(stranger.savePlan(costEdit));
        assertFalse(stranger.deletePlan(accId, form.getPlanId()));
        assertFalse(stranger.retirePlan(accId, form.getPlanId(), true));
        assertTrue(stranger.attachable(UUID.randomUUID().toString()).isEmpty());
        assertTrue(plans.rows("no-such-hotel").isEmpty());
        assertNull(plans.planFormFor(accId, "no-such-plan").getPlanId());
    }

    /**
     * An internal lodging admin who does not operate the hotel prices an EXISTING plan: only the four price fields
     * are taken from their form, the hotel side is re-read from the row whatever the form says, and they may not
     * create a plan at all.
     */
    @Test
    public void aLodgingAdminPricesAnOperatorsPlanButCannotCreateOne() throws IOException {
        final String accId = hotel();
        final Person keeper = savedPerson("Opr");
        makeOperator(keeper, accId);
        final RatePlanCommands asKeeper = new RatePlanCommands(() -> TestCallers.person(keeper.getId()));
        final RatePlanForm costOnly = asKeeper.planFormFor(accId, null);
        costOnly.setName("Operator rate");
        costOnly.setRoomTypeIds(List.of(typeId(accId, 0)));
        costOnly.setValidFrom(SEASON_START);
        costOnly.setValidUntil(SEASON_END);
        costOnly.setCostNightlyPrice(50.0);
        costOnly.setNotes("Breakfast included");
        assertTrue(asKeeper.savePlan(costOnly), costOnly.getProblem());
        final String planId = costOnly.getPlanId();
        assertFalse(plan(accId, planId).isPriced());

        final Person pricer = savedPerson("Prc");
        final OrgCommands orgs = new OrgCommands(TestCallers::siteAdmin);
        assertTrue(orgs.addMember(orgId, pricer.getId()));
        assertTrue(orgs.grantOrgPrivilege(orgId, pricer.getId(), PrivilegeCommands.LODGING_ADMIN));
        final RatePlanCommands asPricer = new RatePlanCommands(() -> TestCallers.person(pricer.getId()));
        assertTrue(asPricer.canEditPrice(accId), "internal: a lodging admin of some organization");
        assertFalse(asPricer.canEditCost(accId), "...who does not operate this hotel");

        final RatePlanForm price = asPricer.planFormFor(accId, planId);
        price.setName("Renamed by the pricer");
        price.setRoomTypeIds(List.of(typeId(accId, 0), typeId(accId, 1)));
        price.setValidUntil(null);
        price.setCostNightlyPrice(1.0);
        price.setCostExtras(List.of(new CostExtraForm("Pricer's tax", "FIXED", 9.0)));
        price.setNotes("Pricer's notes");
        price.setRetired(true);
        price.setPriceKnown(true);
        price.setPricingModel("PER_PERSON");
        price.setNightlyPrice(65.0);
        price.setSingleSupplement(15.0);
        assertTrue(asPricer.savePlan(price), price.getProblem());
        assertNull(price.getProblem());
        assertEquals(price.getName(), "Operator rate", "the form reflects the row's hotel side");
        final RatePlan priced = plan(accId, planId);
        assertTrue(priced.isPriced());
        assertEquals(priced.getPrice().getNightlyCents(), 6500L);
        assertEquals(priced.getPrice().getSingleSupplementCents(), 1500L);
        assertTrue(priced.getPrice().isPerPerson());
        assertEquals(priced.getName(), "Operator rate", "the name is the hotel's");
        assertEquals(priced.getRoomTypeIds(), List.of(typeId(accId, 0)));
        assertEquals(priced.getValidUntil(), SEASON_END);
        assertEquals(priced.getCost().getNightlyCents(), 5000L, "so is the cost");
        assertTrue(priced.getCostExtras().isEmpty());
        assertEquals(priced.getNotes(), "Breakfast included");
        assertFalse(priced.isRetired());

        final RatePlanForm fresh = asPricer.planFormFor(accId, null);
        fresh.setName("Pricer's own plan");
        fresh.setRoomTypeIds(List.of(typeId(accId, 0)));
        fresh.setCostNightlyPrice(40.0);
        fresh.setPriceKnown(true);
        fresh.setNightlyPrice(60.0);
        assertFalse(asPricer.savePlan(fresh));
        assertEquals(fresh.getProblem(),
                "Only the hotel's operators create its rate plans; a lodging admin prices an existing one.");
        assertEquals(plans.rows(accId).size(), 1, "nothing was created");
    }

    /** The dialog's refusals land on the form, and nothing is written. */
    @Test
    public void aPlanIsValidatedBeforeItIsSaved() {
        final String accId = hotel();
        final RatePlanForm form = plans.planFormFor(accId, null);
        assertFalse(plans.savePlan(form));
        assertEquals(form.getProblem(), "A rate plan needs a name.");
        form.setName("Rate");
        assertFalse(plans.savePlan(form));
        assertEquals(form.getProblem(), "Choose at least one room type.");
        form.setRoomTypeIds(List.of("not-a-type"));
        assertFalse(plans.savePlan(form));
        assertTrue(form.getProblem().contains("not at"));
        form.setRoomTypeIds(List.of(typeId(accId, 0), typeId(accId, 0), " "));
        form.setValidFrom(SEASON_END);
        form.setValidUntil(SEASON_START);
        assertFalse(plans.savePlan(form));
        assertEquals(form.getProblem(), "The plan's last date cannot be before its first.");
        form.setValidFrom(SEASON_START);
        form.setValidUntil(SEASON_END);
        form.setCostNightlyPrice(-1.0);
        assertFalse(plans.savePlan(form));
        assertEquals(form.getProblem(), "The nightly cost cannot be negative.");
        form.setCostNightlyPrice(50.0);
        form.setPriceKnown(true);
        form.setNightlyPrice(-5.0);
        assertFalse(plans.savePlan(form));
        assertEquals(form.getProblem(), "The nightly price cannot be negative.");
        form.setNightlyPrice(60.0);
        form.setCostExtras(List.of(new CostExtraForm("", "FIXED", 3.0)));
        assertFalse(plans.savePlan(form));
        assertEquals(form.getProblem(), "Each cost extra needs a label.");
        form.setCostExtras(List.of());
        assertTrue(plans.rows(accId).isEmpty(), "nothing was written along the way");
        assertTrue(plans.savePlan(form));
        assertEquals(form.getRoomTypeIds(), List.of(typeId(accId, 0)), "deduped, blanks dropped");
        assertEquals(plans.rows(accId).size(), 1);
        assertFalse(plans.savePlan(null));
        final RatePlanForm gone = plans.planFormFor(accId, form.getPlanId());
        gone.setPlanId(UUID.randomUUID().toString());
        assertFalse(plans.savePlan(gone));
        assertEquals(gone.getProblem(), "This rate plan no longer exists.");
        assertTrue(admin.retireAccommodation(accId, true));
        assertFalse(plans.savePlan(form), "a retired hotel takes no plan edits");
        assertTrue(admin.retireAccommodation(accId, false));
    }

    // ------------------------------------------------------------------ attach and the fan-out

    /**
     * Attaching creates a linked option with a LODGING event, copies the plan's schedules, and records the trip
     * on the plan; a plan save then fans out -- a cost change leaves the bills alone, a price change moves them.
     */
    @Test
    public void attachCopiesThePlanAndASaveFansOutToTheLinkedOption() throws IOException {
        final String accId = hotel();
        final Person guest = savedPerson("Gue");
        final Trip trip = trip("Fan-out trip", guest);
        final String planId = pricedPlan(accId, "Season rate", 70.0, 50.0);

        // The store is shared with every other test's hotels (and the seeded Pansion's open-dated plan), so
        // the menu is read for THIS hotel only.
        final List<PlanChoice> menu = menuFor(trip, accId);
        assertEquals(menu.size(), 1);
        assertEquals(menu.get(0).getPlanId(), planId);
        assertEquals(menu.get(0).getAccId(), accId);
        assertTrue(menu.get(0).getLabel().endsWith(": Season rate (Sep 1 - Oct 31, 2027)"), menu.get(0).getLabel());
        assertTrue(plans.attach(trip.getId(), menu.get(0).getValue()));
        assertTrue(menuFor(trip, accId).isEmpty(), "already on the trip");

        final ReservationOffer offer = linkedOffer(trip, planId);
        assertEquals(offer.getName(), "Season rate");
        assertEquals(offer.getRoomTypeIds(), List.of(typeId(accId, 0), typeId(accId, 1)));
        assertEquals(offer.getNightlyPriceCents(), 7000L, "the plan's price, copied");
        assertEquals(offer.getCost().getNightlyCents(), 5000L, "the plan's cost, copied");
        assertEquals(offer.getCostExtras().size(), 1);
        assertEquals(offer.getRatePlanVersion(), plan(accId, planId).getVersion());
        assertEquals(offer.getValidFrom(), SEASON_START.atStartOfDay());
        assertEquals(offer.getValidUntil(), SEASON_END.atTime(23, 59));
        assertEquals(offer.getDefaultStart(), trip.getStartDate().toLocalDate().atTime(OfferForm.DEFAULT_ARRIVAL));
        final TripEvent event = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow()
                .getTripEvent(offer.getTripEventId());
        assertNotNull(event, "a LODGING event was created");
        assertEquals(event.getType(), TripEvent.Type.LODGING);
        assertEquals(plan(accId, planId).getLinkedTripIds(), List.of(trip.getId()));
        assertEquals(plans.rows(accId).get(0).getLinkedTrips(), "Fan-out trip");
        assertEquals(plans.rows(accId).get(0).getLinkedCount(), 1);

        final OfferRow row = offerRow(trip, offer);
        assertTrue(row.isLinked());
        assertEquals(row.getRatePlanName(), "Season rate");
        assertFalse(row.isPlanDrift());

        // A stay on the linked option: 3 nights at $70 per room.
        final Reservation stay = reserve(trip, offer, guest);
        final LodgingBiller biller = new LodgingBiller(new TransactionsCommands());
        assertEquals(biller.billedByPerson(stay), Map.of(guest.getId(), 3 * 7000L));

        // Cost edit on the plan: the option follows, the bill does not move.
        final RatePlanForm cost = plans.planFormFor(accId, planId);
        cost.setCostNightlyPrice(45.0);
        cost.setCostExtras(List.of());
        assertTrue(plans.savePlan(cost));
        assertNull(cost.getProblem(), "every linked trip was reached");
        final ReservationOffer afterCost = admin.findOffer(trip.getId(), offer.getId().getValue());
        assertEquals(afterCost.getCost().getNightlyCents(), 4500L);
        assertTrue(afterCost.getCostExtras().isEmpty());
        assertEquals(afterCost.getNightlyPriceCents(), 7000L);
        assertEquals(afterCost.getRatePlanVersion(), plan(accId, planId).getVersion(), "current, no drift");
        assertEquals(biller.billedByPerson(stay), Map.of(guest.getId(), 3 * 7000L), "a cost edit bills nobody");

        // Price edit on the plan: the option follows AND the bill is recomputed.
        final RatePlanForm price = plans.planFormFor(accId, planId);
        price.setNightlyPrice(100.0);
        assertTrue(plans.savePlan(price));
        assertEquals(admin.findOffer(trip.getId(), offer.getId().getValue()).getNightlyPriceCents(), 10000L);
        assertEquals(biller.billedByPerson(stay), Map.of(guest.getId(), 3 * 10000L), "the bill follows the plan");

        // Un-pricing is refused while the stay is active...
        final RatePlanForm unprice = plans.planFormFor(accId, planId);
        unprice.setPriceKnown(false);
        assertFalse(plans.savePlan(unprice));
        assertTrue(unprice.getProblem().contains("cannot lose its participant price"), unprice.getProblem());
        assertTrue(plan(accId, planId).isPriced());
        // ...and so is deleting a linked plan.
        assertFalse(plans.deletePlan(accId, planId));
        assertNotNull(plan(accId, planId));

        // Editing the linked option on the Offers tab keeps the trip's terms editable and the money the plan's.
        final OfferForm edit = admin.offerFormFor(trip.getId(), offer.getId().getValue());
        assertEquals(edit.getRatePlanId(), planId);
        edit.setMinNights(2);
        edit.setNightlyPrice(1.0);
        edit.setCostNightlyPrice(1.0);
        assertTrue(admin.saveOffer(trip.getId(), edit));
        final ReservationOffer edited = admin.findOffer(trip.getId(), offer.getId().getValue());
        assertEquals(edited.getMinNights(), 2);
        assertEquals(edited.getNightlyPriceCents(), 10000L, "the form's money is ignored for a linked option");
        assertEquals(edited.getCost().getNightlyCents(), 4500L);
        assertTrue(edited.isLinked());

        // A copy left behind on purpose reads as drift on the Offers tab.
        final ReservationOffer stale = DAO.getInstance().getReservationOffer(trip.getId(), offer.getId(), Cached.NO)
                .orElseThrow();
        stale.setRatePlanVersion(stale.getRatePlanVersion() - 1);
        assertTrue(DAO.getInstance().saveReservationOffer(stale));
        assertTrue(offerRow(trip, offer).isPlanDrift());

        // Detach: the numbers stay, the link goes, the plan forgets the trip, the bill does not move.
        assertTrue(plans.detach(trip.getId(), offer.getId().getValue()));
        final ReservationOffer loose = admin.findOffer(trip.getId(), offer.getId().getValue());
        assertFalse(loose.isLinked());
        assertEquals(loose.getRatePlanVersion(), 0L);
        assertEquals(loose.getNightlyPriceCents(), 10000L);
        assertEquals(loose.getCost().getNightlyCents(), 4500L);
        assertEquals(biller.billedByPerson(stay), Map.of(guest.getId(), 3 * 10000L));
        assertTrue(plan(accId, planId).getLinkedTripIds().isEmpty());
        assertFalse(offerRow(trip, offer).isLinked());
        assertNull(offerRow(trip, offer).getRatePlanName());
        assertFalse(plans.detach(trip.getId(), offer.getId().getValue()), "nothing left to detach");
        assertFalse(plans.detach(trip.getId(), "no-such-offer"));
        assertFalse(plans.detach(trip.getId(), null));

        // Now the plan may be deleted; a plan save with no price on the (detached) option is also fine.
        final RatePlanForm unpriceNow = plans.planFormFor(accId, planId);
        unpriceNow.setPriceKnown(false);
        assertTrue(plans.savePlan(unpriceNow), "no linked stays any more");
        assertFalse(plan(accId, planId).isPriced());
        assertEquals(admin.findOffer(trip.getId(), offer.getId().getValue()).getNightlyPriceCents(), 10000L,
                "the detached option kept its copy");
        assertTrue(plans.deletePlan(accId, planId));
        assertNull(plan(accId, planId));
        assertFalse(plans.deletePlan(accId, planId), "already gone");
    }

    /** Two linked trips, one of them with its option deleted behind the plan's back: the save prunes it. */
    @Test
    public void aSavePrunesALinkThatNoOptionFollowsAnyMore() throws IOException {
        final String accId = hotel();
        final Trip kept = trip("Kept trip", savedPerson("Kpt"));
        final Trip stale = trip("Stale trip", savedPerson("Stl"));
        final String planId = pricedPlan(accId, "Prune rate", 60.0, 40.0);
        assertTrue(plans.attach(kept.getId(), accId, planId));
        assertTrue(plans.attach(stale.getId(), accId, planId));
        assertEquals(plan(accId, planId).getLinkedTripIds(), List.of(kept.getId(), stale.getId()));
        assertEquals(plans.rows(accId).get(0).getLinkedTrips(), "Kept trip, Stale trip");

        final ReservationOffer staleOffer = linkedOffer(stale, planId);
        assertTrue(DAO.getInstance().deleteReservationOffer(stale.getId(), staleOffer.getId()));
        // And a link to a trip that does not exist at all.
        final RatePlan withGhost = plan(accId, planId);
        assertTrue(withGhost.link("no-such-trip"));
        assertTrue(DAO.getInstance().saveRatePlan(withGhost));

        final RatePlanForm form = plans.planFormFor(accId, planId);
        form.setCostNightlyPrice(42.0);
        assertTrue(plans.savePlan(form));
        assertNull(form.getProblem());
        final RatePlan pruned = plan(accId, planId);
        assertEquals(pruned.getLinkedTripIds(), List.of(kept.getId()), "the stale trip and the ghost are pruned");
        final ReservationOffer keptOffer = linkedOffer(kept, planId);
        assertEquals(keptOffer.getCost().getNightlyCents(), 4200L, "the kept trip followed");
        assertEquals(keptOffer.getRatePlanVersion(), pruned.getVersion(), "pruned BEFORE the push: no drift");

        // Retiring also pushes the version along, so the linked option never reads as drifted.
        assertTrue(plans.retirePlan(accId, planId, true));
        assertTrue(plan(accId, planId).isRetired());
        assertEquals(linkedOffer(kept, planId).getRatePlanVersion(), plan(accId, planId).getVersion());
        assertTrue(plans.rows(accId).get(0).isRetired());
        assertTrue(plans.retirePlan(accId, planId, false));
        assertFalse(plan(accId, planId).isRetired());
        assertFalse(plans.retirePlan(accId, "no-such-plan", true));
    }

    /** Trip delete: every plan its options followed forgets the trip, and nothing else changes. */
    @Test
    public void unlinkAllForgetsATripOnEveryPlanItsOptionsFollowed() throws IOException {
        final String accId = hotel();
        final Trip doomed = trip("Doomed trip", savedPerson("Dmd"));
        final Trip other = trip("Other trip", savedPerson("Oth"));
        final String planId = pricedPlan(accId, "Shared rate", 60.0, 40.0);
        assertTrue(plans.attach(doomed.getId(), accId, planId));
        assertTrue(plans.attach(other.getId(), accId, planId));
        final List<ReservationOffer> offers = new ArrayList<>(admin.getOffers(doomed.getId()));
        offers.add(ReservationOffer.builder().tripId(doomed.getId()).name("Standalone").build());
        assertEquals(plans.unlinkAll(doomed.getId(), offers), 1);
        assertEquals(plan(accId, planId).getLinkedTripIds(), List.of(other.getId()));
        assertEquals(plans.unlinkAll(doomed.getId(), offers), 0, "idempotent");
        assertFalse(plans.unlink(accId, "no-such-plan", doomed.getId()), "a missing plan is tolerated");
        assertFalse(plans.unlink(accId, planId, "never-linked"));
    }

    // ------------------------------------------------------------------ the attach menu and its refusals

    /** The menu offers live plans of live hotels that cover the trip's dates and are not yet on the trip. */
    @Test
    public void attachableExcludesRetiredNonCoveringAndAlreadyLinkedPlans() throws IOException {
        final String accId = hotel();
        final String otherHotel = hotel();
        final Trip trip = trip("Menu trip", savedPerson("Mnu"));
        final String covering = pricedPlan(accId, "Covering", 60.0, 40.0);
        final String open = planWithDates(accId, "Open dates", null, null);
        final String tooShort = planWithDates(accId, "Ends early", SEASON_START, LocalDate.of(2027, 9, 23));
        final String elsewhere = pricedPlan(otherHotel, "Elsewhere", 60.0, 40.0);
        final String retired = pricedPlan(accId, "Retired", 60.0, 40.0);
        assertTrue(plans.retirePlan(accId, retired, true));

        List<PlanChoice> menu = menuFor(trip, accId, otherHotel);
        assertEquals(planIds(menu), List.of(covering, open, elsewhere), "by hotel then plan name; the others are out");
        assertTrue(menu.get(1).getLabel().endsWith(": Open dates (any dates)"), menu.get(1).getLabel());
        assertTrue(plans.attach(trip.getId(), accId, covering));
        menu = menuFor(trip, accId, otherHotel);
        assertEquals(planIds(menu), List.of(open, elsewhere), "the attached plan left the menu");

        assertFalse(plans.attach(trip.getId(), accId, tooShort), "does not cover the trip");
        assertFalse(plans.attach(trip.getId(), accId, retired), "retired");
        assertFalse(plans.attach(trip.getId(), accId, covering), "already on the trip");
        assertFalse(plans.attach(trip.getId(), accId, "no-such-plan"));
        assertFalse(plans.attach(trip.getId(), "no-such-hotel", covering));
        assertFalse(plans.attach(UUID.randomUUID().toString(), accId, open), "unknown trip");
        assertFalse(plans.attach(trip.getId(), "garbage"), "a menu value needs both ids");
        assertFalse(plans.attach(trip.getId(), (String) null));
        assertFalse(plans.attach(trip.getId(), accId + "|"));
        final RatePlanCommands stranger = new RatePlanCommands(() -> TestCallers.person(Person.Id.newInstance()));
        assertFalse(stranger.attach(trip.getId(), accId, open));
        assertFalse(stranger.detach(trip.getId(), linkedOffer(trip, covering).getId().getValue()));
        assertEquals(admin.getOffers(trip.getId()).size(), 1, "the refusals created nothing");

        // A trip outside every plan's dates is offered nothing from these hotels.
        final Trip later = Trip.builder().id(UUID.randomUUID().toString()).title("Later")
                .startDate(LocalDateTime.of(2030, 3, 1, 15, 0)).endDate(LocalDateTime.of(2030, 3, 5, 10, 0)).build();
        later.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(later));
        assertEquals(planIds(menuFor(later, accId, otherHotel)), List.of(open), "only the open-dated plan");

        // The operator of the OTHER hotel reads this hotel's table (they may open the lodging page) but may
        // name neither the price nor the trip.
        final Person keeper = savedPerson("Kpr");
        makeOperator(keeper, otherHotel);
        final RatePlanCommands asKeeper = new RatePlanCommands(() -> TestCallers.person(keeper.getId()));
        final RatePlanRow seen = asKeeper.rows(accId).stream().filter(r -> covering.equals(r.getId())).findFirst()
                .orElseThrow();
        assertNull(seen.getPriceSummary());
        assertEquals(seen.getLinkedTrips(), "1 trip", "another organization's trip is counted, not named");
        assertEquals(seen.getLinkedCount(), 1);
    }

    /** The copy is a copy: editing the plan's own objects afterwards changes nothing on the offer. */
    @Test
    public void copyFromPlanNeverAliasesThePlansSchedules() {
        final RatePlan plan = RatePlan.builder().name("Alias").cost(new RateSchedule(null, 5000L, Map.of("2027-09-22",
                7000L), 0L)).price(new RateSchedule(null, 6000L, null, 1000L))
                .costExtras(List.of(new org.paulsens.trip.model.CostExtra("Tax", null, 100L))).version(4L).build();
        final ReservationOffer offer = ReservationOffer.builder().name("Copy").build();
        OfferSchedules.copyFromPlan(offer, plan);
        assertEquals(offer.getRatePlanId(), plan.getId().getValue());
        assertEquals(offer.getRatePlanVersion(), 4L);
        assertEquals(offer.getPrice(), plan.getPrice());
        assertEquals(offer.getCost(), plan.getCost());
        assertEquals(offer.getCostExtras(), plan.getCostExtras());
        plan.getCost().setNightlyCents(1L);
        plan.getCost().getOverrides().clear();
        plan.getCostExtras().get(0).setAmountCents(999L);
        assertEquals(offer.getCost().getNightlyCents(), 5000L);
        assertEquals(offer.getCost().nightlyCents(LocalDate.of(2027, 9, 22)), 7000L);
        assertEquals(offer.getCostExtras().get(0).getAmountCents(), 100L);
        assertNotEquals(offer.getCost(), plan.getCost());
        assertEquals(OfferSchedules.linkOf(offer, plan).drift(), false);
        assertEquals(OfferSchedules.linkOf(offer, null).planName(), "(deleted plan)");
        assertTrue(OfferSchedules.linkOf(offer, null).drift());
        assertNull(OfferSchedules.linkOf(new ReservationOffer(), plan).planName());
        assertEquals(RatePlanCommands.datesLabel(RatePlan.builder().validFrom(SEASON_START).build()),
                "from Sep 1, 2027");
        assertEquals(RatePlanCommands.datesLabel(RatePlan.builder().validUntil(SEASON_END).build()),
                "until Oct 31, 2027");
    }

    // ------------------------------------------------------------------ the writes that do not happen

    /** The CDI constructor wires the Faces caller lazily: a blank hotel id is refused before anyone asks who. */
    @Test
    public void theDefaultConstructorDefersTheFacesCaller() {
        final RatePlanCommands bean = new RatePlanCommands();
        assertFalse(bean.canEditCost(null));
        assertTrue(bean.rows(null).isEmpty());
        assertTrue(bean.rows(" ").isEmpty());
    }

    /**
     * The trip joins the plan FIRST (so the option copies the version it is compared against); when the option
     * save is then refused -- here the plan names a room type the hotel has since deleted -- the link is undone.
     */
    @Test
    public void attachRollsTheLinkBackWhenTheOptionIsRefused() throws IOException {
        final String accId = hotel();
        final Trip trip = trip("Rollback trip", savedPerson("Rlb"));
        final String tripleType = typeId(accId, 1);
        final String planId = pricedPlan(accId, "Triple rate", 60.0, 40.0);
        assertTrue(admin.deleteRoomType(accId, tripleType), "no room is a Triple, so the type may go");
        assertEquals(plans.rows(accId).get(0).getRoomTypes(), "Double, ?", "a type no longer at the hotel");
        final long before = plan(accId, planId).getVersion();

        assertFalse(plans.attach(trip.getId(), accId, planId));
        assertTrue(admin.getOffers(trip.getId()).isEmpty(), "the refused option was never created");
        assertTrue(plan(accId, planId).getLinkedTripIds().isEmpty(), "the link made first was undone");
        assertEquals(plan(accId, planId).getVersion(), before + 2, "linked, then unlinked");
        assertTrue(DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow().getTripEvents().isEmpty(),
                "no lodging event either");
    }

    /** The fan-out reports a plan whose pruned trip list lost its conditional put, rather than throwing. */
    @Test
    public void syncLinkedReportsATripListItCouldNotPrune() throws IOException {
        final String accId = hotel();
        final String planId = pricedPlan(accId, "Stale rate", 60.0, 40.0);
        final RatePlan stale = plan(accId, planId);
        assertTrue(DAO.getInstance().saveRatePlan(plan(accId, planId)), "another writer moved the row");
        assertTrue(stale.link("ghost-trip"));

        final RatePlanCommands.SyncOutcome sync = plans.syncLinked(stale);
        assertEquals(sync.total(), 0);
        assertEquals(sync.updated(), 0);
        assertEquals(sync.failures(), List.of("the plan's trip list could not be pruned (saved by someone else)"));
        assertTrue(plan(accId, planId).getLinkedTripIds().isEmpty(), "the stored plan never saw the ghost");
    }

    /**
     * A plan whose row another writer moved: every plan write is refused with the reload message and nothing is
     * written -- the dialog's save and retire, the attach's link, the trip-delete cascade's unlink.
     */
    @Test
    public void planWritesLostToAnotherWriterAreRefused() throws IOException {
        final String accId = hotel();
        final Trip trip = trip("Linked trip", savedPerson("Lnk"));
        final Trip other = trip("Other trip", savedPerson("Otr"));
        final String planId = pricedPlan(accId, "Contested rate", 60.0, 40.0);
        assertTrue(plans.attach(trip.getId(), accId, planId));
        final RatePlan before = plan(accId, planId);
        final String key = CacheKeys.LODGING_PLAN_PREFIX + accId;
        final String cached = ageInCache(key, planId, before.getVersion());

        final RatePlanForm form = plans.planFormFor(accId, planId);
        form.setCostNightlyPrice(41.0);
        assertFalse(plans.savePlan(form));
        assertEquals(form.getProblem(), "Saved by someone else: reload the page and try again.");
        assertFalse(plans.retirePlan(accId, planId, true));
        assertFalse(plans.attach(other.getId(), accId, planId), "the link is a plan write too");
        assertTrue(admin.getOffers(other.getId()).isEmpty(), "no option without its link");
        assertFalse(plans.unlink(accId, planId, trip.getId()));

        restoreInCache(key, planId, cached);
        final RatePlan after = plan(accId, planId);
        assertEquals(after.getVersion(), before.getVersion(), "nothing was written");
        assertEquals(after.getCost().getNightlyCents(), 4000L);
        assertFalse(after.isRetired());
        assertEquals(after.getLinkedTripIds(), List.of(trip.getId()));
        assertTrue(plans.savePlan(form), "the same form saves once the row is current again");
        assertEquals(linkedOffer(trip, planId).getCost().getNightlyCents(), 4100L);
    }

    /**
     * An option another writer moved during the fan-out: the plan is saved, the loss is reported on the form and
     * not retried, the option keeps its old copy (drift on the Offers tab), a retire reports it the same way, and
     * the option's detach is refused until its row is current again.
     */
    @Test
    public void anOptionSaveLostDuringTheFanOutIsReportedNotRetried() throws IOException {
        final String accId = hotel();
        final Trip trip = trip("Raced trip", savedPerson("Rcd"));
        final String planId = pricedPlan(accId, "Raced rate", 60.0, 40.0);
        assertTrue(plans.attach(trip.getId(), accId, planId));
        final ReservationOffer offer = linkedOffer(trip, planId);
        final String key = CacheKeys.LODGING_OFFER_PREFIX + trip.getId();
        final String cached = ageInCache(key, offer.getId().getValue(), offer.getVersion());

        final RatePlanForm form = plans.planFormFor(accId, planId);
        form.setNightlyPrice(75.0);
        assertTrue(plans.savePlan(form), "the plan itself is saved");
        assertEquals(form.getProblem(),
                "Plan saved; 0 of 1 trips updated; trip 'Raced trip': saved by someone else");
        assertEquals(plan(accId, planId).getPrice().getNightlyCents(), 7500L);
        assertEquals(linkedOffer(trip, planId).getNightlyPriceCents(), 6000L, "the option kept its copy");
        assertTrue(offerRow(trip, offer).isPlanDrift(), "...and reads as drifted");
        assertTrue(plans.retirePlan(accId, planId, true), "retiring reports the loss and still retires");
        assertTrue(plan(accId, planId).isRetired());
        assertFalse(plans.detach(trip.getId(), offer.getId().getValue()));
        assertTrue(linkedOffer(trip, planId).isLinked());

        restoreInCache(key, offer.getId().getValue(), cached);
        assertTrue(plans.detach(trip.getId(), offer.getId().getValue()));
        assertFalse(admin.findOffer(trip.getId(), offer.getId().getValue()).isLinked());
    }

    /** An option whose plan row is gone: the Offers tab says so, the cascade tolerates it, detach still works. */
    @Test
    public void anOptionOutlivesItsDeletedPlan() throws IOException {
        final String accId = hotel();
        final Trip trip = trip("Orphan trip", savedPerson("Orp"));
        final String planId = pricedPlan(accId, "Short-lived rate", 60.0, 40.0);
        assertTrue(plans.attach(trip.getId(), accId, planId));
        final ReservationOffer offer = linkedOffer(trip, planId);
        assertTrue(DAO.getInstance().deleteRatePlan(accId, RatePlan.Id.from(planId)), "behind the link check");

        final OfferRow row = offerRow(trip, offer);
        assertTrue(row.isLinked());
        assertEquals(row.getRatePlanName(), "(deleted plan)");
        assertTrue(row.isPlanDrift());
        assertEquals(plans.unlinkAll(trip.getId(), admin.getOffers(trip.getId())), 0, "nothing left to unlink");
        assertTrue(plans.detach(trip.getId(), offer.getId().getValue()), "the option is cut loose anyway");
        assertFalse(admin.findOffer(trip.getId(), offer.getId().getValue()).isLinked());
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Makes the shared cache's copy of a row claim a version the table never reached -- what a conditional put
     * lost to another writer looks like to the next reader: the store refuses the versioned save that follows.
     * Returns the cache's original entry for {@link #restoreInCache}.
     */
    private static String ageInCache(final String key, final String field, final long version) {
        final CacheClient cache = DAO.getInstance().getCacheClient();
        final String json = cache.getHashFields(key, List.of(field)).get(field);
        assertNotNull(json, "the row is cached");
        final String aged = json.replace("\"version\":" + version, "\"version\":" + (version + 1));
        assertNotEquals(aged, json, "the cached copy carries the version");
        assertTrue(cache.putHashField(key, field, aged));
        return json;
    }

    private static void restoreInCache(final String key, final String field, final String json) {
        assertTrue(DAO.getInstance().getCacheClient().putHashField(key, field, json));
    }

    /**
     * A fresh hotel with a Double and a Triple room type and two rooms. Named by a zero-padded sequence so two
     * hotels of one test sort in creation order (the attach menu is by hotel name).
     */
    private String hotel() {
        final AccommodationForm form = new AccommodationForm();
        final String tag = RandomData.genAlpha(8) + SEQ.incrementAndGet();
        form.setName("Plan hotel " + String.format("%05d", SEQ.incrementAndGet()) + " " + tag);
        form.setEmail(tag.toLowerCase(java.util.Locale.ROOT) + "@plans.example");
        // Discovery compares phones by their DIGITS: a counter alone collided with HotelCommandsTest's fixtures
        // in the same JVM, so the number carries the clock too.
        form.setPhone("+1 971 " + SEQ.incrementAndGet() + " " + (System.nanoTime() % 1_000_000L));
        form.setStreet("Rate " + SEQ.incrementAndGet() + " " + tag + " Ave");
        form.setCity("Portland");
        final String accId = admin.saveAccommodation(form, orgId);
        assertTrue(accId != null && !accId.isBlank(), "the hotel fixture was refused as a duplicate");
        final RoomTypeForm dbl = new RoomTypeForm();
        dbl.setName("Double");
        dbl.setMinPeople(1);
        dbl.setMaxPeople(2);
        assertTrue(admin.saveRoomType(accId, dbl));
        final RoomTypeForm triple = new RoomTypeForm();
        triple.setName("Triple");
        triple.setMinPeople(2);
        triple.setMaxPeople(3);
        assertTrue(admin.saveRoomType(accId, triple));
        assertEquals(admin.bulkAddRooms(accId, "", 101, 102, "1", typeId(accId, 0)), 2);
        return accId;
    }

    private String typeId(final String accId, final int index) {
        return admin.roomTypeRows(accId).get(index).getId();
    }

    /** A plan over the season on every room type, priced AND costed, through the admin's dialog. */
    private String pricedPlan(final String accId, final String name, final double price, final double cost) {
        final RatePlanForm form = plans.planFormFor(accId, null);
        form.setName(name);
        form.setRoomTypeIds(List.of(typeId(accId, 0), typeId(accId, 1)));
        form.setValidFrom(SEASON_START);
        form.setValidUntil(SEASON_END);
        form.setCostNightlyPrice(cost);
        form.setCostExtras(List.of(new CostExtraForm("City tax", "FIXED", 12.0)));
        form.setPriceKnown(true);
        form.setNightlyPrice(price);
        assertTrue(plans.savePlan(form), form.getProblem());
        return form.getPlanId();
    }

    private String planWithDates(final String accId, final String name, final LocalDate from, final LocalDate to) {
        final RatePlanForm form = plans.planFormFor(accId, null);
        form.setName(name);
        form.setRoomTypeIds(List.of(typeId(accId, 0)));
        form.setValidFrom(from);
        form.setValidUntil(to);
        form.setCostNightlyPrice(40.0);
        form.setPriceKnown(true);
        form.setNightlyPrice(60.0);
        assertTrue(plans.savePlan(form), form.getProblem());
        return form.getPlanId();
    }

    private static RatePlan plan(final String accId, final String planId) {
        return DAO.getInstance().getRatePlan(accId, RatePlan.Id.from(planId), Cached.NO).orElse(null);
    }

    private Trip trip(final String title, final Person person) throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title(title)
                .startDate(CHECK_IN).endDate(CHECK_OUT.plusDays(2))
                .people(new ArrayList<>(List.of(person.getId()))).build();
        trip.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private ReservationOffer linkedOffer(final Trip trip, final String planId) {
        return admin.getOffers(trip.getId()).stream().filter(o -> planId.equals(o.getRatePlanId())).findFirst()
                .orElseThrow();
    }

    private OfferRow offerRow(final Trip trip, final ReservationOffer offer) {
        return admin.offerRows(trip.getId()).stream().filter(r -> r.getId().equals(offer.getId().getValue()))
                .findFirst().orElseThrow();
    }

    private Reservation reserve(final Trip trip, final ReservationOffer offer, final Person who) {
        final ReservationForm form = admin.reservationFormFor(trip.getId(), null);
        form.setOfferId(offer.getId().getValue());
        form.setPersonIds(List.of(who.getId().getValue()));
        form.setStart(CHECK_IN);
        form.setEnd(CHECK_OUT);
        assertEquals(admin.createReservations(trip.getId(), form), 1, form.getProblem());
        return admin.activeReservationsFor(trip.getId(), who.getId()).stream()
                .filter(res -> res.getOfferId().equals(offer.getId())).findFirst().orElseThrow();
    }

    /** The attach menu narrowed to this test's hotels: the store is shared with every other test's plans. */
    private List<PlanChoice> menuFor(final Trip trip, final String... accIds) {
        final List<String> mine = List.of(accIds);
        final List<PlanChoice> choices = new ArrayList<>();
        for (final PlanChoice choice : plans.attachable(trip.getId())) {
            if (mine.contains(choice.getAccId())) {
                choices.add(choice);
            }
        }
        return choices;
    }

    private static List<String> planIds(final List<PlanChoice> choices) {
        final List<String> ids = new ArrayList<>();
        for (final PlanChoice choice : choices) {
            ids.add(choice.getPlanId());
        }
        return ids;
    }

    /** Grants {@code accommodationAdmin} on the hotel: the Hotel Operator role. */
    private static void makeOperator(final Person who, final String accId) {
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(PrivilegeCommands.ACCOMMODATION_ADMIN, accId,
                PrivilegeCommands.ACCOMMODATION_ROLE_NAME).withNewPerson(who.getId())));
    }

    private static Person savedPerson(final String first) throws IOException {
        final Person person = Person.builder().first(first).last(RandomData.genAlpha(8))
                .email(first.toLowerCase(java.util.Locale.ROOT) + "." + RandomData.genAlpha(8)
                        .toLowerCase(java.util.Locale.ROOT) + "@example.com")
                .birthdate(LocalDate.of(1990, 5, 5)).build();
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }
}
