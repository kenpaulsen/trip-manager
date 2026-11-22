package org.paulsens.trip.action;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.paulsens.trip.action.ExpenseViews.AdvanceRow;
import org.paulsens.trip.action.ExpenseViews.BudgetRow;
import org.paulsens.trip.action.ExpenseViews.CurrencyRow;
import org.paulsens.trip.action.ExpenseViews.ExpensePage;
import org.paulsens.trip.action.ExpenseViews.ExpenseRow;
import org.paulsens.trip.action.ExpenseViews.PaymentRow;
import org.paulsens.trip.action.PdfSheet.Table;
import org.paulsens.trip.i18n.Text;

import static org.paulsens.trip.action.BudgetPdf.nonNull;

/**
 * A trip's expense report as a PDF ({@code docs/expense-report.md}, "PDF and email"): the same {@link ExpensePage} the
 * page renders (the report's details, currencies, budget vs actual, every expense with its paid status, the payments
 * recorded, the totals and a signature line), optionally followed by the RECEIPTS, so the treasurer can reconcile it
 * without an account. Each receipt is numbered (R1, R2, ...) on the expense it belongs to; the appendix opens with an
 * index of them, then an image receipt fills a page of its own under its number and a PDF receipt's own pages are
 * appended with its number stamped across the top of the first. (The index names no page numbers: it may itself run to
 * a second page, so any it printed could be wrong.) A receipt whose bytes are gone or cannot be read is listed as
 * missing, never fatal.
 *
 * <p>The sheet's own words (headings, column titles, labels) come from the {@code pdf} bundle in the READER's
 * language, which the caller names: this document is downloaded by the person asking for it AND mailed to the
 * treasurer, who is somebody else, so the request's language is never assumed here. The signatures without a
 * {@code Locale} draw English. The page's values (status, paid chips, the due label) arrive already worded by
 * whoever built the page.
 *
 * <p>Pure: a page and the receipts in, bytes out. {@code ExpenseReportPdfTest} reads the text back.
 */
public final class ExpenseReportPdf {

    /** One receipt to append: its number, what it is for, its file name, its type and its bytes (null = gone). */
    public record ReceiptDoc(String label, String forWhat, String title, String contentType, byte[] bytes) {
    }

    private static final float[] ADVANCES_WEIGHTS = {22, 92, 56, 96, 68, 64, 134};
    private static final boolean[] ADVANCES_RIGHT = {false, false, false, false, true, true, false};
    private static final float[] CURRENCIES_WEIGHTS = {60, 120, 120, 60, 90, 82};
    private static final boolean[] CURRENCIES_RIGHT = {false, false, false, true, true, true};
    private static final float[] BUDGET_WEIGHTS = {170, 70, 80, 70, 70, 72};
    private static final boolean[] BUDGET_RIGHT = {false, false, false, true, true, true};
    private static final float[] EXPENSES_WEIGHTS = {70, 62, 104, 62, 60, 70, 64, 40};
    private static final boolean[] EXPENSES_RIGHT = {false, false, false, true, true, false, false, false};
    private static final float[] PAYMENTS_WEIGHTS = {70, 66, 80, 130, 110, 76};
    private static final boolean[] PAYMENTS_RIGHT = {false, true, false, false, false, false};
    private static final float[] INDEX_WEIGHTS = {50, 220, 170, 92};
    private static final boolean[] INDEX_RIGHT = {false, false, false, false};
    /** A currency code, not a word: the same in every language. */
    private static final String USD = "USD";

    private ExpenseReportPdf() {
    }

    /** The whole document in English, printed today; {@code receiptLabels} maps an expense id to "R1, R2". */
    public static byte[] render(final ExpensePage page, final Map<String, String> receiptLabels,
            final List<ReceiptDoc> receipts) {
        return render(page, receiptLabels, receipts, Locale.ENGLISH);
    }

    /** The whole document, printed today, for a reader of {@code locale}. */
    public static byte[] render(final ExpensePage page, final Map<String, String> receiptLabels,
            final List<ReceiptDoc> receipts, final Locale locale) {
        return render(page, receiptLabels, receipts, LocalDate.now(), locale);
    }

    static byte[] render(final ExpensePage page, final Map<String, String> receiptLabels,
            final List<ReceiptDoc> receipts, final LocalDate printed) {
        return render(page, receiptLabels, receipts, printed, Locale.ENGLISH);
    }

