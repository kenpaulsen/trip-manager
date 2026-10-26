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
import org.paulsens.trip.action.RatePlanViews.AttachForm;
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
 * Hotel rate plans end to end against the in-memory store: who edits a plan (the hotel's side, its only side),
 * the attach that creates a linked option -- unpriced, or priced by the attach dialog, and the same plan twice
 * -- the linked option owning its participant price while the cost follows the plan, the write-through fan-out
 * that never recomputes a bill, the pruning of stale links, detach, and the delete rules.
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

    /** A plan is the hotel's side only: its operator states the cost, and every reader sees the same row. */
    @Test
    public void anOperatorStatesTheCostAndEveryReaderSeesTheSameRow() throws IOException {
        final String accId = hotel();
        final Person keeper = savedPerson("Kep");
        makeOperator(keeper, accId);
        final RatePlanCommands asKeeper = new RatePlanCommands(() -> TestCallers.person(keeper.getId()));
        assertTrue(asKeeper.canEditCost(accId));

        final RatePlanForm form = asKeeper.planFormFor(accId, null);
        assertNull(form.getPlanId(), "a new plan");
        assertEquals(form.getAccId(), accId);
        assertTrue(form.isCostKnown(), "stating the cost is what the dialog is for");
        form.setName("Hotel rate");
        form.setRoomTypeIds(List.of(typeId(accId, 0)));
        form.setValidFrom(SEASON_START);
        form.setValidUntil(SEASON_END);
        form.setCostNightlyPrice(50.0);
        form.setCostExtras(List.of(new CostExtraForm("City tax", "FIXED", 12.0)));
        assertTrue(asKeeper.savePlan(form));
        assertNotNull(form.getPlanId(), "the minted id is written back");
        assertNull(form.getProblem());
        final RatePlan saved = plan(accId, form.getPlanId());
        assertEquals(saved.getCost().getNightlyCents(), 5000L);
        assertEquals(saved.getCostExtras().get(0).getAmountCents(), 1200L);
        assertEquals(saved.getVersion(), 1L);

        final List<RatePlanRow> keeperRows = asKeeper.rows(accId);
        assertEquals(keeperRows.size(), 1);
        assertEquals(keeperRows.get(0).getName(), "Hotel rate");
        assertEquals(keeperRows.get(0).getRoomTypes(), "Double");
        assertEquals(keeperRows.get(0).getDates(), "Sep 1 - Oct 31, 2027");
        assertEquals(keeperRows.get(0).getCostSummary(), "$50.00/night per room, +1 extra");
        assertEquals(keeperRows.get(0).getLinkedCount(), 0);
        assertEquals(keeperRows.get(0).getLinkedTrips(), "");
        assertEquals(plans.rows(accId), keeperRows, "a site admin reads exactly the operator's rows: no price");

        final RatePlanForm costEdit = asKeeper.planFormFor(accId, form.getPlanId());
        assertEquals(costEdit.getCostNightlyPrice(), 50.0);
        assertEquals(costEdit.getCostExtras().size(), 1);
        costEdit.setCostNightlyPrice(55.0);
        costEdit.setCostKnown(true);
        assertTrue(asKeeper.savePlan(costEdit));
        final RatePlan after = plan(accId, form.getPlanId());
        assertEquals(after.getCost().getNightlyCents(), 5500L, "the cost moved");
        final RatePlanForm unstated = asKeeper.planFormFor(accId, form.getPlanId());
        unstated.setCostKnown(false);
        assertTrue(asKeeper.savePlan(unstated));
        assertNull(plan(accId, form.getPlanId()).getCost(), "costKnown off clears the plan's cost");
        assertEquals(plans.rows(accId).get(0).getCostSummary(), "not set");
        assertTrue(asKeeper.savePlan(costEdit), "and states it again");

        // A stranger reads nothing and writes nothing; an operator of ANOTHER hotel reads the table (they may
        // open the lodging page) but not the trips' names.
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
     * An internal lodging admin who does not operate the hotel reads its plans (they may open the lodging page)
     * but edits nothing: a plan is the hotel's quote, and there is no longer a price side for them to own.
     */
    @Test
    public void aLodgingAdminWhoDoesNotOperateTheHotelReadsItsPlansButEditsNothing() throws IOException {
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

        final Person reader = savedPerson("Rdr");
        final OrgCommands orgs = new OrgCommands(TestCallers::siteAdmin);
        assertTrue(orgs.addMember(orgId, reader.getId()));
        assertTrue(orgs.grantOrgPrivilege(orgId, reader.getId(), PrivilegeCommands.LODGING_ADMIN));
        final RatePlanCommands asReader = new RatePlanCommands(() -> TestCallers.person(reader.getId()));
        assertFalse(asReader.canEditCost(accId), "a lodging admin of some organization does not operate this hotel");
        assertEquals(asReader.rows(accId).size(), 1, "...but may read the table");
        assertEquals(asReader.rows(accId).get(0).getNotes(), "Breakfast included");

        final RatePlanForm edit = asReader.planFormFor(accId, planId);
        edit.setName("Renamed by the reader");
        edit.setCostNightlyPrice(1.0);
        assertFalse(asReader.savePlan(edit));
        assertEquals(edit.getProblem(),
                "Not allowed: only this hotel's operators and lodging admins edit its rate plans.");
        final RatePlan untouched = plan(accId, planId);
        assertEquals(untouched.getName(), "Operator rate");
        assertEquals(untouched.getCost().getNightlyCents(), 5000L);
        final RatePlanForm fresh = asReader.planFormFor(accId, null);
        fresh.setName("Reader's own plan");
        fresh.setRoomTypeIds(List.of(typeId(accId, 0)));
        fresh.setCostNightlyPrice(40.0);
        assertFalse(asReader.savePlan(fresh));
        assertFalse(asReader.retirePlan(accId, planId, true));
        assertFalse(asReader.deletePlan(accId, planId));
        assertEquals(plans.rows(accId).size(), 1, "nothing was created or changed");
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
        form.setCostSupplement(-1.0);
        assertFalse(plans.savePlan(form));
        assertEquals(form.getProblem(), "The cost single supplement cannot be negative.");
        form.setCostSupplement(null);
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
     * Attaching creates a linked option with a LODGING event, copies the plan's COST and records the trip on
     * the plan; the option's PRICE is its own -- unpriced from the menu, set on the Offers tab -- a plan save
     * then fans the cost out and never touches a bill, while the option's own price edit recomputes its bills.
     */
    @Test
    public void attachCopiesThePlansCostAndTheLinkedOptionOwnsItsPrice() throws IOException {
        final String accId = hotel();
        final Person guest = savedPerson("Gue");
        final Trip trip = trip("Fan-out trip", guest);
        final String planId = costedPlan(accId, "Season rate", 50.0);

        // The store is shared with every other test's hotels, so the menu is read for THIS hotel only.
        final List<PlanChoice> menu = menuFor(trip, accId);
        assertEquals(menu.size(), 1);
        assertEquals(menu.get(0).getPlanId(), planId);
        assertEquals(menu.get(0).getAccId(), accId);
        assertTrue(menu.get(0).getLabel().endsWith(": Season rate (Sep 1 - Oct 31, 2027)"), menu.get(0).getLabel());
        assertTrue(plans.attach(trip.getId(), menu.get(0).getValue()));
        assertEquals(menuFor(trip, accId).size(), 1, "an attached plan stays in the menu: it may be taken twice");

        final ReservationOffer offer = linkedOffer(trip, planId);
        assertEquals(offer.getName(), "Season rate");
        assertEquals(offer.getRoomTypeIds(), List.of(typeId(accId, 0), typeId(accId, 1)));
        assertFalse(offer.isPriced(), "from the menu: needs participant pricing");
        assertEquals(offer.getCost().getNightlyCents(), 5000L, "the plan's cost, copied");
        assertEquals(offer.getCostExtras().size(), 1);
        assertEquals(offer.getRatePlanVersion(), plan(accId, planId).getVersion());
        assertEquals(offer.getValidFrom(), SEASON_START.atStartOfDay(), "the plan's dates are the option's range");
        assertEquals(offer.getValidUntil(), SEASON_END.atTime(23, 59));
        assertEquals(offer.getDefaultStart(), trip.getStartDate().toLocalDate().atTime(OfferForm.DEFAULT_ARRIVAL),
                "the default stay is the trip's");
        assertEquals(offer.getDefaultEnd(), trip.getEndDate().toLocalDate().atTime(OfferForm.DEFAULT_DEPARTURE));
        final TripEvent event = DAO.getInstance().getTrip(trip.getId(), Cached.NO).orElseThrow()
                .getTripEvent(offer.getTripEventId());
        assertNotNull(event, "a LODGING event was created");
        assertEquals(event.getType(), TripEvent.Type.LODGING);
        assertEquals(plan(accId, planId).getLinkedTripIds(), List.of(trip.getId()));
        assertEquals(plans.rows(accId).get(0).getLinkedTrips(), "Fan-out trip");
        assertEquals(plans.rows(accId).get(0).getLinkedCount(), 1);

        final OfferRow row = offerRow(trip, offer);
        assertTrue(row.isLinked());
        assertTrue(row.isNeedsPricing());
        assertEquals(row.getRatePlanName(), "Season rate");
        assertFalse(row.isPlanDrift());
        assertEquals(row.getCost(), "$50.00/night per room, +1 extra");
        assertTrue(admin.pricedOffers(trip.getId()).isEmpty(), "no stay may be taken on it yet");

        // The Offers tab prices the linked option: ITS price, the cost still the plan's whatever the form says.
        final OfferForm pricing = admin.offerFormFor(trip.getId(), offer.getId().getValue());
        assertEquals(pricing.getRatePlanId(), planId);
        assertEquals(pricing.getRatePlanName(), "Season rate", "the dialog's note");
        assertFalse(pricing.isPriceKnown());
        assertTrue(pricing.isCostKnown());
        pricing.setPriceKnown(true);
        pricing.setPricingModel("PER_ROOM");
        pricing.setNightlyPrice(70.0);
        pricing.setCostNightlyPrice(1.0);
        pricing.setCostExtras(List.of());
        assertTrue(admin.saveOffer(trip.getId(), pricing), pricing.getProblem());
        final ReservationOffer priced = admin.findOffer(trip.getId(), offer.getId().getValue());
        assertEquals(priced.getNightlyPriceCents(), 7000L, "the option's own price");
        assertEquals(priced.getCost().getNightlyCents(), 5000L, "the cost is the plan's, not the form's");
        assertEquals(priced.getCostExtras().size(), 1);
        assertTrue(priced.isLinked());

        // A stay on the linked option: 3 nights at $70 per room.
        final Reservation stay = reserve(trip, priced, guest);
        final LodgingBiller biller = new LodgingBiller(new TransactionsCommands());
        assertEquals(biller.billedByPerson(stay), Map.of(guest.getId(), 3 * 7000L));

        // Cost edit on the plan: the option follows, the bill does not move, nothing recomputes.
        final RecordingAudit audit = new RecordingAudit();
        final RatePlanForm cost = plans.planFormFor(accId, planId);
        cost.setCostNightlyPrice(45.0);
        cost.setCostExtras(List.of());
        assertTrue(auditedPlans(audit).savePlan(cost));
        assertNull(cost.getProblem(), "every linked trip was reached");
        final ReservationOffer afterCost = admin.findOffer(trip.getId(), offer.getId().getValue());
        assertEquals(afterCost.getCost().getNightlyCents(), 4500L);
        assertTrue(afterCost.getCostExtras().isEmpty());
        assertEquals(afterCost.getNightlyPriceCents(), 7000L, "the plan never carries a price to push");
        assertEquals(afterCost.getRatePlanVersion(), plan(accId, planId).getVersion(), "current, no drift");
        assertEquals(biller.billedByPerson(stay), Map.of(guest.getId(), 3 * 7000L), "a plan save bills nobody");
        assertFalse(audit.recomputed(), "a plan save never recomputes: no bill reads the cost side: " + audit.lines);
        assertTrue(audit.lines.stream().anyMatch(line -> line.startsWith("Rate plan 'Season rate' pushed to 1")),
                audit.lines.toString());

        // Price edit on the OPTION: its bill is recomputed; the cost stays the plan's.
        audit.lines.clear();
        final OfferForm raise = admin.offerFormFor(trip.getId(), offer.getId().getValue());
        raise.setNightlyPrice(100.0);
        raise.setMinNights(2);
        assertTrue(auditedLodging(audit).saveOffer(trip.getId(), raise));
        assertTrue(audit.recomputed(), "the option's own price edit recomputes: " + audit.lines);
        final ReservationOffer raised = admin.findOffer(trip.getId(), offer.getId().getValue());
        assertEquals(raised.getNightlyPriceCents(), 10000L);
        assertEquals(raised.getMinNights(), 2);
        assertEquals(raised.getCost().getNightlyCents(), 4500L);
        assertEquals(biller.billedByPerson(stay), Map.of(guest.getId(), 3 * 10000L), "the bill follows the option");

        // A linked option stays at its hotel; un-pricing it with a stay is refused; deleting a linked plan too.
        final OfferForm move = admin.offerFormFor(trip.getId(), offer.getId().getValue());
        final String elsewhere = hotel();
        move.setAccommodationId(elsewhere);
        move.setRoomTypeIds(List.of(typeId(elsewhere, 0)));
        assertFalse(admin.saveOffer(trip.getId(), move));
        assertEquals(move.getProblem(), "An option added from a rate plan stays at that hotel; detach it to move it.");
        assertEquals(admin.findOffer(trip.getId(), offer.getId().getValue()).getAccommodationId().getValue(), accId);
        final OfferForm unprice = admin.offerFormFor(trip.getId(), offer.getId().getValue());
        unprice.setPriceKnown(false);
        assertFalse(admin.saveOffer(trip.getId(), unprice));
        assertTrue(unprice.getProblem().contains("cannot lose its participant price"), unprice.getProblem());
        assertFalse(plans.deletePlan(accId, planId));
        assertNotNull(plan(accId, planId));

        // A copy left behind on purpose reads as drift on the Offers tab.
        final ReservationOffer stale = DAO.getInstance().getReservationOffer(trip.getId(), offer.getId(), Cached.NO)
                .orElseThrow();
        stale.setRatePlanVersion(stale.getRatePlanVersion() - 1);
        assertTrue(DAO.getInstance().saveReservationOffer(stale));
        assertTrue(offerRow(trip, offer).isPlanDrift());

        // Detach: the numbers stay, the link goes, the plan forgets the trip, the bill does not move. Once
        // detached the option may move hotels (its cost is now its own copy).
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
        assertNull(admin.offerFormFor(trip.getId(), offer.getId().getValue()).getRatePlanName());
        assertFalse(plans.detach(trip.getId(), offer.getId().getValue()), "nothing left to detach");
        assertFalse(plans.detach(trip.getId(), "no-such-offer"));
        assertFalse(plans.detach(trip.getId(), null));

        // Now the plan may be deleted; the detached option keeps its copy of the cost.
        assertTrue(plans.deletePlan(accId, planId));
        assertNull(plan(accId, planId));
        assertEquals(admin.findOffer(trip.getId(), offer.getId().getValue()).getCost().getNightlyCents(), 4500L,
                "the detached option kept its copy");
        assertFalse(plans.deletePlan(accId, planId), "already gone");
    }

    /**
     * The attach dialog prices the new option itself, and the same plan goes on a trip SEVERAL times (two
     * options at two prices): both follow the plan's cost, the trip is linked once, and it stays linked until
     * the LAST option following the plan is detached.
     */
    @Test
    public void attachWithPriceFieldsAndTheSamePlanTwice() throws IOException {
        final String accId = hotel();
        final Person guest = savedPerson("Twi");
        final Trip trip = trip("Twice trip", guest);
        final String planId = costedPlan(accId, "Twice rate", 50.0);

        final AttachForm form = plans.attachFormFor(trip.getId());
        assertEquals(form.getTripId(), trip.getId());
        assertTrue(form.isPriceKnown(), "the dialog offers a price");
        assertNull(form.getChoice());
        assertFalse(plans.attach(form), "no plan chosen");
        assertEquals(form.getProblem(), "Choose a rate plan.");
        form.setChoice(accId + "|" + planId);
        form.setPricingModel("PER_PERSON");
        form.setNightlyPrice(-5.0);
        assertFalse(plans.attach(form));
        assertEquals(form.getProblem(), "The nightly price cannot be negative.");
        assertTrue(admin.getOffers(trip.getId()).isEmpty(), "refused before anything was written");
        assertTrue(plan(accId, planId).getLinkedTripIds().isEmpty());
        form.setNightlyPrice(70.0);
        form.setSingleSupplement(20.0);
        assertTrue(plans.attach(form), form.getProblem());
        assertNull(form.getProblem());
        final ReservationOffer first = linkedOffer(trip, planId);
        assertTrue(first.isPriced());
        assertTrue(first.getPrice().isPerPerson());
        assertEquals(first.getNightlyPriceCents(), 7000L);
        assertEquals(first.getSingleSupplementCents(), 2000L);
        assertEquals(first.getCost().getNightlyCents(), 5000L);
        assertEquals(plan(accId, planId).getLinkedTripIds(), List.of(trip.getId()));

        // The same plan again, at another price: a second option, the trip linked once, no drift on either.
        final long version = plan(accId, planId).getVersion();
        final AttachForm again = plans.attachFormFor(trip.getId());
        again.setChoice(accId + "|" + planId);
        again.setNightlyPrice(90.0);
        assertTrue(plans.attach(again), again.getProblem());
        final List<ReservationOffer> following = admin.getOffers(trip.getId()).stream()
                .filter(o -> planId.equals(o.getRatePlanId())).toList();
        assertEquals(following.size(), 2);
        assertEquals(plan(accId, planId).getLinkedTripIds(), List.of(trip.getId()), "linked once");
        assertEquals(plan(accId, planId).getVersion(), version, "an already-linked plan is not rewritten");
        final ReservationOffer second = following.stream().filter(o -> o.getNightlyPriceCents() == 9000L)
                .findFirst().orElseThrow();
        assertFalse(second.getPrice().isPerPerson(), "PER_ROOM by default");
        assertEquals(second.getCost().getNightlyCents(), 5000L);
        assertEquals(second.getRatePlanVersion(), version);
        assertFalse(offerRow(trip, second).isPlanDrift());
        assertEquals(plans.rows(accId).get(0).getLinkedCount(), 1);
        assertEquals(menuFor(trip, accId).size(), 1, "and it could be taken a third time");

        // A plan save reaches both; detaching one leaves the trip linked, detaching the last unlinks it.
        final RatePlanForm cost = plans.planFormFor(accId, planId);
        cost.setCostNightlyPrice(55.0);
        assertTrue(plans.savePlan(cost));
        for (final ReservationOffer offer : following) {
            assertEquals(admin.findOffer(trip.getId(), offer.getId().getValue()).getCost().getNightlyCents(), 5500L);
        }
        assertEquals(admin.findOffer(trip.getId(), first.getId().getValue()).getNightlyPriceCents(), 7000L);
        assertEquals(admin.findOffer(trip.getId(), second.getId().getValue()).getNightlyPriceCents(), 9000L);
        assertTrue(plans.detach(trip.getId(), first.getId().getValue()));
        assertEquals(plan(accId, planId).getLinkedTripIds(), List.of(trip.getId()), "one option still follows");
        assertFalse(plans.deletePlan(accId, planId));
        assertTrue(plans.detach(trip.getId(), second.getId().getValue()));
        assertTrue(plan(accId, planId).getLinkedTripIds().isEmpty());
        assertTrue(plans.deletePlan(accId, planId));
        assertFalse(plans.attach(null), "no form");
        final AttachForm blankTrip = new AttachForm();
        blankTrip.setChoice(accId + "|" + planId);
        assertFalse(plans.attach(blankTrip));
        assertEquals(blankTrip.getProblem(), "Unknown trip.");
    }

    /** Two linked trips, one of them with its option deleted behind the plan's back: the save prunes it. */
    @Test
    public void aSavePrunesALinkThatNoOptionFollowsAnyMore() throws IOException {
        final String accId = hotel();
        final Trip kept = trip("Kept trip", savedPerson("Kpt"));
        final Trip stale = trip("Stale trip", savedPerson("Stl"));
        final String planId = costedPlan(accId, "Prune rate", 40.0);
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
        final String planId = costedPlan(accId, "Shared rate", 40.0);
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

    /** The menu offers live plans of live hotels that cover the trip's dates -- plans already on the trip too. */
    @Test
    public void attachableExcludesRetiredAndNonCoveringPlansButKeepsAttachedOnes() throws IOException {
        final String accId = hotel();
        final String otherHotel = hotel();
        final Trip trip = trip("Menu trip", savedPerson("Mnu"));
        final String covering = costedPlan(accId, "Covering", 40.0);
        final String open = planWithDates(accId, "Open dates", null, null);
        final String tooShort = planWithDates(accId, "Ends early", SEASON_START, LocalDate.of(2027, 9, 23));
        final String elsewhere = costedPlan(otherHotel, "Elsewhere", 40.0);
        final String retired = costedPlan(accId, "Retired", 40.0);
        assertTrue(plans.retirePlan(accId, retired, true));

        List<PlanChoice> menu = menuFor(trip, accId, otherHotel);
        assertEquals(planIds(menu), List.of(covering, open, elsewhere), "by hotel then plan name; the others are out");
        assertTrue(menu.get(1).getLabel().endsWith(": Open dates (any dates)"), menu.get(1).getLabel());
        assertTrue(plans.attach(trip.getId(), accId, covering));
        menu = menuFor(trip, accId, otherHotel);
        assertEquals(planIds(menu), List.of(covering, open, elsewhere), "the attached plan stays in the menu");

        assertFalse(plans.attach(trip.getId(), accId, tooShort), "does not cover the trip");
        assertFalse(plans.attach(trip.getId(), accId, retired), "retired");
        assertTrue(plans.attach(trip.getId(), accId, covering), "the same plan again is another option");
        assertEquals(admin.getOffers(trip.getId()).size(), 2);
        assertFalse(plans.attach(trip.getId(), accId, "no-such-plan"));
        assertFalse(plans.attach(trip.getId(), "no-such-hotel", covering));
        assertFalse(plans.attach(UUID.randomUUID().toString(), accId, open), "unknown trip");
        assertFalse(plans.attach(trip.getId(), "garbage"), "a menu value needs both ids");
        assertFalse(plans.attach(trip.getId(), (String) null));
        assertFalse(plans.attach(trip.getId(), accId + "|"));
        final RatePlanCommands stranger = new RatePlanCommands(() -> TestCallers.person(Person.Id.newInstance()));
        assertFalse(stranger.attach(trip.getId(), accId, open));
        assertFalse(stranger.detach(trip.getId(), linkedOffer(trip, covering).getId().getValue()));
        assertEquals(admin.getOffers(trip.getId()).size(), 2, "the refusals created nothing");

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
        assertEquals(seen.getLinkedTrips(), "1 trip", "another organization's trip is counted, not named");
        assertEquals(seen.getLinkedCount(), 1);
    }

    /** The copy is a copy: editing the plan's own objects afterwards changes nothing on the offer. */
    @Test
    public void copyFromPlanNeverAliasesThePlansSchedulesAndLeavesThePriceAlone() {
        final RatePlan plan = RatePlan.builder().name("Alias").cost(new RateSchedule(null, 5000L, Map.of("2027-09-22",
                7000L), 0L))
                .costExtras(List.of(new org.paulsens.trip.model.CostExtra("Tax", null, 100L))).version(4L).build();
        final RateSchedule own = new RateSchedule(null, 6000L, null, 1000L);
        final ReservationOffer offer = ReservationOffer.builder().name("Copy").price(own).build();
        OfferSchedules.copyFromPlan(offer, plan);
        assertEquals(offer.getRatePlanId(), plan.getId().getValue());
        assertEquals(offer.getRatePlanVersion(), 4L);
        assertEquals(offer.getPrice(), own, "the participant price is the option's own, never the plan's");
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
        final String planId = costedPlan(accId, "Triple rate", 40.0);
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
        final String planId = costedPlan(accId, "Stale rate", 40.0);
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
        final String planId = costedPlan(accId, "Contested rate", 40.0);
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
        final String planId = costedPlan(accId, "Raced rate", 40.0);
        assertTrue(plans.attach(trip.getId(), accId, planId));
        final ReservationOffer offer = linkedOffer(trip, planId);
        final String key = CacheKeys.LODGING_OFFER_PREFIX + trip.getId();
        final String cached = ageInCache(key, offer.getId().getValue(), offer.getVersion());

        final RatePlanForm form = plans.planFormFor(accId, planId);
        form.setCostNightlyPrice(75.0);
        assertTrue(plans.savePlan(form), "the plan itself is saved");
        assertEquals(form.getProblem(),
                "Plan saved; 0 of 1 trips updated; trip 'Raced trip': saved by someone else");
        assertEquals(plan(accId, planId).getCost().getNightlyCents(), 7500L);
        assertEquals(linkedOffer(trip, planId).getCost().getNightlyCents(), 4000L, "the option kept its copy");
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
        final String planId = costedPlan(accId, "Short-lived rate", 40.0);
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

    /** A plan over the season on every room type, costed with a fixed extra, through the admin's dialog. */
    private String costedPlan(final String accId, final String name, final double cost) {
        final RatePlanForm form = plans.planFormFor(accId, null);
        form.setName(name);
        form.setRoomTypeIds(List.of(typeId(accId, 0), typeId(accId, 1)));
        form.setValidFrom(SEASON_START);
        form.setValidUntil(SEASON_END);
        form.setCostNightlyPrice(cost);
        form.setCostExtras(List.of(new CostExtraForm("City tax", "FIXED", 12.0)));
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
        assertTrue(plans.savePlan(form), form.getProblem());
        return form.getPlanId();
    }

    /** Records every lodging audit line, so a test can say what was NOT audited (a recompute, say). */
    private static final class RecordingAudit extends AuditCommands {
        private final List<String> lines = new ArrayList<>();

        @Override
        public String lodging(final String targetType, final String targetId, final String orgId, final String msg,
                final org.paulsens.trip.audit.AuditActor who) {
            lines.add(msg);
            return super.lodging(targetType, targetId, orgId, msg, who);
        }

        boolean recomputed() {
            return lines.stream().anyMatch(line -> line.contains("recomputed"));
        }
    }

    /** A site-admin lodging bean whose audit trail the test can read. */
    private static LodgingCommands auditedLodging(final RecordingAudit audit) {
        return new LodgingCommands(TestCallers::siteAdmin,
                () -> new TripCommands(() -> new OrgCommands(TestCallers::siteAdmin)), TransactionsCommands::new,
                () -> new OrgCommands(TestCallers::siteAdmin), PrivilegeCommands::new, () -> audit,
                MediaCommands::new);
    }

    /** A site-admin plan bean whose fan-out (and whatever it makes the lodging bean do) the test can read. */
    private static RatePlanCommands auditedPlans(final RecordingAudit audit) {
        return new RatePlanCommands(TestCallers::siteAdmin, () -> auditedLodging(audit),
                () -> new TripCommands(() -> new OrgCommands(TestCallers::siteAdmin)), () -> audit);
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
