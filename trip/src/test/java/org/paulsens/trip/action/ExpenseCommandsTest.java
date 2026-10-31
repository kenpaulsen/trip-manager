package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.AdvanceEntry;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.BudgetCategory;
import org.paulsens.trip.model.BudgetLine;
import org.paulsens.trip.model.BudgetLineKind;
import org.paulsens.trip.model.BudgetLodgingLine;
import org.paulsens.trip.model.ExpenseCategory;
import org.paulsens.trip.model.ExpenseLine;
import org.paulsens.trip.model.ExpensePaidStatus;
import org.paulsens.trip.model.ExpensePayment;
import org.paulsens.trip.model.ExpenseStatus;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PricingModel;
import org.paulsens.trip.model.RateSchedule;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripBudget;
import org.paulsens.trip.model.TripExpenseReport;
import org.paulsens.trip.pay.ExpenseOracle;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The expense report bean end to end against the in-memory store: the gate matrix (the budget's), every write
 * with its refusals, the budget-versus-actual rows, and the page reproducing the spreadsheet's Expense Report
 * tab ({@code docs/expense-report.md}). Each test works on its own trip.
 */
public class ExpenseCommandsTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 20, 8, 0);

    private final ExpenseCommands admin = new ExpenseCommands(TestCallers::siteAdmin);
    private String orgId;
    private String orgName;

    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
        orgName = "Expenses " + RandomData.genAlpha(8);
        final Organization org = new OrgCommands(TestCallers::siteAdmin).createOrganization(orgName, "EXP", null);
        orgId = org.getId().getValue();
    }

    // ------------------------------------------------------------------ fixtures

    private Trip newTrip() throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Holy Angels")
                .startDate(START).endDate(LocalDateTime.of(2026, 10, 5, 12, 0)).people(new ArrayList<>()).build();
        trip.setOrgId(orgId);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip;
    }

    private static Person.Id grant(final String name, final String scope) {
        final Person.Id who = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        assertTrue(priv.savePrivilege(priv.getOrCreate(name, scope, name).withNewPerson(who)));
        return who;
    }

    private static ExpenseCommands as(final Person.Id who) {
        return new ExpenseCommands(() -> TestCallers.person(who));
    }

    static ExpenseForm line(final LocalDate date, final String category, final String description,
            final String currency, final Double amount) {
        final ExpenseForm form = new ExpenseForm();
        form.setDate(date);
        form.setCategory(category);
        form.setDescription(description);
        form.setCurrency(currency);
        form.setAmount(amount);
        return form;
    }

    static CurrencyForm currency(final String code, final Double rate) {
        final CurrencyForm form = new CurrencyForm();
        form.setCode(code);
        form.setRate(rate);
        return form;
    }

    private static HeaderForm header(final String name) {
        final HeaderForm form = new HeaderForm();
        form.setSubmittedBy(name);
        return form;
    }

    static AdvanceForm advance(final String type, final Double amount, final String party) {
        final AdvanceForm form = new AdvanceForm();
        form.setType(type);
        form.setAmount(amount);
        form.setParty(party);
        return form;
    }

    /** The spreadsheet's report, typed in through the bean the way the page does it. */
    private void saveSheet(final String tripId) {
        assertTrue(admin.saveHeader(tripId, header("Ken Paulsen")));
        assertTrue(admin.saveAdvance(tripId, advance("CASH", 45_765.0, "CFPW")));
        assertTrue(admin.saveCurrency(tripId, currency("EUR", 0.83)));
        assertTrue(admin.saveCurrency(tripId, currency("bam", 1.66)));
        for (final ExpenseLine sheet : ExpenseOracle.report().getLines()) {
            final ExpenseForm form = line(sheet.getDate(), sheet.getCategory().name(), sheet.getDescription(),
                    sheet.getCurrency(), sheet.getAmountMinor() / 100.0);
            assertTrue(admin.saveLine(tripId, form), "Saved " + sheet.getDescription() + ": " + form.getProblem());
        }
    }

    private static ExpenseRow row(final ExpensePage page, final String description) {
        for (final ExpenseRow row : page.getLineRows()) {
            if (description.equals(row.getDescription())) {
                return row;
            }
        }
        throw new AssertionError("No row " + description);
    }

    private static TripExpenseReport stored(final String tripId) {
        return DAO.getInstance().getTripExpenseReport(tripId, Cached.NO).orElseThrow();
    }

    // ------------------------------------------------------------------ gates

    @Test
    public void gatesAreTheBudgets() throws IOException {
        final String id = newTrip().getId();
        final ExpenseCommands nobody = new ExpenseCommands(
                () -> new Caller(null, false, AuditActor.system(), new PrivilegeCommands()));
        assertFalse(nobody.canViewExpenses(id));
        final ExpenseCommands stranger = as(Person.Id.newInstance());
        assertFalse(stranger.canViewExpenses(id));
        assertFalse(stranger.canEditExpenses(id));
        assertTrue(as(grant(PrivilegeCommands.TRIP_MGR, id)).canEditExpenses(id));
        assertTrue(as(grant(PrivilegeCommands.TRIP_FIN_ADMIN, id)).canEditExpenses(id));
        final ExpenseCommands finView = as(grant(PrivilegeCommands.TRIP_FIN_VIEW, id));
        assertTrue(finView.canViewExpenses(id));
        assertFalse(finView.canEditExpenses(id));
        assertFalse(finView.saveHeader(id, header("x")), "A viewer writes nothing");
        assertFalse(finView.saveAdvance(id, advance("CASH", 1.0, null)));
        assertFalse(finView.deleteAdvance(id, "x"));
        assertFalse(finView.submit(id));
        final ExpenseCommands global = as(grant(PrivilegeCommands.VIEW_FINANCES, null));
        assertTrue(global.canViewExpenses(id));
        assertFalse(global.canEditExpenses(id));
        assertFalse(admin.canViewExpenses(UUID.randomUUID().toString()), "Unknown trip");
        assertFalse(admin.canViewExpenses(null));

        assertFalse(stranger.reportPage(id).isExists());
        assertTrue(stranger.reportPage(id).getLineRows().isEmpty());
        assertEquals(stranger.expenseHeadline(id), "");
        assertTrue(stranger.lineFormsFor(id).isEmpty());
        assertTrue(stranger.currencyFormsFor(id).isEmpty());
        assertTrue(stranger.budgetChoices(id).isEmpty());
        assertTrue(stranger.receiptRowsFor(id, null).isEmpty());
        assertNull(stranger.headerFormFor(id).getSubmittedBy());
        assertTrue(stranger.advanceFormsFor(id).isEmpty());
        assertNull(stranger.advanceFormFor(id, "x").getId());
        assertTrue(stranger.participantChoices(id).isEmpty());
        assertEquals(stranger.currencyChoices(id).size(), 2, "USD and Add only");
        assertEquals(stranger.receiptHeading(id, "x"), "Receipts");
        assertNull(stranger.currencyFormFor(id, "EUR").getCode());
        assertNull(stranger.lineFormFor(id, "x").getId());
        assertTrue(stranger.paymentFormsFor(id).isEmpty());
        assertTrue(stranger.unpaidLineChoices(id, null).isEmpty());
        assertNull(stranger.paymentFormFor(id, "x").getId());
    }

    @Test
    public void onlyFinanceAdminsRecordPayments() throws IOException {
        final String id = newTrip().getId();
        assertTrue(admin.canRecordPayments(id), "Site admin");
        assertTrue(as(grant(PrivilegeCommands.TRIP_FIN_ADMIN, id)).canRecordPayments(id));
        final ExpenseCommands manager = as(grant(PrivilegeCommands.TRIP_MGR, id));
        assertTrue(manager.canEditExpenses(id));
        assertFalse(manager.canRecordPayments(id), "A trip manager, usually the one paid, records no payment");
        assertFalse(manager.recordPayment(id, payment(null, 10.0, "Zelle")));
        assertFalse(manager.deletePayment(id, "x"));
        assertFalse(manager.reportPage(id).isCanRecordPayments());
        assertTrue(admin.reportPage(id).isCanRecordPayments());
        assertFalse(as(grant(PrivilegeCommands.TRIP_FIN_VIEW, id)).canRecordPayments(id));
        assertFalse(as(grant(PrivilegeCommands.VIEW_FINANCES, null)).canRecordPayments(id));
        assertFalse(new ExpenseCommands(() -> new Caller(null, false, AuditActor.system(), new PrivilegeCommands()))
                .canRecordPayments(id));
        assertFalse(admin.canRecordPayments(UUID.randomUUID().toString()), "Unknown trip");
        assertFalse(admin.recordPayment(id, null), "Nothing to save");
    }

    // ------------------------------------------------------------------ paid status and payments

    private static PaymentForm payment(final LocalDate date, final Double amount, final String method,
            final String... lineIds) {
        final PaymentForm form = new PaymentForm();
        form.setDate(date);
        form.setAmount(amount);
        form.setMethod(method);
        form.setLineIds(new ArrayList<>(List.of(lineIds)));
        return form;
    }

    private String lineId(final String tripId, final String description) {
        return row(admin.reportPage(tripId), description).getId();
    }

    @Test
    public void linesSettledOutsideTheReportLeaveTheDue() throws IOException {
        final String id = newTrip().getId();
        saveSheet(id);
        final ExpenseForm gradnici = admin.lineFormFor(id, lineId(id, "Gradnici donation"));
        assertEquals(gradnici.getPaidStatus(), "NOT_PAID");
        gradnici.setPaidStatus("ORG");
        assertTrue(admin.saveLine(id, gradnici), gradnici.getProblem());
        final ExpenseForm tips = admin.lineFormFor(id, stored(id).getLines().get(3).getId());
        tips.setPaidStatus("REIMBURSED");
        assertTrue(admin.saveLine(id, tips));
        final ExpenseForm unknown = admin.lineFormFor(id, lineId(id, "Guide"));
        unknown.setPaidStatus("SOMETIMES");
        assertFalse(admin.saveLine(id, unknown));
        assertEquals(unknown.getProblem(), "Choose a paid status.");

        final ExpensePage page = admin.reportPage(id);
        assertEquals(page.getTotal(), "$55,560.95", "Still spent");
        assertTrue(page.isHasSettled());
        assertEquals(page.getPaidByOrg(), "$265.06");
        assertEquals(page.getReimbursed(), "$120.48");
        assertEquals(page.getOutOfPocket(), "$55,175.41");
        assertEquals(page.getDue(), "$9,410.41");
        assertTrue(page.isDueToSubmitter());
        final ExpenseRow row = row(page, "Gradnici donation");
        assertEquals(row.getPaidChip(), "Org paid");
        assertEquals(row.getPaidKey(), "org");
        assertNull(row(page, "Guide").getPaidChip());
        assertEquals(row(page, "Guide").getPaidKey(), "not_paid");
        assertEquals(page.getUnpaidCount(), 16);
        assertEquals(page.getStillDueLabel(), "Still due to Ken Paulsen");
        assertEquals(admin.paidStatuses().get(1).getLabel(), "Paid by organization");
        assertEquals(stored(id).line(row.getId()).getPaidStatus(), ExpensePaidStatus.ORG);

        // An edit keeps the status (saveLine rebuilds the line from the form).
        final ExpenseForm again = admin.lineFormFor(id, row.getId());
        again.setNote("Paid by the office");
        assertTrue(admin.saveLine(id, again));
        assertEquals(stored(id).line(row.getId()).getPaidStatus(), ExpensePaidStatus.ORG);
        final ExpenseForm blank = line(null, null, "Blank status", "USD", 1.0);
        blank.setPaidStatus(null);
        assertTrue(admin.saveLine(id, blank));
        assertEquals(stored(id).line(blank.getId()).getPaidStatus(), ExpensePaidStatus.NOT_PAID);
    }

    @Test
    public void paymentsSettleLinesAndTheBalance() throws IOException {
        final String id = newTrip().getId();
        saveSheet(id);
        final String guide = lineId(id, "Guide");
        final String tolls = lineId(id, "Tolls");
        final List<Choice> unpaid = admin.unpaidLineChoices(id, null);
        assertEquals(unpaid.size(), 18);
        assertEquals(unpaid.get(8).getLabel(), "Sep 29, 2026, Guide, $1,440.00");
        assertEquals(unpaid.get(11).getLabel(), "Various, Tolls, $30.00");

        final PaymentForm first = admin.newPaymentForm();
        assertEquals(first.getDate(), LocalDate.now());
        first.setLineIds(new ArrayList<>(List.of(guide, tolls)));
        admin.prefillAmount(id, first);
        assertEquals(first.getAmount(), 1_470.0, "The ticked lines' dollars");
        first.setAmount(1_000.0);
        first.setDate(LocalDate.of(2026, 10, 20));
        first.setMethod(" Zelle ");
        first.setNote("Less part of the advance");
        assertTrue(admin.recordPayment(id, first), first.getProblem());
        assertNotNull(first.getId());

        ExpensePage page = admin.reportPage(id);
        assertEquals(page.getStatus(), "Partly paid");
        assertEquals(page.getStatusKey(), "partly_paid");
        assertEquals(row(page, "Guide").getPaidChip(), "Paid Oct 20");
        assertEquals(row(page, "Guide").getPaidKey(), "paid");
        assertEquals(row(page, "Guide").getPaymentId(), first.getId());
        final PaymentRow paid = page.getPaymentRows().get(0);
        assertEquals(paid.getAmount(), "$1,000.00");
        assertEquals(paid.getMethod(), "Zelle");
        assertEquals(paid.getLines(), "Guide, Tolls");
        assertEquals(paid.getLineCount(), 2);
        assertEquals(paid.getDateText(), "Oct 20, 2026");
        assertTrue(paid.getRecordedLine().startsWith("Recorded by "), paid.getRecordedLine());
        assertEquals(page.getPaymentsTotal(), "$1,000.00");
        assertEquals(page.getStillDue(), "$8,795.95", "$9,795.95 owed, $1,000.00 paid");
        assertTrue(page.isStillDueToSubmitter());
        assertEquals(page.getUnpaidCount(), 16);
        assertEquals(admin.unpaidLineChoices(id, null).size(), 16, "Paid lines leave the checklist");
        assertEquals(admin.unpaidLineChoices(id, first.getId()).size(), 18, "...except for their own payment");

        // A paid line is guarded until its payment goes.
        assertFalse(admin.deleteLine(id, guide));
        final ExpenseForm settle = admin.lineFormFor(id, guide);
        settle.setPaidStatus("ORG");
        assertFalse(admin.saveLine(id, settle));
        assertEquals(settle.getProblem(), ExpenseCommands.PAID_LINE);
        final ExpenseForm text = admin.lineFormFor(id, guide);
        text.setNote("Fr. Leon");
        assertTrue(admin.saveLine(id, text), "Its text and amount stay editable");

        // A line can be in one payment only, and a settled line in none.
        final PaymentForm twice = payment(LocalDate.of(2026, 10, 21), 5.0, "Cash", guide);
        assertFalse(admin.recordPayment(id, twice));
        assertEquals(twice.getProblem(), "'Guide' is already in a payment: reload the page.");
        final ExpenseForm org = admin.lineFormFor(id, lineId(id, "Party supplies"));
        org.setPaidStatus("ORG");
        assertTrue(admin.saveLine(id, org));
        final PaymentForm settled = payment(LocalDate.of(2026, 10, 21), 5.0, "Cash", org.getId());
        assertFalse(admin.recordPayment(id, settled));
        assertEquals(settled.getProblem(), "'Party supplies' was paid by organization: it needs no payment.");
        final PaymentForm gone = payment(LocalDate.of(2026, 10, 21), 5.0, "Cash", "no-such-line");
        assertFalse(admin.recordPayment(id, gone));
        assertEquals(gone.getProblem(), "An expense you ticked no longer exists: reload the page.");

        // Edit the first payment: its stamp stays, its lines change.
        final PaymentForm edit = admin.paymentFormFor(id, first.getId());
        assertEquals(edit.getLineIds(), List.of(guide, tolls));
        assertEquals(edit.getAmount(), 1_000.0);
        edit.setLineIds(new ArrayList<>(List.of(guide, guide, "")));
        final ExpensePayment before = stored(id).payment(first.getId());
        assertTrue(admin.recordPayment(id, edit), edit.getProblem());
        final ExpensePayment after = stored(id).payment(first.getId());
        assertEquals(after.getLineIds(), List.of(guide), "Duplicates and blanks dropped");
        assertEquals(after.getRecordedOn(), before.getRecordedOn());
        assertEquals(after.getRecordedById(), TestCallers.SITE_ADMIN_ID);
        assertEquals(admin.paymentFormsFor(id).size(), 1);

        // The rest of the expenses and the balance in a second payment: Paid, Settled.
        final List<String> rest = new ArrayList<>();
        for (final Choice choice : admin.unpaidLineChoices(id, null)) {
            rest.add(choice.getId());
        }
        final PaymentForm second = payment(LocalDate.of(2026, 10, 22), null, "Check 2002");
        second.setLineIds(rest);
        page = admin.reportPage(id);
        second.setAmount(Double.parseDouble(page.getStillDue().replace("$", "").replace(",", "")));
        assertTrue(admin.recordPayment(id, second), second.getProblem());
        page = admin.reportPage(id);
        assertEquals(page.getStatus(), "Paid");
        assertTrue(page.isSettled());
        assertEquals(page.getStillDueLabel(), "Settled");
        assertEquals(page.getStillDue(), "$0.00");
        assertEquals(page.getUnpaidCount(), 0);

        // Deleting a payment unpays its lines.
        assertTrue(admin.deletePayment(id, second.getId()));
        assertTrue(admin.deletePayment(id, second.getId()), "Already gone");
        assertEquals(admin.reportPage(id).getStatus(), "Partly paid");
        assertFalse(admin.deleteLine(id, guide), "Still in the first payment");
        assertTrue(admin.deletePayment(id, first.getId()));
        assertTrue(admin.deleteLine(id, guide), "Unpaid again, so deletable");
        assertEquals(admin.paymentFormFor(id, first.getId()).getId(), null, "A gone payment: a fresh form");
    }

    @Test
    public void aPaymentMayRunEitherWayAndCoverNoLine() throws IOException {
        final String id = newTrip().getId();
        assertTrue(admin.saveHeader(id, header("Ken")));
        assertTrue(admin.saveAdvance(id, advance("CASH", 2_000.0, null)));
        assertTrue(admin.saveLine(id, line(null, null, "Hotel", "USD", 1_500.0)));
        final ExpensePage due = admin.reportPage(id);
        assertEquals(due.getDue(), "$500.00");
        assertFalse(due.isDueToSubmitter(), "The advance was larger: the submitter owes the organization");
        final PaymentForm back = payment(LocalDate.of(2026, 10, 20), -500.0, "Zelle to the office");
        back.setNote("Unused advance returned");
        assertTrue(admin.recordPayment(id, back), back.getProblem());
        final ExpensePage page = admin.reportPage(id);
        assertTrue(page.getPaymentRows().get(0).isNegative());
        assertEquals(page.getPaymentRows().get(0).getAmount(), "-$500.00");
        assertEquals(page.getPaymentRows().get(0).getLines(), ExpenseCommands.NO_LINES);
        assertEquals(page.getStillDueLabel(), "Settled");
        assertEquals(page.getStatus(), "Partly paid", "The hotel is still an unpaid expense");
        final PaymentForm zero = payment(LocalDate.of(2026, 10, 20), 0.0, "Netted against the advance",
                stored(id).getLines().get(0).getId());
        assertTrue(admin.recordPayment(id, zero), zero.getProblem());
        assertEquals(admin.reportPage(id).getStatus(), "Paid");
    }

    @Test
    public void paymentsAreChecked() throws IOException {
        final String id = newTrip().getId();
        final PaymentForm noDate = payment(null, 1.0, "Zelle");
        assertFalse(admin.recordPayment(id, noDate));
        assertEquals(noDate.getProblem(), "Enter the date it was paid.");
        final PaymentForm noAmount = payment(LocalDate.of(2026, 10, 20), null, "Zelle");
        assertFalse(admin.recordPayment(id, noAmount));
        assertEquals(noAmount.getProblem(), "The amount must be between -$10,000,000.00 and $10,000,000.00.");
        assertFalse(admin.recordPayment(id, payment(LocalDate.of(2026, 10, 20), -10_000_001.0, "Zelle")));
        assertFalse(admin.recordPayment(id, payment(LocalDate.of(2026, 10, 20), Double.NaN, "Zelle")));
        final PaymentForm noMethod = payment(LocalDate.of(2026, 10, 20), 1.0, " ");
        assertFalse(admin.recordPayment(id, noMethod));
        assertEquals(noMethod.getProblem(), "Enter how it was paid (Zelle, check 1042, cash...).");
        assertFalse(admin.recordPayment(id, payment(LocalDate.of(2026, 10, 20), 1.0,
                "m".repeat(ExpenseCommands.MAX_NAME + 1))));
        final PaymentForm longNote = payment(LocalDate.of(2026, 10, 20), 1.0, "Zelle");
        longNote.setNote("n".repeat(ExpenseCommands.MAX_NOTE + 1));
        assertFalse(admin.recordPayment(id, longNote));
        assertEquals(longNote.getProblem(), "A note is at most 500 characters.");
        final PaymentForm stale = payment(LocalDate.of(2026, 10, 20), 1.0, "Zelle");
        stale.setId("gone");
        assertFalse(admin.recordPayment(id, stale));
        assertEquals(stale.getProblem(), "This payment no longer exists: reload the page.");

        final TripExpenseReport report = TripExpenseReport.builder().tripId(id).orgId(orgId).build();
        for (int i = 0; i < ExpenseCommands.MAX_PAYMENTS; i++) {
            report.getPayments().add(ExpensePayment.builder().amountCents(1L).method("x").build());
        }
        assertTrue(DAO.getInstance().saveTripExpenseReport(report));
        final PaymentForm tooMany = payment(LocalDate.of(2026, 10, 20), 1.0, "Zelle");
        assertFalse(admin.recordPayment(id, tooMany));
        assertEquals(tooMany.getProblem(), "A report holds at most 100 payments.");
        admin.prefillAmount(id, null);
        final PaymentForm stranger = payment(null, null, null, "x");
        as(Person.Id.newInstance()).prefillAmount(id, stranger);
        assertNull(stranger.getAmount(), "A stranger gets no figure");
    }

    // ------------------------------------------------------------------ the sheet

    @Test
    public void theSheetRendersToTheCent() throws IOException {
        final Trip trip = newTrip();
        final String id = trip.getId();
        assertEquals(admin.expenseHeadline(id), ExpenseCommands.NO_EXPENSES);
        saveSheet(id);
        final ExpensePage page = admin.reportPage(id);
        assertTrue(page.isExists());
        assertEquals(page.getTripTitle(), "Holy Angels");
        assertEquals(page.getTripDates(), "Sep 20, 2026 to Oct 5, 2026");
        assertEquals(page.getOrgName(), orgName);
        assertEquals(page.getCashAdvance(), "$45,765.00");
        assertEquals(page.getTotal(), "$55,560.95");
        assertTrue(page.isDueToSubmitter());
        assertEquals(page.getDue(), "$9,795.95", "Unsigned beside its label");
        assertEquals(page.getDueLabel(), "Due to Ken Paulsen");
        assertEquals(admin.expenseHeadline(id), "$55,560.95 spent");
        assertEquals(page.getStatus(), "Draft");
        assertEquals(page.getStatusKey(), "draft");
        assertEquals(page.getLinesWithoutReceipt(), 18);
        assertTrue(page.getWarnings().isEmpty());

        final ExpenseRow candles = row(page, "Candles for consecration");
        assertEquals(candles.getAmountText(), "23.00 EUR");
        assertEquals(candles.getUsd(), "$27.71");
        assertTrue(candles.isForeign());
        assertEquals(candles.getDateText(), "Sep 25, 2026");
        assertEquals(candles.getCategory(), "Supplies");
        assertEquals(row(page, "Rental Van").getUsd(), "$481.93");
        assertEquals(row(page, "Tolls").getDateText(), ExpenseCommands.VARIOUS);
        assertEquals(row(page, "Guide").getAmountText(), "$1,440.00");
        assertTrue(page.getLineRows().get(0).isFirst());
        assertTrue(page.getLineRows().get(17).isLast());

        final CurrencyRow eur = page.getCurrencyRows().get(0);
        assertEquals(eur.getCode(), "EUR");
        assertEquals(eur.getRateText(), "1 USD = 0.83 EUR");
        assertEquals(eur.getInverseText(), "1 EUR = $1.2048");
        assertEquals(eur.getLines(), 3);
        assertEquals(eur.getForeignTotal(), "283.00 EUR");
        assertEquals(eur.getUsdTotal(), "$340.96");
        assertEquals(page.getCurrencyRows().get(1).getCode(), "BAM", "Typed lower case, stored upper");

        // Fine-tuning a rate re-prices its lines and the due figure.
        final CurrencyForm tuned = admin.currencyFormFor(id, "EUR");
        assertTrue(tuned.isExisting());
        assertEquals(tuned.getRate(), 0.83);
        tuned.setRate(0.85);
        assertTrue(admin.saveCurrency(id, tuned));
        assertEquals(row(admin.reportPage(id), "Gradnici donation").getUsd(), "$258.82");
    }

    @Test
    public void aSurplusIsDueToTheOrganization() throws IOException {
        final String id = newTrip().getId();
        assertTrue(admin.saveAdvance(id, advance("CASH", 100.0, null)));
        assertTrue(admin.saveLine(id, line(null, null, "Postage", "USD", 40.0)));
        final ExpensePage page = admin.reportPage(id);
        assertFalse(page.isDueToSubmitter());
        assertEquals(page.getDue(), "$60.00");
        assertEquals(page.getDueLabel(), "Due to " + orgName);
        assertEquals(row(page, "Postage").getCategory(), "Other", "A blank category with no match is Other");
    }

    // ------------------------------------------------------------------ the header and submitting

    @Test
    public void theHeaderSavesAndRefuses() throws IOException {
        final String id = newTrip().getId();
        final HeaderForm form = admin.headerFormFor(id);
        assertNull(form.getPaymentInstructions());
        form.setSubmittedBy("  Ken  ");
        form.setPaymentInstructions("Zelle the balance");
        assertTrue(admin.saveHeader(id, form));
        final TripExpenseReport report = stored(id);
        assertEquals(report.getSubmittedBy(), "Ken");
        assertEquals(report.getOrgId(), orgId);
        assertNotNull(report.getCreated());
        assertEquals(report.getStatus(), ExpenseStatus.DRAFT);
        assertEquals(admin.headerFormFor(id).getPaymentInstructions(), "Zelle the balance");

        final HeaderForm bad = header("n".repeat(ExpenseCommands.MAX_NAME + 1));
        assertFalse(admin.saveHeader(id, bad));
        assertEquals(bad.getProblem(), "The name is at most 120 characters.");
        final HeaderForm longInstructions = header("x");
        longInstructions.setPaymentInstructions("i".repeat(ExpenseCommands.MAX_TEXT + 1));
        assertFalse(admin.saveHeader(id, longInstructions));
        assertEquals(longInstructions.getProblem(), "The payment instructions is at most 2000 characters.");
        assertFalse(admin.saveHeader(id, null));
    }

    @Test
    public void submittingStampsAndWithdraws() throws IOException {
        final String id = newTrip().getId();
        assertFalse(admin.submit(id), "Nothing to submit");
        assertTrue(admin.unsubmit(id), "Not submitted: nothing to withdraw");
        assertTrue(admin.saveLine(id, line(LocalDate.of(2026, 9, 29), "GUIDE", "Guide", "USD", 1440.0)));
        final TripExpenseReport created = stored(id);
        created.setSubmittedBy(null);
        assertTrue(DAO.getInstance().saveTripExpenseReport(created));
        assertTrue(admin.submit(id));
        final TripExpenseReport report = stored(id);
        assertEquals(report.getStatus(), ExpenseStatus.SUBMITTED);
        assertEquals(report.getSubmittedById(), TestCallers.SITE_ADMIN_ID);
        final ExpensePage page = admin.reportPage(id);
        assertTrue(page.isSubmitted());
        assertTrue(page.getSubmittedLine().startsWith("Submitted by (no name) on "), page.getSubmittedLine());
        assertTrue(admin.unsubmit(id));
        assertEquals(stored(id).getStatus(), ExpenseStatus.DRAFT);
        assertNull(stored(id).getSubmittedById());
    }

    // ------------------------------------------------------------------ currencies

    @Test
    public void currenciesAreCheckedAndGuarded() throws IOException {
        final String id = newTrip().getId();
        final CurrencyForm usd = currency("USD", 1.0);
        assertFalse(admin.saveCurrency(id, usd));
        assertEquals(usd.getProblem(), "Dollars need no rate.");
        final CurrencyForm bad = currency("EURO", 0.8);
        assertFalse(admin.saveCurrency(id, bad));
        assertEquals(bad.getProblem(), "A currency code is three letters, like EUR.");
        final CurrencyForm zero = currency("EUR", 0.0);
        assertFalse(admin.saveCurrency(id, zero));
        assertEquals(zero.getProblem(), "Enter the rate: how many EUR one US dollar buys (more than 0).");
        assertFalse(admin.saveCurrency(id, currency("EUR", Double.NaN)));
        assertFalse(admin.saveCurrency(id, currency("EUR", 2_000_000.0)), "Over the sanity cap");
        final CurrencyForm longNote = currency("EUR", 0.83);
        longNote.setNote("n".repeat(ExpenseCommands.MAX_NOTE + 1));
        assertFalse(admin.saveCurrency(id, longNote));
        final CurrencyForm eur = currency("EUR", 0.83);
        eur.setNote("Revolut, Sep 20");
        assertTrue(admin.saveCurrency(id, eur));
        assertTrue(eur.isExisting());
        final CurrencyForm again = currency("EUR", 0.9);
        assertFalse(admin.saveCurrency(id, again));
        assertEquals(again.getProblem(), "EUR is already on the report: edit its rate instead.");
        final CurrencyForm gone = currency("GBP", 0.75);
        gone.setExisting(true);
        assertFalse(admin.saveCurrency(id, gone));
        assertEquals(gone.getProblem(), "This currency is no longer on the report: reload the page.");
        assertFalse(admin.saveCurrency(id, null));
        assertEquals(admin.currencyFormsFor(id).size(), 1);
        assertEquals(admin.currencyFormsFor(id).get(0).getNote(), "Revolut, Sep 20");
        assertNull(admin.currencyFormFor(id, "GBP").getCode(), "Unknown: a fresh form");
        assertFalse(admin.newCurrencyForm().isExisting());

        final List<Choice> choices = admin.currencyChoices(id);
        assertEquals(choices.get(0).getId(), "USD");
        assertEquals(choices.get(1).getId(), "EUR");
        assertEquals(choices.get(2).getId(), ExpenseViews.NEW_CURRENCY);

        assertTrue(admin.saveLine(id, line(null, "TIPS", "Tips", "EUR", 40.0)));
        assertFalse(admin.deleteCurrency(id, "EUR"), "In use");
        assertTrue(admin.deleteCurrency(id, "GBP"), "Already gone");
        final String lineId = stored(id).getLines().get(0).getId();
        assertTrue(admin.deleteLine(id, lineId));
        assertTrue(admin.deleteCurrency(id, "EUR"));
        assertTrue(stored(id).getCurrencies().isEmpty());
    }

    @Test
    public void aReportHoldsAtMostTwentyCurrencies() throws IOException {
        final String id = newTrip().getId();
        for (int i = 0; i < ExpenseCommands.MAX_CURRENCIES; i++) {
            assertTrue(admin.saveCurrency(id, currency("Q" + (char) ('A' + i) + "Z", 1.5)));
        }
        final CurrencyForm one = currency("ZZZ", 1.5);
        assertFalse(admin.saveCurrency(id, one));
        assertEquals(one.getProblem(), "A report holds at most 20 currencies.");
        final ExpenseForm adding = line(null, null, "x", ExpenseViews.NEW_CURRENCY, 1.0);
        adding.setNewCode("ZZZ");
        adding.setNewRate(1.5);
        assertFalse(admin.saveLine(id, adding));
        assertEquals(adding.getProblem(), "A report holds at most 20 currencies.");
    }

    @Test
    public void aCurrencyIsAddedFromTheExpenseDialog() throws IOException {
        final String id = newTrip().getId();
        final ExpenseForm form = line(LocalDate.of(2026, 10, 4), "TRANSPORTATION", "Rental Van",
                ExpenseViews.NEW_CURRENCY, 800.0);
        assertTrue(form.isAddingCurrency());
        form.setNewCode("bam");
        form.setNewRate(1.66);
        assertTrue(admin.saveLine(id, form), form.getProblem());
        assertEquals(form.getCurrency(), "BAM");
        assertNull(form.getNewCode());
        assertEquals(stored(id).currency("BAM").getPerUsdMicros(), 1_660_000L);
        assertEquals(row(admin.reportPage(id), "Rental Van").getUsd(), "$481.93");

        final ExpenseForm existing = line(null, null, "Gas", ExpenseViews.NEW_CURRENCY, 20.0);
        existing.setNewCode("BAM");
        existing.setNewRate(9.0);
        assertTrue(admin.saveLine(id, existing), "A code the report has is just used");
        assertEquals(stored(id).currency("BAM").getPerUsdMicros(), 1_660_000L, "...without changing its rate");
        final ExpenseForm dollars = line(null, null, "Snacks", ExpenseViews.NEW_CURRENCY, 5.0);
        dollars.setNewCode("usd");
        assertTrue(admin.saveLine(id, dollars));
        assertEquals(dollars.getCurrency(), "USD");

        final ExpenseForm noRate = line(null, null, "Bread", ExpenseViews.NEW_CURRENCY, 5.0);
        noRate.setNewCode("HRK");
        assertFalse(admin.saveLine(id, noRate));
        assertEquals(noRate.getProblem(), "Enter the rate: how many HRK one US dollar buys (more than 0).");
    }

    // ------------------------------------------------------------------ expenses

    @Test
    public void expensesAreCheckedAndEditedByIdentity() throws IOException {
        final String id = newTrip().getId();
        final ExpenseForm blank = line(null, null, " ", "USD", 1.0);
        assertFalse(admin.saveLine(id, blank));
        assertEquals(blank.getProblem(), "An expense needs a description.");
        final ExpenseForm badCategory = line(null, "NOPE", "x", "USD", 1.0);
        assertFalse(admin.saveLine(id, badCategory));
        assertEquals(badCategory.getProblem(), "Choose a category.");
        final ExpenseForm noAmount = line(null, null, "x", "USD", null);
        assertFalse(admin.saveLine(id, noAmount));
        assertEquals(noAmount.getProblem(), "Enter the amount, in the currency it was paid in.");
        assertFalse(admin.saveLine(id, line(null, null, "x", "USD", -1.0)));
        assertFalse(admin.saveLine(id, line(null, null, "x", "USD", 2e9)));
        assertFalse(admin.saveLine(id, line(null, null, "d".repeat(ExpenseCommands.MAX_DESCRIPTION + 1), "USD",
                1.0)));
        final ExpenseForm longNote = line(null, null, "x", "USD", 1.0);
        longNote.setNote("n".repeat(ExpenseCommands.MAX_NOTE + 1));
        assertFalse(admin.saveLine(id, longNote));
        final ExpenseForm unknown = line(null, null, "x", "EUR", 1.0);
        assertFalse(admin.saveLine(id, unknown));
        assertEquals(unknown.getProblem(), "Choose a currency the report has, or add one.");
        assertFalse(admin.saveLine(id, null));

        final ExpenseForm a = line(LocalDate.of(2026, 9, 20), "TRANSPORTATION", "Van", "USD", 255.83);
        a.setNote("PDX to SEA");
        assertTrue(admin.saveLine(id, a));
        assertNotNull(a.getId());
        assertTrue(admin.saveLine(id, line(null, "MEALS", "Dinner", "USD", 100.0)));
        assertTrue(admin.saveLine(id, line(null, "POSTAGE", "Stamps", "USD", 10.0)));
        final ExpenseForm edit = admin.lineFormFor(id, a.getId());
        assertEquals(edit.getNote(), "PDX to SEA");
        assertEquals(edit.getAmount(), 255.83);
        edit.setAmount(260.0);
        assertTrue(admin.saveLine(id, edit));
        assertEquals(stored(id).line(a.getId()).getAmountMinor(), 26_000L);
        assertEquals(stored(id).getLines().size(), 3, "An edit replaces, never adds");
        assertEquals(admin.lineFormsFor(id).size(), 3);
        assertNull(admin.lineFormFor(id, "gone").getId());
        assertEquals(admin.newLineForm().getCurrency(), "USD");

        final ExpenseForm gone = line(null, null, "x", "USD", 1.0);
        gone.setId("gone");
        assertFalse(admin.saveLine(id, gone));
        assertEquals(gone.getProblem(), "This expense no longer exists: reload the page.");

        // Moves act on the clicked line's id, whatever position it holds now.
        final String dinner = stored(id).getLines().get(1).getId();
        assertTrue(admin.moveLine(id, dinner, -1));
        assertEquals(stored(id).getLines().get(0).getId(), dinner);
        assertTrue(admin.moveLine(id, dinner, -1), "Already first: nothing to do");
        assertTrue(admin.moveLine(id, dinner, 1));
        assertEquals(stored(id).getLines().get(1).getId(), dinner);
        assertFalse(admin.moveLine(id, "gone", 1));
        assertTrue(admin.deleteLine(id, dinner));
        assertTrue(admin.deleteLine(id, dinner), "Already gone");
        assertEquals(stored(id).getLines().size(), 2);
        assertFalse(as(Person.Id.newInstance()).deleteLine(id, a.getId()));
    }

    @Test
    public void aForeignLineWithoutARateWarnsAndCountsNothing() throws IOException {
        final String id = newTrip().getId();
        assertTrue(admin.saveCurrency(id, currency("EUR", 0.83)));
        assertTrue(admin.saveLine(id, line(null, null, "Candles", "EUR", 23.0)));
        final TripExpenseReport report = stored(id);
        report.currency("EUR").setPerUsdMicros(0L);
        assertTrue(DAO.getInstance().saveTripExpenseReport(report));
        final ExpensePage page = admin.reportPage(id);
        assertEquals(page.getTotal(), "$0.00");
        assertTrue(row(page, "Candles").isRateMissing());
        assertTrue(page.getCurrencyRows().get(0).isRateMissing());
        assertEquals(page.getCurrencyRows().get(0).getInverseText(), "no rate");
        assertEquals(page.getWarnings().size(), 1);
        assertTrue(page.getWarnings().get(0).startsWith("The expenses in EUR have no exchange rate"));
    }

    // ------------------------------------------------------------------ the budget

    @Test
    public void everyBudgetLineShowsWhatRemains() throws IOException {
        final Trip trip = newTrip();
        final String id = trip.getId();
        assertFalse(admin.reportPage(id).isHasBudget());
        assertEquals(admin.reportPage(id).getBudgetRows().size(), 1, "Only the Not in budget row");

        final Accommodation hotel = Accommodation.builder().name("Pansion " + RandomData.genAlpha(5)).build();
        assertTrue(DAO.getInstance().saveAccommodation(hotel));
        final RateSchedule perRoom = RateSchedule.builder().pricingModel(PricingModel.PER_ROOM).nightlyCents(50_00)
                .build();
        final ReservationOffer offer = ReservationOffer.builder().tripId(id).name("Double")
                .accommodationId(hotel.getId()).price(perRoom).cost(perRoom).build();
        assertTrue(DAO.getInstance().saveReservationOffer(offer));
        final BudgetLine guide = BudgetLine.builder().name("Guide").category(BudgetCategory.GUIDE)
                .amountCents(144_000L).build();
        final BudgetLine badge = BudgetLine.builder().name("Badge").kind(BudgetLineKind.INDIVIDUAL)
                .amountCents(1_000L).build();
        final BudgetLodgingLine rooms = BudgetLodgingLine.builder().offerId(offer.getId().getValue())
                .label("Pansion · Double").nights(10).people(4).rooms(2).build();
        final TripBudget budget = TripBudget.builder().tripId(id).orgId(orgId).people(46).tripNights(10)
                .lines(List.of(guide, badge)).lodging(List.of(rooms)).build();
        assertTrue(DAO.getInstance().saveTripBudget(budget));

        final List<Choice> choices = admin.budgetChoices(id);
        assertEquals(choices.size(), 3);
        assertEquals(choices.get(0).getLabel(), "Shared: Guide ($1,440.00)");
        assertEquals(choices.get(1).getLabel(), "Per person: Badge ($460.00)");
        assertTrue(choices.get(2).getLabel().startsWith("Lodging: " + hotel.getName()), choices.get(2).getLabel());
        assertTrue(choices.get(2).getLabel().endsWith("($1,000.00)"), "The hotel cost: 2 rooms x 10 nights x $50");

        // Choosing a budget line in the dialog sets the category to match.
        final ExpenseForm guideForm = line(null, "GENERAL", "Marija", "USD", 1_500.0);
        guideForm.setBudgetLineId(guide.getId());
        admin.pickBudgetLine(id, guideForm);
        assertEquals(guideForm.getCategory(), "GUIDE");
        assertTrue(admin.saveLine(id, guideForm));
        final ExpenseForm roomForm = line(null, null, "Pansion", "USD", 900.0);
        roomForm.setBudgetLineId(rooms.getId());
        assertTrue(admin.saveLine(id, roomForm));
        assertEquals(roomForm.getCategory(), "LODGING", "A blank category takes the match's");
        final ExpenseForm badgeForm = line(null, null, "Badges", "USD", 400.0);
        badgeForm.setBudgetLineId(badge.getId());
        admin.pickBudgetLine(id, badgeForm);
        assertEquals(badgeForm.getCategory(), "GENERAL", "A per-person line has no category of its own");
        assertTrue(admin.saveLine(id, badgeForm));
        assertTrue(admin.saveLine(id, line(null, "MEALS", "Dinner", "USD", 50.0)));
        final ExpenseForm stale = line(null, null, "x", "USD", 1.0);
        stale.setBudgetLineId("gone");
        assertFalse(admin.saveLine(id, stale));
        assertEquals(stale.getProblem(), "That budget line no longer exists: choose another.");
        admin.pickBudgetLine(id, null);
        admin.pickBudgetLine(id, stale);
        assertNull(stale.getCategory(), "An unknown line leaves the category alone");

        final ExpensePage page = admin.reportPage(id);
        assertTrue(page.isHasBudget());
        final List<BudgetRow> rows = page.getBudgetRows();
        assertEquals(rows.size(), 4);
        assertEquals(rows.get(0).getSection(), "Shared");
        assertEquals(rows.get(0).getCategory(), "Guide");
        assertEquals(rows.get(0).getSpent(), "$1,500.00");
        assertEquals(rows.get(0).getRemaining(), "-$60.00");
        assertTrue(rows.get(0).isRemainingNegative());
        assertEquals(rows.get(1).getSection(), "Per person");
        assertEquals(rows.get(1).getBudget(), "$460.00");
        assertEquals(rows.get(1).getRemaining(), "$60.00");
        assertFalse(rows.get(1).isRemainingNegative());
        assertEquals(rows.get(2).getSection(), "Lodging");
        assertEquals(rows.get(2).getCategory(), "Lodging");
        assertEquals(rows.get(2).getRemaining(), "$100.00");
        assertTrue(rows.get(3).isNotInBudget());
        assertEquals(rows.get(3).getSpent(), "$50.00");
        assertEquals(page.getBudgetTotal(), "$2,900.00");
        assertEquals(page.getSpentTotal(), "$2,850.00", "Adds up to the report's total");
        assertEquals(page.getSpentTotal(), page.getTotal());
        assertEquals(page.getRemainingTotal(), "$50.00");
        assertEquals(row(page, "Marija").getBudgetLabel(), "Guide");
        assertTrue(row(page, "Pansion").getBudgetLabel().startsWith(hotel.getName()));
        assertNull(row(page, "Dinner").getBudgetLabel());

        // A budget line deleted later: its expenses fall to Not in budget and say so.
        budget.setLines(new ArrayList<>(List.of(badge)));
        assertTrue(DAO.getInstance().saveTripBudget(budget));
        final ExpensePage after = admin.reportPage(id);
        assertEquals(row(after, "Marija").getBudgetLabel(), ExpenseCommands.DELETED_LINE);
        assertEquals(after.getBudgetRows().get(after.getBudgetRows().size() - 1).getSpent(), "$1,550.00");
        assertEquals(ExpenseCommands.categoryOf(budget, null), null);
        assertEquals(ExpenseCommands.categoryOf(null, "x"), null);
    }

    // ------------------------------------------------------------------ receipts (links only)

    @Test
    public void receiptLinksAreCappedAndFound() throws IOException {
        final String id = newTrip().getId();
        assertTrue(admin.saveLine(id, line(null, null, "Guide", "USD", 1_440.0)));
        final String lineId = stored(id).getLines().get(0).getId();
        final List<String> problems = new ArrayList<>();
        assertTrue(admin.attachReceipt(id, lineId, "r-line", problems::add));
        assertTrue(admin.attachReceipt(id, null, "r-report", problems::add));
        assertFalse(admin.attachReceipt(id, "gone", "r-x", problems::add));
        assertEquals(problems, List.of("This expense no longer exists: reload the page."));
        assertEquals(stored(id).line(lineId).getReceiptIds(), List.of("r-line"));
        assertEquals(stored(id).getReceiptIds(), List.of("r-report"));
        assertEquals(admin.reportPage(id).getLinesWithoutReceipt(), 0);
        assertEquals(admin.reportPage(id).getLineRows().get(0).getReceiptCount(), 1);
        assertTrue(admin.receiptRowsFor(id, lineId).isEmpty(), "No media row: nothing to list");
        assertTrue(admin.receiptRowsFor(id, "gone").isEmpty());
        assertEquals(admin.receiptHeading(id, null), "Report receipts");
        assertEquals(admin.receiptHeading(id, lineId), "Receipts: Guide");

        assertTrue(admin.detachReceipt(id, "r-line", problems::add));
        assertTrue(admin.detachReceipt(id, "r-report", problems::add));
        assertFalse(admin.detachReceipt(id, "r-none", problems::add));
        assertEquals(problems.get(problems.size() - 1), "That receipt is not on this report.");
        assertTrue(stored(id).allReceiptIds().isEmpty());

        for (int i = 0; i < ExpenseCommands.MAX_RECEIPTS_PER_LINE; i++) {
            assertTrue(admin.attachReceipt(id, lineId, "r" + i, problems::add));
        }
        assertFalse(admin.attachReceipt(id, lineId, "one-more", problems::add));
        for (int i = 0; i < ExpenseCommands.MAX_REPORT_RECEIPTS; i++) {
            assertTrue(admin.attachReceipt(id, "", "q" + i, problems::add));
        }
        assertFalse(admin.attachReceipt(id, "", "one-more", problems::add));
        assertEquals(problems.get(problems.size() - 1), "A report holds at most 60 report-level receipts.");
    }

    // ------------------------------------------------------------------ the treasurer

    private void treasurer(final String email) throws IOException {
        final Organization org = DAO.getInstance().getOrganization(Organization.Id.from(orgId), Cached.NO)
                .orElseThrow();
        if (email == null) {
            org.getSettingsOverrides().remove(KnownSettings.FINANCE_TREASURER_EMAIL.getName());
        } else {
            org.getSettingsOverrides().put(KnownSettings.FINANCE_TREASURER_EMAIL.getName(), email);
        }
        assertTrue(DAO.getInstance().saveOrganization(org));
    }

    @Test
    public void submittingCanEmailTheTreasurerThePdf() throws IOException {
        final String id = newTrip().getId();
        assertTrue(admin.saveLine(id, line(null, "GUIDE", "Guide", "USD", 1_440.0)));
        final MailCommands mail = Mockito.mock(MailCommands.class);
        final ExpenseCommands bean = new ExpenseCommands(TestCallers::siteAdmin);
        bean.mailForTest(mail);
        try {
            treasurer(null);
            assertNull(bean.reportPage(id).getTreasurerEmail(), "no treasurer set anywhere");
            assertFalse(bean.hasTreasurer(id));
            assertFalse(bean.emailTreasurer(id), "nothing to send to");
            assertTrue(bean.submit(id, false), "submitting alone never mails");
            Mockito.verifyNoInteractions(mail);

            treasurer("treasurer@example.org");
            assertEquals(bean.reportPage(id).getTreasurerEmail(), "treasurer@example.org");
            assertTrue(bean.hasTreasurer(id));
            assertFalse(as(Person.Id.newInstance()).hasTreasurer(id), "only for the report's readers");
            Mockito.when(mail.sendWithAttachment(ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);
            assertTrue(bean.submit(id, true));
            final ArgumentCaptor<byte[]> pdf = ArgumentCaptor.forClass(byte[].class);
            final ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            Mockito.verify(mail).sendWithAttachment(ArgumentMatchers.any(),
                    ArgumentMatchers.eq("treasurer@example.org"),
                    ArgumentMatchers.any(), ArgumentMatchers.eq("Expense report: Holy Angels"), body.capture(),
                    ArgumentMatchers.eq("Holy Angels - Expense Report.pdf"), pdf.capture(),
                    ArgumentMatchers.eq("application/pdf"), ArgumentMatchers.any());
            assertTrue(new String(pdf.getValue(), 0, 5, java.nio.charset.StandardCharsets.US_ASCII)
                    .equals("%PDF-"), "the attachment is a PDF");
            assertTrue(body.getValue().contains("<b>Holy Angels</b>") && body.getValue().contains("$1,440.00"),
                    body.getValue());
            assertTrue(body.getValue().contains("Cash advanced: $"), body.getValue());
            assertTrue(body.getValue().contains("with its receipts after it"), body.getValue());
            assertFalse(body.getValue().contains("Payments recorded"), "No payments yet");
            assertEquals(stored(id).getEmailedTo(), "treasurer@example.org");
            assertTrue(bean.reportPage(id).getEmailedLine().startsWith("Emailed to treasurer@example.org on "));

            Mockito.when(mail.sendWithAttachment(ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(false);
            assertFalse(bean.emailTreasurer(id), "a failed send says so");
            final ExpenseForm hotel = line(null, null, "Hotel", "USD", 100.0);
            hotel.setPaidStatus("ORG");
            assertTrue(admin.saveLine(id, hotel));
            assertTrue(admin.recordPayment(id, payment(LocalDate.of(2026, 10, 20), 1_440.0, "Zelle")));
            final ArgumentCaptor<String> paidBody = ArgumentCaptor.forClass(String.class);
            Mockito.when(mail.sendWithAttachment(ArgumentMatchers.any(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), paidBody.capture(), ArgumentMatchers.any(),
                    ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);
            assertTrue(bean.emailTreasurer(id));
            assertTrue(paidBody.getValue().contains("Paid by organization: $100.00"), paidBody.getValue());
            assertTrue(paidBody.getValue().contains("Payments recorded: $1,440.00<br/><b>Settled</b>"),
                    paidBody.getValue());
            assertFalse(as(Person.Id.newInstance()).emailTreasurer(id), "a stranger emails nothing");
        } finally {
            treasurer(null);
        }
    }

    @Test
    public void theTreasurerSettingMustBeAnAddress() throws IOException {
        final String id = newTrip().getId();
        try {
            treasurer("not an address");
            assertNull(admin.reportPage(id).getTreasurerEmail(), "an unusable stored value counts as none");
        } finally {
            treasurer(null);
        }
    }


    // ------------------------------------------------------------------ the cash-advance worksheet

    @Test
    public void theWorksheetAddsUpSignedAndPrices() throws IOException {
        final String id = newTrip().getId();
        final AdvanceForm cash = admin.newAdvanceForm(id, "CASH");
        assertEquals(cash.getParty(), orgName, "the organization usually gives the advance");
        assertEquals(admin.newAdvanceForm(id, "RETURNED").getParty(), orgName);
        assertNull(admin.newAdvanceForm(id, "CREDIT").getParty());
        assertEquals(admin.newAdvanceForm(id, "bogus").getType(), "OTHER");
        cash.setAmount(1000.0);
        cash.setDate(LocalDate.of(2026, 9, 19));
        assertTrue(admin.saveAdvance(id, cash), cash.getProblem());
        assertNotNull(cash.getId());
        assertTrue(admin.saveAdvance(id, advance("CREDIT", 200.0, "previous trip")));
        final AdvanceForm euros = advance("OTHER", 83.0, "ignored for Other");
        euros.setCurrency(ExpenseViews.NEW_CURRENCY);
        euros.setNewCode("eur");
        euros.setNewRate(0.83);
        euros.setNote("Left in the envelope");
        assertTrue(admin.saveAdvance(id, euros), euros.getProblem());
        assertEquals(euros.getCurrency(), "EUR");
        assertNull(euros.getNewCode());
        assertTrue(admin.saveAdvance(id, advance("RETURNED", 300.0, orgName)));

        final ExpensePage page = admin.reportPage(id);
        assertEquals(page.getAdvanceCount(), 4);
        assertEquals(page.getCashAdvance(), "$1,000.00", "1,000 + 200 + 100 - 300");
        assertFalse(page.isAdvanceNegative());
        final List<AdvanceRow> rows = page.getAdvanceRows();
        assertEquals(rows.get(0).getNumber(), 1);
        assertEquals(rows.get(0).getTypeLabel(), "Cash advance");
        assertEquals(rows.get(0).getParty(), orgName);
        assertEquals(rows.get(0).getDateText(), "Sep 19, 2026");
        assertEquals(rows.get(0).getAmount(), "$1,000.00");
        assertEquals(rows.get(0).getUsd(), "$1,000.00");
        assertEquals(rows.get(1).getParty(), "previous trip");
        assertEquals(rows.get(2).getAmount(), "83.00 EUR");
        assertEquals(rows.get(2).getUsd(), "$100.00");
        assertEquals(rows.get(2).getDateText(), "");
        assertNull(rows.get(2).getParty(), "Other asks no 'who'");
        assertEquals(rows.get(2).getNote(), "Left in the envelope");
        assertNull(rows.get(2).getTransactionLine());
        assertEquals(rows.get(3).getNumber(), 4);
        assertEquals(rows.get(3).getUsd(), "-$300.00");
        assertTrue(rows.get(3).isNegative());
        assertEquals(page.getDueLabel(), "Due to " + orgName);
        assertEquals(page.getDue(), "$1,000.00");
        assertEquals(page.getCurrencyRows().get(0).getCode(), "EUR", "the dialog added the currency");

        final AdvanceForm edit = admin.advanceFormFor(id, rows.get(1).getId());
        assertEquals(edit.getParty(), "previous trip");
        assertEquals(edit.getType(), "CREDIT");
        edit.setAmount(250.0);
        assertTrue(admin.saveAdvance(id, edit));
        assertEquals(admin.reportPage(id).getAdvanceRows().get(1).getUsd(), "$250.00");
        assertEquals(admin.reportPage(id).getCashAdvance(), "$1,050.00");
        assertEquals(admin.advanceFormsFor(id).size(), 4);
        assertFalse(admin.deleteCurrency(id, "EUR"), "an advance uses it");
        assertTrue(admin.deleteAdvance(id, rows.get(3).getId()));
        assertEquals(admin.reportPage(id).getCashAdvance(), "$1,350.00");
        assertTrue(admin.deleteAdvance(id, "gone"), "already gone");
        assertTrue(admin.saveAdvance(id, advance("RETURNED", 5000.0, "Ken")));
        final ExpensePage negative = admin.reportPage(id);
        assertTrue(negative.isAdvanceNegative());
        assertEquals(negative.getCashAdvance(), "-$3,650.00");
        assertTrue(negative.isDueToSubmitter());
        assertEquals(negative.getDue(), "$3,650.00");
    }

    @Test
    public void worksheetEntriesAreChecked() throws IOException {
        final String id = newTrip().getId();
        assertFalse(admin.saveAdvance(id, null));
        final AdvanceForm noType = advance("WHAT", 1.0, null);
        assertFalse(admin.saveAdvance(id, noType));
        assertEquals(noType.getProblem(), "Pick what kind of advance this is.");
        final AdvanceForm noAmount = advance("CASH", null, null);
        assertFalse(admin.saveAdvance(id, noAmount));
        assertEquals(noAmount.getProblem(), "Enter the amount.");
        final AdvanceForm negative = advance("CASH", -1.0, null);
        assertFalse(admin.saveAdvance(id, negative));
        assertEquals(negative.getProblem(), "The amount must be between $0 and $10,000,000.00.");
        final AdvanceForm longParty = advance("CASH", 1.0, "p".repeat(ExpenseCommands.MAX_DESCRIPTION + 1));
        assertFalse(admin.saveAdvance(id, longParty));
        assertEquals(longParty.getProblem(), "The answer is at most 200 characters.");
        final AdvanceForm longNote = advance("OTHER", 1.0, null);
        longNote.setNote("n".repeat(ExpenseCommands.MAX_TEXT + 1));
        assertFalse(admin.saveAdvance(id, longNote));
        assertEquals(longNote.getProblem(), "The note is at most 2000 characters.");
        final AdvanceForm nobody = advance("PARTICIPANT", 1.0, null);
        assertFalse(admin.saveAdvance(id, nobody));
        assertEquals(nobody.getProblem(), "Pick who handed you the cash.");
        nobody.setPersonIds(new ArrayList<>(List.of(Person.Id.newInstance().getValue())));
        assertFalse(admin.saveAdvance(id, nobody));
        assertEquals(nobody.getProblem(), "(unknown person) is not on this trip: reload the page.");
        final AdvanceForm badCurrency = advance("CASH", 1.0, null);
        badCurrency.setCurrency("XXX");
        assertFalse(admin.saveAdvance(id, badCurrency));
        assertEquals(badCurrency.getProblem(), "Choose a currency the report has, or add one.");
        final AdvanceForm gone = advance("CASH", 1.0, null);
        gone.setId("gone");
        assertFalse(admin.saveAdvance(id, gone));
        assertEquals(gone.getProblem(), "This advance no longer exists: reload the page.");
        for (int i = 0; i < ExpenseCommands.MAX_ADVANCES; i++) {
            assertTrue(admin.saveAdvance(id, advance("CASH", 1.0, null)));
        }
        final AdvanceForm over = advance("CASH", 1.0, null);
        assertFalse(admin.saveAdvance(id, over));
        assertEquals(over.getProblem(), "A worksheet holds at most 100 advances.");

        assertEquals(admin.advanceTypes().size(), 5);
        assertEquals(admin.advanceTypes().get(3).getLabel(), "Money returned / transferred");
        assertEquals(admin.typeLabel("CASH"), "Cash advance");
        assertEquals(admin.typeLabel("x"), "Advance");
        assertEquals(admin.partyLabel("RETURNED"), "Who did you give the money to?");
        assertEquals(admin.partyLabel("CASH"), "Who gave the advance?");
        assertEquals(admin.partyLabel("CREDIT"), "Who or where did this credit come from?");
        assertNull(admin.partyLabel("OTHER"));
        assertNull(admin.partyLabel("x"));
        assertTrue(admin.typeHelp("RETURNED").contains("subtracted"));
        assertTrue(admin.typeHelp("OTHER").contains("other types"));
        assertEquals(admin.typeHelp("x"), "");
    }

    @Test
    public void aParticipantEntryRecordsOnePaymentForOnePerson() throws IOException {
        final Trip trip = newTrip();
        final String id = trip.getId();
        final Person ken = Person.builder().first("Ken").last("Paulsen").build();
        final Person trinity = Person.builder().first("Trinity").last("Adams").build();
        assertTrue(DAO.getInstance().savePerson(ken));
        assertTrue(DAO.getInstance().savePerson(trinity));
        trip.getPeople().add(ken.getId());
        trip.getPeople().add(trinity.getId());
        assertTrue(DAO.getInstance().saveTrip(trip));
        final List<Choice> who = admin.participantChoices(id);
        assertEquals(who.size(), 2);
        assertEquals(who.get(0).getLabel(), "Adams, Trinity", "sorted by last name");
        assertEquals(who.get(1).getId(), ken.getId().getValue());
        final TransactionsCommands ledger = new TransactionsCommands();
        final int before = ledger.getTransactions(ken.getId()).size();

        final AdvanceForm given = advance("PARTICIPANT", 83.0, null);
        given.setCurrency(ExpenseViews.NEW_CURRENCY);
        given.setNewCode("EUR");
        given.setNewRate(0.83);
        given.setDate(LocalDate.of(2026, 9, 25));
        given.setPersonIds(new ArrayList<>(List.of(ken.getId().getValue())));
        given.setRecordTransaction(true);
        assertTrue(given.isOnePerson());
        assertFalse(new AdvanceForm().isOnePerson());
        assertTrue(admin.saveAdvance(id, given), given.getProblem());
        assertNotNull(given.getTransactionId());
        assertFalse(given.isRecordTransaction(), "a second save does not ask again");
        assertTrue(given.getTransactionLine().startsWith("A payment of $100.00 was recorded for Ken Paulsen on "),
                given.getTransactionLine());
        final AdvanceEntry entry = stored(id).getAdvances().get(0);
        assertEquals(entry.getTransaction().getTxId(), given.getTransactionId());
        assertEquals(entry.getTransaction().getPersonId(), ken.getId().getValue());
        assertEquals(entry.getTransaction().getAmountCents(), 10_000L);
        assertNotNull(entry.getTransaction().getRecordedOn());
        assertEquals(ledger.getTransactions(ken.getId()).size(), before + 1);
        final Transaction tx = ledger.getTransaction(ken.getId(), given.getTransactionId());
        assertEquals(tx.getTxType(), Transaction.TransactionType.Payment);
        assertEquals(tx.getCategory(), "Payment");
        assertEquals(tx.getNote(), ExpenseCommands.PARTICIPANT_TX_NOTE);
        assertEquals(tx.getAmount(), 100.0f);
        assertEquals(tx.getTxDate(), LocalDate.of(2026, 9, 25).atStartOfDay());
        assertEquals(tx.getOrgId(), orgId);
        final BindingCommands bind = new BindingCommands();
        assertEquals(bind.getBindings(bind.key(ken.getId().getValue(), tx.getTxId()), BindingType.TRANSACTION,
                BindingType.TRIP), List.of(id));
        final AdvanceRow row = admin.reportPage(id).getAdvanceRows().get(0);
        assertEquals(row.getParty(), "Ken Paulsen");
        assertEquals(row.getTransactionLine(), given.getTransactionLine());

        final AdvanceForm again = admin.advanceFormFor(id, entry.getId());
        assertEquals(again.getTransactionId(), tx.getTxId());
        again.setRecordTransaction(true);
        again.setAmount(90.0);
        assertTrue(admin.saveAdvance(id, again), "editing warns but saves");
        assertEquals(ledger.getTransactions(ken.getId()).size(), before + 1, "never a second transaction");
        assertEquals(stored(id).getAdvances().get(0).getTransaction().getAmountCents(), 10_000L,
                "the stamp keeps what was recorded, not the edited amount");
        assertEquals(admin.reportPage(id).getAdvanceRows().get(0).getUsd(), "$108.43");

        final AdvanceForm two = advance("PARTICIPANT", 50.0, null);
        two.setPersonIds(new ArrayList<>(List.of(ken.getId().getValue(), trinity.getId().getValue(),
                ken.getId().getValue())));
        two.setRecordTransaction(true);
        assertTrue(admin.saveAdvance(id, two), two.getProblem());
        assertNull(two.getTransactionId(), "two people: the report records nothing");
        assertEquals(two.getPersonIds().size(), 2, "de-duplicated");
        assertEquals(admin.reportPage(id).getAdvanceRows().get(1).getParty(), "Ken Paulsen; Trinity Adams");
        assertEquals(ledger.getTransactions(ken.getId()).size(), before + 1);

        final ExpenseCommands manager = as(grant(PrivilegeCommands.TRIP_MGR, id));
        final AdvanceForm byManager = advance("PARTICIPANT", 20.0, null);
        byManager.setPersonIds(new ArrayList<>(List.of(trinity.getId().getValue())));
        byManager.setRecordTransaction(true);
        assertTrue(manager.saveAdvance(id, byManager), byManager.getProblem());
        assertNull(byManager.getTransactionId(), "a trip manager's tick is ignored");
        assertTrue(ledger.getTransactions(trinity.getId()).isEmpty());
        final ExpenseCommands finAdmin = as(grant(PrivilegeCommands.TRIP_FIN_ADMIN, id));
        final AdvanceForm byFinAdmin = advance("PARTICIPANT", 20.0, null);
        byFinAdmin.setPersonIds(new ArrayList<>(List.of(trinity.getId().getValue())));
        byFinAdmin.setRecordTransaction(true);
        assertTrue(finAdmin.saveAdvance(id, byFinAdmin), byFinAdmin.getProblem());
        assertNotNull(byFinAdmin.getTransactionId(), "a finance admin may record it");
        assertEquals(ledger.getTransactions(trinity.getId()).size(), 1);
        assertEquals(ledger.getTransactions(trinity.getId()).get(0).getAmount(), 20.0f);

        assertTrue(admin.deleteAdvance(id, entry.getId()), "deleting warns and leaves the ledger alone");
        assertNotNull(ledger.getTransaction(ken.getId(), tx.getTxId()));
        assertEquals(admin.reportPage(id).getAdvanceCount(), 3);
    }

    // ------------------------------------------------------------------ helpers

    @Test
    public void formattingHelpers() {
        assertEquals(ExpenseCommands.foreign(2_294_295L, "EUR"), "22,942.95 EUR");
        assertEquals(ExpenseCommands.size(340_200L), "340 KB");
        assertEquals(ExpenseCommands.size(10L), "1 KB");
        assertEquals(ExpenseCommands.size(1_250_000L), "1.3 MB");
        assertEquals(ExpenseCommands.micros(null), 0L);
        assertEquals(ExpenseCommands.micros(Double.POSITIVE_INFINITY), 0L);
        assertEquals(ExpenseCommands.micros(0.83), 830_000L);
        final Trip undated = Trip.builder().build();
        undated.setStartDate(null);
        assertNull(ExpenseCommands.tripDates(undated));
        undated.setStartDate(START);
        undated.setEndDate(null);
        assertEquals(ExpenseCommands.tripDates(undated), "Sep 20, 2026");
        assertEquals(admin.categories().size(), ExpenseCategory.values().length);
        assertEquals(admin.categories().get(7).getLabel(), "Postage");
    }
}
