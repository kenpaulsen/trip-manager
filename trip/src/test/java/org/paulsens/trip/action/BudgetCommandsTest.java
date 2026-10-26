package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.action.BudgetViews.BudgetPage;
import org.paulsens.trip.action.BudgetViews.InputsForm;
import org.paulsens.trip.action.BudgetViews.LineForm;
import org.paulsens.trip.action.BudgetViews.LodgingLineForm;
import org.paulsens.trip.action.BudgetViews.LodgingRow;
import org.paulsens.trip.action.BudgetViews.OfferChoice;
import org.paulsens.trip.action.BudgetViews.PayeeRow;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.BudgetLine;
import org.paulsens.trip.model.BudgetLodgingLine;
import org.paulsens.trip.model.CostExtra;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PricingModel;
import org.paulsens.trip.model.RatePlan;
import org.paulsens.trip.model.RateSchedule;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripBudget;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The budget bean end to end against the in-memory store: the gate matrix, the pre-fills, every write with
 * its refusals, and the page reproducing the sheet and lodging oracles ({@code docs/budget.md}). Each test
 * works on its own trip, so no test sees another's budget.
 */
public class BudgetCommandsTest {
    private static final LocalDateTime START = LocalDateTime.of(2027, 9, 14, 8, 0);

    private final BudgetCommands admin = new BudgetCommands(TestCallers::siteAdmin);
    private String orgId;
    private Accommodation pansion;
    private RatePlan plan;

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        final Organization org = new OrgCommands(TestCallers::siteAdmin)
                .createOrganization("Budget " + RandomData.genAlpha(8), "BDG", null);
        orgId = org.getId().getValue();
        pansion = Accommodation.builder().name("Pansion " + RandomData.genAlpha(5)).build();
        assertTrue(DAO.getInstance().saveAccommodation(pansion));
        plan = RatePlan.builder().accommodationId(pansion.getId()).name("Hotel rate 2027")
                .cost(schedule(PricingModel.PER_ROOM, 50_00, 0)).build();
        assertTrue(DAO.getInstance().saveRatePlan(plan));
    }

    // ------------------------------------------------------------------ fixtures

    private Trip newTrip(final int people) throws IOException {
        final List<Person.Id> ids = new ArrayList<>();
        for (int i = 0; i < people; i++) {
            ids.add(Person.Id.newInstance());
        }
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Budget trip")
                .startDate(START).endDate(START.plusDays(10)).people(ids).build();
        trip.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static RateSchedule schedule(final PricingModel model, final long nightly, final long supplement) {
        return RateSchedule.builder().pricingModel(model).nightlyCents(nightly).singleSupplementCents(supplement)
                .build();
    }

    /** The seeded plan's shape: price PER_PERSON $60 + $20 single, cost PER_ROOM $50 + FIXED $12 city tax. */
    private ReservationOffer seededOffer(final Trip trip, final String name) throws IOException {
        final ReservationOffer offer = ReservationOffer.builder().tripId(trip.getId()).name(name)
                .accommodationId(pansion.getId()).price(schedule(PricingModel.PER_PERSON, 60_00, 20_00))
                .cost(schedule(PricingModel.PER_ROOM, 50_00, 0))
                .costExtras(List.of(CostExtra.builder().label("City tax").basis(CostExtra.Basis.FIXED)
                        .amountCents(12_00).build()))
                .ratePlanId(plan.getId().getValue()).ratePlanVersion(plan.getVersion())
                .defaultStart(START.plusDays(1)).defaultEnd(START.plusDays(4)).build();
        assertTrue(DAO.getInstance().saveReservationOffer(offer));
        return offer;
    }

    private ReservationOffer perPersonOffer(final Trip trip, final RateSchedule cost) throws IOException {
        final ReservationOffer offer = ReservationOffer.builder().tripId(trip.getId()).name("Per person")
                .accommodationId(pansion.getId()).price(schedule(PricingModel.PER_PERSON, 40_00, 0)).cost(cost)
                .build();
        assertTrue(DAO.getInstance().saveReservationOffer(offer));
        return offer;
    }

    private static Person.Id grant(final String name, final String scope) {
        final Person.Id who = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(name, scope, name).withNewPerson(who)));
        return who;
    }

    private static BudgetCommands as(final Person.Id who) {
        return new BudgetCommands(() -> TestCallers.person(who));
    }

    private static LineForm line(final String kind, final String category, final String name, final Double amount,
            final String payee) {
        final LineForm form = new LineForm();
        form.setKind(kind);
        form.setCategory(category);
        form.setName(name);
        form.setAmount(amount);
        form.setPayee(payee);
        return form;
    }

    private static InputsForm inputs(final int people, final int nights, final Double budget, final Double toBill) {
        final InputsForm form = new InputsForm();
        form.setPeople(people);
        form.setTripNights(nights);
        form.setPerPersonBudget(budget);
        form.setToBill(toBill);
        return form;
    }

    private void saveSheet(final Trip trip) {
        final String id = trip.getId();
        assertTrue(admin.saveInputs(id, inputs(46, 10, 400.0, 1000.0)));
        final Object[][] lines = {
            {"GENERAL", "Van", 0.0, null}, {"GENERAL", "Badges + Printing", 300.0, null},
            {"GENERAL", "Guide", 1440.0, "Guide"}, {"GENERAL", "Van PDX-SEA", 0.0, null},
            {"GENERAL", "Buses + Transfers", 3000.0, null}, {"GENERAL", "Tips", 700.0, "Tips"},
            {"GENERAL", "Misc", 500.0, "Other"}, {"SCHOLARSHIP", "Sr. Angelicah", 1000.0, null},
            {"SCHOLARSHIP", "Sr. Dorothy", 1848.53, null}, {"SCHOLARSHIP", "Unnamed", 1000.0, null},
            {"LEADER", "Audie", 2000.0, null}, {"LEADER", "Ken Air + Room", 1806.03, null},
            {"LEADER", "Fr John Air + Room", 2307.63, null},
        };
        for (final Object[] row : lines) {
            assertTrue(admin.saveLine(id, line("SHARED", (String) row[0], (String) row[1], (Double) row[2],
                    (String) row[3])), "Saved " + row[1]);
        }
    }

    // ------------------------------------------------------------------ gates

    @Test
    public void gatesFollowTheTripAndFinancePrivileges() throws IOException {
        final Trip trip = newTrip(2);
        final String id = trip.getId();
        final BudgetCommands nobody = new BudgetCommands(
                () -> new Caller(null, false, AuditActor.system(), new PrivilegeCommands()));
        assertFalse(nobody.canViewBudget(id));
        assertFalse(nobody.canEditBudget(id));
        final BudgetCommands stranger = as(Person.Id.newInstance());
        assertFalse(stranger.canViewBudget(id));
        assertFalse(stranger.canEditBudget(id));

        final BudgetCommands mgr = as(grant(PrivilegeCommands.TRIP_MGR, id));
        assertTrue(mgr.canEditBudget(id));
        assertTrue(mgr.canViewBudget(id));
        final BudgetCommands finAdmin = as(grant(PrivilegeCommands.TRIP_FIN_ADMIN, id));
        assertTrue(finAdmin.canEditBudget(id));
        final BudgetCommands finView = as(grant(PrivilegeCommands.TRIP_FIN_VIEW, id));
        assertTrue(finView.canViewBudget(id), "A trip finance viewer reads the budget");
        assertFalse(finView.canEditBudget(id), "...and edits nothing");
        assertFalse(finView.saveInputs(id, inputs(1, 1, null, null)));
        final BudgetCommands global = as(grant(PrivilegeCommands.VIEW_FINANCES, null));
        assertTrue(global.canViewBudget(id), "The global finance viewer reads every trip's budget");
        assertFalse(global.canEditBudget(id));

        assertTrue(admin.canEditBudget(id), "A site admin holds everything");
        final String unknown = UUID.randomUUID().toString();
        assertFalse(admin.canEditBudget(unknown), "Unknown trip");
        assertFalse(admin.canViewBudget(unknown));
        assertFalse(admin.canViewBudget(null));
        assertFalse(admin.canViewBudget(" "));

        assertFalse(stranger.budgetPage(id).isExists(), "No view, an empty page");
        assertTrue(stranger.budgetPage(id).getGroups().isEmpty());
        assertEquals(stranger.budgetHeadline(id), "");
        assertTrue(stranger.lodgingChoices(id).isEmpty());
        assertTrue(stranger.lineFormsFor(id).isEmpty());
        assertTrue(stranger.lodgingFormsFor(id).isEmpty());
        assertEquals(stranger.inputsFormFor(id).getPeople(), 0);
        assertEquals(stranger.newLodgingForm(id).getPeople(), 0);
    }

    // ------------------------------------------------------------------ pre-fills

    @Test
    public void aNewBudgetPreFillsFromTheRosterAndTheTripDates() throws IOException {
        final Trip trip = newTrip(3);
        final String id = trip.getId();
        assertEquals(admin.tripPeople(id), 3);
        assertEquals(admin.tripNights(id), 10);
        assertEquals(admin.tripPeople(UUID.randomUUID().toString()), 0);
        assertEquals(admin.tripNights(UUID.randomUUID().toString()), 0);
        final BudgetPage page = admin.budgetPage(id);
        assertFalse(page.isExists());
        assertEquals(page.getPeople(), 3);
        assertEquals(page.getTripNights(), 10);
        assertFalse(page.isPeopleDiffers());
        assertFalse(page.isHasLodging());
        assertEquals(page.getWarnings().get(0),
                "No lodging line yet: the per-person figures do not include lodging.");
        assertEquals(page.getGroups().size(), 3);
        assertEquals(page.getGroups().get(1).getLabel(), "Scholarships");
        assertEquals(admin.budgetHeadline(id), BudgetCommands.NO_BUDGET);
        final InputsForm form = admin.inputsFormFor(id);
        assertEquals(form.getPeople(), 3);
        assertEquals(form.getTripNights(), 10);
        assertEquals(form.getPerPersonBudget(), 0.0);
        assertEquals(admin.newLodgingForm(id).getNights(), 10);
        assertEquals(admin.newLodgingForm(id).getPeople(), 3);

        assertTrue(admin.saveInputs(id, inputs(46, 9, 400.0, 1000.0)));
        final BudgetPage saved = admin.budgetPage(id);
        assertTrue(saved.isExists());
        assertEquals(saved.getVersion(), 1L);
        assertTrue(saved.isPeopleDiffers(), "Typed 46 against a roster of 3: the page offers Use");
        assertTrue(saved.isNightsDiffers());
        assertEquals(admin.newLodgingForm(id).getPeople(), 46, "A new lodging line takes the budget's people");
        assertEquals(admin.inputsFormFor(id).getToBill(), 1000.0);
        final TripBudget row = DAO.getInstance().getTripBudget(id, Cached.NO).orElseThrow();
        assertEquals(row.getOrgId(), orgId);
        assertEquals(row.getCreatedBy(), TestCallers.SITE_ADMIN_ID);
        assertNotNull(row.getCreated());

        final Trip undated = Trip.builder().id(UUID.randomUUID().toString()).title("Undated").build();
        undated.setStartDate(null);
        undated.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(undated));
        assertEquals(admin.tripNights(undated.getId()), 0, "No dates, no nights");
    }

    @Test
    public void pickingAnOptionSetsNightsAndTheFieldsItNeeds() throws IOException {
        final Trip trip = newTrip(2);
        final ReservationOffer seeded = seededOffer(trip, "Double room");
        final ReservationOffer perPerson = perPersonOffer(trip, schedule(PricingModel.PER_PERSON, 30_00, 0));
        final LodgingLineForm form = admin.newLodgingForm(trip.getId());
        form.setOfferId(seeded.getId().getValue());
        admin.pickOffer(trip.getId(), form);
        assertEquals(form.getNights(), 3, "The option's default stay");
        assertTrue(form.isNeedsRooms());
        assertTrue(form.isNeedsSingles());
        assertEquals(form.getDefaultPayee(), pansion.getName());
        form.setOfferId(perPerson.getId().getValue());
        admin.pickOffer(trip.getId(), form);
        assertEquals(form.getNights(), 10, "No default stay: the trip dates");
        assertFalse(form.isNeedsRooms(), "Per person on both sides: no rooms field");
        assertFalse(form.isNeedsSingles());
        form.setOfferId("gone");
        admin.pickOffer(trip.getId(), form);
        assertFalse(form.isNeedsRooms());
        assertNull(form.getDefaultPayee());
        form.setOfferId(null);
        admin.pickOffer(trip.getId(), form);
        admin.pickOffer(trip.getId(), null);

        final List<OfferChoice> choices = admin.lodgingChoices(trip.getId());
        assertEquals(choices.size(), 2);
        final OfferChoice first = choices.get(0);
        assertEquals(first.getLabel(), pansion.getName() + ": Double room · Hotel rate 2027 · "
                + "$60.00/night per person, +$20.00 single · hotel $50.00/night per room, +1 extra");
        assertTrue(first.isNeedsRooms());
        assertFalse(first.isPriceMissing());
        assertFalse(first.isCostMissing());
        perPerson.setDisabled(true);
        assertTrue(DAO.getInstance().saveReservationOffer(perPerson));
        assertEquals(admin.lodgingChoices(trip.getId()).size(), 1, "Only offered options are listed");
    }

    // ------------------------------------------------------------------ the oracles

    @Test
    public void theSheetAndTheLodgingOracleOnThePage() throws IOException {
        final Trip trip = newTrip(46);
        final String id = trip.getId();
        saveSheet(trip);
        final ReservationOffer offer = seededOffer(trip, "Double room");
        final LodgingLineForm lodging = admin.newLodgingForm(id);
        lodging.setOfferId(offer.getId().getValue());
        lodging.setNights(10);
        lodging.setPeople(46);
        lodging.setRooms(28);
        lodging.setSingles(10);
        lodging.setPayee("Huanita");
        assertTrue(admin.saveLodgingLine(id, lodging), lodging.getProblem());
        assertTrue(lodging.getId() != null && !lodging.getId().isBlank(), "The new line's id comes back");

        final BudgetPage page = admin.budgetPage(id);
        assertEquals(page.getGroups().get(0).getSubtotal(), "$5,940.00");
        assertEquals(page.getGroups().get(1).getSubtotal(), "$3,848.53");
        assertEquals(page.getGroups().get(2).getSubtotal(), "$6,113.66");
        assertEquals(page.getSharedTotal(), "$15,902.19");
        assertEquals(page.getEstimatedPerPerson(), "$345.70");
        assertTrue(page.isBudgetAboveEstimate());
        assertEquals(page.getBudgetLeftOver(), "$2,497.81");
        assertFalse(page.isLeftOverNegative());
        final LodgingRow row = page.getLodgingRows().get(0);
        assertEquals(row.getLabel(), pansion.getName() + " · Double room");
        assertEquals(row.getRatePlanName(), "Hotel rate 2027");
        assertEquals(row.getQuantities(), "10 nights × 46 people, 28 rooms, 10 single");
        assertEquals(row.getRevenue(), "$29,600.00");
        assertEquals(row.getHotelCost(), "$14,012.00");
        assertEquals(row.getMargin(), "$15,588.00");
        assertEquals(row.getPerPersonPrice(), "$600.00");
        assertEquals(row.getSingleAddOn(), "$200.00");
        assertEquals(row.getTotalPerPersonGround(), "$945.70");
        assertEquals(row.getToBillReference(), "$1,000.00");
        assertFalse(row.isBillAbove());
        assertFalse(row.isBillBelow(), "Typed $1,000 equals the reference");
        assertEquals(row.getPayee(), "Huanita");
        assertEquals(page.getLodgingMargin(), "$15,588.00");
        assertEquals(page.getOverall(), "$18,085.81");
        assertTrue(page.getWarnings().isEmpty(), page.getWarnings().toString());
        assertEquals(admin.budgetHeadline(id), "$18,085.81");
        final List<String> payees = new ArrayList<>();
        for (final PayeeRow payee : page.getPayees()) {
            payees.add(payee.getName() + "=" + payee.getAmount());
        }
        assertEquals(payees, List.of("Huanita=$14,012.00", "Guide=$1,440.00", "Tips=$700.00", "Other=$500.00"));
        assertEquals(page.getGrandTotal(), "$16,652.00");

        // The reference follows the typed budget ($300 + $600 = $900): $850 is below it, $1,200 above.
        assertTrue(admin.saveInputs(id, inputs(46, 10, 300.0, 850.0)));
        final LodgingRow below = admin.budgetPage(id).getLodgingRows().get(0);
        assertTrue(below.isBillBelow());
        assertFalse(admin.budgetPage(id).isBudgetAboveEstimate());
        assertTrue(admin.saveInputs(id, inputs(46, 10, 300.0, 1200.0)));
        assertTrue(admin.budgetPage(id).getLodgingRows().get(0).isBillAbove());
    }

    @Test
    public void negativesWarningsAndDeletedOptions() throws IOException {
        final Trip trip = newTrip(2);
        final String id = trip.getId();
        assertTrue(admin.saveInputs(id, inputs(2, 10, 10.0, 0.0)));
        assertTrue(admin.saveLine(id, line("SHARED", "GENERAL", "Bus", 5000.0, null)));
        final ReservationOffer unpriced = ReservationOffer.builder().tripId(id).name("Unpriced")
                .accommodationId(pansion.getId()).cost(schedule(PricingModel.PER_ROOM, 10_00, 0)).build();
        assertTrue(DAO.getInstance().saveReservationOffer(unpriced));
        final ReservationOffer costless = perPersonOffer(trip, null);
        for (final ReservationOffer offer : List.of(unpriced, costless)) {
            final LodgingLineForm form = admin.newLodgingForm(id);
            form.setOfferId(offer.getId().getValue());
            form.setRooms(1);
            assertTrue(admin.saveLodgingLine(id, form), form.getProblem());
        }
        BudgetPage page = admin.budgetPage(id);
        assertTrue(page.isLeftOverNegative());
        assertEquals(page.getBudgetLeftOver(), "-$4,980.00");
        assertFalse(page.isMarginNegative(), "-$100 on the unpriced option, +$800 on the costless one");
        assertEquals(page.getOverall(), "-$4,280.00");
        assertTrue(page.isOverallNegative());
        assertTrue(page.getLodgingRows().get(0).isPriceMissing());
        assertTrue(page.getLodgingRows().get(0).isMarginNegative());
        assertTrue(page.getLodgingRows().get(1).isCostMissing());
        assertEquals(page.getLodgingRows().get(1).getSingleAddOn(), "$0.00");
        assertEquals(page.getLodgingRows().get(1).getQuantities(), "10 nights × 2 people",
                "Per person, no supplement: no rooms, no singles");
        assertEquals(page.getWarnings().size(), 2, page.getWarnings().toString());
        assertTrue(page.getWarnings().get(0).contains("no participant price"));
        assertTrue(page.getWarnings().get(1).contains("no hotel cost"));
        assertTrue(admin.budgetHeadline(id).startsWith("-$"));

        assertTrue(DAO.getInstance().deleteReservationOffer(id, unpriced.getId()));
        page = admin.budgetPage(id);
        final LodgingRow gone = page.getLodgingRows().get(0);
        assertTrue(gone.isDeletedOption());
        assertEquals(gone.getLabel(), pansion.getName() + " · Unpriced " + BudgetCommands.DELETED_OPTION);
        assertEquals(gone.getRevenue(), "$0.00");
        assertEquals(gone.getHotelCost(), "$0.00", "A deleted option counts 0");
        assertFalse(gone.isPriceMissing());
        assertNull(gone.getRatePlanName());
        assertNull(gone.getPayee());
        assertTrue(page.getWarnings().get(0).contains("no longer one of the trip's lodging options"));
        // An edit of a line whose option is gone must pick a live one.
        final LodgingLineForm stale = admin.lodgingFormFor(id, gone.getId());
        assertFalse(admin.saveLodgingLine(id, stale));
        assertEquals(stale.getProblem(), "Choose one of the trip's lodging options.");
        final TripBudget row = DAO.getInstance().getTripBudget(id, Cached.NO).orElseThrow();
        row.getLodging().get(0).setLabel(null);
        assertTrue(DAO.getInstance().saveTripBudget(row));
        assertEquals(admin.budgetPage(id).getLodgingRows().get(0).getLabel(), BudgetCommands.DELETED_OPTION);
    }

    // ------------------------------------------------------------------ lines

    @Test
    public void linesSaveEditMoveAndDelete() throws IOException {
        final Trip trip = newTrip(4);
        final String id = trip.getId();
        final BudgetCommands mgr = as(grant(PrivilegeCommands.TRIP_MGR, id));
        assertTrue(mgr.saveLine(id, line("SHARED", "GENERAL", "Guide", 1440.0, "Guide")));
        assertTrue(mgr.saveLine(id, line("SHARED", "GENERAL", "Tips", 700.0, null)));
        assertTrue(mgr.saveLine(id, line("SHARED", "LEADER", "Audie", 2000.0, null)));
        final LineForm snacks = line("INDIVIDUAL", "GENERAL", "Snacks", 5.0, "Market");
        snacks.setPerNight(true);
        snacks.setNote(" daily ");
        assertTrue(mgr.saveLine(id, snacks));
        assertTrue(mgr.saveLine(id, line("INDIVIDUAL", null, "Rosary", null, null)), "A blank amount is $0");

        BudgetPage page = mgr.budgetPage(id);
        assertEquals(page.getGroups().get(0).getRows().size(), 2);
        assertTrue(page.getGroups().get(0).getRows().get(0).isFirst());
        assertTrue(page.getGroups().get(0).getRows().get(1).isLast());
        assertEquals(page.getIndividualRows().get(0).getTotal(), "$5.00 × 10 nights = $50.00");
        assertTrue(page.getIndividualRows().get(0).isPerNight());
        assertEquals(page.getIndividualRows().get(0).getNote(), "daily");
        assertEquals(page.getIndividualRows().get(1).getTotal(), "$0.00");
        assertEquals(page.getIndividualExtras(), "$50.00");
        assertEquals(page.getPayees().get(1).getAmount(), "$200.00", "Market: $50 x 4 people");

        final String guideId = page.getGroups().get(0).getRows().get(0).getId();
        final String tipsId = page.getGroups().get(0).getRows().get(1).getId();
        final LineForm edit = mgr.lineFormFor(id, guideId);
        assertEquals(edit.getAmount(), 1440.0);
        assertEquals(edit.getPayee(), "Guide");
        edit.setAmount(1500.0);
        edit.setCategory("SCHOLARSHIP");
        assertTrue(mgr.saveLine(id, edit));
        assertEquals(edit.getId(), guideId, "An edit keeps its id");
        page = mgr.budgetPage(id);
        assertEquals(page.getGroups().get(1).getRows().get(0).getAmount(), "$1,500.00", "Moved to Scholarships");
        assertEquals(mgr.lineFormFor(id, "nope").getKind(), "SHARED", "A gone line opens a fresh form");
        assertEquals(mgr.lineFormFor(id, snacks.getId()).getCategory(), "GENERAL");
        assertEquals(mgr.lineFormsFor(id).size(), 5);

        // Moves stay within a group: Tips steps past Audie, Snacks and Rosary to swap with Misc.
        assertTrue(mgr.saveLine(id, line("SHARED", "GENERAL", "Misc", 500.0, null)));
        assertTrue(mgr.moveLine(id, tipsId, 1));
        assertEquals(mgr.budgetPage(id).getGroups().get(0).getRows().get(1).getId(), tipsId,
                "Tips moved below Misc, past the lines of other groups");
        assertTrue(mgr.moveLine(id, tipsId, 1), "Already last: nothing to do");
        assertTrue(mgr.moveLine(id, tipsId, -1));
        assertEquals(mgr.budgetPage(id).getGroups().get(0).getRows().get(0).getId(), tipsId);
        assertFalse(mgr.moveLine(id, "nope", 1));

        assertTrue(mgr.deleteLine(id, tipsId));
        assertNull(DAO.getInstance().getTripBudget(id, Cached.NO).orElseThrow().line(tipsId));
        assertTrue(mgr.deleteLine(id, tipsId), "Already gone: quietly true");
        assertFalse(mgr.deleteLine(id, null));
        final LineForm gone = line("SHARED", "GENERAL", "Ghost", 1.0, null);
        gone.setId("no-such-line");
        assertFalse(mgr.saveLine(id, gone));
        assertEquals(gone.getProblem(), "This line no longer exists: reload the page.");
    }

    @Test
    public void lineAndInputRefusals() throws IOException {
        final Trip trip = newTrip(1);
        final String id = trip.getId();
        assertRefused(id, line("BOGUS", "GENERAL", "x", 1.0, null), "Choose shared or per person.");
        assertRefused(id, line(null, "GENERAL", "x", 1.0, null), "Choose shared or per person.");
        assertRefused(id, line("SHARED", "BOGUS", "x", 1.0, null), "Choose a category.");
        assertRefused(id, line("SHARED", "GENERAL", " ", 1.0, null), "A line needs a name.");
        assertRefused(id, line("SHARED", "GENERAL", "x".repeat(121), 1.0, null),
                "A line's name is at most 120 characters.");
        assertRefused(id, line("SHARED", "GENERAL", "x", -1.0, null),
                "The amount must be between $0 and $10,000,000.00.");
        assertRefused(id, line("SHARED", "GENERAL", "x", Double.POSITIVE_INFINITY, null),
                "The amount must be between $0 and $10,000,000.00.");
        assertRefused(id, line("SHARED", "GENERAL", "x", Double.NaN, null),
                "The amount must be between $0 and $10,000,000.00.");
        assertRefused(id, line("SHARED", "GENERAL", "x", 10_000_000.01, null),
                "The amount must be between $0 and $10,000,000.00.");
        final LineForm longNote = line("SHARED", "GENERAL", "x", 1.0, null);
        longNote.setNote("n".repeat(501));
        assertRefused(id, longNote, "A note is at most 500 characters.");
        assertRefused(id, line("SHARED", "GENERAL", "x", 1.0, "p".repeat(81)), "A payee is at most 80 characters.");
        assertFalse(admin.saveLine(id, null));
        assertFalse(admin.saveLine(UUID.randomUUID().toString(), line("SHARED", "GENERAL", "x", 1.0, null)),
                "Unknown trip: the gate");

        assertFalse(admin.saveInputs(id, inputs(-1, 1, null, null)));
        assertFalse(admin.saveInputs(id, inputs(10_001, 1, null, null)));
        assertFalse(admin.saveInputs(id, inputs(1, 366, null, null)));
        assertFalse(admin.saveInputs(id, inputs(1, -1, null, null)));
        assertFalse(admin.saveInputs(id, inputs(1, 1, -5.0, null)));
        assertFalse(admin.saveInputs(id, inputs(1, 1, 5.0, -1.0)));
        assertFalse(admin.saveInputs(id, null));
        assertTrue(DAO.getInstance().getTripBudget(id, Cached.NO).isEmpty(), "Nothing refused was stored");
        assertTrue(admin.saveInputs(id, inputs(1, 1, null, null)), "Blank dollars are $0");

        final BudgetCommands stranger = as(Person.Id.newInstance());
        assertFalse(stranger.saveLine(id, line("SHARED", "GENERAL", "x", 1.0, null)));
        assertFalse(stranger.deleteLine(id, "x"));
        assertFalse(stranger.moveLine(id, "x", 1));
        assertFalse(stranger.deleteLodgingLine(id, "x"));
        assertFalse(stranger.saveLodgingLine(id, new LodgingLineForm()));
    }

    private void assertRefused(final String tripId, final LineForm form, final String problem) {
        assertFalse(admin.saveLine(tripId, form));
        assertEquals(form.getProblem(), problem);
    }

    @Test
    public void lineAndLodgingCaps() throws IOException {
        final Trip trip = newTrip(1);
        final String id = trip.getId();
        final TripBudget full = TripBudget.builder().tripId(id).orgId(orgId).build();
        for (int i = 0; i < BudgetCommands.MAX_LINES; i++) {
            full.getLines().add(BudgetLine.builder().name("L" + i).build());
        }
        final ReservationOffer offer = perPersonOffer(trip, schedule(PricingModel.PER_PERSON, 1_00, 0));
        for (int i = 0; i < BudgetCommands.MAX_LODGING_LINES; i++) {
            full.getLodging().add(BudgetLodgingLine.builder()
                    .offerId(offer.getId().getValue()).nights(1).people(1).build());
        }
        assertTrue(DAO.getInstance().saveTripBudget(full));
        final LineForm more = line("SHARED", "GENERAL", "One more", 1.0, null);
        assertFalse(admin.saveLine(id, more));
        assertEquals(more.getProblem(), "A budget holds at most 200 lines.");
        final LodgingLineForm lodging = admin.newLodgingForm(id);
        lodging.setOfferId(offer.getId().getValue());
        lodging.setPeople(1);
        lodging.setNights(1);
        assertFalse(admin.saveLodgingLine(id, lodging));
        assertEquals(lodging.getProblem(), "A budget holds at most 50 lodging lines.");
    }

    // ------------------------------------------------------------------ lodging lines

    @Test
    public void lodgingLinesSaveEditRefuseAndDelete() throws IOException {
        final Trip trip = newTrip(5);
        final String id = trip.getId();
        final BudgetCommands finAdmin = as(grant(PrivilegeCommands.TRIP_FIN_ADMIN, id));
        final ReservationOffer seeded = seededOffer(trip, "Double room");
        final ReservationOffer perPerson = perPersonOffer(trip, schedule(PricingModel.PER_PERSON, 30_00, 0));

        final LodgingLineForm form = finAdmin.newLodgingForm(id);
        assertFalse(finAdmin.saveLodgingLine(id, form));
        assertEquals(form.getProblem(), "Choose one of the trip's lodging options.");
        form.setOfferId(seeded.getId().getValue());
        form.setRooms(0);
        assertFalse(finAdmin.saveLodgingLine(id, form));
        assertEquals(form.getProblem(), "This option is priced per room: enter how many rooms.");
        form.setRooms(3);
        form.setSingles(6);
        assertFalse(finAdmin.saveLodgingLine(id, form));
        assertEquals(form.getProblem(), "Single rooms must be between 0 and the number of people.");
        form.setSingles(1);
        form.setNights(0);
        assertFalse(finAdmin.saveLodgingLine(id, form));
        assertEquals(form.getProblem(), "Nights must be between 1 and 365.");
        form.setNights(3);
        form.setPeople(0);
        assertFalse(finAdmin.saveLodgingLine(id, form));
        assertEquals(form.getProblem(), "People must be between 1 and 10000.");
        form.setPeople(5);
        form.setNote("n".repeat(501));
        assertFalse(finAdmin.saveLodgingLine(id, form));
        assertEquals(form.getProblem(), "A note is at most 500 characters.");
        form.setNote("Book early");
        assertTrue(finAdmin.saveLodgingLine(id, form), form.getProblem());
        assertNull(form.getProblem(), "A successful save clears the dialog's refusal");
        final String lineId = form.getId();

        final LodgingRow row = finAdmin.budgetPage(id).getLodgingRows().get(0);
        assertEquals(row.getPayee(), pansion.getName(), "A blank payee is the hotel");
        assertEquals(row.getNote(), "Book early");
        assertEquals(row.getQuantities(), "3 nights × 5 people, 3 rooms, 1 single");
        assertEquals(finAdmin.lodgingFormsFor(id).size(), 1);

        // Switching to a per-person option drops the hidden rooms and singles.
        final LodgingLineForm edit = finAdmin.lodgingFormFor(id, lineId);
        assertTrue(edit.isNeedsRooms());
        assertEquals(edit.getDefaultPayee(), pansion.getName());
        edit.setOfferId(perPerson.getId().getValue());
        assertTrue(finAdmin.saveLodgingLine(id, edit), edit.getProblem());
        assertEquals(edit.getId(), lineId);
        final BudgetLodgingLine stored =
                DAO.getInstance().getTripBudget(id, Cached.NO).orElseThrow().lodgingLine(lineId);
        assertEquals(stored.getRooms(), 0);
        assertEquals(stored.getSingles(), 0);
        assertEquals(stored.getLabel(), pansion.getName() + " · Per person");
        assertEquals(finAdmin.lodgingFormFor(id, "nope").getPeople(), 5, "A gone line opens a fresh form");

        final LodgingLineForm ghost = finAdmin.newLodgingForm(id);
        ghost.setId("no-such-line");
        ghost.setOfferId(perPerson.getId().getValue());
        assertFalse(finAdmin.saveLodgingLine(id, ghost));
        assertEquals(ghost.getProblem(), "This lodging line no longer exists: reload the page.");
        assertFalse(finAdmin.saveLodgingLine(id, null));

        assertTrue(finAdmin.deleteLodgingLine(id, lineId));
        assertTrue(finAdmin.budgetPage(id).getLodgingRows().isEmpty());
        assertTrue(finAdmin.deleteLodgingLine(id, lineId), "Already gone: quietly true");
        assertFalse(finAdmin.deleteLodgingLine(id, null));
    }

    @Test
    public void staticHelpers() {
        assertEquals(BudgetCommands.cents(null), 0L);
        assertEquals(BudgetCommands.cents(1848.53), 1_848_53L);
        assertEquals(BudgetCommands.cents(0.005), 1L, "HALF_UP to the cent");
        assertEquals(BudgetCommands.money(-1_50), "-$1.50");
        final LineForm form = admin.newLineForm(" ", null);
        assertEquals(form.getKind(), "SHARED");
        assertEquals(form.getCategory(), "GENERAL");
        assertEquals(admin.newLineForm("INDIVIDUAL", "LEADER").getCategory(), "LEADER");
    }
}
