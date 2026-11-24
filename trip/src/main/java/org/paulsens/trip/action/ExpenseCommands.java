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
import org.paulsens.trip.action.ExpenseViews.AdvanceForm;
import org.paulsens.trip.action.ExpenseViews.AdvanceRow;
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
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.i18n.Formats;
import org.paulsens.trip.i18n.ReaderLanguage;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.AdvanceEntry;
import org.paulsens.trip.model.AdvanceType;
import org.paulsens.trip.model.BindingType;
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
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripBudget;
import org.paulsens.trip.model.TripExpenseReport;
import org.paulsens.trip.pay.BudgetMath;
import org.paulsens.trip.pay.ExpenseMath;
import org.paulsens.trip.pay.MoneyMath;
import org.paulsens.trip.util.EmailAddresses;
import org.paulsens.trip.util.Html;
import org.paulsens.trip.util.PersonNames;
import org.paulsens.trip.util.Util;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import static org.paulsens.trip.action.MoneyForms.cents;
import static org.paulsens.trip.action.MoneyForms.choice;
import static org.paulsens.trip.action.MoneyForms.choicesOf;
import static org.paulsens.trip.action.MoneyForms.dollars;
import static org.paulsens.trip.action.MoneyForms.dollarsProblem;
import static org.paulsens.trip.action.MoneyForms.money;
import static org.paulsens.trip.action.MoneyForms.parse;
import static org.paulsens.trip.action.MoneyForms.putAt;
import static org.paulsens.trip.action.MoneyForms.signedDollarsProblem;
import static org.paulsens.trip.action.MoneyForms.tooLong;
import static org.paulsens.trip.action.PageFeedback.info;
import static org.paulsens.trip.action.PageFeedback.refuse;
import static org.paulsens.trip.action.PageFeedback.refuseInto;
import static org.paulsens.trip.action.PageFeedback.warn;

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
 *
 * <p>The cash advanced is a WORKSHEET of typed {@link AdvanceEntry}s (cash from the organization, a credit
 * applied, cash handed over by participants, money returned, other), each in its own currency; the report's
 * "cash advance" is their signed sum. A participant entry for one person may record a Payment
 * {@link Transaction} for them, once and only for a finance admin; the ledger is never touched again from here,
 * a later edit or delete only warns.
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
    // The English of feedback.expenseCommands.noExpenses, deletedLine, various, noLines and paidLine: the page
    // reads the bundle, and these stay only as the names the tests compare its English against.
    static final String NO_EXPENSES = "No expenses yet";
    static final String DELETED_LINE = "(deleted budget line)";
    static final String VARIOUS = "Various";
    static final int MAX_ADVANCES = 100;
    static final String NO_LINES = "No specific expenses";
    static final String PARTICIPANT_TX_NOTE = "Cash given during the trip";
    static final String MANUAL_TX_NOTE = "Record this payment in each person's transactions yourself: the report "
            + "does not do it for more than one person.";
    static final String PAID_LINE = "This expense is in a recorded payment: remove it from the payment first.";
    /** A month and day with no year, the paid chip's "Oct 7", in the reader's own order and month names. */
    private static final String MONTH_DAY = "MMMd";

    private final Supplier<Caller> callerSource;
    private final Supplier<AuditCommands> auditSource;
    /** The ledger a participant entry records into; plain instances, like every test of the ledger uses. */
    private final Supplier<TransactionsCommands> txSource = TransactionsCommands::new;
    private final Supplier<BindingCommands> bindSource = BindingCommands::new;
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

    /** May read the report: an editor, the trip's finance viewers, or a finance viewer of its organization. */
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

    /** Everything the page renders, in the request's language; an empty page for a caller who may not view it. */
    public ExpensePage reportPage(final String tripId) {
        return reportPageIn(tripId, RequestContext.currentLocale());
    }

    /**
     * {@link #reportPage} with every label, date and sentence in {@code locale}: the PDF and the treasurer's
     * mail are built from this page, and a report mailed to someone else must read in one language throughout.
     * A distinct name rather than an overload: EL chooses among overloads by argument type, not count.
     */
    public ExpensePage reportPageIn(final String tripId, final Locale locale) {
        final ExpensePage page = new ExpensePage();
        if (!canViewExpenses(tripId)) {
            return page;
        }
        final Trip trip = gate.knownTrip(tripId);
        final TripExpenseReport report = reportOrDefault(trip, Cached.YES);
        page.setExists(report.getVersion() > 0L);
        page.setVersion(report.getVersion());
        page.setStatus(report.getStatus().getLabel(locale));
        page.setStatusKey(report.getStatus().name().toLowerCase(Locale.ROOT));
        page.setTripTitle(trip.getTitle());
        page.setTripDates(Formats.dateSpan(trip.getStartDate(), trip.getEndDate(), locale));
        page.setOrgName(orgName(trip));
        page.setSubmittedBy(report.getSubmittedBy());
        page.setSubmitted(report.getSubmittedOn() != null);
        if (report.getSubmittedOn() != null) {
            page.setSubmittedLine(Text.forLocale(locale, "feedback.expenseCommands.submittedLine",
                    nameOr(report.getSubmittedBy(), noName(locale)), Formats.date(report.getSubmittedOn(), locale)));
        }
        fillAdvances(page, report, locale);
        page.setPaymentInstructions(report.getPaymentInstructions());
        for (final CurrencyRate rate : report.getCurrencies()) {
            page.getCurrencyRows().add(currencyRow(report, rate, locale));
        }
        final TripBudget budget = budgetOf(tripId);
        fillBudget(page, report, budget, BudgetCommands.offersById(tripId), locale);
        fillLines(page, report, budget, locale);
        page.setReportReceipts(receiptRows(report.getReceiptIds(), locale));
        fillTotals(page, report, locale);
        fillPayments(page, report, locale);
        page.setCanRecordPayments(canRecordPayments(tripId));
        page.setLinesWithoutReceipt(ExpenseMath.linesWithoutReceipt(report));
        page.setTreasurerEmail(treasurerEmail(trip));
        if (report.getEmailedOn() != null) {
            page.setEmailedLine(Text.forLocale(locale, "feedback.expenseCommands.emailedLine",
                    report.getEmailedTo(), Formats.date(report.getEmailedOn(), locale)));
        }
        warnMissingRates(page, report, locale);
        return page;
    }

    /** The worksheet, numbered in the order entered, and the cash advanced it adds up to. */
    private void fillAdvances(final ExpensePage page, final TripExpenseReport report, final Locale locale) {
        final List<AdvanceEntry> entries = report.getAdvances();
        for (int i = 0; i < entries.size(); i++) {
            page.getAdvanceRows().add(advanceRow(report, entries.get(i), i + 1, locale));
        }
        final long advanced = ExpenseMath.advanced(report);
        page.setCashAdvance(money(advanced));
        page.setAdvanceNegative(advanced < 0);
        page.setAdvanceCount(entries.size());
    }

    private AdvanceRow advanceRow(final TripExpenseReport report, final AdvanceEntry entry, final int number,
            final Locale locale) {
        final AdvanceRow row = new AdvanceRow();
        row.setId(entry.getId());
        row.setNumber(number);
        row.setTypeLabel(entry.getType().getLabel(locale));
        row.setDateText((entry.getDate() == null) ? "" : Formats.date(entry.getDate(), locale));
        row.setParty((entry.getType() == AdvanceType.PARTICIPANT) ? participantNames(entry, locale)
                : entry.getParty());
        row.setAmount(entry.isUsd() ? money(entry.getAmountMinor())
                : foreign(entry.getAmountMinor(), entry.getCurrency()));
        final long usd = ExpenseMath.advanceUsd(report, entry);
        row.setUsd(money(usd));
        row.setNegative(usd < 0);
        row.setRateMissing(ExpenseMath.rateMissing(report, entry));
        row.setNote(entry.getNote());
        row.setTransactionLine(transactionLine(entry, locale));
        return row;
    }

    /** "Ken Paulsen; Trinity Paulsen": the participants who handed over the cash, in the order picked. */
    private String participantNames(final AdvanceEntry entry, final Locale locale) {
        final List<String> names = new ArrayList<>();
        for (final String id : entry.getPersonIds()) {
            names.add(personName(id, locale));
        }
        return names.isEmpty() ? null : String.join("; ", names);
    }

    /** "A payment of $100.00 was recorded for Ken Paulsen on Oct 30, 2026."; null when the entry recorded none. */
    private String transactionLine(final AdvanceEntry entry, final Locale locale) {
        final AdvanceEntry.RecordedTransaction tx = entry.getTransaction();
        if (tx == null) {
            return null;
        }
        final String amount = money(tx.getAmountCents());
        final String name = personName(tx.getPersonId(), locale);
        return (tx.getRecordedOn() == null)
                ? Text.forLocale(locale, "feedback.expenseCommands.paymentRecordedFor", amount, name)
                : Text.forLocale(locale, "feedback.expenseCommands.paymentRecordedForOn", amount, name,
                        Formats.date(tx.getRecordedOn(), locale));
    }

    /** {@link #personName(String, Locale)} in the request's language. */
    private static String personName(final String id) {
        return personName(id, RequestContext.currentLocale());
    }

    /** "Ken Paulsen", or "(unknown person)" for an id no longer on file. */
    private static String personName(final String id, final Locale locale) {
        final Person person = (id == null) ? null
                : DAO.getInstance().getPerson(Person.Id.from(id), Cached.YES).orElse(null);
        if (person == null) {
            return Text.forLocale(locale, "feedback.expenseCommands.unknownPerson");
        }
        return PersonNames.displayOr(person, Text.forLocale(locale, "feedback.expenseCommands.unnamedPerson"));
    }

    /**
     * Total spent, the part settled outside the report, and what is due: the cash advanced less what the
     * submitter is not yet reimbursed for.
     */
    private static void fillTotals(final ExpensePage page, final TripExpenseReport report, final Locale locale) {
        final long byOrg = ExpenseMath.settledBy(report, ExpensePaidStatus.ORG);
        final long reimbursed = ExpenseMath.settledBy(report, ExpensePaidStatus.REIMBURSED);
        final long due = ExpenseMath.due(report);
        page.setTotal(money(ExpenseMath.total(report)));
        page.setHasSettled(report.getLines().stream().anyMatch(line -> !line.isOutOfPocket()));
        page.setPaidByOrg(money(byOrg));
        page.setReimbursed(money(reimbursed));
        page.setOutOfPocket(money(ExpenseMath.outOfPocket(report)));
        page.setDueToSubmitter(due < 0);
        page.setDueLabel(dueLabel(dueTo(page, report, due), due, locale));
        page.setDue(money(Math.abs(due)));
    }

    /** The name a signed balance is owed to: the submitter when negative, else the organization; null if unnamed. */
    private static String dueTo(final ExpensePage page, final TripExpenseReport report, final long balance) {
        return nameOr((balance < 0) ? report.getSubmittedBy() : page.getOrgName(), null);
    }

    /** "Due to Ken Paulsen"; an unnamed party is "the submitter" or "the organization", each its own sentence. */
    private static String dueLabel(final String name, final long balance, final Locale locale) {
        if (name != null) {
            return Text.forLocale(locale, "feedback.expenseCommands.dueTo", name);
        }
        return (balance < 0) ? Text.forLocale(locale, "feedback.expenseCommands.dueToSubmitter")
                : Text.forLocale(locale, "feedback.expenseCommands.dueToOrganization");
    }

    private static String stillDueLabel(final String name, final long balance, final Locale locale) {
        if (balance == 0L) {
            return Text.forLocale(locale, "feedback.expenseCommands.settled");
        }
        if (name != null) {
            return Text.forLocale(locale, "feedback.expenseCommands.stillDueTo", name);
        }
        return (balance < 0) ? Text.forLocale(locale, "feedback.expenseCommands.stillDueToSubmitter")
                : Text.forLocale(locale, "feedback.expenseCommands.stillDueToOrganization");
    }

    /** The payments table and what is still due after them. */
    private static void fillPayments(final ExpensePage page, final TripExpenseReport report, final Locale locale) {
        for (final ExpensePayment payment : report.getPayments()) {
            page.getPaymentRows().add(paymentRow(report, payment, locale));
        }
        final long stillDue = ExpenseMath.stillDue(report);
        page.setPaymentsTotal(money(ExpenseMath.paymentsTotal(report)));
        page.setSettled(stillDue == 0L);
        page.setStillDueToSubmitter(stillDue < 0);
        page.setStillDueLabel(stillDueLabel(dueTo(page, report, stillDue), stillDue, locale));
        page.setStillDue(money(Math.abs(stillDue)));
        page.setUnpaidCount(report.unpaidLines().size());
    }

    private static PaymentRow paymentRow(final TripExpenseReport report, final ExpensePayment payment,
            final Locale locale) {
        final PaymentRow row = new PaymentRow();
        row.setId(payment.getId());
        row.setDateText((payment.getDate() == null) ? "" : Formats.date(payment.getDate(), locale));
        row.setAmount(money(payment.getAmountCents()));
        row.setNegative(payment.getAmountCents() < 0);
        row.setMethod(payment.getMethod());
        row.setNote(payment.getNote());
        final List<String> names = new ArrayList<>();
        for (final String lineId : payment.getLineIds()) {
            final ExpenseLine line = report.line(lineId);
            if (line != null) {
                names.add(nameOr(line.getDescription(), unnamedExpense(locale)));
            }
        }
        row.setLines(names.isEmpty() ? Text.forLocale(locale, "feedback.expenseCommands.noLines")
                : String.join(", ", names));
        row.setLineCount(names.size());
        if (payment.getRecordedOn() != null) {
            row.setRecordedLine(Text.forLocale(locale, "feedback.expenseCommands.recordedLine",
                    nameOr(payment.getRecordedBy(), noName(locale)), Formats.date(payment.getRecordedOn(), locale)));
        }
        return row;
    }

    private static CurrencyRow currencyRow(final TripExpenseReport report, final CurrencyRate rate,
            final Locale locale) {
        final CurrencyRow row = new CurrencyRow();
        row.setCode(rate.getCode());
        row.setRateText("1 USD = " + ExpenseMath.rate(rate.getPerUsdMicros()).toPlainString() + " " + rate.getCode());
        final BigDecimal inverse = ExpenseMath.usdPerUnit(rate.getPerUsdMicros());
        row.setInverseText((inverse == null) ? Text.forLocale(locale, "feedback.expenseCommands.noRate")
                : "1 " + rate.getCode() + " = $" + inverse.toPlainString());
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
            final Map<String, ReservationOffer> offers, final Locale locale) {
        final Set<String> ids = new HashSet<>();
        long planned = 0L;
        long spent = 0L;
        if (budget != null) {
            page.setHasBudget(!budget.getLines().isEmpty() || !budget.getLodging().isEmpty());
            for (final BudgetLine line : budget.getLines()) {
                final long amount = ExpenseMath.budgeted(budget, line);
                page.getBudgetRows().add(budgetRow(report, line.getId(), line.isShared()
                        ? Text.forLocale(locale, "feedback.expenseCommands.sectionShared")
                        : Text.forLocale(locale, "common.perPerson"),
                        line.getName(), BudgetMath.categoryOf(line, locale), amount));
                ids.add(line.getId());
                planned += amount;
                spent += ExpenseMath.spentOn(report, line.getId());
            }
            for (final BudgetLodgingLine line : budget.getLodging()) {
                final ReservationOffer offer = offers.get(line.getOfferId());
                final long amount = ExpenseMath.budgeted(line, offer);
                page.getBudgetRows().add(budgetRow(report, line.getId(),
                        Text.forLocale(locale, "common.lodging"),
                        BudgetCommands.lodgingLabel(line, offer, locale), BudgetMath.LODGING_CATEGORY, amount));
                ids.add(line.getId());
                planned += amount;
                spent += ExpenseMath.spentOn(report, line.getId());
            }
        }
        final BudgetRow outside = new BudgetRow();
        outside.setNotInBudget(true);
        outside.setLabel(Text.forLocale(locale, "feedback.expenseCommands.notInBudget"));
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

    private void fillLines(final ExpensePage page, final TripExpenseReport report, final TripBudget budget,
            final Locale locale) {
        final List<ExpenseLine> lines = report.getLines();
        for (int i = 0; i < lines.size(); i++) {
            final ExpenseLine line = lines.get(i);
            final ExpenseRow row = new ExpenseRow();
            row.setId(line.getId());
            row.setDateText((line.getDate() == null) ? various(locale) : Formats.date(line.getDate(), locale));
            row.setCategory(line.getCategory().getLabel(locale));
            row.setDescription(line.getDescription());
            row.setForeign(!line.isUsd());
            row.setAmountText(line.isUsd() ? money(line.getAmountMinor())
                    : foreign(line.getAmountMinor(), line.getCurrency()));
            row.setUsd(money(ExpenseMath.lineUsd(report, line)));
            row.setRateMissing(ExpenseMath.rateMissing(report, line));
            row.setBudgetLabel(budgetLabel(budget, line.getBudgetLineId(), locale));
            row.setReceiptCount(line.getReceiptIds().size());
            row.setNote(line.getNote());
            paidChip(row, report, line, locale);
            row.setFirst(i == 0);
            row.setLast(i == lines.size() - 1);
            page.getLineRows().add(row);
        }
    }

    /** The line's chip: "Paid Oct 7" once a payment settles it, else its own paid status (none when unreimbursed). */
    private static void paidChip(final ExpenseRow row, final TripExpenseReport report, final ExpenseLine line,
            final Locale locale) {
        final ExpensePayment payment = report.paymentFor(line.getId());
        if (payment != null) {
            row.setPaidKey("paid");
            row.setPaymentId(payment.getId());
            row.setPaidChip((payment.getDate() == null) ? Text.forLocale(locale, "common.paid")
                    : Text.forLocale(locale, "feedback.expenseCommands.paidChipOn",
                            DateTimeFormatter.ofLocalizedPattern(MONTH_DAY).withLocale(locale)
                                    .format(payment.getDate())));
            return;
        }
        row.setPaidKey(line.getPaidStatus().name().toLowerCase(Locale.ROOT));
        row.setPaidChip(line.getPaidStatus().getChip(locale));
    }

    /** The matched budget line's name; null when unmatched, {@link #DELETED_LINE} when it is gone. */
    private static String budgetLabel(final TripBudget budget, final String budgetLineId, final Locale locale) {
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
                    BudgetCommands.offersById(budget.getTripId()).get(lodging.getOfferId()), locale);
        }
        return Text.forLocale(locale, "feedback.expenseCommands.deletedLine");
    }

    private static void warnMissingRates(final ExpensePage page, final TripExpenseReport report,
            final Locale locale) {
        final Set<String> warned = new HashSet<>();
        for (final ExpenseLine line : report.getLines()) {
            if (ExpenseMath.rateMissing(report, line) && warned.add(line.getCurrency())) {
                page.getWarnings().add(Text.forLocale(locale, "feedback.expenseCommands.expensesWithoutRate",
                        line.getCurrency()));
            }
        }
        for (final AdvanceEntry entry : report.getAdvances()) {
            if (ExpenseMath.rateMissing(report, entry) && warned.add(entry.getCurrency())) {
                page.getWarnings().add(Text.forLocale(locale, "feedback.expenseCommands.advancesWithoutRate",
                        entry.getCurrency()));
            }
        }
    }

    /** The receipts with these ids, in order; ids whose row is gone are skipped. */
    List<ReceiptRow> receiptRows(final List<String> mediaIds, final Locale locale) {
        final List<ReceiptRow> rows = new ArrayList<>();
        for (final String id : mediaIds) {
            final MediaItem item = DAO.getInstance().getMedia(id, Cached.YES).orElse(null);
            if (item != null) {
                rows.add(receiptRow(item, locale));
            }
        }
        return rows;
    }

    static ReceiptRow receiptRow(final MediaItem item, final Locale locale) {
        final ReceiptRow row = new ReceiptRow();
        row.setMediaId(item.getId());
        row.setTitle((item.getTitle() == null) ? Text.forLocale(locale, "common.receipt") : item.getTitle());
        // The buster: a rotated receipt keeps its URL path, and the page's own image cache would show the old turn.
        row.setUrl(ExpenseReceipts.path(item.getId()) + item.getCacheBuster());
        row.setPdf(ExpenseReceipts.PDF.equals(item.getContentType()));
        row.setThumbUrl(row.isPdf() ? null
                : ExpenseReceipts.path(item.getId()) + ExpenseReceipts.THUMB + item.getCacheBuster());
        row.setSize(size(item.getSize()));
        return row;
    }

    /** The receipt dialog's list: a line's receipts, or the report-level ones when {@code lineId} is blank. */
    public List<ReceiptRow> receiptRowsFor(final String tripId, final String lineId) {
        final Trip trip = gate.viewableTrip(tripId);
        if (trip == null) {
            return new ArrayList<>();
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.YES);
        if (lineId == null || lineId.isBlank()) {
            return receiptRows(report.getReceiptIds(), RequestContext.currentLocale());
        }
        final ExpenseLine line = report.line(lineId);
        return (line == null) ? new ArrayList<>() : receiptRows(line.getReceiptIds(), RequestContext.currentLocale());
    }

    /** The receipt dialog's heading: the line's description, or "Report receipts" at report level. */
    public String receiptHeading(final String tripId, final String lineId) {
        if (lineId == null || lineId.isBlank()) {
            return Text.get("feedback.expenseCommands.reportReceipts");
        }
        final Trip trip = gate.viewableTrip(tripId);
        final ExpenseLine line = (trip == null) ? null
                : reportOrDefault(trip, Cached.YES).line(lineId);
        return (line == null) ? Text.get("common.receipts")
                : Text.get("feedback.expenseCommands.receiptsOf", nameOr(line.getDescription(), unnamedExpense()));
    }

    /** The header fields as a form. */
    public HeaderForm headerFormFor(final String tripId) {
        final HeaderForm form = new HeaderForm();
        final Trip trip = gate.viewableTrip(tripId);
        if (trip == null) {
            return form;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.YES);
        form.setSubmittedBy(report.getSubmittedBy());
        form.setPaymentInstructions(report.getPaymentInstructions());
        return form;
    }

    /** A fresh expense form: dollars, Other. */
    public ExpenseForm newLineForm() {
        return new ExpenseForm();
    }

    /** The expense dialog's form for an existing line; a fresh form when the line is gone. */
    public ExpenseForm lineFormFor(final String tripId, final String lineId) {
        final Trip trip = gate.viewableTrip(tripId);
        final ExpenseLine line = (trip == null) ? null
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
        final Trip trip = gate.viewableTrip(tripId);
        if (trip == null) {
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
        final Trip trip = gate.viewableTrip(tripId);
        final CurrencyRate rate = (trip == null) ? null
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
        final Trip trip = gate.viewableTrip(tripId);
        if (trip == null) {
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
        final Trip trip = gate.viewableTrip(tripId);
        if (trip != null) {
            for (final CurrencyRate rate : reportOrDefault(trip, Cached.YES).getCurrencies()) {
                choices.add(choice(rate.getCode(), rate.getCode()));
            }
        }
        choices.add(choice(ExpenseViews.NEW_CURRENCY, Text.get("feedback.expenseCommands.addCurrency")));
        return choices;
    }

    /**
     * The expense dialog's Budget line menu: every line of the trip's budget, shared then per person then
     * lodging, labeled with what it plans for the whole trip ("Shared: Guide ($1,440.00)").
     */
    public List<Choice> budgetChoices(final String tripId) {
        final List<Choice> choices = new ArrayList<>();
        final TripBudget budget = canViewExpenses(tripId) ? budgetOf(tripId) : null;
        if (budget == null) {
            return choices;
        }
        for (final BudgetLine line : budget.getLines()) {
            final String planned = money(ExpenseMath.budgeted(budget, line));
            choices.add(choice(line.getId(), line.isShared()
                    ? Text.get("feedback.expenseCommands.budgetChoiceShared", line.getName(), planned)
                    : Text.get("feedback.expenseCommands.budgetChoicePerPerson", line.getName(), planned)));
        }
        final Map<String, ReservationOffer> offers = BudgetCommands.offersById(tripId);
        for (final BudgetLodgingLine line : budget.getLodging()) {
            final ReservationOffer offer = offers.get(line.getOfferId());
            choices.add(choice(line.getId(), Text.get("feedback.expenseCommands.budgetChoiceLodging",
                    BudgetCommands.lodgingLabel(line, offer), money(ExpenseMath.budgeted(line, offer)))));
        }
        return choices;
    }

    /** The Category menu: every category, in menu order, value = the enum name. */
    public List<Choice> categories() {
        return choicesOf(ExpenseCategory.values(), ExpenseCategory::getLabel);
    }

    /** The Paid status menu: Not yet reimbursed, Paid by organization, Already reimbursed; value = the enum name. */
    public List<Choice> paidStatuses() {
        return choicesOf(ExpensePaidStatus.values(), ExpensePaidStatus::getLabel);
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
            return Text.get("feedback.expenseCommands.noExpenses");
        }
        return Text.get("feedback.expenseCommands.spent", money(ExpenseMath.total(report)));
    }

    // ================================================================== writes

    /** Saves the report's own fields; refusals land on {@code form.problem} and growl. */
    public boolean saveHeader(final String tripId, final HeaderForm form) {
        final Consumer<String> refusal = (form == null) ? PageFeedback::refuse : headerRefusal(form);
        final Trip trip = gate.editableTrip(tripId, form, refusal, notAllowed());
        if (trip == null) {
            return false;
        }
        final String problem = headerProblem(form);
        if (problem != null) {
            return refuseInto(refusal, problem);
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        report.setSubmittedBy(Util.trimToNull(form.getSubmittedBy()));
        report.setPaymentInstructions(Util.trimToNull(form.getPaymentInstructions()));
        if (!persist(trip, report, refusal, "saved the details",
                Text.get("feedback.expenseCommands.reportSaved"))) {
            return false;
        }
        form.setProblem(null);
        return true;
    }

    private static Consumer<String> headerRefusal(final HeaderForm form) {
        return message -> refuseHeader(form, message);
    }

    /** The header has no dialog of its own: a refusal both growls and stays on the form for the REST answer. */
    private static void refuseHeader(final HeaderForm form, final String message) {
        form.setProblem(message);
        refuse(message);
    }

    private static String headerProblem(final HeaderForm form) {
        final String name = tooLong("feedback.moneyForms.nameTooLong", form.getSubmittedBy(), MAX_NAME);
        if (name != null) {
            return name;
        }
        return tooLong("feedback.moneyForms.paymentInstructionsTooLong", form.getPaymentInstructions(), MAX_TEXT);
    }

    /** Stamps the report submitted, by the caller, now; refusals growl. Submitting again re-stamps it. */
    public boolean submit(final String tripId) {
        final Trip trip = gate.editableTrip(tripId, tripId, PageFeedback::refuse, notAllowed());
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        if (report.getLines().isEmpty()) {
            return refuse(Text.get("feedback.expenseCommands.nothingToSubmit"));
        }
        if (report.getSubmittedBy() == null) {
            report.setSubmittedBy(callerName());
        }
        report.setSubmittedById(caller().personId());
        report.setSubmittedOn(LocalDateTime.now());
        return persist(trip, report, PageFeedback::refuse, "submitted",
                Text.get("feedback.expenseCommands.reportSubmitted"));
    }

    /** Takes the submit stamp back off (the report is a draft again, unless it has been paid); refusals growl. */
    public boolean unsubmit(final String tripId) {
        final Trip trip = gate.editableTrip(tripId, tripId, PageFeedback::refuse, notAllowed());
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        if (report.getSubmittedOn() == null) {
            return true;
        }
        report.setSubmittedOn(null);
        report.setSubmittedById(null);
        return persist(trip, report, PageFeedback::refuse, "withdrew the submission",
                Text.get("feedback.expenseCommands.submissionWithdrawn"));
    }

    /** Adds a currency or edits a rate; refusals land on {@code form.problem}. */
    public boolean saveCurrency(final String tripId, final CurrencyForm form) {
        final Consumer<String> refusal = (form == null) ? PageFeedback::refuse : form::setProblem;
        final Trip trip = gate.editableTrip(tripId, form, refusal, notAllowed());
        if (trip == null) {
            return false;
        }
        final String code = CurrencyRate.normalizeCode(form.getCode());
        final String problem = currencyProblem(code, form.getRate(), form.getNote());
        if (problem != null) {
            return refuseInto(refusal, problem);
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final CurrencyRate existing = report.currency(code);
        if (form.isExisting() && existing == null) {
            return refuseInto(refusal, Text.get("feedback.expenseCommands.currencyGone"));
        }
        if (!form.isExisting() && existing != null) {
            return refuseInto(refusal, Text.get("feedback.expenseCommands.currencyAlreadyThere", code));
        }
        if (existing == null && report.getCurrencies().size() >= MAX_CURRENCIES) {
            return refuseInto(refusal, Text.get("feedback.expenseCommands.tooManyCurrencies", MAX_CURRENCIES));
        }
        final CurrencyRate rate = CurrencyRate.builder().code(code).perUsdMicros(micros(form.getRate()))
                .note(form.getNote()).build();
        putAt(report.getCurrencies(), existing, rate);
        if (!persist(trip, report, refusal, ((existing == null) ? "added " : "set the rate of ") + code + " at "
                + ExpenseMath.rate(rate.getPerUsdMicros()).toPlainString(),
                Text.get("feedback.expenseCommands.currencySaved"))) {
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
            return Text.get("feedback.expenseCommands.currencyCodeShape");
        }
        if (ExpenseLine.USD.equals(code)) {
            return Text.get("feedback.expenseCommands.dollarsNeedNoRate");
        }
        final long micros = micros(rate);
        if (rate == null || rate.isNaN() || rate.isInfinite() || micros <= 0L || micros > MAX_RATE_MICROS) {
            return Text.get("feedback.expenseCommands.enterRate", code);
        }
        return tooLong("feedback.moneyForms.noteTooLong", note, MAX_NOTE);
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
        final Trip trip = gate.editableTrip(tripId, code, PageFeedback::refuse, notAllowed());
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
            return refuse(Text.get("feedback.expenseCommands.currencyUsedByExpenses", using, code));
        }
        final int advances = report.advancesUsing(code).size();
        if (advances > 0) {
            return refuse(Text.get("feedback.expenseCommands.currencyUsedByAdvances", advances, code));
        }
        report.getCurrencies().remove(rate);
        return persist(trip, report, PageFeedback::refuse, "removed " + code,
                Text.get("feedback.expenseCommands.currencyRemoved"));
    }

    /**
     * Creates or edits an expense; refusals land on {@code form.problem}, a new line's id on {@code form.id}.
     * Choosing "Add a currency..." in the form adds that currency in the same put. A blank category takes the
     * matched budget line's (else Other).
     */
    public boolean saveLine(final String tripId, final ExpenseForm form) {
        final Consumer<String> refusal = (form == null) ? PageFeedback::refuse : form::setProblem;
        final Trip trip = gate.editableTrip(tripId, form, refusal, notAllowed());
        if (trip == null) {
            return false;
        }
        final String problem = lineProblem(form);
        if (problem != null) {
            return refuseInto(refusal, problem);
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final String currency = currencyFor(report, form, refusal);
        if (currency == null) {
            return false;
        }
        final TripBudget budget = budgetOf(tripId);
        final String budgetLineId = Util.trimToNull(form.getBudgetLineId());
        final ExpenseCategory matched = categoryOf(budget, budgetLineId);
        if (budgetLineId != null && matched == null) {
            return refuseInto(refusal, Text.get("feedback.expenseCommands.budgetLineGone"));
        }
        final ExpenseCategory category = (Util.trimToNull(form.getCategory()) == null)
                ? ((matched == null) ? ExpenseCategory.GENERAL : matched)
                : parse(ExpenseCategory.class, form.getCategory());
        final boolean adding = form.getId() == null || form.getId().isBlank();
        final ExpenseLine existing = adding ? null : report.line(form.getId());
        if (!adding && existing == null) {
            return refuseInto(refusal, Text.get("feedback.expenseCommands.expenseGone"));
        }
        if (adding && report.getLines().size() >= MAX_LINES) {
            return refuseInto(refusal, Text.get("feedback.expenseCommands.tooManyExpenses", MAX_LINES));
        }
        final ExpensePaidStatus paidStatus = paidStatusOf(form);
        if (!adding && paidStatus.isSettled() && report.isPaid(existing.getId())) {
            return refuseInto(refusal, Text.get("feedback.expenseCommands.paidLine"));
        }
        final ExpenseLine line = ExpenseLine.builder().id(adding ? null : existing.getId()).date(form.getDate())
                .category(category).description(form.getDescription()).currency(currency)
                .amountMinor(cents(form.getAmount())).budgetLineId(budgetLineId).note(form.getNote())
                .receiptIds(adding ? null : existing.getReceiptIds()).paidStatus(paidStatus).build();
        putAt(report.getLines(), existing, line);
        if (!persist(trip, report, refusal, (adding ? "added" : "edited") + " expense '" + line.getDescription()
                + "'", Text.get("feedback.expenseCommands.expenseSaved"))) {
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

    /** The form's paid status; Not yet reimbursed when it names none. */
    private static ExpensePaidStatus paidStatusOf(final ExpenseForm form) {
        final ExpensePaidStatus status = parse(ExpensePaidStatus.class, form.getPaidStatus());
        return (status == null) ? ExpensePaidStatus.NOT_PAID : status;
    }

    private static String lineProblem(final ExpenseForm form) {
        if (Util.trimToNull(form.getCategory()) != null && parse(ExpenseCategory.class, form.getCategory()) == null) {
            return Text.get("feedback.expenseCommands.chooseCategory");
        }
        if (Util.trimToNull(form.getPaidStatus()) != null
                && parse(ExpensePaidStatus.class, form.getPaidStatus()) == null) {
            return Text.get("feedback.expenseCommands.choosePaidStatus");
        }
        if (Util.trimToNull(form.getDescription()) == null) {
            return Text.get("feedback.expenseCommands.needsDescription");
        }
        final String description =
                tooLong("feedback.moneyForms.descriptionTooLong", form.getDescription(), MAX_DESCRIPTION);
        if (description != null) {
            return description;
        }
        final Double amount = form.getAmount();
        if (amount == null || amount.isNaN() || amount.isInfinite() || amount < 0
                || cents(amount) > MAX_AMOUNT_MINOR) {
            return Text.get("feedback.expenseCommands.enterLineAmount");
        }
        return tooLong("feedback.moneyForms.noteTooLong", form.getNote(), MAX_NOTE);
    }

    /**
     * The line's currency code: USD, one the report has, or (for "Add a currency...") a new one added to
     * {@code report} here so it saves with the line. Null after refusing.
     */
    private static String currencyFor(final TripExpenseReport report, final ExpenseForm form,
            final Consumer<String> refusal) {
        return currencyFor(report, form.isAddingCurrency(), form.getCurrency(), form.getNewCode(), form.getNewRate(),
                refusal);
    }

    /** {@link #currencyFor(TripExpenseReport, ExpenseForm, Consumer)} for any dialog with the currency fields. */
    private static String currencyFor(final TripExpenseReport report, final boolean adding, final String currency,
            final String newCode, final Double newRate, final Consumer<String> refusal) {
        if (!adding) {
            final String code = CurrencyRate.normalizeCode(currency);
            if (code == null || (!ExpenseLine.USD.equals(code) && report.currency(code) == null)) {
                refusal.accept(Text.get("feedback.expenseCommands.chooseCurrency"));
                return null;
            }
            return code;
        }
        final String code = CurrencyRate.normalizeCode(newCode);
        if (code != null && (ExpenseLine.USD.equals(code) || report.currency(code) != null)) {
            return code;
        }
        final String problem = currencyProblem(code, newRate, null);
        if (problem != null) {
            refusal.accept(problem);
            return null;
        }
        if (report.getCurrencies().size() >= MAX_CURRENCIES) {
            refusal.accept(Text.get("feedback.expenseCommands.tooManyCurrencies", MAX_CURRENCIES));
            return null;
        }
        report.getCurrencies().add(CurrencyRate.builder().code(code).perUsdMicros(micros(newRate)).build());
        return code;
    }

    /** Deletes an expense and its receipts; refusals growl. Deleting one that is already gone succeeds quietly. */
    public boolean deleteLine(final String tripId, final String lineId) {
        final Trip trip = gate.editableTrip(tripId, lineId, PageFeedback::refuse, notAllowed());
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final ExpenseLine line = report.line(lineId);
        if (line == null) {
            return true;
        }
        if (report.isPaid(lineId)) {
            return refuse(Text.get("feedback.expenseCommands.paidLine"));
        }
        report.getLines().remove(line);
        if (!persist(trip, report, PageFeedback::refuse, "deleted expense '" + line.getDescription() + "'",
                Text.get("feedback.expenseCommands.expenseDeleted"))) {
            return false;
        }
        // After the put: a refused save must leave the files its line still points at.
        receiptSource.get().purge(line.getReceiptIds());
        return true;
    }

    /** Moves an expense one place up ({@code dir < 0}) or down. */
    public boolean moveLine(final String tripId, final String lineId, final int dir) {
        final Trip trip = gate.editableTrip(tripId, lineId, PageFeedback::refuse, notAllowed());
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final ExpenseLine line = report.line(lineId);
        if (line == null) {
            return refuse(Text.get("feedback.expenseCommands.expenseGone"));
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
        final Trip trip = gate.viewableTrip(tripId);
        final ExpensePayment payment = (trip == null) ? null
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
        final Trip trip = gate.viewableTrip(tripId);
        if (trip == null) {
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
        final Trip trip = gate.viewableTrip(tripId);
        if (trip == null) {
            return choices;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.YES);
        final ExpensePayment editing = report.payment(paymentId);
        for (final ExpenseLine line : report.getLines()) {
            final boolean mine = editing != null && editing.getLineIds().contains(line.getId());
            if (mine || (line.isOutOfPocket() && !report.isPaid(line.getId()))) {
                choices.add(choice(line.getId(), ((line.getDate() == null) ? various()
                        : Formats.date(line.getDate(), RequestContext.currentLocale()))
                        + ", " + nameOr(line.getDescription(), unnamedExpense()) + ", "
                        + money(ExpenseMath.lineUsd(report, line))));
            }
        }
        return choices;
    }

    /** The checklist just changed: the amount becomes what the ticked expenses add up to (still editable). */
    public void prefillAmount(final String tripId, final PaymentForm form) {
        final Trip trip = (form == null) ? null : gate.viewableTrip(tripId);
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
        final Trip trip = gate.settleableTrip(tripId, form, refusal, notSettler());
        if (trip == null) {
            return false;
        }
        final String problem = paymentProblem(form);
        if (problem != null) {
            return refuseInto(refusal, problem);
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final boolean adding = form.getId() == null || form.getId().isBlank();
        final ExpensePayment existing = adding ? null : report.payment(form.getId());
        if (!adding && existing == null) {
            return refuseInto(refusal, Text.get("feedback.expenseCommands.paymentGone"));
        }
        if (adding && report.getPayments().size() >= MAX_PAYMENTS) {
            return refuseInto(refusal, Text.get("feedback.expenseCommands.tooManyPayments", MAX_PAYMENTS));
        }
        final List<String> lineIds = distinct(form.getLineIds());
        final String lineProblem = paymentLinesProblem(report, existing, lineIds);
        if (lineProblem != null) {
            return refuseInto(refusal, lineProblem);
        }
        final ExpensePayment payment = ExpensePayment.builder().id(adding ? null : existing.getId())
                .date(form.getDate()).amountCents(cents(form.getAmount())).method(form.getMethod())
                .note(form.getNote()).lineIds(lineIds)
                .recordedById(adding ? caller().personId() : existing.getRecordedById())
                .recordedBy(adding ? callerName() : existing.getRecordedBy())
                .recordedOn(adding ? LocalDateTime.now() : existing.getRecordedOn()).build();
        putAt(report.getPayments(), existing, payment);
        final String what = (adding ? "recorded" : "edited") + " a payment of " + money(payment.getAmountCents())
                + " by " + payment.getMethod() + " for " + lineIds.size()
                + ((lineIds.size() == 1) ? " expense" : " expenses");
        if (!persist(trip, report, refusal, what, adding ? Text.get("feedback.expenseCommands.paymentRecorded")
                : Text.get("feedback.expenseCommands.paymentSaved"))) {
            return false;
        }
        form.setId(payment.getId());
        form.setLineIds(new ArrayList<>(lineIds));
        form.setProblem(null);
        return true;
    }

    private static String paymentProblem(final PaymentForm form) {
        if (form.getDate() == null) {
            return Text.get("feedback.expenseCommands.enterPaidDate");
        }
        final String amount = signedDollarsProblem("feedback.moneyForms.signedAmountRange", form.getAmount());
        if (amount != null) {
            return amount;
        }
        if (Util.trimToNull(form.getMethod()) == null) {
            return Text.get("feedback.expenseCommands.enterMethod");
        }
        final String method = tooLong("feedback.moneyForms.methodTooLong", form.getMethod(), MAX_NAME);
        return (method != null) ? method : tooLong("feedback.moneyForms.noteTooLong", form.getNote(), MAX_NOTE);
    }

    /** Why these lines cannot be in this payment, or null: each must be still to be paid, or already in it. */
    private static String paymentLinesProblem(final TripExpenseReport report, final ExpensePayment editing,
            final List<String> lineIds) {
        for (final String lineId : lineIds) {
            final ExpenseLine line = report.line(lineId);
            if (line == null) {
                return Text.get("feedback.expenseCommands.tickedExpenseGone");
            }
            if (!line.isOutOfPocket()) {
                return Text.get("feedback.expenseCommands.needsNoPayment",
                        nameOr(line.getDescription(), unnamedExpense()),
                        line.getPaidStatus().getLabel().toLowerCase(Locale.ROOT));
            }
            final ExpensePayment holder = report.paymentFor(lineId);
            if (holder != null && holder != editing) {
                return Text.get("feedback.expenseCommands.alreadyInPayment",
                        nameOr(line.getDescription(), unnamedExpense()));
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
        final Trip trip = gate.settleableTrip(tripId, paymentId, PageFeedback::refuse, notSettler());
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
                + money(payment.getAmountCents()) + " by " + payment.getMethod(),
                Text.get("feedback.expenseCommands.paymentDeleted"));
    }

    // ================================================================== PDF and the treasurer

    /** Whether the report can be emailed: a reader of it, on a trip whose organization has a treasurer. */
    public boolean hasTreasurer(final String tripId) {
        final Trip trip = gate.viewableTrip(tripId);
        return trip != null && treasurerEmail(trip) != null;
    }

    /** The treasurer address for a trip's organization (its own setting, else the site's), or null. */
    String treasurerEmail(final Trip trip) {
        final String value = configSource.get().getString(KnownSettings.FINANCE_TREASURER_EMAIL,
                trip.getOrganization());
        return EmailAddresses.isValid(value) ? value.trim() : null;
    }

    /**
     * The report as a PDF in {@code locale} (with its receipts appended when {@code withReceipts}), or null for a
     * caller who may not read it. A download is in the request's language; the treasurer's copy in theirs.
     */
    public byte[] reportPdf(final String tripId, final boolean withReceipts, final Locale locale) {
        if (!canViewExpenses(tripId)) {
            return null;
        }
        final Trip trip = gate.knownTrip(tripId);
        final TripExpenseReport report = reportOrDefault(trip, Cached.YES);
        final Map<String, String> labels = new HashMap<>();
        final List<ExpenseReportPdf.ReceiptDoc> receipts = receiptDocs(report, labels, locale);
        return ExpenseReportPdf.render(reportPageIn(tripId, locale), labels, withReceipts ? receipts : List.of(),
                locale);
    }

    /**
     * Every receipt in report order (report level first, then line by line), numbered R1, R2...; fills
     * {@code labels} with each expense's numbers ("R2, R3") for the PDF's Receipts column.
     */
    private List<ExpenseReportPdf.ReceiptDoc> receiptDocs(final TripExpenseReport report,
            final Map<String, String> labels, final Locale locale) {
        final List<ExpenseReportPdf.ReceiptDoc> docs = new ArrayList<>();
        final ExpenseReceipts store = receiptSource.get();
        for (final String id : report.getReceiptIds()) {
            docs.add(receiptDoc(store, id, "R" + (docs.size() + 1),
                    Text.forLocale(locale, "pdf.expenseReport.forTheReport"), locale));
        }
        for (final ExpenseLine line : report.getLines()) {
            final List<String> mine = new ArrayList<>();
            for (final String id : line.getReceiptIds()) {
                final String label = "R" + (docs.size() + 1);
                mine.add(label);
                docs.add(receiptDoc(store, id, label, line.getDescription(), locale));
            }
            if (!mine.isEmpty()) {
                labels.put(line.getId(), String.join(", ", mine));
            }
        }
        return docs;
    }

    private static ExpenseReportPdf.ReceiptDoc receiptDoc(final ExpenseReceipts store, final String mediaId,
            final String label, final String forWhat, final Locale locale) {
        final MediaItem item = DAO.getInstance().getMedia(mediaId, Cached.YES).orElse(null);
        if (item == null) {
            return new ExpenseReportPdf.ReceiptDoc(label, forWhat,
                    Text.forLocale(locale, "pdf.expenseReport.receiptDeleted"), null, null);
        }
        final ExpenseReceipts.Served served = store.served(item, false).orElse(null);
        return new ExpenseReportPdf.ReceiptDoc(label, forWhat, item.getTitle(),
                (served == null) ? item.getContentType() : served.contentType(),
                (served == null) ? null : served.bytes());
    }

    /**
     * Emails the report to the organization's treasurer as ONE PDF with the receipts appended (owner decision
     * 2026-10-26); when that would be too large for an email, the report alone, saying so in the message. The
     * submitter is the Reply-To. Records who it went to and when; refusals growl. The mail and the PDF are in
     * the treasurer's language (staff: their own saved choice, else English), never the submitter's.
     */
    public boolean emailTreasurer(final String tripId) {
        final Trip trip = gate.editableTrip(tripId, tripId, PageFeedback::refuse, notAllowed());
        if (trip == null) {
            return false;
        }
        final String treasurer = treasurerEmail(trip);
        if (treasurer == null) {
            final String orgName = nameOr(orgName(trip), null);
            return refuse((orgName == null) ? Text.get("feedback.expenseCommands.noTreasurer")
                    : Text.get("feedback.expenseCommands.noTreasurerFor", orgName));
        }
        final Locale locale = ReaderLanguage.ofStaffAddress(treasurer, trip);
        byte[] pdf = reportPdf(tripId, true, locale);
        final boolean receiptsLeftOut = pdf.length > MailCommands.MAX_ATTACHMENT_BYTES;
        if (receiptsLeftOut) {
            pdf = reportPdf(tripId, false, locale);
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final String subject = (report.getSubmittedBy() == null)
                ? Text.forLocale(locale, "mail.expenseReport.subject", trip.getTitle())
                : Text.forLocale(locale, "mail.expenseReport.subjectBy", trip.getTitle(), report.getSubmittedBy());
        final boolean sent = mailSource.get().sendWithAttachment(
                new MailAddressCommands(configSource.get()).siteFrom(), treasurer, callerEmail(), subject,
                treasurerBody(trip, report, receiptsLeftOut, locale), ExpenseReportPdf.fileName(trip.getTitle()),
                pdf, ExpenseReceipts.PDF, caller().auditActor());
        if (!sent) {
            return refuse(Text.get("feedback.expenseCommands.notEmailed", treasurer));
        }
        report.setEmailedTo(treasurer);
        report.setEmailedOn(LocalDateTime.now());
        persist(trip, report, PageFeedback::refuse, "emailed the report to " + treasurer, null);
        info(receiptsLeftOut ? Text.get("feedback.expenseCommands.emailedWithoutReceipts", treasurer)
                : Text.get("feedback.expenseCommands.emailed", treasurer));
        return true;
    }

    /**
     * The message to the treasurer: who, what trip, the bottom line, and what is attached. Every label and line
     * comes from the same locale-explicit page the PDF is drawn from, so the two read alike.
     */
    private String treasurerBody(final Trip trip, final TripExpenseReport report, final boolean receiptsLeftOut,
            final Locale locale) {
        final ExpensePage page = reportPageIn(trip.getId(), locale);
        final String who = esc(nameOr(report.getSubmittedBy(), Text.forLocale(locale, "common.someone")));
        final StringBuilder html = new StringBuilder();
        html.append("<p>").append((page.getTripDates() == null || page.getTripDates().isBlank())
                ? Text.forLocale(locale, "mail.expenseReport.submitted", who, esc(trip.getTitle()))
                : Text.forLocale(locale, "mail.expenseReport.submittedDates", who, esc(trip.getTitle()),
                        esc(page.getTripDates()))).append("</p>");
        html.append("<p>").append(labeled(locale, "pdf.expenseReport.totalSpent", page.getTotal()));
        if (page.isHasSettled()) {
            html.append("<br/>").append(labeled(ExpensePaidStatus.ORG.getLabel(locale), page.getPaidByOrg()))
                    .append("<br/>").append(labeled(ExpensePaidStatus.REIMBURSED.getLabel(locale),
                            page.getReimbursed()));
        }
        html.append("<br/>").append(labeled(locale, "pdf.expenseReport.cashAdvanced", page.getCashAdvance()))
                .append("<br/><b>").append(labeled(page.getDueLabel(), page.getDue())).append("</b>");
        if (!page.getPaymentRows().isEmpty()) {
            html.append("<br/>").append(labeled(locale, "pdf.expenseReport.paymentsRecorded",
                    page.getPaymentsTotal())).append("<br/><b>")
                    .append(page.isSettled() ? esc(page.getStillDueLabel())
                            : labeled(page.getStillDueLabel(), page.getStillDue()))
                    .append("</b>");
        }
        html.append("</p>");
        if (report.getPaymentInstructions() != null) {
            html.append("<p>").append(labeled(locale, "pdf.expenseReport.paymentInstructions",
                    report.getPaymentInstructions())).append("</p>");
        }
        html.append("<p>").append(esc(Text.forLocale(locale, receiptsLeftOut ? "mail.expenseReport.attachedAlone"
                : "mail.expenseReport.attachedWithReceipts"))).append("</p>");
        return html.toString();
    }

    /** "Total spent: $1,234.00": a label read from {@code key}, a colon, and the value, escaped. */
    private static String labeled(final Locale locale, final String key, final String value) {
        return labeled(Text.forLocale(locale, key), value);
    }

    private static String labeled(final String label, final String value) {
        return esc(label) + ": " + esc(value);
    }

    private static String esc(final String text) {
        return Html.escapeText(text).replace("\n", "<br/>");
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
        final Trip trip = gate.editableTrip(tripId, mediaId, refusal, notAllowed());
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final List<String> ids;
        if (lineId == null || lineId.isBlank()) {
            ids = report.getReceiptIds();
            if (ids.size() >= MAX_REPORT_RECEIPTS) {
                return refuseInto(refusal,
                        Text.get("feedback.expenseCommands.tooManyReportReceipts", MAX_REPORT_RECEIPTS));
            }
        } else {
            final ExpenseLine line = report.line(lineId);
            if (line == null) {
                return refuseInto(refusal, Text.get("feedback.expenseCommands.expenseGone"));
            }
            ids = line.getReceiptIds();
            if (ids.size() >= MAX_RECEIPTS_PER_LINE) {
                return refuseInto(refusal,
                        Text.get("feedback.expenseCommands.tooManyLineReceipts", MAX_RECEIPTS_PER_LINE));
            }
        }
        ids.add(mediaId);
        return persist(trip, report, refusal, "attached a receipt", null);
    }

    /** Unlinks a receipt from wherever the report holds it; false (after refusing) when it holds it nowhere. */
    boolean detachReceipt(final String tripId, final String mediaId, final Consumer<String> refusal) {
        final Trip trip = gate.editableTrip(tripId, mediaId, refusal, notAllowed());
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        if (!report.getReceiptIds().remove(mediaId)) {
            final ExpenseLine line = report.lineWithReceipt(mediaId);
            if (line == null) {
                return refuseInto(refusal, Text.get("feedback.expenseCommands.receiptNotHere"));
            }
            line.getReceiptIds().remove(mediaId);
        }
        return persist(trip, report, refusal, "removed a receipt", null);
    }

    // ================================================================== cash-advance worksheet

    /** The worksheet types in menu order; value = the enum name. */
    public List<Choice> advanceTypes() {
        return choicesOf(AdvanceType.values(), AdvanceType::getLabel);
    }

    /** The dialog's title for a type name; "Advance" for an unknown one. */
    public String typeLabel(final String type) {
        final AdvanceType parsed = parse(AdvanceType.class, type);
        return (parsed == null) ? Text.get("feedback.expenseCommands.advance") : parsed.getLabel();
    }

    /** The question above the type's "who" field; null for a type without one. */
    public String partyLabel(final String type) {
        final AdvanceType parsed = parse(AdvanceType.class, type);
        if (parsed == null) {
            return null;
        }
        return switch (parsed) {
            case CASH -> Text.get("feedback.expenseCommands.partyLabel.CASH");
            case CREDIT -> Text.get("feedback.expenseCommands.partyLabel.CREDIT");
            case RETURNED -> Text.get("feedback.expenseCommands.partyLabel.RETURNED");
            default -> null;
        };
    }

    /** The help text the dialog shows for a type; empty for an unknown one. */
    public String typeHelp(final String type) {
        final AdvanceType parsed = parse(AdvanceType.class, type);
        if (parsed == null) {
            return "";
        }
        return switch (parsed) {
            case CASH -> Text.get("feedback.expenseCommands.typeHelp.CASH");
            case CREDIT -> Text.get("feedback.expenseCommands.typeHelp.CREDIT");
            case PARTICIPANT -> Text.get("feedback.expenseCommands.typeHelp.PARTICIPANT");
            case RETURNED -> Text.get("feedback.expenseCommands.typeHelp.RETURNED");
            case OTHER -> Text.get("feedback.expenseCommands.typeHelp.OTHER");
        };
    }

    /** The trip's participants for the picker, "Last, Preferred", sorted; value = the person id. */
    public List<Choice> participantChoices(final String tripId) {
        final List<Choice> choices = new ArrayList<>();
        final Trip trip = gate.viewableTrip(tripId);
        if (trip == null) {
            return choices;
        }
        for (final Person.Id id : trip.getPeople()) {
            final Person person = (id == null) ? null : DAO.getInstance().getPerson(id, Cached.YES).orElse(null);
            if (person != null) {
                choices.add(choice(id.getValue(),
                        nameOr(person.getLast(), "") + ", " + nameOr(person.getPreferredName(), "")));
            }
        }
        choices.sort((a, b) -> a.getLabel().compareToIgnoreCase(b.getLabel()));
        return choices;
    }

    /** A fresh entry of this type: dollars, no date, the organization's name prefilled where it usually is. */
    public AdvanceForm newAdvanceForm(final String tripId, final String type) {
        final AdvanceForm form = new AdvanceForm();
        final AdvanceType parsed = parse(AdvanceType.class, type);
        form.setType((parsed == null) ? AdvanceType.OTHER.name() : parsed.name());
        final Trip trip = gate.knownTrip(tripId);
        if (trip != null && (parsed == AdvanceType.CASH || parsed == AdvanceType.RETURNED)) {
            form.setParty(orgName(trip));
        }
        return form;
    }

    /** The entry with this id as a form; a blank form when there is none or the caller may not view. */
    public AdvanceForm advanceFormFor(final String tripId, final String advanceId) {
        final Trip trip = gate.viewableTrip(tripId);
        if (trip == null) {
            return new AdvanceForm();
        }
        final AdvanceEntry entry = reportOrDefault(trip, Cached.YES).advance(advanceId);
        return (entry == null) ? new AdvanceForm() : advanceForm(entry);
    }

    private AdvanceForm advanceForm(final AdvanceEntry entry) {
        final AdvanceForm form = new AdvanceForm();
        form.setId(entry.getId());
        form.setType(entry.getType().name());
        form.setDate(entry.getDate());
        form.setCurrency(entry.getCurrency());
        form.setAmount(dollars(entry.getAmountMinor()));
        form.setParty(entry.getParty());
        form.setPersonIds(new ArrayList<>(entry.getPersonIds()));
        form.setNote(entry.getNote());
        form.setTransactionLine(transactionLine(entry, RequestContext.currentLocale()));
        form.setTransactionId((entry.getTransaction() == null) ? null : entry.getTransaction().getTxId());
        return form;
    }

    /** Every worksheet entry as a form (the REST GET). */
    public List<AdvanceForm> advanceFormsFor(final String tripId) {
        final List<AdvanceForm> forms = new ArrayList<>();
        final Trip trip = gate.viewableTrip(tripId);
        if (trip == null) {
            return forms;
        }
        for (final AdvanceEntry entry : reportOrDefault(trip, Cached.YES).getAdvances()) {
            forms.add(advanceForm(entry));
        }
        return forms;
    }

    /**
     * Adds an entry (blank {@code form.id}) or edits one; refusals land on {@code form.problem}, a new entry's id
     * on {@code form.id}. When a finance admin ticks {@code recordTransaction} on a participant entry for ONE
     * person that has recorded none yet, a Payment transaction is recorded for them after the save (see
     * {@link #recordParticipantPayment}); the tick is ignored for everyone else. Editing an entry that recorded
     * one warns that the ledger is not updated.
     */
    public boolean saveAdvance(final String tripId, final AdvanceForm form) {
        final Consumer<String> refusal = (form == null) ? PageFeedback::refuse : form::setProblem;
        final Trip trip = gate.editableTrip(tripId, form, refusal, notAllowed());
        if (trip == null) {
            return false;
        }
        final AdvanceType type = parse(AdvanceType.class, form.getType());
        final String problem = advanceProblem(trip, type, form);
        if (problem != null) {
            return refuseInto(refusal, problem);
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final boolean adding = form.getId() == null || form.getId().isBlank();
        final AdvanceEntry existing = adding ? null : report.advance(form.getId());
        if (!adding && existing == null) {
            return refuseInto(refusal, Text.get("feedback.expenseCommands.advanceGone"));
        }
        if (adding && report.getAdvances().size() >= MAX_ADVANCES) {
            return refuseInto(refusal, Text.get("feedback.expenseCommands.tooManyAdvances", MAX_ADVANCES));
        }
        final String currency = currencyFor(report, form.isAddingCurrency(), form.getCurrency(), form.getNewCode(),
                form.getNewRate(), refusal);
        if (currency == null) {
            return false;
        }
        final AdvanceEntry entry = AdvanceEntry.builder().id(adding ? null : existing.getId()).type(type)
                .date(form.getDate()).currency(currency).amountMinor(cents(form.getAmount())).note(form.getNote())
                .fields(advanceFields(type, form))
                .personIds((type == AdvanceType.PARTICIPANT) ? distinct(form.getPersonIds()) : List.of())
                .transaction(adding ? null : existing.getTransaction()).build();
        putAt(report.getAdvances(), existing, entry);
        // Audit text stays English whatever the editor reads (docs/i18n.md).
        final String what = (adding ? "added" : "edited") + " the " + type.getLabel(Locale.ENGLISH) + " of "
                + money(Math.abs(ExpenseMath.advanceUsd(report, entry))) + " on the cash-advance worksheet";
        if (!persist(trip, report, refusal, what, Text.get("feedback.expenseCommands.advanceSaved"))) {
            return false;
        }
        if (entry.getTransaction() != null) {
            warnRecorded(entry);
        } else if (form.isRecordTransaction() && type == AdvanceType.PARTICIPANT && entry.getPersonIds().size() == 1
                && canRecordPayments(tripId)) {
            recordParticipantPayment(trip, report, entry);
        }
        form.setId(entry.getId());
        form.setCurrency(currency);
        form.setPersonIds(new ArrayList<>(entry.getPersonIds()));
        form.setNewCode(null);
        form.setNewRate(null);
        form.setRecordTransaction(false);
        form.setTransactionLine(transactionLine(entry, RequestContext.currentLocale()));
        form.setTransactionId((entry.getTransaction() == null) ? null : entry.getTransaction().getTxId());
        form.setProblem(null);
        return true;
    }

    /** The type's "who" answer under its key; nothing for a type that asks none. */
    private static Map<String, String> advanceFields(final AdvanceType type, final AdvanceForm form) {
        final Map<String, String> fields = new HashMap<>();
        if (type.partyKey() != null && Util.trimToNull(form.getParty()) != null) {
            fields.put(type.partyKey(), form.getParty().trim());
        }
        return fields;
    }

    private static String advanceProblem(final Trip trip, final AdvanceType type, final AdvanceForm form) {
        if (type == null) {
            return Text.get("feedback.expenseCommands.pickAdvanceType");
        }
        if (form.getAmount() == null) {
            return Text.get("feedback.expenseCommands.enterAmount");
        }
        final String amount = dollarsProblem("feedback.moneyForms.amountRange", form.getAmount());
        if (amount != null) {
            return amount;
        }
        if (cents(form.getAmount()) > MAX_AMOUNT_MINOR) {
            return Text.get("feedback.expenseCommands.amountTooLarge");
        }
        final String party = tooLong("feedback.moneyForms.answerTooLong", form.getParty(), MAX_DESCRIPTION);
        if (party != null) {
            return party;
        }
        if (type == AdvanceType.PARTICIPANT) {
            final List<String> ids = distinct(form.getPersonIds());
            if (ids.isEmpty()) {
                return Text.get("feedback.expenseCommands.pickParticipants");
            }
            for (final String id : ids) {
                if (!trip.getPeople().contains(Person.Id.from(id))) {
                    return Text.get("feedback.expenseCommands.notOnTrip", personName(id));
                }
            }
        }
        return tooLong("feedback.moneyForms.reportNoteTooLong", form.getNote(), MAX_TEXT);
    }

    /**
     * Records the Payment transaction for a participant entry's one person: the USD figure at today's rate,
     * dated the entry's date (today when it has none), bound to the trip and audited like the Transactions
     * page does, then stamped on the entry with a second put so it is never recorded twice. The ledger write
     * comes AFTER the worksheet save on purpose: an entry always exists before money is recorded against it.
     */
    private void recordParticipantPayment(final Trip trip, final TripExpenseReport report, final AdvanceEntry entry) {
        final Person.Id personId = Person.Id.from(entry.getPersonIds().get(0));
        final long cents = ExpenseMath.advanceUsd(report, entry);
        final LocalDateTime when = (entry.getDate() == null) ? LocalDateTime.now() : entry.getDate().atStartOfDay();
        final Transaction tx = new Transaction(null, personId, null, Transaction.Type.Tx,
                Transaction.TransactionType.Payment, when, MoneyMath.toFloatDollars(cents), "Payment",
                PARTICIPANT_TX_NOTE);
        if (!txSource.get().saveTransaction(tx, trip.getId())) {
            warn(Text.get("feedback.expenseCommands.transactionNotRecorded"));
            return;
        }
        final BindingCommands bind = bindSource.get();
        bind.setBindings(bind.key(personId.getValue(), tx.getTxId()), BindingType.TRANSACTION, BindingType.TRIP,
                List.of(trip.getId()), true);
        final Person person = DAO.getInstance().getPerson(personId, Cached.YES).orElse(null);
        auditSource.get().transaction(person, tx, caller().auditActor());
        entry.setTransaction(AdvanceEntry.RecordedTransaction.builder().txId(tx.getTxId())
                .personId(personId.getValue()).amountCents(cents).recordedOn(LocalDateTime.now()).build());
        final String name = personName(personId.getValue());
        // The audit line names the person in English even when the fallback words stand for a missing name.
        if (!persist(trip, report, ExpenseCommands::stampFailed, "recorded a " + money(cents) + " payment for "
                + personName(personId.getValue(), Locale.ENGLISH), null)) {
            return;
        }
        info(Text.get("feedback.expenseCommands.participantPaymentRecorded", money(cents), name));
    }

    private static void stampFailed(final String reason) {
        warn(Text.get("feedback.expenseCommands.stampFailed", reason));
    }

    /** The edit/delete warning for an entry that recorded a transaction: the ledger is not updated from here. */
    private void warnRecorded(final AdvanceEntry entry) {
        final AdvanceEntry.RecordedTransaction tx = entry.getTransaction();
        final String amount = money(tx.getAmountCents());
        final String name = personName(tx.getPersonId());
        warn((tx.getRecordedOn() == null) ? Text.get("feedback.expenseCommands.txWarningEarlier", amount, name)
                : Text.get("feedback.expenseCommands.txWarning", amount, name,
                        Formats.date(tx.getRecordedOn(), RequestContext.currentLocale())));
    }

    /** Deletes a worksheet entry; one that recorded a transaction leaves it in the ledger and warns. */
    public boolean deleteAdvance(final String tripId, final String advanceId) {
        final Trip trip = gate.editableTrip(tripId, advanceId, PageFeedback::refuse, notAllowed());
        if (trip == null) {
            return false;
        }
        final TripExpenseReport report = reportOrDefault(trip, Cached.NO);
        final AdvanceEntry entry = report.advance(advanceId);
        if (entry == null) {
            return true;
        }
        report.getAdvances().remove(entry);
        // Audit text stays English whatever the editor reads (docs/i18n.md).
        final String what = "deleted the " + entry.getType().getLabel(Locale.ENGLISH) + " of "
                + money(Math.abs(ExpenseMath.advanceUsd(report, entry))) + " from the cash-advance worksheet";
        if (!persist(trip, report, PageFeedback::refuse, what, Text.get("feedback.expenseCommands.advanceDeleted"))) {
            return false;
        }
        if (entry.getTransaction() != null) {
            warnRecorded(entry);
        }
        return true;
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
                return refuseInto(refusal, Text.get("feedback.expenseCommands.notSaved"));
            }
        } catch (final ConditionalCheckFailedException ex) {
            return refuseInto(refusal, Text.get("feedback.shared.savedBySomeoneElse"));
        } catch (final IllegalArgumentException ex) {
            return refuseInto(refusal, Text.get("feedback.expenseCommands.tooLarge"));
        } catch (final IOException ex) {
            log.error("Unable to save the expense report of trip {}", trip.getId(), ex);
            return refuseInto(refusal, Text.get("feedback.expenseCommands.saveFailed"));
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
        return PersonNames.displayOr(DAO.getInstance().getPerson(id, Cached.YES).orElse(null), null);
    }

    private static String orgName(final Trip trip) {
        final Organization org = trip.getOrganization();
        return (org == null) ? null : org.getName();
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

    private static String notAllowed() {
        return Text.get("feedback.expenseCommands.notAllowed");
    }

    private static String notSettler() {
        return Text.get("feedback.expenseCommands.notSettler");
    }

    /** What stands where a name was never typed. */
    private static String noName(final Locale locale) {
        return Text.forLocale(locale, "feedback.expenseCommands.noName");
    }

    /** What stands for an expense with no description, in the request's language. */
    private static String unnamedExpense() {
        return unnamedExpense(RequestContext.currentLocale());
    }

    private static String unnamedExpense(final Locale locale) {
        return Text.forLocale(locale, "feedback.expenseCommands.unnamedExpense");
    }

    private static String various() {
        return various(RequestContext.currentLocale());
    }

    private static String various(final Locale locale) {
        return Text.forLocale(locale, "feedback.expenseCommands.various");
    }

    private static String nameOr(final String text, final String fallback) {
        return (text == null || text.isBlank()) ? fallback : text;
    }

    Caller caller() {
        return callerSource.get();
    }
}
