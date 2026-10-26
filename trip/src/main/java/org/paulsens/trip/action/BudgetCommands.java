package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.BudgetViews.BudgetPage;
import org.paulsens.trip.action.BudgetViews.Choice;
import org.paulsens.trip.action.BudgetViews.InputsForm;
import org.paulsens.trip.action.BudgetViews.LineForm;
import org.paulsens.trip.action.BudgetViews.LineRow;
import org.paulsens.trip.action.BudgetViews.LodgingLineForm;
import org.paulsens.trip.action.BudgetViews.LodgingRow;
import org.paulsens.trip.action.BudgetViews.OfferChoice;
import org.paulsens.trip.action.BudgetViews.OtherRow;
import org.paulsens.trip.action.BudgetViews.PayeeRow;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.BudgetCategory;
import org.paulsens.trip.model.BudgetLine;
import org.paulsens.trip.model.BudgetLineKind;
import org.paulsens.trip.model.BudgetLodgingLine;
import org.paulsens.trip.model.RatePlan;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripBudget;
import org.paulsens.trip.pay.BudgetMath;
import org.paulsens.trip.pay.BudgetMath.LodgingFigures;
import org.paulsens.trip.pay.BudgetMath.OtherItem;
import org.paulsens.trip.pay.BudgetMath.PayeeTotal;
import org.paulsens.trip.pay.CostExtras;
import org.paulsens.trip.pay.LodgingPricing;
import org.paulsens.trip.pay.MoneyMath;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import static org.paulsens.trip.action.PageFeedback.info;
import static org.paulsens.trip.action.PageFeedback.refuse;

/**
 * A trip's planning budget, exposed to pages as {@code #{budget}} ({@code docs/budget.md}): the four typed
 * inputs, SHARED and INDIVIDUAL lines, and lodging lines that read one of the trip's lodging options LIVE.
 * The arithmetic is {@link BudgetMath}'s; this bean gates, validates, applies forms to a fresh read of the one
 * {@link TripBudget} row, saves it with one versioned put, audits, and renders {@link BudgetViews} rows.
 *
 * <p>Gates: edit = site admin, {@code tripMgr@trip} or {@code tripFinAdmin@trip}; view = an editor,
 * {@code tripFinView@trip} or the global {@code viewFinances}. Offers are read straight from the DAO rather
 * than through {@link LodgingCommands}, whose gates are the lodging tab's, not the budget's.
 */
@Slf4j
@Named("budget")
@ApplicationScoped
public class BudgetCommands {
    static final int MAX_LINES = 200;
    static final int MAX_LODGING_LINES = 50;
    static final int MAX_PEOPLE = 10_000;
    static final int MAX_NIGHTS = 365;
    static final long MAX_CENTS = 10_000_000_00L;
    static final int MAX_NAME = 120;
    static final int MAX_NOTE = 500;
    static final int MAX_PAYEE = 80;
    static final String DELETED_OPTION = "(deleted option)";
    static final String NO_BUDGET = "No budget yet";
    private static final String SEP = " · ";

    private final Supplier<Caller> callerSource;
    private final Supplier<TripCommands> tripSource;
    private final Supplier<AuditCommands> auditSource;

    public BudgetCommands() {
        this(Caller::current);
    }

    /** Test seam (the LodgingCommands pattern): {@code Caller.current()} needs a FacesContext tests lack. */
    public BudgetCommands(final Supplier<Caller> callerSource) {
        this(callerSource, () -> new TripCommands(() -> new OrgCommands(callerSource)), AuditCommands::new);
    }

    BudgetCommands(final Supplier<Caller> callerSource, final Supplier<TripCommands> tripSource,
            final Supplier<AuditCommands> auditSource) {
        this.callerSource = callerSource;
        this.tripSource = tripSource;
        this.auditSource = auditSource;
    }

    // ================================================================== gates

    /** May edit the budget: site admin, the trip's managers or its finance admins. */
    public boolean canEditBudget(final String tripId) {
        if (!signedInOnKnownTrip(tripId)) {
            return false;
        }
        final Caller caller = caller();
        return caller.has(PrivilegeCommands.TRIP_MGR, tripId) || caller.has(PrivilegeCommands.TRIP_FIN_ADMIN, tripId);
    }

    /** May read the budget: an editor, the trip's finance viewers, or a global finance viewer. */
    public boolean canViewBudget(final String tripId) {
        if (canEditBudget(tripId)) {
            return true;
        }
        if (!signedInOnKnownTrip(tripId)) {
            return false;
        }
        final Caller caller = caller();
        return caller.has(PrivilegeCommands.TRIP_FIN_VIEW, tripId) || caller.has(PrivilegeCommands.VIEW_FINANCES);
    }

    private boolean signedInOnKnownTrip(final String tripId) {
        return tripId != null && !tripId.isBlank() && caller().isAuthenticated() && knownTrip(tripId) != null;
    }

    /** The trip, or null for an unknown one ({@code getTrip} answers a miss with a blank trip and a fresh id). */
    private Trip knownTrip(final String tripId) {
        final Trip trip = tripSource.get().getTrip(tripId);
        return (trip == null || !tripId.equals(trip.getId())) ? null : trip;
    }

    // ================================================================== reads

    /** The roster's head count: the people on the trip. */
    public int tripPeople(final String tripId) {
        final Trip trip = knownTrip(tripId);
        return (trip == null) ? 0 : trip.getPeople().size();
    }

    /** The nights between the trip's start and end dates. */
    public int tripNights(final String tripId) {
        final Trip trip = knownTrip(tripId);
        return (trip == null) ? 0 : nightsOf(trip);
    }

    private static int nightsOf(final Trip trip) {
        if (trip.getStartDate() == null || trip.getEndDate() == null) {
            return 0;
        }
        return Math.min(MAX_NIGHTS, LodgingPricing.nights(trip.getStartDate(), trip.getEndDate()));
    }

