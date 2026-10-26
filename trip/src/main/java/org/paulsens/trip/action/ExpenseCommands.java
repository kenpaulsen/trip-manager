package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.BudgetViews.Choice;
import org.paulsens.trip.action.ExpenseViews.BudgetRow;
import org.paulsens.trip.action.ExpenseViews.CurrencyForm;
import org.paulsens.trip.action.ExpenseViews.CurrencyRow;
import org.paulsens.trip.action.ExpenseViews.ExpensePage;
import org.paulsens.trip.action.ExpenseViews.HeaderForm;
import org.paulsens.trip.action.ExpenseViews.ExpenseForm;
import org.paulsens.trip.action.ExpenseViews.ExpenseRow;
import org.paulsens.trip.action.ExpenseViews.PaymentForm;
import org.paulsens.trip.action.ExpenseViews.PaymentRow;
import org.paulsens.trip.action.ExpenseViews.ReceiptRow;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BudgetLine;
import org.paulsens.trip.model.BudgetLodgingLine;
import org.paulsens.trip.model.CurrencyRate;
import org.paulsens.trip.model.ExpenseCategory;
import org.paulsens.trip.model.ExpenseLine;
import org.paulsens.trip.model.ExpensePaidStatus;
import org.paulsens.trip.model.ExpensePayment;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripBudget;
import org.paulsens.trip.model.TripExpenseReport;
import org.paulsens.trip.pay.BudgetMath;
import org.paulsens.trip.pay.ExpenseMath;
import org.paulsens.trip.util.EmailAddresses;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import static org.paulsens.trip.action.MoneyForms.cents;
import static org.paulsens.trip.action.MoneyForms.dollars;
import static org.paulsens.trip.action.MoneyForms.dollarsProblem;
import static org.paulsens.trip.action.MoneyForms.money;
import static org.paulsens.trip.action.MoneyForms.parse;
import static org.paulsens.trip.action.MoneyForms.refused;
import static org.paulsens.trip.action.MoneyForms.signedDollarsProblem;
import static org.paulsens.trip.action.MoneyForms.tooLong;
import static org.paulsens.trip.action.PageFeedback.info;
import static org.paulsens.trip.action.PageFeedback.refuse;

/**
 * A trip's expense report, exposed to pages as {@code #{expenses}} ({@code docs/expense-report.md}): what was
 * actually spent, in whatever currency, against the cash advanced, each expense optionally matched to a line of
 * the trip's budget so the budget table shows what remains. The arithmetic is {@link ExpenseMath}'s; this bean
 * gates (the budget's own gate, {@link TripFinanceGate}), validates, applies forms to a fresh read of the one
 * {@link TripExpenseReport} row, saves it with one versioned put, audits, and renders {@link ExpenseViews} rows.
 *
 * <p>Receipts are stored by {@link ExpenseReceipts}; this bean only links their ids to the report or a line,
 * and asks it to purge the files of a line it deletes. Payments against the report are recorded here too,
 * behind the narrower settle gate (site admin or the trip's finance admins, never a trip manager as such).
 */
@Slf4j
@Named("expenses")
@ApplicationScoped
public class ExpenseCommands {
    static final int MAX_LINES = 300;
    static final int MAX_CURRENCIES = 20;
    static final int MAX_RECEIPTS_PER_LINE = 40;
    static final int MAX_REPORT_RECEIPTS = 60;
    static final int MAX_NAME = 120;
    static final int MAX_DESCRIPTION = 200;
    static final int MAX_NOTE = 500;
    static final int MAX_TEXT = 2000;
    static final int MAX_PAYMENTS = 100;
    /** Foreign units per dollar: a sanity cap well above any real currency (the dong is about 26,000). */
    static final long MAX_RATE_MICROS = 1_000_000L * CurrencyRate.MICROS;
    /** The largest foreign amount a line takes, in hundredths: a billion units covers any currency. */
    static final long MAX_AMOUNT_MINOR = 100_000_000_000L;
    static final String NO_EXPENSES = "No expenses yet";
    static final String DELETED_LINE = "(deleted budget line)";
    static final String VARIOUS = "Various";
    static final String SHARED = "Shared";
    static final String PER_PERSON = "Per person";
    static final String LODGING = "Lodging";
    static final String NO_LINES = "No specific expenses";
    static final String PAID_LINE = "This expense is in a recorded payment: remove it from the payment first.";
    private static final String NOT_ALLOWED =
            "Not allowed: only the trip's managers and finance admins can edit its expense report.";
    private static final String NOT_SETTLER =
            "Not allowed: only the trip's finance admins can record payments on its expense report.";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private final Supplier<Caller> callerSource;
    private final Supplier<AuditCommands> auditSource;
    private final Supplier<ExpenseReceipts> receiptSource;
    private final TripFinanceGate gate;
    private final Supplier<ConfigCommands> configSource = ConfigCommands::new;
    /** The CDI mail bean (its SES client is built once); a test swaps it for a spy. */
    private Supplier<MailCommands> mailSource = () -> org.paulsens.trip.api.Beans.get(MailCommands.class);

    public ExpenseCommands() {
        this(Caller::current);
    }

    /** Test seam (the BudgetCommands pattern): {@code Caller.current()} needs a FacesContext tests lack. */
    public ExpenseCommands(final Supplier<Caller> callerSource) {
        this(callerSource, () -> new TripCommands(() -> new OrgCommands(callerSource)), AuditCommands::new,
                () -> new ExpenseReceipts(callerSource));
    }

    ExpenseCommands(final Supplier<Caller> callerSource, final Supplier<TripCommands> tripSource,
            final Supplier<AuditCommands> auditSource, final Supplier<ExpenseReceipts> receiptSource) {
        this.callerSource = callerSource;
        this.auditSource = auditSource;
        this.receiptSource = receiptSource;
        this.gate = new TripFinanceGate(callerSource, tripSource);
    }

    // ================================================================== gates

    /** May edit the report: the budget's editors (site admin, the trip's managers and finance admins). */
    public boolean canEditExpenses(final String tripId) {
        return gate.canEdit(tripId);
    }

    /** May read the report: an editor, the trip's finance viewers, or a global finance viewer. */
    public boolean canViewExpenses(final String tripId) {
        return gate.canView(tripId);
    }

    /** May record payments against the report: site admin or the trip's finance admins (not its managers). */
    public boolean canRecordPayments(final String tripId) {
        return gate.canSettle(tripId);
    }

    // ================================================================== reads

    /** The stored report, else an empty one at version 0 naming the caller as its submitter. */
    private TripExpenseReport reportOrDefault(final Trip trip, final Cached cached) {
        final TripExpenseReport stored = DAO.getInstance().getTripExpenseReport(trip.getId(), cached).orElse(null);
        if (stored != null) {
            return stored;
        }
        return TripExpenseReport.builder().tripId(trip.getId()).orgId(trip.getOrgId())
                .submittedBy(callerName()).build();
    }

    /** The trip's budget, or null when it has none. */
    private static TripBudget budgetOf(final String tripId) {
        return DAO.getInstance().getTripBudget(tripId, Cached.YES).orElse(null);
    }