    static byte[] render(final ExpensePage page, final Map<String, String> receiptLabels,
            final List<ReceiptDoc> receipts, final LocalDate printed, final Locale locale) {
        final String title = Text.forLocale(locale, "pdf.expenseReport.title", (page.getTripTitle() == null)
                ? Text.forLocale(locale, "common.trip") : page.getTripTitle());
        try {
            final PdfSheet out = new PdfSheet(title, locale);
            out.title(title, List.of(nonNull(page.getTripDates()), nonNull(page.getOrgName()),
                    statusLine(page, locale)));
            for (final String warning : page.getWarnings()) {
                out.paragraph(Text.forLocale(locale, "pdf.shared.note", warning), true);
            }
            out.section(Text.forLocale(locale, "common.report"), 70);
            out.fields(List.of(
                    new String[] {Text.forLocale(locale, "pdf.expenseReport.submittedBy"),
                        nonNull(page.getSubmittedBy())},
                    new String[] {Text.forLocale(locale, "pdf.expenseReport.paymentInstructions"),
                        nonNull(page.getPaymentInstructions())}));
            if (!page.getAdvanceRows().isEmpty()) {
                out.section(Text.forLocale(locale, "pdf.expenseReport.cashAdvanced"));
                out.table(advancesTable(locale), advances(page.getAdvanceRows()), new String[] {"",
                    Text.forLocale(locale, "pdf.expenseReport.totalAdvanced"), "", "", "", page.getCashAdvance(),
                    ""});
            }
            if (!page.getCurrencyRows().isEmpty()) {
                out.section(Text.forLocale(locale, "pdf.expenseReport.currencies"));
                out.table(currenciesTable(locale), currencies(page.getCurrencyRows()), null);
            }
            out.section(Text.forLocale(locale, "common.budget"));
            out.table(budgetTable(locale), budget(page.getBudgetRows()), new String[] {
                Text.forLocale(locale, "common.total"), "", "", page.getBudgetTotal(), page.getSpentTotal(),
                page.getRemainingTotal()});
            out.section(Text.forLocale(locale, "common.expenses"));
            out.table(expensesTable(locale), expenses(page.getLineRows(), receiptLabels), new String[] {
                Text.forLocale(locale, "pdf.expenseReport.totalSpent"), "", "", "", page.getTotal(), "", "", ""});
            if (page.getLinesWithoutReceipt() > 0) {
                out.paragraph(Text.forLocale(locale, "pdf.expenseReport.withoutReceipt",
                        page.getLinesWithoutReceipt()), true);
            }
            if (!page.getPaymentRows().isEmpty()) {
                out.section(Text.forLocale(locale, "common.payments"));
                out.table(paymentsTable(locale), payments(page.getPaymentRows()), new String[] {
                    Text.forLocale(locale, "pdf.expenseReport.paymentsRecorded"), page.getPaymentsTotal(), "", "",
                    "", ""});
            }
            totals(out, page, locale);
            out.gap(30);
            out.paragraph(Text.forLocale(locale, "pdf.expenseReport.signatureLine"), false);
            appendix(out, receipts, locale);
            return out.finish(printed);
        } catch (final IOException ex) {
            throw new UncheckedIOException("Could not draw the expense report PDF", ex);
        }
    }

    /** "Status: Draft", with the submitted line after it once there is one. */
    private static String statusLine(final ExpensePage page, final Locale locale) {
        return (page.getSubmittedLine() == null)
                ? Text.forLocale(locale, "pdf.expenseReport.status", page.getStatus())
                : Text.forLocale(locale, "pdf.expenseReport.statusSubmitted", page.getStatus(),
                        page.getSubmittedLine());
    }

    private static Table advancesTable(final Locale locale) {
        return Table.of(new String[] {"#", Text.forLocale(locale, "common.type"),
            Text.forLocale(locale, "common.date"), Text.forLocale(locale, "pdf.expenseReport.fromTo"),
            Text.forLocale(locale, "common.amount"), USD, Text.forLocale(locale, "common.note")},
                ADVANCES_WEIGHTS, ADVANCES_RIGHT);
    }