    /** The stored budget, else the pre-filled defaults (roster head count, trip-date nights) at version 0. */
    private TripBudget budgetOrDefault(final Trip trip, final Cached cached) {
        final TripBudget stored = DAO.getInstance().getTripBudget(trip.getId(), cached).orElse(null);
        if (stored != null) {
            return stored;
        }
        return TripBudget.builder().tripId(trip.getId()).orgId(trip.getOrgId()).people(trip.getPeople().size())
                .tripNights(nightsOf(trip)).build();
    }

    /** Everything the page renders; an empty page for a caller who may not view the budget. */
    public BudgetPage budgetPage(final String tripId) {
        final BudgetPage page = new BudgetPage();
        if (!canViewBudget(tripId)) {
            return page;
        }
        final Trip trip = knownTrip(tripId);
        final TripBudget budget = budgetOrDefault(trip, Cached.YES);
        final Map<String, ReservationOffer> offers = offersById(tripId);
        page.setExists(budget.getVersion() > 0L);
        page.setVersion(budget.getVersion());
        page.setPeople(budget.getPeople());
        page.setTripNights(budget.getTripNights());
        page.setTripPeople(trip.getPeople().size());
        page.setTripNightsFromDates(nightsOf(trip));
        page.setPeopleDiffers(page.getTripPeople() != budget.getPeople());
        page.setNightsDiffers(page.getTripNightsFromDates() != budget.getTripNights());
        page.setSharedRows(rowsOf(budget, budget.sharedLines()));
        page.setSharedTotal(money(BudgetMath.sharedTotal(budget)));
        page.setIndividualRows(rowsOf(budget, budget.individualLines()));
        page.setIndividualExtras(money(BudgetMath.individualExtras(budget)));
        final long estimate = BudgetMath.estimatedPerPerson(budget);
        page.setEstimatedPerPerson(money(estimate));
        page.setPerPersonBudget(money(budget.getPerPersonBudgetCents()));
        page.setBudgetAboveEstimate(budget.getPerPersonBudgetCents() >= estimate);
        page.setToBill(money(budget.getToBillCents()));
        fillLodging(page, budget, offers);
        final long leftOver = BudgetMath.budgetLeftOver(budget);
        page.setBudgetLeftOver(money(leftOver));
        page.setLeftOverNegative(leftOver < 0);
        final Function<BudgetLodgingLine, LodgingFigures> figures =
                line -> BudgetMath.lodging(line, offers.get(line.getOfferId()));
        final Function<BudgetLodgingLine, String> hotel = line -> hotelName(offers.get(line.getOfferId()));
        fillToBill(page, budget, figures);
        final List<PayeeTotal> payees = BudgetMath.payeeTotals(budget, figures, hotel);
        for (final PayeeTotal payee : payees) {
            page.getPayees().add(payeeRow(payee));
        }
        final List<OtherItem> others = BudgetMath.otherItems(budget, figures,
                line -> lodgingLabel(line, offers.get(line.getOfferId())), hotel);
        for (final OtherItem item : others) {
            page.getOtherRows().add(otherRow(item));
        }
        final long onSite = BudgetMath.grandTotal(payees);
        final long other = BudgetMath.otherTotal(others);
        page.setOnSiteTotal(money(onSite));
        page.setOtherTotal(money(other));
        page.setTripTotal(money(onSite + other));
        return page;
    }

    /**
     * The basic ground budget card: the typed figure, as a range up to + the largest single-room add-on +
     * the per-person extras when either can raise what one participant pays, and below it what the budget has
     * to cover per person ((shared costs + lodging hotel cost) / people).
     */
    private static void fillToBill(final BudgetPage page, final TripBudget budget,
            final Function<BudgetLodgingLine, LodgingFigures> figures) {
        final long floor = budget.getToBillCents();
        final long ceiling = floor + BudgetMath.maxSingleAddOn(budget, figures) + BudgetMath.individualExtras(budget);
        page.setToBillRange((ceiling > floor) ? money(floor) + " to " + money(ceiling) : money(floor));
        long lodgingCost = 0L;
        for (final BudgetLodgingLine line : budget.getLodging()) {
            lodgingCost += figures.apply(line).hotelCost();
        }
        page.setEstimatedGroundPerPerson(money(BudgetMath.estimatedGroundPerPerson(budget, lodgingCost)));
    }

    private static PayeeRow payeeRow(final PayeeTotal payee) {
        final PayeeRow row = new PayeeRow();
        row.setName(payee.name());
        row.setItems(payee.items());
        row.setAmount(money(payee.cents()));
        return row;
    }

    private static OtherRow otherRow(final OtherItem item) {
        final OtherRow row = new OtherRow();
        row.setName(item.name());
        row.setPayee(item.payee());
        row.setAmount(money(item.cents()));
        return row;
    }

    private void fillLodging(final BudgetPage page, final TripBudget budget,
            final Map<String, ReservationOffer> offers) {
        long revenue = 0L;
        long cost = 0L;
        for (final BudgetLodgingLine line : budget.getLodging()) {
            final ReservationOffer offer = offers.get(line.getOfferId());
            final LodgingFigures figures = BudgetMath.lodging(line, offer);
            revenue += figures.revenue();
            cost += figures.hotelCost();
            final LodgingRow row = lodgingRow(budget, line, offer, figures);
            page.getLodgingRows().add(row);
            if (row.isDeletedOption()) {
                page.getWarnings().add("'" + row.getLabel() + "' is no longer one of the trip's lodging options; "
                        + "its line counts $0.00.");
            } else {
                if (row.isPriceMissing()) {
                    page.getWarnings().add("'" + row.getLabel() + "' has no participant price yet; its revenue "
                            + "counts $0.00.");
                }
                if (row.isCostMissing()) {
                    page.getWarnings().add("'" + row.getLabel() + "' has no hotel cost yet; its cost counts $0.00.");
                }
            }
        }
        page.setHasLodging(budget.hasLodging());
        if (!budget.hasLodging()) {
            page.getWarnings().add(0, "No lodging line yet: the per-person figures do not include lodging.");
        }
        page.setLodgingRevenue(money(revenue));
        page.setLodgingCost(money(cost));
        page.setLodgingMargin(money(revenue - cost));
        page.setMarginNegative(revenue - cost < 0);
        page.setMarginPositive(revenue - cost > 0);
        final long overall = BudgetMath.overall(budget, revenue - cost);
        page.setOverall(money(overall));
        page.setOverallNegative(overall < 0);
        page.setOverallLabel((overall < 0) ? "Overall deficit" : "Overall surplus");
        page.setOverallAmount(money(Math.abs(overall)));
    }