    /** Everything the page renders; an empty page for a caller who may not view the report. */
    public ExpensePage reportPage(final String tripId) {
        final ExpensePage page = new ExpensePage();
        if (!canViewExpenses(tripId)) {
            return page;
        }
        final Trip trip = gate.knownTrip(tripId);
        final TripExpenseReport report = reportOrDefault(trip, Cached.YES);
        page.setExists(report.getVersion() > 0L);
        page.setVersion(report.getVersion());
        page.setStatus(report.getStatus().getLabel());
        page.setStatusKey(report.getStatus().name().toLowerCase(Locale.ROOT));
        page.setTripTitle(trip.getTitle());
        page.setTripDates(tripDates(trip));
        page.setOrgName(orgName(trip));
        page.setSubmittedBy(report.getSubmittedBy());
        page.setSubmitted(report.getSubmittedOn() != null);
        if (report.getSubmittedOn() != null) {
            page.setSubmittedLine("Submitted by " + nameOr(report.getSubmittedBy(), "(no name)") + " on "
                    + DAY.format(report.getSubmittedOn()));
        }
        page.setCashAdvance(money(report.getCashAdvanceCents()));
        page.setCashAdvanceDetails(report.getCashAdvanceDetails());
        page.setPaymentInstructions(report.getPaymentInstructions());
        for (final CurrencyRate rate : report.getCurrencies()) {
            page.getCurrencyRows().add(currencyRow(report, rate));
        }
        final TripBudget budget = budgetOf(tripId);
        fillBudget(page, report, budget, BudgetCommands.offersById(tripId));
        fillLines(page, report, budget);
        page.setReportReceipts(receiptRows(report.getReceiptIds()));
        fillTotals(page, report);
        fillPayments(page, report);
        page.setCanRecordPayments(canRecordPayments(tripId));
        page.setLinesWithoutReceipt(ExpenseMath.linesWithoutReceipt(report));
        page.setTreasurerEmail(treasurerEmail(trip));
        if (report.getEmailedOn() != null) {
            page.setEmailedLine("Emailed to " + report.getEmailedTo() + " on " + DAY.format(report.getEmailedOn()));
        }
        warnMissingRates(page, report);
        return page;
    }

    /**
     * Total spent, the part settled outside the report, and what is due: the advance less what the submitter is
     * still out of pocket for.
     */
    private static void fillTotals(final ExpensePage page, final TripExpenseReport report) {
        final long byOrg = ExpenseMath.settledBy(report, ExpensePaidStatus.ORG);
        final long reimbursed = ExpenseMath.settledBy(report, ExpensePaidStatus.REIMBURSED);
        final long due = ExpenseMath.due(report);
        page.setTotal(money(ExpenseMath.total(report)));
        page.setHasSettled(report.getLines().stream().anyMatch(line -> !line.isOutOfPocket()));
        page.setPaidByOrg(money(byOrg));
        page.setReimbursed(money(reimbursed));
        page.setOutOfPocket(money(ExpenseMath.outOfPocket(report)));
        page.setDueToSubmitter(due < 0);
        page.setDueLabel("Due to " + dueTo(page, report, due));
        page.setDue(money(Math.abs(due)));
    }

    /** Who a signed balance is owed to: the submitter when negative, else the organization. */
    private static String dueTo(final ExpensePage page, final TripExpenseReport report, final long balance) {
        return (balance < 0) ? nameOr(report.getSubmittedBy(), "the submitter")
                : nameOr(page.getOrgName(), "the organization");
    }

    /** The payments table and what is still due after them. */
    private static void fillPayments(final ExpensePage page, final TripExpenseReport report) {
        for (final ExpensePayment payment : report.getPayments()) {
            page.getPaymentRows().add(paymentRow(report, payment));
        }
        final long stillDue = ExpenseMath.stillDue(report);
        page.setPaymentsTotal(money(ExpenseMath.paymentsTotal(report)));
        page.setSettled(stillDue == 0L);
        page.setStillDueToSubmitter(stillDue < 0);
        page.setStillDueLabel((stillDue == 0L) ? "Settled" : "Still due to " + dueTo(page, report, stillDue));
        page.setStillDue(money(Math.abs(stillDue)));
        page.setUnpaidCount(report.unpaidLines().size());
    }

    private static PaymentRow paymentRow(final TripExpenseReport report, final ExpensePayment payment) {
        final PaymentRow row = new PaymentRow();
        row.setId(payment.getId());
        row.setDateText((payment.getDate() == null) ? "" : DAY.format(payment.getDate()));
        row.setAmount(money(payment.getAmountCents()));
        row.setNegative(payment.getAmountCents() < 0);
        row.setMethod(payment.getMethod());
        row.setNote(payment.getNote());
        final List<String> names = new ArrayList<>();
        for (final String lineId : payment.getLineIds()) {
            final ExpenseLine line = report.line(lineId);
            if (line != null) {
                names.add(nameOr(line.getDescription(), "expense"));
            }
        }
        row.setLines(names.isEmpty() ? NO_LINES : String.join(", ", names));
        row.setLineCount(names.size());
        if (payment.getRecordedOn() != null) {
            row.setRecordedLine("Recorded by " + nameOr(payment.getRecordedBy(), "(no name)") + " on "
                    + DAY.format(payment.getRecordedOn()));
        }
        return row;
    }

    private static CurrencyRow currencyRow(final TripExpenseReport report, final CurrencyRate rate) {
        final CurrencyRow row = new CurrencyRow();
        row.setCode(rate.getCode());
        row.setRateText("1 USD = " + ExpenseMath.rate(rate.getPerUsdMicros()).toPlainString() + " " + rate.getCode());
        final BigDecimal inverse = ExpenseMath.usdPerUnit(rate.getPerUsdMicros());
        row.setInverseText((inverse == null) ? "no rate" : "1 " + rate.getCode() + " = $" + inverse.toPlainString());
        row.setNote(rate.getNote());
        row.setLines(report.linesUsing(rate.getCode()).size());
        row.setForeignTotal(foreign(ExpenseMath.currencyTotal(report, rate.getCode()), rate.getCode()));
        row.setUsdTotal(money(ExpenseMath.currencyUsd(report, rate.getCode())));
        row.setRateMissing(rate.getPerUsdMicros() <= 0L);
        return row;
    }

    /**
     * The budget-versus-actual rows: every budget line in the budget page's order (shared, then per person,
     * then lodging), what it planned, what the matched expenses spent and what remains; then the "Not in
     * budget" row, so the Spent column always adds up to the report's total.
     */
    private static void fillBudget(final ExpensePage page, final TripExpenseReport report, final TripBudget budget,
            final Map<String, ReservationOffer> offers) {
        final Set<String> ids = new HashSet<>();
        long planned = 0L;
        long spent = 0L;
        if (budget != null) {
            page.setHasBudget(!budget.getLines().isEmpty() || !budget.getLodging().isEmpty());
            for (final BudgetLine line : budget.getLines()) {
                final long amount = ExpenseMath.budgeted(budget, line);
                page.getBudgetRows().add(budgetRow(report, line.getId(), line.isShared() ? SHARED : PER_PERSON,
                        line.getName(), BudgetMath.categoryOf(line), amount));
                ids.add(line.getId());
                planned += amount;
                spent += ExpenseMath.spentOn(report, line.getId());
            }
            for (final BudgetLodgingLine line : budget.getLodging()) {
                final ReservationOffer offer = offers.get(line.getOfferId());
                final long amount = ExpenseMath.budgeted(line, offer);
                page.getBudgetRows().add(budgetRow(report, line.getId(), LODGING,
                        BudgetCommands.lodgingLabel(line, offer), BudgetMath.LODGING_CATEGORY, amount));
                ids.add(line.getId());
                planned += amount;
                spent += ExpenseMath.spentOn(report, line.getId());
            }
        }
        final BudgetRow outside = new BudgetRow();
        outside.setNotInBudget(true);
        outside.setLabel("Not in budget");
        final long notInBudget = ExpenseMath.notInBudget(report, ids);
        outside.setSpent(money(notInBudget));
        page.getBudgetRows().add(outside);
        page.setBudgetTotal(money(planned));
        page.setSpentTotal(money(spent + notInBudget));
        page.setRemainingTotal(money(planned - spent - notInBudget));
        page.setRemainingNegative(planned - spent - notInBudget < 0);
    }

