package org.paulsens.trip.action;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.ExpenseViews.BudgetRow;
import org.paulsens.trip.action.ExpenseViews.CurrencyRow;
import org.paulsens.trip.action.ExpenseViews.ExpensePage;
import org.paulsens.trip.action.ExpenseViews.ExpenseRow;
import org.paulsens.trip.action.ExpenseViews.PaymentRow;
import org.paulsens.trip.action.PdfSheet.Table;

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
 * <p>Pure: a page and the receipts in, bytes out. {@code ExpenseReportPdfTest} reads the text back.
 */
public final class ExpenseReportPdf {

    /** One receipt to append: its number, what it is for, its file name, its type and its bytes (null = gone). */
    public record ReceiptDoc(String label, String forWhat, String title, String contentType, byte[] bytes) {
    }

    private static final Table CURRENCIES = Table.of(new String[] {"Currency", "Rate", "In dollars", "Expenses",
        "Total", "USD"}, new float[] {60, 120, 120, 60, 90, 82}, new boolean[] {false, false, false, true, true, true});
    private static final Table BUDGET = Table.of(new String[] {"Budget line", "Kind", "Category", "Budget", "Spent",
        "Remaining"}, new float[] {170, 70, 80, 70, 70, 72}, new boolean[] {false, false, false, true, true, true});
    private static final Table EXPENSES = Table.of(new String[] {"Date", "Category", "Description", "Amount", "USD",
        "Budget line", "Paid", "Receipts"}, new float[] {70, 62, 104, 62, 60, 70, 64, 40},
            new boolean[] {false, false, false, true, true, false, false, false});
    private static final Table PAYMENTS = Table.of(new String[] {"Date", "Amount", "Method", "Expenses", "Note",
        "Recorded"}, new float[] {70, 66, 80, 130, 110, 76},
            new boolean[] {false, true, false, false, false, false});
    private static final Table INDEX = Table.of(new String[] {"Receipt", "For", "File", "Attached as"},
            new float[] {50, 220, 170, 92}, new boolean[] {false, false, false, false});

    private ExpenseReportPdf() {
    }

    /** The whole document, printed today; {@code receiptLabels} maps an expense id to "R1, R2". */
    public static byte[] render(final ExpensePage page, final Map<String, String> receiptLabels,
            final List<ReceiptDoc> receipts) {
        return render(page, receiptLabels, receipts, LocalDate.now());
    }

    static byte[] render(final ExpensePage page, final Map<String, String> receiptLabels,
            final List<ReceiptDoc> receipts, final LocalDate printed) {
        final String title = ((page.getTripTitle() == null) ? "Trip" : page.getTripTitle()) + ": Expense Report";
        try {
            final PdfSheet out = new PdfSheet(title);
            out.title(title, List.of(nonNull(page.getTripDates()), nonNull(page.getOrgName()),
                    "Status: " + page.getStatus() + (page.getSubmittedLine() == null ? ""
                            : ". " + page.getSubmittedLine())));
            for (final String warning : page.getWarnings()) {
                out.paragraph("Note: " + warning, true);
            }
            out.section("Report", 70);
            out.fields(List.of(new String[] {"Submitted by", nonNull(page.getSubmittedBy())},
                    new String[] {"Cash advance", page.getCashAdvance()},
                    new String[] {"How the cash was advanced", nonNull(page.getCashAdvanceDetails())},
                    new String[] {"Payment instructions", nonNull(page.getPaymentInstructions())}));
            if (!page.getCurrencyRows().isEmpty()) {
                out.section("Currencies");
                out.table(CURRENCIES, currencies(page.getCurrencyRows()), null);
            }
            out.section("Budget");
            out.table(BUDGET, budget(page.getBudgetRows()), new String[] {"Total", "", "", page.getBudgetTotal(),
                page.getSpentTotal(), page.getRemainingTotal()});
            out.section("Expenses");
            out.table(EXPENSES, expenses(page.getLineRows(), receiptLabels), new String[] {"Total spent", "", "",
                "", page.getTotal(), "", "", ""});
            if (page.getLinesWithoutReceipt() > 0) {
                out.paragraph(page.getLinesWithoutReceipt() + (page.getLinesWithoutReceipt() == 1
                        ? " expense has" : " expenses have") + " no receipt.", true);
            }
            if (!page.getPaymentRows().isEmpty()) {
                out.section("Payments");
                out.table(PAYMENTS, payments(page.getPaymentRows()), new String[] {"Payments recorded",
                    page.getPaymentsTotal(), "", "", "", ""});
            }
            totals(out, page);
            out.gap(30);
            out.paragraph("Signature: ______________________________     Date: ______________", false);
            appendix(out, receipts);
            return out.finish(printed);
        } catch (final IOException ex) {
            throw new UncheckedIOException("Could not draw the expense report PDF", ex);
        }
    }

    /** Total spent, what was settled outside the report, the due figure, and what is still due after payments. */
    private static void totals(final PdfSheet out, final ExpensePage page) throws IOException {
        out.section("Totals", 70);
        final List<String[]> fields = new ArrayList<>();
        fields.add(new String[] {"Total spent", page.getTotal()});
        if (page.isHasSettled()) {
            fields.add(new String[] {"Paid by organization", page.getPaidByOrg()});
            fields.add(new String[] {"Already reimbursed", page.getReimbursed()});
            fields.add(new String[] {"Out of pocket", page.getOutOfPocket()});
        }
        fields.add(new String[] {"Cash advance", page.getCashAdvance()});
        out.fields(fields);
        out.bigTotal(page.getDueLabel(), page.getDue(), page.isDueToSubmitter());
        if (!page.getPaymentRows().isEmpty()) {
            out.fields(List.<String[]>of(new String[] {"Payments recorded", page.getPaymentsTotal()}));
            out.bigTotal(page.getStillDueLabel(), page.getStillDue(), page.isStillDueToSubmitter());
        }
    }

    /** {@code "Spring Demo Trip - Expense Report.pdf"}. */
    public static String fileName(final String tripTitle) {
        return BudgetPdf.safeTitle(tripTitle) + " - Expense Report.pdf";
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
    private static void appendix(final PdfSheet out, final List<ReceiptDoc> receipts) throws IOException {
        if (receipts == null || receipts.isEmpty()) {
            return;
        }
        out.section("Receipts");
        final List<String[]> index = new ArrayList<>();
        final List<Integer> pagesOf = new ArrayList<>();
        for (final ReceiptDoc receipt : receipts) {
            final int pages = pageCount(receipt);
            pagesOf.add(pages);
            index.add(new String[] {receipt.label(), nonNull(receipt.forWhat()), nonNull(receipt.title()),
                attachedAs(receipt, pages)});
        }
        out.table(INDEX, index, null);
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
    private static String attachedAs(final ReceiptDoc receipt, final int pages) {
        if (pages == 0) {
            return "missing";
        }
        if (!isPdf(receipt)) {
            return "image";
        }
        return "PDF, " + pages + (pages == 1 ? " page" : " pages");
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