    private static Table currenciesTable(final Locale locale) {
        return Table.of(new String[] {Text.forLocale(locale, "common.currency"),
            Text.forLocale(locale, "pdf.expenseReport.exchangeRate"),
            Text.forLocale(locale, "pdf.expenseReport.inDollars"), Text.forLocale(locale, "common.expenses"),
            Text.forLocale(locale, "common.total"), USD}, CURRENCIES_WEIGHTS, CURRENCIES_RIGHT);
    }

    private static Table budgetTable(final Locale locale) {
        return Table.of(new String[] {Text.forLocale(locale, "common.budgetLine"),
            Text.forLocale(locale, "common.kind"), Text.forLocale(locale, "common.category"),
            Text.forLocale(locale, "common.budget"), Text.forLocale(locale, "pdf.expenseReport.spent"),
            Text.forLocale(locale, "pdf.expenseReport.remaining")}, BUDGET_WEIGHTS, BUDGET_RIGHT);
    }

    private static Table expensesTable(final Locale locale) {
        return Table.of(new String[] {Text.forLocale(locale, "common.date"),
            Text.forLocale(locale, "common.category"), Text.forLocale(locale, "common.description"),
            Text.forLocale(locale, "common.amount"), USD, Text.forLocale(locale, "common.budgetLine"),
            Text.forLocale(locale, "common.paid"), Text.forLocale(locale, "common.receipts")},
                EXPENSES_WEIGHTS, EXPENSES_RIGHT);
    }

    private static Table paymentsTable(final Locale locale) {
        return Table.of(new String[] {Text.forLocale(locale, "common.date"),
            Text.forLocale(locale, "common.amount"), Text.forLocale(locale, "common.method"),
            Text.forLocale(locale, "common.expenses"), Text.forLocale(locale, "common.note"),
            Text.forLocale(locale, "pdf.expenseReport.recorded")}, PAYMENTS_WEIGHTS, PAYMENTS_RIGHT);
    }

    private static Table indexTable(final Locale locale) {
        return Table.of(new String[] {Text.forLocale(locale, "common.receipt"),
            Text.forLocale(locale, "pdf.expenseReport.receiptFor"), Text.forLocale(locale, "common.file"),
            Text.forLocale(locale, "pdf.expenseReport.attachedAs")}, INDEX_WEIGHTS, INDEX_RIGHT);
    }

    /** Total spent, what was settled outside the report, the due figure, and what is still due after payments. */
    private static void totals(final PdfSheet out, final ExpensePage page, final Locale locale)
            throws IOException {
        out.section(Text.forLocale(locale, "pdf.expenseReport.totals"), 70);
        final List<String[]> fields = new ArrayList<>();
        fields.add(new String[] {Text.forLocale(locale, "pdf.expenseReport.totalSpent"), page.getTotal()});
        if (page.isHasSettled()) {
            fields.add(new String[] {Text.forLocale(locale, "labels.enum.ExpensePaidStatus.ORG"),
                page.getPaidByOrg()});
            fields.add(new String[] {Text.forLocale(locale, "labels.enum.ExpensePaidStatus.REIMBURSED"),
                page.getReimbursed()});
            fields.add(new String[] {Text.forLocale(locale, "labels.enum.ExpensePaidStatus.NOTPAID"),
                page.getOutOfPocket()});
        }
        fields.add(new String[] {Text.forLocale(locale, "pdf.expenseReport.cashAdvanced"), page.getCashAdvance()});
        out.fields(fields);
        out.bigTotal(page.getDueLabel(), page.getDue(), page.isDueToSubmitter());
        if (!page.getPaymentRows().isEmpty()) {
            out.fields(List.<String[]>of(new String[] {
                Text.forLocale(locale, "pdf.expenseReport.paymentsRecorded"), page.getPaymentsTotal()}));
            out.bigTotal(page.getStillDueLabel(), page.getStillDue(), page.isStillDueToSubmitter());
        }
    }

    /** {@code "Spring Demo Trip - Expense Report.pdf"}. */
    public static String fileName(final String tripTitle) {
        return BudgetPdf.safeTitle(tripTitle) + " - Expense Report.pdf";
    }

    /** The worksheet; an entry that recorded a transaction says so under its note. */
    private static List<String[]> advances(final List<AdvanceRow> rows) {
        final List<String[]> out = new ArrayList<>();
        for (final AdvanceRow a : rows) {
            final String note = (a.getTransactionLine() == null) ? nonNull(a.getNote())
                    : (nonNull(a.getNote()) + " " + a.getTransactionLine()).trim();
            out.add(new String[] {String.valueOf(a.getNumber()), a.getTypeLabel(), a.getDateText(),
                nonNull(a.getParty()), a.getAmount(), a.getUsd(), note});
        }
        return out;
    }