    private static BudgetRow budgetRow(final TripExpenseReport report, final String id, final String section,
            final String label, final String category, final long planned) {
        final long spent = ExpenseMath.spentOn(report, id);
        final BudgetRow row = new BudgetRow();
        row.setId(id);
        row.setSection(section);
        row.setLabel(label);
        row.setCategory(category);
        row.setBudget(money(planned));
        row.setSpent(money(spent));
        row.setRemaining(money(planned - spent));
        row.setRemainingNegative(planned - spent < 0);
        return row;
    }

    private void fillLines(final ExpensePage page, final TripExpenseReport report, final TripBudget budget) {
        final List<ExpenseLine> lines = report.getLines();
        for (int i = 0; i < lines.size(); i++) {
            final ExpenseLine line = lines.get(i);
            final ExpenseRow row = new ExpenseRow();
            row.setId(line.getId());
            row.setDateText((line.getDate() == null) ? VARIOUS : DAY.format(line.getDate()));
            row.setCategory(line.getCategory().getLabel());
            row.setDescription(line.getDescription());
            row.setForeign(!line.isUsd());
            row.setAmountText(line.isUsd() ? money(line.getAmountMinor())
                    : foreign(line.getAmountMinor(), line.getCurrency()));
            row.setUsd(money(ExpenseMath.lineUsd(report, line)));
            row.setRateMissing(ExpenseMath.rateMissing(report, line));
            row.setBudgetLabel(budgetLabel(budget, line.getBudgetLineId()));
            row.setReceiptCount(line.getReceiptIds().size());
            row.setNote(line.getNote());
            paidChip(row, report, line);
            row.setFirst(i == 0);
            row.setLast(i == lines.size() - 1);
            page.getLineRows().add(row);
        }
    }

    /** The line's chip: "Paid Oct 7" once a payment settles it, else its own paid status (none for Not paid). */
    private static void paidChip(final ExpenseRow row, final TripExpenseReport report, final ExpenseLine line) {
        final ExpensePayment payment = report.paymentFor(line.getId());
        if (payment != null) {
            row.setPaidKey("paid");
            row.setPaymentId(payment.getId());
            row.setPaidChip("Paid" + ((payment.getDate() == null) ? "" : " " + SHORT_DAY.format(payment.getDate())));
            return;
        }
        row.setPaidKey(line.getPaidStatus().name().toLowerCase(Locale.ROOT));
        row.setPaidChip(line.getPaidStatus().getChip());
    }

    /** The matched budget line's name; null when unmatched, {@link #DELETED_LINE} when it is gone. */
    private static String budgetLabel(final TripBudget budget, final String budgetLineId) {
        if (budgetLineId == null) {
            return null;
        }
        final BudgetLine line = (budget == null) ? null : budget.line(budgetLineId);
        if (line != null) {
            return line.getName();
        }
        final BudgetLodgingLine lodging = (budget == null) ? null : budget.lodgingLine(budgetLineId);
        if (lodging != null) {
            return BudgetCommands.lodgingLabel(lodging,
                    BudgetCommands.offersById(budget.getTripId()).get(lodging.getOfferId()));
        }
        return DELETED_LINE;
    }

    private static void warnMissingRates(final ExpensePage page, final TripExpenseReport report) {
        final Set<String> warned = new HashSet<>();
        for (final ExpenseLine line : report.getLines()) {
            if (ExpenseMath.rateMissing(report, line) && warned.add(line.getCurrency())) {
                page.getWarnings().add("The expenses in " + line.getCurrency() + " have no exchange rate and count "
                        + "$0.00: enter the rate under Currencies.");
            }
        }
    }

    /** The receipts with these ids, in order; ids whose row is gone are skipped. */
    List<ReceiptRow> receiptRows(final List<String> mediaIds) {
        final List<ReceiptRow> rows = new ArrayList<>();
        for (final String id : mediaIds) {
            final MediaItem item = DAO.getInstance().getMedia(id, Cached.YES).orElse(null);
            if (item != null) {
                rows.add(receiptRow(item));
            }
        }
        return rows;
    }

    static ReceiptRow receiptRow(final MediaItem item) {
        final ReceiptRow row = new ReceiptRow();
        row.setMediaId(item.getId());
        row.setTitle((item.getTitle() == null) ? "Receipt" : item.getTitle());
        row.setUrl(ExpenseReceipts.path(item.getId()));
        row.setPdf(ExpenseReceipts.PDF.equals(item.getContentType()));
        row.setThumbUrl(row.isPdf() ? null : ExpenseReceipts.path(item.getId()) + ExpenseReceipts.THUMB);
        row.setSize(size(item.getSize()));
        return row;
    }

    /** The receipt dialog's list: a line's receipts, or the report-level ones when {@code lineId} is blank. */
    public List<ReceiptRow> receiptRowsFor(final String tripId, final String lineId) {
        final Trip trip = gate.knownTrip(tripId);
        if (trip == null || !canViewExpenses(tripId)) {
            return new ArrayList<>();
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.YES);
        if (lineId == null || lineId.isBlank()) {
            return receiptRows(report.getReceiptIds());
        }
        final ExpenseLine line = report.line(lineId);
        return (line == null) ? new ArrayList<>() : receiptRows(line.getReceiptIds());
    }

    /** The receipt dialog's heading: the line's description, or "Report receipts" at report level. */
    public String receiptHeading(final String tripId, final String lineId) {
        if (lineId == null || lineId.isBlank()) {
            return "Report receipts";
        }
        final Trip trip = gate.knownTrip(tripId);
        final ExpenseLine line = (trip == null || !canViewExpenses(tripId)) ? null
                : reportOrDefault(trip, Cached.YES).line(lineId);
        return (line == null) ? "Receipts" : "Receipts: " + nameOr(line.getDescription(), "expense");
    }

    /** The header fields as a form. */
    public HeaderForm headerFormFor(final String tripId) {
        final HeaderForm form = new HeaderForm();
        final Trip trip = gate.knownTrip(tripId);
        if (trip == null || !canViewExpenses(tripId)) {
            return form;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.YES);
        form.setSubmittedBy(report.getSubmittedBy());
        form.setCashAdvance(dollars(report.getCashAdvanceCents()));
        form.setCashAdvanceDetails(report.getCashAdvanceDetails());
        form.setPaymentInstructions(report.getPaymentInstructions());
        return form;
    }

    /** A fresh expense form: dollars, Other. */
    public ExpenseForm newLineForm() {
        return new ExpenseForm();
    }

    /** The expense dialog's form for an existing line; a fresh form when the line is gone. */
    public ExpenseForm lineFormFor(final String tripId, final String lineId) {
        final Trip trip = gate.knownTrip(tripId);
        final ExpenseLine line = (trip == null || !canViewExpenses(tripId)) ? null
                : reportOrDefault(trip, Cached.YES).line(lineId);
        return (line == null) ? newLineForm() : formOf(line);
    }

    private static ExpenseForm formOf(final ExpenseLine line) {
        final ExpenseForm form = new ExpenseForm();
        form.setId(line.getId());
        form.setDate(line.getDate());
        form.setCategory(line.getCategory().name());
        form.setDescription(line.getDescription());
        form.setCurrency(line.getCurrency());
        form.setAmount(dollars(line.getAmountMinor()));
        form.setBudgetLineId(line.getBudgetLineId());
        form.setNote(line.getNote());
        form.setPaidStatus(line.getPaidStatus().name());
        return form;
    }

    /** Every expense as a form (the REST GET). */
    public List<ExpenseForm> lineFormsFor(final String tripId) {
        final List<ExpenseForm> forms = new ArrayList<>();
        final Trip trip = gate.knownTrip(tripId);
        if (trip == null || !canViewExpenses(tripId)) {
            return forms;
        }
        for (final ExpenseLine line : reportOrDefault(trip, Cached.YES).getLines()) {
            forms.add(formOf(line));
        }
        return forms;
    }

    /** A fresh currency form. */
    public CurrencyForm newCurrencyForm() {
        return new CurrencyForm();
    }