    private LodgingRow lodgingRow(final TripBudget budget, final BudgetLodgingLine line,
            final ReservationOffer offer, final LodgingFigures figures) {
        final LodgingRow row = new LodgingRow();
        row.setId(line.getId());
        row.setDeletedOption(offer == null);
        row.setLabel(lodgingLabel(line, offer));
        row.setRatePlanName((offer == null) ? null : ratePlanName(offer));
        row.setOnSite(line.isOnSite());
        row.setQuantities(quantities(line, offer));
        row.setPriceLabel((offer == null) ? "" : OfferSchedules.pricingLabel(offer));
        row.setCostLabel((offer == null) ? "" : OfferSchedules.costLabel(offer));
        row.setPayee((line.getPayee() != null) ? line.getPayee() : hotelName(offer));
        row.setNote(line.getNote());
        row.setRevenue(money(figures.revenue()));
        row.setHotelCost(money(figures.hotelCost()));
        row.setMargin(money(figures.margin()));
        row.setMarginNegative(figures.margin() < 0);
        row.setPerPersonPrice(money(figures.perPersonPrice()));
        row.setSingleAddOn(figures.singleAddOnApplies() ? money(figures.singleAddOn()) : "none");
        row.setTotalPerPersonGround(money(BudgetMath.totalPerPersonGround(budget, figures)));
        final long reference = BudgetMath.toBillReference(budget, figures);
        row.setToBillReference(money(reference));
        row.setBillAbove(budget.getToBillCents() > reference);
        row.setBillBelow(budget.getToBillCents() < reference);
        row.setPriceMissing(offer != null && figures.priceMissing());
        row.setCostMissing(offer != null && figures.costMissing());
        return row;
    }

    /** What a lodging line is called in a list: the live option's name, else its captured label, flagged. */
    private static String lodgingLabel(final BudgetLodgingLine line, final ReservationOffer offer) {
        return (offer == null) ? deletedLabel(line) : offerName(offer);
    }

    private static String deletedLabel(final BudgetLodgingLine line) {
        return (line.getLabel() == null) ? DELETED_OPTION : line.getLabel() + " " + DELETED_OPTION;
    }

    /** "10 nights × 46 people, 28 rooms, 10 single": rooms and singles only when they count. */
    static String quantities(final BudgetLodgingLine line, final ReservationOffer offer) {
        final StringBuilder sb = new StringBuilder();
        sb.append(line.getNights()).append(line.getNights() == 1 ? " night × " : " nights × ")
                .append(line.getPeople()).append(line.getPeople() == 1 ? " person" : " people");
        if (line.getRooms() > 0 || BudgetMath.needsRooms(offer)) {
            sb.append(", ").append(line.getRooms()).append(line.getRooms() == 1 ? " room" : " rooms");
        }
        if (line.getSingles() > 0 || BudgetMath.needsSingles(offer)) {
            sb.append(", ").append(line.getSingles()).append(" single");
        }
        return sb.toString();
    }