    private static List<String[]> currencies(final List<CurrencyRow> rows) {
        final List<String[]> out = new ArrayList<>();
        for (final CurrencyRow c : rows) {
            out.add(new String[] {c.getCode(), c.getRateText(), c.getInverseText(), String.valueOf(c.getLines()),
                c.getForeignTotal(), c.getUsdTotal()});
        }
        return out;
    }

    private static List<String[]> budget(final List<BudgetRow> rows) {
        final List<String[]> out = new ArrayList<>();
        for (final BudgetRow b : rows) {
            out.add(new String[] {b.getLabel(), nonNull(b.getSection()), nonNull(b.getCategory()),
                nonNull(b.getBudget()), b.getSpent(), nonNull(b.getRemaining())});
        }
        return out;
    }

    private static List<String[]> expenses(final List<ExpenseRow> rows, final Map<String, String> labels) {
        final List<String[]> out = new ArrayList<>();
        for (final ExpenseRow r : rows) {
            out.add(new String[] {r.getDateText(), r.getCategory(), nonNull(r.getDescription()), r.getAmountText(),
                r.getUsd(), nonNull(r.getBudgetLabel()), nonNull(r.getPaidChip()), nonNull(labels.get(r.getId()))});
        }
        return out;
    }

    private static List<String[]> payments(final List<PaymentRow> rows) {
        final List<String[]> out = new ArrayList<>();
        for (final PaymentRow p : rows) {
            out.add(new String[] {p.getDateText(), p.getAmount(), nonNull(p.getMethod()), p.getLines(),
                nonNull(p.getNote()), nonNull(p.getRecordedLine())});
        }
        return out;
    }

    /** The receipts: an index of them, then each one under its number. */
    private static void appendix(final PdfSheet out, final List<ReceiptDoc> receipts, final Locale locale)
            throws IOException {
        if (receipts == null || receipts.isEmpty()) {
            return;
        }
        out.section(Text.forLocale(locale, "common.receipts"));
        final List<String[]> index = new ArrayList<>();
        final List<Integer> pagesOf = new ArrayList<>();
        for (final ReceiptDoc receipt : receipts) {
            final int pages = pageCount(receipt);
            pagesOf.add(pages);
            index.add(new String[] {receipt.label(), nonNull(receipt.forWhat()), nonNull(receipt.title()),
                attachedAs(receipt, pages, locale)});
        }
        out.table(indexTable(locale), index, null);
        for (int i = 0; i < receipts.size(); i++) {
            final ReceiptDoc receipt = receipts.get(i);
            if (pagesOf.get(i) == 0) {
                continue;
            }
            final String caption = receipt.label() + ": " + nonNull(receipt.forWhat()) + " (" + receipt.title() + ")";
            if (isPdf(receipt)) {
                out.appendPdf(receipt.bytes(), caption);
            } else {
                out.imagePage(caption, receipt.bytes(), "image/jpeg".equals(receipt.contentType()));
            }
        }
    }

    /** "image", "PDF, 2 pages", or "missing" for one the document could not include. */
    private static String attachedAs(final ReceiptDoc receipt, final int pages, final Locale locale) {
        if (pages == 0) {
            return Text.forLocale(locale, "pdf.expenseReport.attachedMissing");
        }
        if (!isPdf(receipt)) {
            return Text.forLocale(locale, "pdf.expenseReport.attachedImage");
        }
        return Text.forLocale(locale, "pdf.expenseReport.attachedPdf", pages);
    }

    /** How many pages a receipt adds: 1 for an image, its own count for a readable PDF, 0 when unusable. */
    static int pageCount(final ReceiptDoc receipt) {
        if (receipt.bytes() == null || receipt.bytes().length == 0) {
            return 0;
        }
        if (!isPdf(receipt)) {
            return 1;
        }
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = org.apache.pdfbox.Loader.loadPDF(receipt.bytes())) {
            return pdf.isEncrypted() ? 0 : pdf.getNumberOfPages();
        } catch (final IOException ex) {
            return 0;
        }
    }

    private static boolean isPdf(final ReceiptDoc receipt) {
        return ExpenseReceipts.PDF.equals(receipt.contentType());
    }
}