    /** The currency dialog's form for a currency the report has; a fresh form when it is gone. */
    public CurrencyForm currencyFormFor(final String tripId, final String code) {
        final Trip trip = gate.knownTrip(tripId);
        final CurrencyRate rate = (trip == null || !canViewExpenses(tripId)) ? null
                : reportOrDefault(trip, Cached.YES).currency(code);
        if (rate == null) {
            return newCurrencyForm();
        }
        return currencyForm(rate);
    }

    private static CurrencyForm currencyForm(final CurrencyRate rate) {
        final CurrencyForm form = new CurrencyForm();
        form.setCode(rate.getCode());
        form.setRate(ExpenseMath.rate(rate.getPerUsdMicros()).doubleValue());
        form.setNote(rate.getNote());
        form.setExisting(true);
        return form;
    }

    /** Every currency as a form (the REST GET). */
    public List<CurrencyForm> currencyFormsFor(final String tripId) {
        final List<CurrencyForm> forms = new ArrayList<>();
        final Trip trip = gate.knownTrip(tripId);
        if (trip == null || !canViewExpenses(tripId)) {
            return forms;
        }
        for (final CurrencyRate rate : reportOrDefault(trip, Cached.YES).getCurrencies()) {
            forms.add(currencyForm(rate));
        }
        return forms;
    }

    /** The expense dialog's Currency menu: USD, the report's currencies, then "Add a currency...". */
    public List<Choice> currencyChoices(final String tripId) {
        final List<Choice> choices = new ArrayList<>();
        choices.add(choice(ExpenseLine.USD, "USD"));
        final Trip trip = gate.knownTrip(tripId);
        if (trip != null && canViewExpenses(tripId)) {
            for (final CurrencyRate rate : reportOrDefault(trip, Cached.YES).getCurrencies()) {
                choices.add(choice(rate.getCode(), rate.getCode()));
            }
        }
        choices.add(choice(ExpenseViews.NEW_CURRENCY, "Add a currency..."));
        return choices;
    }

    /**
     * The expense dialog's Budget line menu: every line of the trip's budget, shared then per person then
     * lodging, labelled with what it plans for the whole trip ("Shared: Guide ($1,440.00)").
     */
    public List<Choice> budgetChoices(final String tripId) {
        final List<Choice> choices = new ArrayList<>();
        final TripBudget budget = canViewExpenses(tripId) ? budgetOf(tripId) : null;
        if (budget == null) {
            return choices;
        }
        for (final BudgetLine line : budget.getLines()) {
            choices.add(choice(line.getId(), (line.isShared() ? SHARED : PER_PERSON) + ": " + line.getName()
                    + " (" + money(ExpenseMath.budgeted(budget, line)) + ")"));
        }
        final Map<String, ReservationOffer> offers = BudgetCommands.offersById(tripId);
        for (final BudgetLodgingLine line : budget.getLodging()) {
            final ReservationOffer offer = offers.get(line.getOfferId());
            choices.add(choice(line.getId(), LODGING + ": " + BudgetCommands.lodgingLabel(line, offer) + " ("
                    + money(ExpenseMath.budgeted(line, offer)) + ")"));
        }
        return choices;
    }

    /** The Category menu: every category, in menu order, value = the enum name. */
    public List<Choice> categories() {
        final List<Choice> choices = new ArrayList<>();
        for (final ExpenseCategory category : ExpenseCategory.values()) {
            choices.add(choice(category.name(), category.getLabel()));
        }
        return choices;
    }

    /** The Paid status menu: Not paid, Paid by organization, Already reimbursed; value = the enum name. */
    public List<Choice> paidStatuses() {
        final List<Choice> choices = new ArrayList<>();
        for (final ExpensePaidStatus status : ExpensePaidStatus.values()) {
            choices.add(choice(status.name(), status.getLabel()));
        }
        return choices;
    }

    /** The budget line just chosen in the dialog sets the category to match it (still editable after). */
    public void pickBudgetLine(final String tripId, final ExpenseForm form) {
        final ExpenseCategory category = (form == null) ? null : categoryOf(budgetOf(tripId), form.getBudgetLineId());
        if (category != null) {
            form.setCategory(category.name());
        }
    }

    /** The category a budget line implies: its own (same name), Lodging, or Other for a per-person line. */
    static ExpenseCategory categoryOf(final TripBudget budget, final String budgetLineId) {
        if (budget == null || budgetLineId == null) {
            return null;
        }
        final BudgetLine line = budget.line(budgetLineId);
        if (line != null) {
            return line.isShared() ? ExpenseCategory.ofBudget(line.getCategory()) : ExpenseCategory.GENERAL;
        }
        return (budget.lodgingLine(budgetLineId) == null) ? null : ExpenseCategory.LODGING;
    }

    /** The Reports dashboard card's number: what has been spent, or "No expenses yet". */
    public String expenseHeadline(final String tripId) {
        if (!canViewExpenses(tripId)) {
            return "";
        }
        final TripExpenseReport report = DAO.getInstance().getTripExpenseReport(tripId, Cached.YES).orElse(null);
        if (report == null || report.getLines().isEmpty()) {
            return NO_EXPENSES;
        }
        return money(ExpenseMath.total(report)) + " spent";
    }

    // ================================================================== writes