    private static List<LineRow> rowsOf(final TripBudget budget, final List<BudgetLine> lines) {
        final int tripNights = budget.getTripNights();
        final List<LineRow> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            final BudgetLine line = lines.get(i);
            final LineRow row = new LineRow();
            row.setId(line.getId());
            row.setName(line.getName());
            row.setCategory(line.isShared() ? line.getCategory().getLabel() : "");
            row.setNote(line.getNote());
            row.setPayee(line.getPayee());
            row.setAmount(money(line.getAmountCents()));
            row.setPercentLabel(percentLabel(budget, line));
            row.setOnSite(line.isOnSite());
            row.setPerNight(line.isPerNight());
            row.setTotal(line.isPerNight()
                    ? money(line.getAmountCents()) + " × " + tripNights + " nights = "
                            + money(line.totalCents(tripNights))
                    : money(line.getAmountCents()));
            row.setFirst(i == 0);
            row.setLast(i == lines.size() - 1);
            rows.add(row);
        }
        return rows;
    }

    /** "10% of Guide" for a percent line; null for a typed amount. */
    static String percentLabel(final TripBudget budget, final BudgetLine line) {
        if (!line.isPercent()) {
            return null;
        }
        final BudgetLine base = budget.line(line.getPercentOf());
        return percentText(line.getPercentBps()) + " of " + ((base == null) ? "(deleted line)" : base.getName());
    }

    /** Basis points as a percentage with no trailing zeros: 1000 is "10%", 1250 is "12.5%". */
    static String percentText(final int bps) {
        return BigDecimal.valueOf(bps).movePointLeft(2).stripTrailingZeros().toPlainString() + "%";
    }

    /** The inputs dialog's form: the stored inputs, else the pre-filled defaults. */
    public InputsForm inputsFormFor(final String tripId) {
        final InputsForm form = new InputsForm();
        final Trip trip = knownTrip(tripId);
        if (trip == null || !canViewBudget(tripId)) {
            return form;
        }
        final TripBudget budget = budgetOrDefault(trip, Cached.YES);
        form.setPeople(budget.getPeople());
        form.setTripNights(budget.getTripNights());
        form.setPerPersonBudget(dollars(budget.getPerPersonBudgetCents()));
        form.setToBill(dollars(budget.getToBillCents()));
        return form;
    }

    /** A fresh line form of this kind and category (the page's "Add" buttons pass both). */
    public LineForm newLineForm(final String kind, final String category) {
        final LineForm form = new LineForm();
        form.setKind((kind == null || kind.isBlank()) ? BudgetLineKind.SHARED.name() : kind);
        form.setCategory((category == null || category.isBlank()) ? BudgetCategory.GENERAL.name() : category);
        return form;
    }

    /** The line dialog's form for an existing line; a fresh SHARED form when the line is gone. */
    public LineForm lineFormFor(final String tripId, final String lineId) {
        final Trip trip = knownTrip(tripId);
        final BudgetLine line = (trip == null || !canViewBudget(tripId)) ? null
                : budgetOrDefault(trip, Cached.YES).line(lineId);
        return (line == null) ? newLineForm(null, null) : formOf(line);
    }

    private static LineForm formOf(final BudgetLine line) {
        final LineForm form = new LineForm();
        form.setId(line.getId());
        form.setKind(line.getKind().name());
        form.setCategory(line.isShared() ? line.getCategory().name() : BudgetCategory.GENERAL.name());
        form.setName(line.getName());
        form.setAmount(dollars(line.getAmountCents()));
        form.setPerNight(line.isPerNight());
        form.setNote(line.getNote());
        form.setPayee(line.getPayee());
        form.setPercentMode(line.isPercent());
        form.setPercentOf(line.getPercentOf());
        form.setPercent(line.isPercent() ? BigDecimal.valueOf(line.getPercentBps()).movePointLeft(2).doubleValue()
                : null);
        form.setOffSite(line.isOffSite());
        return form;
    }

    /** Every typed line as a form (the REST GET). */
    public List<LineForm> lineFormsFor(final String tripId) {
        final List<LineForm> forms = new ArrayList<>();
        final Trip trip = knownTrip(tripId);
        if (trip == null || !canViewBudget(tripId)) {
            return forms;
        }
        for (final BudgetLine line : budgetOrDefault(trip, Cached.YES).getLines()) {
            forms.add(formOf(line));
        }
        return forms;
    }

    /** The line dialog's Category menu: every category, in menu order, value = the enum name. */
    public List<Choice> categories() {
        final List<Choice> choices = new ArrayList<>();
        for (final BudgetCategory category : BudgetCategory.values()) {
            choices.add(choice(category.name(), category.getLabel()));
        }
        return choices;
    }

    /**
     * The lines a percent line of {@code kind} may follow: every line of the same kind except {@code lineId}
     * itself, labelled with its current amount ("Guide ($1,440.00)"). Lines that already depend on
     * {@code lineId} are listed too; choosing one is refused on save as a cycle, with the reason.
     */
    public List<Choice> percentChoices(final String tripId, final String kind, final String lineId) {
        final List<Choice> choices = new ArrayList<>();
        final Trip trip = knownTrip(tripId);
        final BudgetLineKind wanted = parse(BudgetLineKind.class, kind);
        if (trip == null || wanted == null || !canViewBudget(tripId)) {
            return choices;
        }
        for (final BudgetLine line : budgetOrDefault(trip, Cached.YES).getLines()) {
            if (line.getKind() == wanted && !line.getId().equals(lineId)) {
                choices.add(choice(line.getId(), line.getName() + " (" + money(line.getAmountCents()) + ")"));
            }
        }
        return choices;
    }

    private static Choice choice(final String id, final String label) {
        final Choice choice = new Choice();
        choice.setId(id);
        choice.setLabel(label);
        return choice;
    }

    // ================================================================== clone

    /** How many recent trips the clone menu looks through for the organization's budgets. */
    static final int CLONE_SCAN = 400;

    /** Whether "Clone budget from..." is offered: an editor, on a budget with no lines of any kind yet. */
    public boolean canClone(final String tripId) {
        if (!canEditBudget(tripId)) {
            return false;
        }
        final TripBudget budget = DAO.getInstance().getTripBudget(tripId, Cached.YES).orElse(null);
        return budget == null || (budget.getLines().isEmpty() && budget.getLodging().isEmpty());
    }

    /**
     * The budgets this trip may be cloned from: other trips of the SAME organization (the tenancy boundary;
     * never another org's) whose budget the caller may read and that has at least one line, newest first.
     */
    public List<Choice> cloneChoices(final String tripId) {
        final List<Choice> choices = new ArrayList<>();
        final Trip trip = knownTrip(tripId);
        if (trip == null || trip.getOrgId() == null || !canEditBudget(tripId)) {
            return choices;
        }
        for (final Trip source : DAO.getInstance().getRecentTrips(CLONE_SCAN, Cached.YES)) {
            if (cloneable(trip, source)) {
                final TripBudget budget = DAO.getInstance().getTripBudget(source.getId(), Cached.YES).orElse(null);
                if (budget != null && !budget.getLines().isEmpty()) {
                    choices.add(choice(source.getId(), source.getTitle() + " (" + budget.getLines().size()
                            + (budget.getLines().size() == 1 ? " line)" : " lines)")));
                }
            }
        }
        return choices;
    }

    private boolean cloneable(final Trip target, final Trip source) {
        return !source.getId().equals(target.getId()) && target.getOrgId().equals(source.getOrgId())
                && canViewBudget(source.getId());
    }

    /**
     * Fills this trip's EMPTY budget from another of the organization's budgets: every typed line (fresh ids,
     * percent links re-pointed at the copies), the per-person shared budget and the basic ground budget.
     * People and nights stay this trip's, and lodging lines are not copied, because they point at the source
     * trip's own lodging options; the growl says so.
     */
    public boolean cloneBudget(final String tripId, final String fromTripId) {
        final Trip trip = editableTrip(tripId, fromTripId, PageFeedback::refuse);
        if (trip == null) {
            return false;
        }
        if (!canClone(tripId)) {
            return refuse("This budget already has lines: a budget can only be cloned into an empty one.");
        }
        final Trip source = knownTrip(fromTripId);
        if (source == null || trip.getOrgId() == null || !cloneable(trip, source)) {
            return refuse("Choose another of this organization's budgets to clone from.");
        }
        final TripBudget from = DAO.getInstance().getTripBudget(fromTripId, Cached.NO).orElse(null);
        if (from == null || from.getLines().isEmpty()) {
            return refuse("That trip has no budget lines to clone.");
        }
        final TripBudget budget = budgetOrDefault(trip, Cached.NO);
        budget.setPerPersonBudgetCents(from.getPerPersonBudgetCents());
        budget.setToBillCents(from.getToBillCents());
        budget.getLines().addAll(copies(from.getLines()));
        final String lodging = from.hasLodging() ? " Lodging lines were not copied: add this trip's own." : "";
        return persist(trip, budget, PageFeedback::refuse, "cloned " + from.getLines().size() + " lines from '"
                + source.getTitle() + "'", "Budget cloned from " + source.getTitle() + "." + lodging);
    }

    /** Copies of the lines with fresh ids, each percent link re-pointed at the copy of the line it followed. */
    private static List<BudgetLine> copies(final List<BudgetLine> lines) {
        final Map<String, String> ids = new LinkedHashMap<>();
        for (final BudgetLine line : lines) {
            ids.put(line.getId(), UUID.randomUUID().toString());
        }
        final List<BudgetLine> out = new ArrayList<>();
        for (final BudgetLine line : lines) {
            out.add(BudgetLine.builder().id(ids.get(line.getId())).kind(line.getKind()).category(line.getCategory())
                    .name(line.getName()).amountCents(line.getAmountCents()).perNight(line.isPerNight())
                    .note(line.getNote()).payee(line.getPayee())
                    .percentOf(line.isPercent() ? ids.get(line.getPercentOf()) : null)
                    .percentBps(line.getPercentBps()).offSite(line.isOffSite()).build());
        }
        return out;
    }

    /** The trip's offered lodging options for the lodging dialog's menu, by hotel then option name. */
    public List<OfferChoice> lodgingChoices(final String tripId) {
        final List<OfferChoice> choices = new ArrayList<>();
        if (!canViewBudget(tripId)) {
            return choices;
        }
        final List<ReservationOffer> offers = new ArrayList<>();
        for (final ReservationOffer offer : DAO.getInstance().getReservationOffers(tripId, Cached.YES)) {
            if (offer.isEnabled()) {
                offers.add(offer);
            }
        }
        offers.sort(Comparator.comparing(BudgetCommands::sortKey));
        for (final ReservationOffer offer : offers) {
            choices.add(choiceOf(offer));
        }
        return choices;
    }

    private static String sortKey(final ReservationOffer offer) {
        return (Objects.toString(hotelName(offer), "") + "\u0000" + offer.getName()).toLowerCase(Locale.ROOT);
    }

    private OfferChoice choiceOf(final ReservationOffer offer) {
        final OfferChoice choice = new OfferChoice();
        choice.setOfferId(offer.getId().getValue());
        final StringBuilder label = new StringBuilder(hotelName(offer)).append(": ").append(offer.getName());
        final String plan = ratePlanName(offer);
        if (plan != null) {
            label.append(SEP).append(plan);
        }
        label.append(SEP).append(OfferSchedules.pricingLabel(offer))
                .append(SEP).append("hotel ").append(OfferSchedules.costLabel(offer));
        choice.setLabel(label.toString());
        choice.setNeedsRooms(BudgetMath.needsRooms(offer));
        choice.setNeedsSingles(BudgetMath.needsSingles(offer));
        choice.setPriceMissing(!offer.isPriced());
        choice.setCostMissing(offer.getCost() == null);
        return choice;
    }

    /** A fresh lodging-line form: people from the budget (else the roster), nights from the trip dates. */
    public LodgingLineForm newLodgingForm(final String tripId) {
        final LodgingLineForm form = new LodgingLineForm();
        final Trip trip = knownTrip(tripId);
        if (trip == null || !canViewBudget(tripId)) {
            return form;
        }
        final TripBudget budget = budgetOrDefault(trip, Cached.YES);
        form.setPeople(budget.getPeople());
        form.setNights(budget.getTripNights());
        return form;
    }

    /** The lodging dialog's form for an existing line; a fresh form when the line is gone. */
    public LodgingLineForm lodgingFormFor(final String tripId, final String lineId) {
        final Trip trip = knownTrip(tripId);
        final BudgetLodgingLine line = (trip == null || !canViewBudget(tripId)) ? null
                : budgetOrDefault(trip, Cached.YES).lodgingLine(lineId);
        if (line == null) {
            return newLodgingForm(tripId);
        }
        final LodgingLineForm form = formOf(line);
        refreshFlags(form, offersById(tripId).get(line.getOfferId()));
        return form;
    }

    private static LodgingLineForm formOf(final BudgetLodgingLine line) {
        final LodgingLineForm form = new LodgingLineForm();
        form.setId(line.getId());
        form.setOfferId(line.getOfferId());
        form.setNights(line.getNights());
        form.setPeople(line.getPeople());
        form.setRooms(line.getRooms());
        form.setSingles(line.getSingles());
        form.setPayee(line.getPayee());
        form.setNote(line.getNote());
        form.setOffSite(line.isOffSite());
        return form;
    }

    /** Every lodging line as a form (the REST GET). */
    public List<LodgingLineForm> lodgingFormsFor(final String tripId) {
        final List<LodgingLineForm> forms = new ArrayList<>();
        final Trip trip = knownTrip(tripId);
        if (trip == null || !canViewBudget(tripId)) {
            return forms;
        }
        for (final BudgetLodgingLine line : budgetOrDefault(trip, Cached.YES).getLodging()) {
            forms.add(formOf(line));
        }
        return forms;
    }

    /**
     * The dialog's option menu changed: nights follow the option's default stay (else the trip dates) and the
     * rooms / single fields show only when the option needs them.
     */
    public void pickOffer(final String tripId, final LodgingLineForm form) {
        if (form == null) {
            return;
        }
        final ReservationOffer offer = (form.getOfferId() == null) ? null : offersById(tripId).get(form.getOfferId());
        refreshFlags(form, offer);
        if (offer == null) {
            return;
        }
        final int stay = (offer.getDefaultStart() == null || offer.getDefaultEnd() == null) ? 0
                : LodgingPricing.nights(offer.getDefaultStart(), offer.getDefaultEnd());
        form.setNights((stay > 0) ? Math.min(MAX_NIGHTS, stay) : tripNights(tripId));
    }

    private static void refreshFlags(final LodgingLineForm form, final ReservationOffer offer) {
        form.setNeedsRooms(BudgetMath.needsRooms(offer));
        form.setNeedsSingles(BudgetMath.needsSingles(offer));
        form.setDefaultPayee((offer == null) ? null : hotelName(offer));
    }

    /** The Reports dashboard card's number: the overall surplus (signed), or "No budget yet". */
    public String budgetHeadline(final String tripId) {
        if (!canViewBudget(tripId)) {
            return "";
        }
        final TripBudget budget = DAO.getInstance().getTripBudget(tripId, Cached.YES).orElse(null);
        if (budget == null) {
            return NO_BUDGET;
        }
        final Map<String, ReservationOffer> offers = offersById(tripId);
        long margin = 0L;
        for (final BudgetLodgingLine line : budget.getLodging()) {
            margin += BudgetMath.lodging(line, offers.get(line.getOfferId())).margin();
        }
        return money(BudgetMath.overall(budget, margin));
    }

    // ================================================================== writes

    /** Saves the four typed inputs; refusals growl. */
    public boolean saveInputs(final String tripId, final InputsForm form) {
        final Trip trip = editableTrip(tripId, form, PageFeedback::refuse);
        if (trip == null) {
            return false;
        }
        final String problem = inputsProblem(form);
        if (problem != null) {
            return refuse(problem);
        }
        final TripBudget budget = budgetOrDefault(trip, Cached.NO);
        budget.setPeople(form.getPeople());
        budget.setTripNights(form.getTripNights());
        budget.setPerPersonBudgetCents(cents(form.getPerPersonBudget()));
        budget.setToBillCents(cents(form.getToBill()));
        return persist(trip, budget, PageFeedback::refuse, "saved the inputs", "Budget inputs saved.");
    }

    private static String inputsProblem(final InputsForm form) {
        if (form.getPeople() < 0 || form.getPeople() > MAX_PEOPLE) {
            return "People must be between 0 and " + MAX_PEOPLE + ".";
        }
        if (form.getTripNights() < 0 || form.getTripNights() > MAX_NIGHTS) {
            return "Trip nights must be between 0 and " + MAX_NIGHTS + ".";
        }
        final String budget = dollarsProblem("The per-person budget", form.getPerPersonBudget());
        return (budget != null) ? budget : dollarsProblem("The amount to bill per person", form.getToBill());
    }

    /** Creates or edits a typed line; refusals land on {@code form.problem}, a new line's id on {@code form.id}. */
    public boolean saveLine(final String tripId, final LineForm form) {
        final Consumer<String> refusal = (form == null) ? PageFeedback::refuse : form::setProblem;
        final Trip trip = editableTrip(tripId, form, refusal);
        if (trip == null) {
            return false;
        }
        final BudgetLineKind kind = parse(BudgetLineKind.class, form.getKind());
        final BudgetCategory category = parse(BudgetCategory.class, form.getCategory());
        final String problem = lineProblem(form, kind, category);
        if (problem != null) {
            return refused(refusal, problem);
        }
        final TripBudget budget = budgetOrDefault(trip, Cached.NO);
        final boolean adding = form.getId() == null || form.getId().isBlank();
        final BudgetLine existing = adding ? null : budget.line(form.getId());
        if (!adding && existing == null) {
            return refused(refusal, "This line no longer exists: reload the page.");
        }
        if (adding && budget.getLines().size() >= MAX_LINES) {
            return refused(refusal, "A budget holds at most " + MAX_LINES + " lines.");
        }
        final BudgetLine line = BudgetLine.builder().id(adding ? null : existing.getId()).kind(kind)
                .category(category).name(form.getName())
                .amountCents(form.isPercentMode() ? 0L : cents(form.getAmount()))
                .percentOf(form.isPercentMode() ? form.getPercentOf() : null)
                .percentBps(form.isPercentMode() ? bps(form.getPercent()) : 0)
                .perNight(form.isPerNight()).note(form.getNote()).payee(form.getPayee()).offSite(form.isOffSite())
                .build();
        if (adding) {
            budget.getLines().add(line);
        } else {
            budget.getLines().set(budget.getLines().indexOf(existing), line);
        }
        // Checked against the budget WITH this line in place: a cycle runs through the line being saved.
        final String percent = BudgetMath.percentProblem(budget, line);
        if (percent != null) {
            return refused(refusal, percent);
        }
        if (!adding && line.getKind() != existing.getKind() && !budget.linesFollowing(line.getId()).isEmpty()) {
            return refused(refusal, "Other lines are a percentage of this one, so it cannot change between shared "
                    + "and per person.");
        }
        if (!persist(trip, budget, refusal, (adding ? "added" : "edited") + " line '" + line.getName() + "'",
                "Line saved.")) {
            return false;
        }
        form.setId(line.getId());
        form.setProblem(null);
        return true;
    }

    private static String lineProblem(final LineForm form, final BudgetLineKind kind, final BudgetCategory category) {
        if (kind == null) {
            return "Choose shared or per person.";
        }
        if (kind == BudgetLineKind.SHARED && category == null) {
            return "Choose a category.";
        }
        if (form.getName() == null || form.getName().isBlank()) {
            return "A line needs a name.";
        }
        if (form.getName().trim().length() > MAX_NAME) {
            return "A line's name is at most " + MAX_NAME + " characters.";
        }
        final String amount = form.isPercentMode() ? percentProblem(form) : dollarsProblem("The amount",
                form.getAmount());
        return (amount != null) ? amount : textProblem(form.getNote(), form.getPayee());
    }

    private static String percentProblem(final LineForm form) {
        if (form.getPercentOf() == null || form.getPercentOf().isBlank()) {
            return "Choose the line the percentage is of.";
        }
        final Double percent = form.getPercent();
        if (percent == null || percent.isNaN() || percent.isInfinite() || percent <= 0
                || bps(percent) > BudgetLine.MAX_PERCENT_BPS) {
            return "The percentage must be more than 0% and at most "
                    + percentText(BudgetLine.MAX_PERCENT_BPS) + ".";
        }
        return null;
    }

    /** A percentage (12.5) to basis points (1250), HALF_UP; 0 for null. */
    static int bps(final Double percent) {
        return (percent == null) ? 0
                : BigDecimal.valueOf(percent).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /** Deletes a typed line; refusals growl. Deleting a line that is already gone succeeds quietly. */
    public boolean deleteLine(final String tripId, final String lineId) {
        final Trip trip = editableTrip(tripId, lineId, PageFeedback::refuse);
        if (trip == null) {
            return false;
        }
        final TripBudget budget = budgetOrDefault(trip, Cached.NO);
        final BudgetLine line = budget.line(lineId);
        if (line == null) {
            return true;
        }
        final List<BudgetLine> following = budget.linesFollowing(lineId);
        if (!following.isEmpty()) {
            return refuse("'" + following.get(0).getName() + "' is a percentage of '" + line.getName()
                    + "': change or delete it first.");
        }
        budget.getLines().remove(line);
        return persist(trip, budget, PageFeedback::refuse, "deleted line '" + line.getName() + "'", "Line deleted.");
    }

    /**
     * Moves a typed line one place up ({@code dir < 0}) or down within its own table (same kind): the shared
     * and per-person lines are listed apart, so a swap across them would look like nothing happened.
     */
    public boolean moveLine(final String tripId, final String lineId, final int dir) {
        final Trip trip = editableTrip(tripId, lineId, PageFeedback::refuse);
        if (trip == null) {
            return false;
        }
        final TripBudget budget = budgetOrDefault(trip, Cached.NO);
        final BudgetLine line = budget.line(lineId);
        if (line == null) {
            return refuse("This line no longer exists: reload the page.");
        }
        final List<BudgetLine> lines = budget.getLines();
        final int from = lines.indexOf(line);
        final int step = (dir < 0) ? -1 : 1;
        for (int i = from + step; i >= 0 && i < lines.size(); i += step) {
            final BudgetLine other = lines.get(i);
            if (other.getKind() == line.getKind()) {
                Collections.swap(lines, from, i);
                return persist(trip, budget, PageFeedback::refuse, "moved line '" + line.getName() + "'", null);
            }
        }
        return true;
    }

    /** Creates or edits a lodging line; refusals land on {@code form.problem}, a new line's id on {@code form.id}. */
    public boolean saveLodgingLine(final String tripId, final LodgingLineForm form) {
        final Consumer<String> refusal = (form == null) ? PageFeedback::refuse : form::setProblem;
        final Trip trip = editableTrip(tripId, form, refusal);
        if (trip == null) {
            return false;
        }
        final ReservationOffer offer = (form.getOfferId() == null) ? null
                : offersById(tripId).get(form.getOfferId());
        if (offer == null) {
            return refused(refusal, "Choose one of the trip's lodging options.");
        }
        refreshFlags(form, offer);
        final String problem = lodgingProblem(form);
        if (problem != null) {
            return refused(refusal, problem);
        }
        final TripBudget budget = budgetOrDefault(trip, Cached.NO);
        final boolean adding = form.getId() == null || form.getId().isBlank();
        final BudgetLodgingLine existing = adding ? null : budget.lodgingLine(form.getId());
        if (!adding && existing == null) {
            return refused(refusal, "This lodging line no longer exists: reload the page.");
        }
        if (adding && budget.getLodging().size() >= MAX_LODGING_LINES) {
            return refused(refusal, "A budget holds at most " + MAX_LODGING_LINES + " lodging lines.");
        }
        final BudgetLodgingLine line = BudgetLodgingLine.builder().id(adding ? null : existing.getId())
                .offerId(offer.getId().getValue()).label(offerName(offer)).nights(form.getNights())
                .people(form.getPeople()).rooms(form.isNeedsRooms() ? form.getRooms() : 0)
                .singles(form.isNeedsSingles() ? form.getSingles() : 0).payee(form.getPayee()).note(form.getNote())
                .offSite(form.isOffSite())
                .build();
        if (adding) {
            budget.getLodging().add(line);
        } else {
            budget.getLodging().set(budget.getLodging().indexOf(existing), line);
        }
        if (!persist(trip, budget, refusal, (adding ? "added" : "edited") + " lodging line '" + line.getLabel()
                + "'", "Lodging line saved.")) {
            return false;
        }
        form.setId(line.getId());
        form.setProblem(null);
        return true;
    }

    private static String lodgingProblem(final LodgingLineForm form) {
        if (form.getNights() < 1 || form.getNights() > MAX_NIGHTS) {
            return "Nights must be between 1 and " + MAX_NIGHTS + ".";
        }
        if (form.getPeople() < 1 || form.getPeople() > MAX_PEOPLE) {
            return "People must be between 1 and " + MAX_PEOPLE + ".";
        }
        if (form.isNeedsRooms() && (form.getRooms() < 1 || form.getRooms() > MAX_PEOPLE)) {
            return "This option is priced per room: enter how many rooms.";
        }
        if (form.isNeedsSingles() && (form.getSingles() < 0 || form.getSingles() > form.getPeople())) {
            return "Single rooms must be between 0 and the number of people.";
        }
        return textProblem(form.getNote(), form.getPayee());
    }

    /** Deletes a lodging line; refusals growl. Deleting a line that is already gone succeeds quietly. */
    public boolean deleteLodgingLine(final String tripId, final String lineId) {
        final Trip trip = editableTrip(tripId, lineId, PageFeedback::refuse);
        if (trip == null) {
            return false;
        }
        final TripBudget budget = budgetOrDefault(trip, Cached.NO);
        final BudgetLodgingLine line = budget.lodgingLine(lineId);
        if (line == null) {
            return true;
        }
        budget.getLodging().remove(line);
        return persist(trip, budget, PageFeedback::refuse, "deleted lodging line '" + line.getLabel() + "'",
                "Lodging line deleted.");
    }

    // ================================================================== write plumbing

    /** The gate, the form, and the trip read for edit; null (after refusing) when any of them fails. */
    private Trip editableTrip(final String tripId, final Object form, final Consumer<String> refusal) {
        if (!canEditBudget(tripId)) {
            refuse("Not allowed: only the trip's managers and finance admins can edit its budget.");
            return null;
        }
        if (form == null) {
            refuse("Nothing to save.");
            return null;
        }
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        if (trip == null || !tripId.equals(trip.getId())) {
            refusal.accept("Unknown trip.");
            return null;
        }
        return trip;
    }

    /** Stamps a new budget, saves it with one versioned put, audits, and growls {@code done} when non-null. */
    private boolean persist(final Trip trip, final TripBudget budget, final Consumer<String> refusal,
            final String what, final String done) {
        // Every save re-derives the percent lines, so an edit of the line they follow reaches them too.
        BudgetMath.resolvePercents(budget);
        if (budget.getVersion() == 0L) {
            budget.setTripId(trip.getId());
            budget.setOrgId(trip.getOrgId());
            budget.setCreatedBy(caller().personId());
            budget.setCreated(LocalDateTime.now());
        }
        try {
            if (!DAO.getInstance().saveTripBudget(budget)) {
                return refused(refusal, "The budget could not be saved.");
            }
        } catch (final ConditionalCheckFailedException ex) {
            return refused(refusal, "Saved by someone else: reload the page and try again.");
        } catch (final IllegalArgumentException ex) {
            return refused(refusal, "The budget is too large to save: remove some lines or shorten their notes.");
        } catch (final IOException ex) {
            log.error("Unable to save the budget of trip {}", trip.getId(), ex);
            return refused(refusal, "The budget could not be saved: " + ex.getMessage());
        }
        auditSource.get().budget(trip.getId(), trip.getOrgId(), "Budget of '" + trip.getTitle() + "': " + what,
                caller().auditActor());
        if (done != null) {
            info(done);
        }
        return true;
    }

    private static boolean refused(final Consumer<String> refusal, final String message) {
        refusal.accept(message);
        return false;
    }

    private static String dollarsProblem(final String what, final Double dollars) {
        if (dollars == null) {
            return null;
        }
        if (dollars.isNaN() || dollars.isInfinite() || dollars < 0 || cents(dollars) > MAX_CENTS) {
            return what + " must be between $0 and " + MoneyMath.formatCents(MAX_CENTS) + ".";
        }
        return null;
    }

    private static String textProblem(final String note, final String payee) {
        if (note != null && note.trim().length() > MAX_NOTE) {
            return "A note is at most " + MAX_NOTE + " characters.";
        }
        if (payee != null && payee.trim().length() > MAX_PAYEE) {
            return "A payee is at most " + MAX_PAYEE + " characters.";
        }
        return null;
    }

    private static <E extends Enum<E>> E parse(final Class<E> type, final String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, name.trim());
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    // ================================================================== offers and names

    /** The trip's lodging options by id, enabled or not: a line keeps reading an option that stopped selling. */
    private static Map<String, ReservationOffer> offersById(final String tripId) {
        final Map<String, ReservationOffer> byId = new LinkedHashMap<>();
        for (final ReservationOffer offer : DAO.getInstance().getReservationOffers(tripId, Cached.YES)) {
            byId.put(offer.getId().getValue(), offer);
        }
        return byId;
    }

    private static String hotelName(final ReservationOffer offer) {
        if (offer == null || offer.getAccommodationId() == null) {
            return null;
        }
        return DAO.getInstance().getAccommodation(offer.getAccommodationId(), Cached.YES)
                .map(Accommodation::getName).orElse("(deleted hotel)");
    }

    /** "Pansion · Double room": what a lodging line is called, and what it keeps once the option is gone. */
    private static String offerName(final ReservationOffer offer) {
        final String hotel = hotelName(offer);
        return (hotel == null) ? offer.getName() : hotel + SEP + offer.getName();
    }

    /** The rate plan the option follows, by name; null for a standalone option. */
    private static String ratePlanName(final ReservationOffer offer) {
        if (!offer.isLinked() || offer.getAccommodationId() == null) {
            return null;
        }
        return DAO.getInstance().getRatePlan(offer.getAccommodationId().getValue(),
                RatePlan.Id.from(offer.getRatePlanId()), Cached.YES).map(RatePlan::getName).orElse("(deleted plan)");
    }

    // ================================================================== money

    static String money(final long cents) {
        return CostExtras.formatSigned(cents);
    }

    static long cents(final Double dollars) {
        return (dollars == null) ? 0L
                : BigDecimal.valueOf(dollars).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private static Double dollars(final long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).doubleValue();
    }

    Caller caller() {
        return callerSource.get();
    }
}