    /** Saves the report's own fields; refusals land on {@code form.problem} and growl. */
    public boolean saveHeader(final String tripId, final HeaderForm form) {
        final Consumer<String> refusal = (form == null) ? PageFeedback::refuse : headerRefusal(form);
        final Trip trip = gate.editableTrip(tripId, form, refusal, NOT_ALLOWED);
        if (trip == null) {
            return false;
        }
        final String problem = headerProblem(form);
        if (problem != null) {
            return refused(refusal, problem);
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        report.setSubmittedBy(trimToNull(form.getSubmittedBy()));
        report.setCashAdvanceCents(cents(form.getCashAdvance()));
        report.setCashAdvanceDetails(trimToNull(form.getCashAdvanceDetails()));
        report.setPaymentInstructions(trimToNull(form.getPaymentInstructions()));
        if (!persist(trip, report, refusal, "saved the details", "Expense report saved.")) {
            return false;
        }
        form.setProblem(null);
        return true;
    }

    private static Consumer<String> headerRefusal(final HeaderForm form) {
        return message -> refuseInto(form, message);
    }

    /** The header has no dialog of its own: a refusal both growls and stays on the form for the REST answer. */
    private static void refuseInto(final HeaderForm form, final String message) {
        form.setProblem(message);
        refuse(message);
    }

    private static String headerProblem(final HeaderForm form) {
        final String advance = dollarsProblem("The cash advance", form.getCashAdvance());
        if (advance != null) {
            return advance;
        }
        final String name = tooLong("The name", form.getSubmittedBy(), MAX_NAME);
        if (name != null) {
            return name;
        }
        final String details = tooLong("How the cash was advanced", form.getCashAdvanceDetails(), MAX_TEXT);
        if (details != null) {
            return details;
        }
        return tooLong("The payment instructions", form.getPaymentInstructions(), MAX_TEXT);
    }

    /** Stamps the report submitted, by the caller, now; refusals growl. Submitting again re-stamps it. */
    public boolean submit(final String tripId) {
        final Trip trip = gate.editableTrip(tripId, tripId, PageFeedback::refuse, NOT_ALLOWED);
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        if (report.getLines().isEmpty()) {
            return refuse("Add at least one expense before submitting.");
        }
        if (report.getSubmittedBy() == null) {
            report.setSubmittedBy(callerName());
        }
        report.setSubmittedById(caller().personId());
        report.setSubmittedOn(LocalDateTime.now());
        return persist(trip, report, PageFeedback::refuse, "submitted", "Expense report submitted.");
    }

    /** Takes the submit stamp back off (the report is a draft again, unless it has been paid); refusals growl. */
    public boolean unsubmit(final String tripId) {
        final Trip trip = gate.editableTrip(tripId, tripId, PageFeedback::refuse, NOT_ALLOWED);
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        if (report.getSubmittedOn() == null) {
            return true;
        }
        report.setSubmittedOn(null);
        report.setSubmittedById(null);
        return persist(trip, report, PageFeedback::refuse, "withdrew the submission", "Submission withdrawn.");
    }

    /** Adds a currency or edits a rate; refusals land on {@code form.problem}. */
    public boolean saveCurrency(final String tripId, final CurrencyForm form) {
        final Consumer<String> refusal = (form == null) ? PageFeedback::refuse : form::setProblem;
        final Trip trip = gate.editableTrip(tripId, form, refusal, NOT_ALLOWED);
        if (trip == null) {
            return false;
        }
        final String code = CurrencyRate.normalizeCode(form.getCode());
        final String problem = currencyProblem(code, form.getRate(), form.getNote());
        if (problem != null) {
            return refused(refusal, problem);
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final CurrencyRate existing = report.currency(code);
        if (form.isExisting() && existing == null) {
            return refused(refusal, "This currency is no longer on the report: reload the page.");
        }
        if (!form.isExisting() && existing != null) {
            return refused(refusal, code + " is already on the report: edit its rate instead.");
        }
        if (existing == null && report.getCurrencies().size() >= MAX_CURRENCIES) {
            return refused(refusal, "A report holds at most " + MAX_CURRENCIES + " currencies.");
        }
        final CurrencyRate rate = CurrencyRate.builder().code(code).perUsdMicros(micros(form.getRate()))
                .note(form.getNote()).build();
        if (existing == null) {
            report.getCurrencies().add(rate);
        } else {
            report.getCurrencies().set(report.getCurrencies().indexOf(existing), rate);
        }
        if (!persist(trip, report, refusal, ((existing == null) ? "added " : "set the rate of ") + code + " at "
                + ExpenseMath.rate(rate.getPerUsdMicros()).toPlainString(), "Currency saved.")) {
            return false;
        }
        form.setCode(code);
        form.setExisting(true);
        form.setProblem(null);
        return true;
    }

    /** Why a currency cannot be saved, or null. */
    private static String currencyProblem(final String code, final Double rate, final String note) {
        if (code == null) {
            return "A currency code is three letters, like EUR.";
        }
        if (ExpenseLine.USD.equals(code)) {
            return "Dollars need no rate.";
        }
        final long micros = micros(rate);
        if (rate == null || rate.isNaN() || rate.isInfinite() || micros <= 0L || micros > MAX_RATE_MICROS) {
            return "Enter the rate: how many " + code + " one US dollar buys (more than 0).";
        }
        return tooLong("A note", note, MAX_NOTE);
    }

    /** A typed rate in millionths, HALF_UP; 0 for null or not a number. */
    static long micros(final Double rate) {
        if (rate == null || rate.isNaN() || rate.isInfinite()) {
            return 0L;
        }
        return ExpenseMath.micros(BigDecimal.valueOf(rate));
    }

    /** Removes a currency no expense uses; refusals growl. Removing one that is already gone succeeds quietly. */
    public boolean deleteCurrency(final String tripId, final String code) {
        final Trip trip = gate.editableTrip(tripId, code, PageFeedback::refuse, NOT_ALLOWED);
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final CurrencyRate rate = report.currency(code);
        if (rate == null) {
            return true;
        }
        final int using = report.linesUsing(code).size();
        if (using > 0) {
            return refuse(using + ((using == 1) ? " expense is" : " expenses are") + " in " + code
                    + ": change or delete " + ((using == 1) ? "it" : "them") + " first.");
        }
        report.getCurrencies().remove(rate);
        return persist(trip, report, PageFeedback::refuse, "removed " + code, "Currency removed.");
    }

    /**
     * Creates or edits an expense; refusals land on {@code form.problem}, a new line's id on {@code form.id}.
     * Choosing "Add a currency..." in the form adds that currency in the same put. A blank category takes the
     * matched budget line's (else Other).
     */
    public boolean saveLine(final String tripId, final ExpenseForm form) {
        final Consumer<String> refusal = (form == null) ? PageFeedback::refuse : form::setProblem;
        final Trip trip = gate.editableTrip(tripId, form, refusal, NOT_ALLOWED);
        if (trip == null) {
            return false;
        }
        final String problem = lineProblem(form);
        if (problem != null) {
            return refused(refusal, problem);
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final String currency = currencyFor(report, form, refusal);
        if (currency == null) {
            return false;
        }
        final TripBudget budget = budgetOf(tripId);
        final String budgetLineId = trimToNull(form.getBudgetLineId());
        final ExpenseCategory matched = categoryOf(budget, budgetLineId);
        if (budgetLineId != null && matched == null) {
            return refused(refusal, "That budget line no longer exists: choose another.");
        }
        final ExpenseCategory category = (trimToNull(form.getCategory()) == null)
                ? ((matched == null) ? ExpenseCategory.GENERAL : matched)
                : parse(ExpenseCategory.class, form.getCategory());
        final boolean adding = form.getId() == null || form.getId().isBlank();
        final ExpenseLine existing = adding ? null : report.line(form.getId());
        if (!adding && existing == null) {
            return refused(refusal, "This expense no longer exists: reload the page.");
        }
        if (adding && report.getLines().size() >= MAX_LINES) {
            return refused(refusal, "A report holds at most " + MAX_LINES + " expenses.");
        }
        final ExpensePaidStatus paidStatus = paidStatusOf(form);
        if (!adding && paidStatus.isSettled() && report.isPaid(existing.getId())) {
            return refused(refusal, PAID_LINE);
        }
        final ExpenseLine line = ExpenseLine.builder().id(adding ? null : existing.getId()).date(form.getDate())
                .category(category).description(form.getDescription()).currency(currency)
                .amountMinor(cents(form.getAmount())).budgetLineId(budgetLineId).note(form.getNote())
                .receiptIds(adding ? null : existing.getReceiptIds()).paidStatus(paidStatus).build();
        if (adding) {
            report.getLines().add(line);
        } else {
            report.getLines().set(report.getLines().indexOf(existing), line);
        }
        if (!persist(trip, report, refusal, (adding ? "added" : "edited") + " expense '" + line.getDescription()
                + "'", "Expense saved.")) {
            return false;
        }
        form.setId(line.getId());
        form.setCurrency(currency);
        form.setCategory(category.name());
        form.setPaidStatus(paidStatus.name());
        form.setNewCode(null);
        form.setNewRate(null);
        form.setProblem(null);
        return true;
    }

    /** The form's paid status; Not paid when it names none. */
    private static ExpensePaidStatus paidStatusOf(final ExpenseForm form) {
        final ExpensePaidStatus status = parse(ExpensePaidStatus.class, form.getPaidStatus());
        return (status == null) ? ExpensePaidStatus.NOT_PAID : status;
    }

    private static String lineProblem(final ExpenseForm form) {
        if (trimToNull(form.getCategory()) != null && parse(ExpenseCategory.class, form.getCategory()) == null) {
            return "Choose a category.";
        }
        if (trimToNull(form.getPaidStatus()) != null && parse(ExpensePaidStatus.class, form.getPaidStatus()) == null) {
            return "Choose a paid status.";
        }
        if (trimToNull(form.getDescription()) == null) {
            return "An expense needs a description.";
        }
        final String description = tooLong("A description", form.getDescription(), MAX_DESCRIPTION);
        if (description != null) {
            return description;
        }
        final Double amount = form.getAmount();
        if (amount == null || amount.isNaN() || amount.isInfinite() || amount < 0
                || cents(amount) > MAX_AMOUNT_MINOR) {
            return "Enter the amount, in the currency it was paid in.";
        }
        return tooLong("A note", form.getNote(), MAX_NOTE);
    }

    /**
     * The line's currency code: USD, one the report has, or (for "Add a currency...") a new one added to
     * {@code report} here so it saves with the line. Null after refusing.
     */
    private static String currencyFor(final TripExpenseReport report, final ExpenseForm form,
            final Consumer<String> refusal) {
        if (!form.isAddingCurrency()) {
            final String code = CurrencyRate.normalizeCode(form.getCurrency());
            if (code == null || (!ExpenseLine.USD.equals(code) && report.currency(code) == null)) {
                refusal.accept("Choose a currency the report has, or add one.");
                return null;
            }
            return code;
        }
        final String code = CurrencyRate.normalizeCode(form.getNewCode());
        if (code != null && (ExpenseLine.USD.equals(code) || report.currency(code) != null)) {
            return code;
        }
        final String problem = currencyProblem(code, form.getNewRate(), null);
        if (problem != null) {
            refusal.accept(problem);
            return null;
        }
        if (report.getCurrencies().size() >= MAX_CURRENCIES) {
            refusal.accept("A report holds at most " + MAX_CURRENCIES + " currencies.");
            return null;
        }
        report.getCurrencies().add(CurrencyRate.builder().code(code).perUsdMicros(micros(form.getNewRate())).build());
        return code;
    }

    /** Deletes an expense and its receipts; refusals growl. Deleting one that is already gone succeeds quietly. */
    public boolean deleteLine(final String tripId, final String lineId) {
        final Trip trip = gate.editableTrip(tripId, lineId, PageFeedback::refuse, NOT_ALLOWED);
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final ExpenseLine line = report.line(lineId);
        if (line == null) {
            return true;
        }
        if (report.isPaid(lineId)) {
            return refuse(PAID_LINE);
        }
        report.getLines().remove(line);
        if (!persist(trip, report, PageFeedback::refuse, "deleted expense '" + line.getDescription() + "'",
                "Expense deleted.")) {
            return false;
        }
        // After the put: a refused save must leave the files its line still points at.
        receiptSource.get().purge(line.getReceiptIds());
        return true;
    }

    /** Moves an expense one place up ({@code dir < 0}) or down. */
    public boolean moveLine(final String tripId, final String lineId, final int dir) {
        final Trip trip = gate.editableTrip(tripId, lineId, PageFeedback::refuse, NOT_ALLOWED);
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final ExpenseLine line = report.line(lineId);
        if (line == null) {
            return refuse("This expense no longer exists: reload the page.");
        }
        final List<ExpenseLine> lines = report.getLines();
        final int from = lines.indexOf(line);
        final int to = from + ((dir < 0) ? -1 : 1);
        if (to < 0 || to >= lines.size()) {
            return true;
        }
        Collections.swap(lines, from, to);
        return persist(trip, report, PageFeedback::refuse, "moved expense '" + line.getDescription() + "'", null);
    }

    /**
     * Submits, then (when {@code emailTreasurer}) emails the report to the organization's treasurer: the Submit
     * dialog's one call. A refused email does not undo the submit; both outcomes growl.
     */
    public boolean submit(final String tripId, final Boolean emailTreasurer) {
        if (!submit(tripId)) {
            return false;
        }
        return !Boolean.TRUE.equals(emailTreasurer) || emailTreasurer(tripId);
    }

    // ================================================================== payments

    /** A fresh payment form, dated today. */
    public PaymentForm newPaymentForm() {
        final PaymentForm form = new PaymentForm();
        form.setDate(LocalDate.now());
        return form;
    }

    /** The payment dialog's form for a recorded payment; a fresh form when it is gone. */
    public PaymentForm paymentFormFor(final String tripId, final String paymentId) {
        final Trip trip = gate.knownTrip(tripId);
        final ExpensePayment payment = (trip == null || !canViewExpenses(tripId)) ? null
                : reportOrDefault(trip, Cached.YES).payment(paymentId);
        return (payment == null) ? newPaymentForm() : paymentForm(payment);
    }

    private static PaymentForm paymentForm(final ExpensePayment payment) {
        final PaymentForm form = new PaymentForm();
        form.setId(payment.getId());
        form.setDate(payment.getDate());
        form.setAmount(dollars(payment.getAmountCents()));
        form.setMethod(payment.getMethod());
        form.setNote(payment.getNote());
        form.setLineIds(new ArrayList<>(payment.getLineIds()));
        return form;
    }

    /** Every payment as a form (the REST GET). */
    public List<PaymentForm> paymentFormsFor(final String tripId) {
        final List<PaymentForm> forms = new ArrayList<>();
        final Trip trip = gate.knownTrip(tripId);
        if (trip == null || !canViewExpenses(tripId)) {
            return forms;
        }
        for (final ExpensePayment payment : reportOrDefault(trip, Cached.YES).getPayments()) {
            forms.add(paymentForm(payment));
        }
        return forms;
    }

    /**
     * The payment dialog's checklist: every expense still to be paid, plus those of the payment being edited
     * ({@code paymentId}, blank for a new one), labeled "Oct 3, Taxi, $27.71".
     */
    public List<Choice> unpaidLineChoices(final String tripId, final String paymentId) {
        final List<Choice> choices = new ArrayList<>();
        final Trip trip = gate.knownTrip(tripId);
        if (trip == null || !canViewExpenses(tripId)) {
            return choices;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.YES);
        final ExpensePayment editing = report.payment(paymentId);
        for (final ExpenseLine line : report.getLines()) {
            final boolean mine = editing != null && editing.getLineIds().contains(line.getId());
            if (mine || (line.isOutOfPocket() && !report.isPaid(line.getId()))) {
                choices.add(choice(line.getId(), ((line.getDate() == null) ? VARIOUS : DAY.format(line.getDate()))
                        + ", " + nameOr(line.getDescription(), "expense") + ", "
                        + money(ExpenseMath.lineUsd(report, line))));
            }
        }
        return choices;
    }

    /** The checklist just changed: the amount becomes what the ticked expenses add up to (still editable). */
    public void prefillAmount(final String tripId, final PaymentForm form) {
        final Trip trip = (form == null || !canViewExpenses(tripId)) ? null : gate.knownTrip(tripId);
        if (trip != null) {
            form.setAmount(dollars(ExpenseMath.linesUsd(reportOrDefault(trip, Cached.YES), form.getLineIds())));
        }
    }

    /**
     * Records a payment (blank {@code form.id}) or edits one; refusals land on {@code form.problem}, a new
     * payment's id on {@code form.id}. Finance admins only. The amount is signed and free: the note says why it
     * differs from the expenses it settles.
     */
    public boolean recordPayment(final String tripId, final PaymentForm form) {
        final Consumer<String> refusal = (form == null) ? PageFeedback::refuse : form::setProblem;
        final Trip trip = gate.settleableTrip(tripId, form, refusal, NOT_SETTLER);
        if (trip == null) {
            return false;
        }
        final String problem = paymentProblem(form);
        if (problem != null) {
            return refused(refusal, problem);
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final boolean adding = form.getId() == null || form.getId().isBlank();
        final ExpensePayment existing = adding ? null : report.payment(form.getId());
        if (!adding && existing == null) {
            return refused(refusal, "This payment no longer exists: reload the page.");
        }
        if (adding && report.getPayments().size() >= MAX_PAYMENTS) {
            return refused(refusal, "A report holds at most " + MAX_PAYMENTS + " payments.");
        }
        final List<String> lineIds = distinct(form.getLineIds());
        final String lineProblem = paymentLinesProblem(report, existing, lineIds);
        if (lineProblem != null) {
            return refused(refusal, lineProblem);
        }
        final ExpensePayment payment = ExpensePayment.builder().id(adding ? null : existing.getId())
                .date(form.getDate()).amountCents(cents(form.getAmount())).method(form.getMethod())
                .note(form.getNote()).lineIds(lineIds)
                .recordedById(adding ? caller().personId() : existing.getRecordedById())
                .recordedBy(adding ? callerName() : existing.getRecordedBy())
                .recordedOn(adding ? LocalDateTime.now() : existing.getRecordedOn()).build();
        if (adding) {
            report.getPayments().add(payment);
        } else {
            report.getPayments().set(report.getPayments().indexOf(existing), payment);
        }
        final String what = (adding ? "recorded" : "edited") + " a payment of " + money(payment.getAmountCents())
                + " by " + payment.getMethod() + " for " + lineIds.size()
                + ((lineIds.size() == 1) ? " expense" : " expenses");
        if (!persist(trip, report, refusal, what, adding ? "Payment recorded." : "Payment saved.")) {
            return false;
        }
        form.setId(payment.getId());
        form.setLineIds(new ArrayList<>(lineIds));
        form.setProblem(null);
        return true;
    }

    private static String paymentProblem(final PaymentForm form) {
        if (form.getDate() == null) {
            return "Enter the date it was paid.";
        }
        final String amount = signedDollarsProblem("The amount", form.getAmount());
        if (amount != null) {
            return amount;
        }
        if (trimToNull(form.getMethod()) == null) {
            return "Enter how it was paid (Zelle, check 1042, cash...).";
        }
        final String method = tooLong("How it was paid", form.getMethod(), MAX_NAME);
        return (method != null) ? method : tooLong("A note", form.getNote(), MAX_NOTE);
    }

    /** Why these lines cannot be in this payment, or null: each must be still to be paid, or already in it. */
    private static String paymentLinesProblem(final TripExpenseReport report, final ExpensePayment editing,
            final List<String> lineIds) {
        for (final String lineId : lineIds) {
            final ExpenseLine line = report.line(lineId);
            if (line == null) {
                return "An expense you ticked no longer exists: reload the page.";
            }
            if (!line.isOutOfPocket()) {
                return "'" + nameOr(line.getDescription(), "expense") + "' was "
                        + line.getPaidStatus().getLabel().toLowerCase(Locale.ROOT) + ": it needs no payment.";
            }
            final ExpensePayment holder = report.paymentFor(lineId);
            if (holder != null && holder != editing) {
                return "'" + nameOr(line.getDescription(), "expense") + "' is already in a payment: reload the page.";
            }
        }
        return null;
    }

    private static List<String> distinct(final List<String> ids) {
        final List<String> out = new ArrayList<>();
        if (ids != null) {
            for (final String id : ids) {
                if (id != null && !id.isBlank() && !out.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /** Deletes a payment (its expenses are unpaid again); refusals growl. Deleting one already gone succeeds. */
    public boolean deletePayment(final String tripId, final String paymentId) {
        final Trip trip = gate.settleableTrip(tripId, paymentId, PageFeedback::refuse, NOT_SETTLER);
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final ExpensePayment payment = report.payment(paymentId);
        if (payment == null) {
            return true;
        }
        report.getPayments().remove(payment);
        return persist(trip, report, PageFeedback::refuse, "deleted the payment of "
                + money(payment.getAmountCents()) + " by " + payment.getMethod(), "Payment deleted.");
    }

    // ================================================================== PDF and the treasurer

    /** Whether the report can be emailed: a reader of it, on a trip whose organization has a treasurer. */
    public boolean hasTreasurer(final String tripId) {
        final Trip trip = canViewExpenses(tripId) ? gate.knownTrip(tripId) : null;
        return trip != null && treasurerEmail(trip) != null;
    }

    /** The treasurer address for a trip's organization (its own setting, else the site's), or null. */
    String treasurerEmail(final Trip trip) {
        final String value = configSource.get().getString(KnownSettings.FINANCE_TREASURER_EMAIL,
                trip.getOrganization());
        return EmailAddresses.isValid(value) ? value.trim() : null;
    }

    /**
     * The report as a PDF (with its receipts appended when {@code withReceipts}), or null for a caller who may
     * not read it.
     */
    public byte[] reportPdf(final String tripId, final boolean withReceipts) {
        if (!canViewExpenses(tripId)) {
            return null;
        }
        final Trip trip = gate.knownTrip(tripId);
        final TripExpenseReport report = reportOrDefault(trip, Cached.YES);
        final Map<String, String> labels = new HashMap<>();
        final List<ExpenseReportPdf.ReceiptDoc> receipts = receiptDocs(report, labels);
        return ExpenseReportPdf.render(reportPage(tripId), labels, withReceipts ? receipts : List.of());
    }

    /**
     * Every receipt in report order (report level first, then line by line), numbered R1, R2...; fills
     * {@code labels} with each expense's numbers ("R2, R3") for the PDF's Receipts column.
     */
    private List<ExpenseReportPdf.ReceiptDoc> receiptDocs(final TripExpenseReport report,
            final Map<String, String> labels) {
        final List<ExpenseReportPdf.ReceiptDoc> docs = new ArrayList<>();
        final ExpenseReceipts store = receiptSource.get();
        for (final String id : report.getReceiptIds()) {
            docs.add(receiptDoc(store, id, "R" + (docs.size() + 1), "The report"));
        }
        for (final ExpenseLine line : report.getLines()) {
            final List<String> mine = new ArrayList<>();
            for (final String id : line.getReceiptIds()) {
                final String label = "R" + (docs.size() + 1);
                mine.add(label);
                docs.add(receiptDoc(store, id, label, line.getDescription()));
            }
            if (!mine.isEmpty()) {
                labels.put(line.getId(), String.join(", ", mine));
            }
        }
        return docs;
    }

    private static ExpenseReportPdf.ReceiptDoc receiptDoc(final ExpenseReceipts store, final String mediaId,
            final String label, final String forWhat) {
        final MediaItem item = DAO.getInstance().getMedia(mediaId, Cached.YES).orElse(null);
        if (item == null) {
            return new ExpenseReportPdf.ReceiptDoc(label, forWhat, "(deleted)", null, null);
        }
        final ExpenseReceipts.Served served = store.served(item, false).orElse(null);
        return new ExpenseReportPdf.ReceiptDoc(label, forWhat, item.getTitle(),
                (served == null) ? item.getContentType() : served.contentType(),
                (served == null) ? null : served.bytes());
    }

    /**
     * Emails the report to the organization's treasurer as ONE PDF with the receipts appended (owner decision
     * 2026-10-26); when that would be too large for an email, the report alone, saying so in the message. The
     * submitter is the Reply-To. Records who it went to and when; refusals growl.
     */
    public boolean emailTreasurer(final String tripId) {
        final Trip trip = gate.editableTrip(tripId, tripId, PageFeedback::refuse, NOT_ALLOWED);
        if (trip == null) {
            return false;
        }
        final String treasurer = treasurerEmail(trip);
        if (treasurer == null) {
            return refuse("No treasurer email is set for " + nameOr(orgName(trip), "this organization")
                    + ": an organization admin sets it under Organization settings, Configuration.");
        }
        byte[] pdf = reportPdf(tripId, true);
        final boolean receiptsLeftOut = pdf.length > MailCommands.MAX_ATTACHMENT_BYTES;
        if (receiptsLeftOut) {
            pdf = reportPdf(tripId, false);
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final String subject = "Expense report: " + trip.getTitle()
                + ((report.getSubmittedBy() == null) ? "" : " (" + report.getSubmittedBy() + ")");
        final boolean sent = mailSource.get().sendWithAttachment(
                new MailAddressCommands(configSource.get()).siteFrom(), treasurer, callerEmail(), subject,
                treasurerBody(trip, report, receiptsLeftOut), ExpenseReportPdf.fileName(trip.getTitle()), pdf,
                ExpenseReceipts.PDF, caller().auditActor());
        if (!sent) {
            return refuse("The report could not be emailed to " + treasurer + ". Try again.");
        }
        report.setEmailedTo(treasurer);
        report.setEmailedOn(LocalDateTime.now());
        persist(trip, report, PageFeedback::refuse, "emailed the report to " + treasurer, null);
        info("Expense report emailed to " + treasurer + (receiptsLeftOut ? " (without the receipts, which made it "
                + "too large to email)." : "."));
        return true;
    }

    /** The message to the treasurer: who, what trip, the bottom line, and what is attached. */
    private String treasurerBody(final Trip trip, final TripExpenseReport report, final boolean receiptsLeftOut) {
        final ExpensePage page = reportPage(trip.getId());
        final StringBuilder html = new StringBuilder();
        html.append("<p>").append(esc(nameOr(report.getSubmittedBy(), "Someone"))).append(" submitted the expense ")
                .append("report for <b>").append(esc(trip.getTitle())).append("</b>")
                .append((page.getTripDates() == null) ? "" : " (" + esc(page.getTripDates()) + ")").append(".</p>");
        html.append("<p>Total spent: ").append(esc(page.getTotal()));
        if (page.isHasSettled()) {
            html.append("<br/>Paid by organization: ").append(esc(page.getPaidByOrg()))
                    .append("<br/>Already reimbursed: ").append(esc(page.getReimbursed()));
        }
        html.append("<br/>Cash advance: ").append(esc(page.getCashAdvance())).append("<br/><b>")
                .append(esc(page.getDueLabel())).append(": ").append(esc(page.getDue())).append("</b>");
        if (!page.getPaymentRows().isEmpty()) {
            html.append("<br/>Payments recorded: ").append(esc(page.getPaymentsTotal())).append("<br/><b>")
                    .append(esc(page.getStillDueLabel())).append(page.isSettled() ? "" : ": " + esc(page.getStillDue()))
                    .append("</b>");
        }
        html.append("</p>");
        if (report.getPaymentInstructions() != null) {
            html.append("<p>Payment instructions: ").append(esc(report.getPaymentInstructions())).append("</p>");
        }
        html.append("<p>").append(receiptsLeftOut
                ? "The report is attached. Its receipts made the file too large to email; they are on the report "
                        + "online."
                : "The report is attached as a PDF, with its receipts after it.").append("</p>");
        return html.toString();
    }

    private static String esc(final String text) {
        return (text == null) ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br/>");
    }

    /** The caller's own email, the treasurer's Reply-To; null when they have none. */
    private String callerEmail() {
        final Person.Id id = caller().personId();
        final Person person = (id == null) ? null : DAO.getInstance().getPerson(id, Cached.YES).orElse(null);
        return (person == null || !EmailAddresses.isValid(person.getEmail())) ? null : person.getEmail();
    }

    void mailForTest(final MailCommands mail) {
        this.mailSource = () -> mail;
    }

    /**
     * Links a stored receipt to a line, or to the report when {@code lineId} is blank, with the report's own
     * versioned put. Called by {@link ExpenseReceipts} after it stored the file, which rolls the file back when
     * this refuses.
     */
    boolean attachReceipt(final String tripId, final String lineId, final String mediaId,
            final Consumer<String> refusal) {
        final Trip trip = gate.editableTrip(tripId, mediaId, refusal, NOT_ALLOWED);
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final List<String> ids;
        if (lineId == null || lineId.isBlank()) {
            ids = report.getReceiptIds();
            if (ids.size() >= MAX_REPORT_RECEIPTS) {
                return refused(refusal, "A report holds at most " + MAX_REPORT_RECEIPTS + " report-level receipts.");
            }
        } else {
            final ExpenseLine line = report.line(lineId);
            if (line == null) {
                return refused(refusal, "This expense no longer exists: reload the page.");
            }
            ids = line.getReceiptIds();
            if (ids.size() >= MAX_RECEIPTS_PER_LINE) {
                return refused(refusal, "An expense holds at most " + MAX_RECEIPTS_PER_LINE + " receipts.");
            }
        }
        ids.add(mediaId);
        return persist(trip, report, refusal, "attached a receipt", null);
    }

    /** Unlinks a receipt from wherever the report holds it; false (after refusing) when it holds it nowhere. */
    boolean detachReceipt(final String tripId, final String mediaId, final Consumer<String> refusal) {
        final Trip trip = gate.editableTrip(tripId, mediaId, refusal, NOT_ALLOWED);
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        if (!report.getReceiptIds().remove(mediaId)) {
            final ExpenseLine line = report.lineWithReceipt(mediaId);
            if (line == null) {
                return refused(refusal, "That receipt is not on this report.");
            }
            line.getReceiptIds().remove(mediaId);
        }
        return persist(trip, report, refusal, "removed a receipt", null);
    }

    // ================================================================== write plumbing

    /** Stamps a new report, saves it with one versioned put, audits, and growls {@code done} when non-null. */
    private boolean persist(final Trip trip, final TripExpenseReport report, final Consumer<String> refusal,
            final String what, final String done) {
        if (report.getVersion() == 0L) {
            report.setTripId(trip.getId());
            report.setOrgId(trip.getOrgId());
            report.setCreatedBy(caller().personId());
            report.setCreated(LocalDateTime.now());
        }
        try {
            if (!DAO.getInstance().saveTripExpenseReport(report)) {
                return refused(refusal, "The expense report could not be saved.");
            }
        } catch (final ConditionalCheckFailedException ex) {
            return refused(refusal, "Saved by someone else: reload the page and try again.");
        } catch (final IllegalArgumentException ex) {
            return refused(refusal, "The expense report is too large to save: shorten some notes.");
        } catch (final IOException ex) {
            log.error("Unable to save the expense report of trip {}", trip.getId(), ex);
            return refused(refusal, "The expense report was not saved: the error has been logged. Try again.");
        }
        auditSource.get().expenseReport(trip.getId(), trip.getOrgId(),
                "Expense report of '" + trip.getTitle() + "': " + what, caller().auditActor());
        if (done != null) {
            info(done);
        }
        return true;
    }

    // ================================================================== names and text

    /** The caller's name as a report would print it ("Ken Paulsen"); null when nobody is signed in. */
    private String callerName() {
        final Person.Id id = caller().personId();
        if (id == null) {
            return null;
        }
        final Person person = DAO.getInstance().getPerson(id, Cached.YES).orElse(null);
        if (person == null) {
            return null;
        }
        return trimToNull((nameOr(person.getPreferredName(), "") + " " + nameOr(person.getLast(), "")).trim());
    }

    private static String orgName(final Trip trip) {
        final Organization org = trip.getOrganization();
        return (org == null) ? null : org.getName();
    }

    /** "Sep 20, 2026 to Oct 5, 2026"; just the start when there is no end; null with no dates. */
    static String tripDates(final Trip trip) {
        final LocalDateTime start = trip.getStartDate();
        final LocalDateTime end = trip.getEndDate();
        if (start == null) {
            return null;
        }
        return (end == null) ? DAY.format(start) : DAY.format(start) + " to " + DAY.format(end);
    }

    /** "23.00 EUR": hundredths of a foreign currency with its code. */
    static String foreign(final long amountMinor, final String code) {
        return String.format(Locale.US, "%,.2f %s",
                BigDecimal.valueOf(amountMinor).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY), code);
    }

    /** "1.2 MB", "340 KB". */
    static String size(final long bytes) {
        if (bytes >= 1_000_000L) {
            return BigDecimal.valueOf(bytes).movePointLeft(6).setScale(1, RoundingMode.HALF_UP).toPlainString()
                    + " MB";
        }
        return Math.max(1L, (bytes + 500L) / 1_000L) + " KB";
    }

    private static String nameOr(final String text, final String fallback) {
        return (text == null || text.isBlank()) ? fallback : text;
    }

    private static String trimToNull(final String text) {
        if (text == null) {
            return null;
        }
        final String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Choice choice(final String id, final String label) {
        final Choice choice = new Choice();
        choice.setId(id);
        choice.setLabel(label);
        return choice;
    }

    Caller caller() {
        return callerSource.get();
    }
}
