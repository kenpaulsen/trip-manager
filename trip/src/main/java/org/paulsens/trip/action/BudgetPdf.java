package org.paulsens.trip.action;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.action.BudgetViews.BudgetPage;
import org.paulsens.trip.action.BudgetViews.LineRow;
import org.paulsens.trip.action.BudgetViews.LodgingRow;
import org.paulsens.trip.action.BudgetViews.OtherRow;
import org.paulsens.trip.action.BudgetViews.PayeeRow;
import org.paulsens.trip.action.PdfSheet.Table;

/**
 * The trip's budget as a PDF ({@code docs/budget.md}, "PDF"): the same {@link BudgetPage} the page renders,
 * section for section in the page's order (inputs, Shared Expenses, Lodging, per-person extras, per person by
 * lodging option, Payees, Other Payments, the totals, the Summary), for printing or handing to someone without
 * an account. Pure: a page in, bytes out; {@code BudgetPdfTest} reads the text back.
 */
public final class BudgetPdf {

    private static final Table SHARED = Table.of(new String[] {"Name", "Category", "Payee", "Note", "Amount"},
            new float[] {150, 90, 90, 130, 72}, new boolean[] {false, false, false, false, true});
    private static final Table LODGING = Table.of(
            new String[] {"Option", "Quantities", "Price", "Hotel rate", "Revenue", "Hotel cost", "Margin"},
            new float[] {120, 110, 62, 62, 60, 60, 58}, new boolean[] {false, false, false, false, true, true, true});
    private static final Table EXTRAS = Table.of(new String[] {"Name", "Note", "Payee", "Per person"},
            new float[] {170, 170, 100, 92}, new boolean[] {false, false, false, true});
    private static final Table PER_OPTION = Table.of(new String[] {"Option", "Lodging price", "Hotel cost",
        "Single add-on", "Ground cost", "Ground budget"}, new float[] {152, 76, 76, 76, 76, 76},
            new boolean[] {false, true, true, true, true, true});
    private static final Table PAYEES = Table.of(new String[] {"Payee", "Category", "Lines", "Amount"},
            new float[] {200, 200, 50, 82}, new boolean[] {false, false, true, true});
    private static final Table OTHER = Table.of(new String[] {"Item", "Category", "Payee", "Amount"},
            new float[] {190, 130, 130, 82}, new boolean[] {false, false, false, true});

    private BudgetPdf() {
    }

    /** The whole document, printed today. */
    public static byte[] render(final BudgetPage page, final String tripTitle, final String orgName) {
        return render(page, tripTitle, orgName, LocalDate.now());
    }

    static byte[] render(final BudgetPage page, final String tripTitle, final String orgName,
            final LocalDate printed) {
        final String title = ((tripTitle == null) ? "Trip" : tripTitle) + ": Budget";
        try {
            final PdfSheet out = new PdfSheet(title);
            out.title(title, List.of(nonNull(orgName), page.getPeople() + " people, " + page.getTripNights()
                    + " trip nights"));
            for (final String warning : page.getWarnings()) {
                out.paragraph("Note: " + warning, true);
            }
            out.section("Inputs", 60);
            out.fields(List.of(new String[] {"People", String.valueOf(page.getPeople())},
                    new String[] {"Trip nights", String.valueOf(page.getTripNights())},
                    new String[] {"Per-person shared budget", page.getPerPersonBudget()
                            + " (estimated shared cost per person " + page.getEstimatedPerPerson() + ")"},
                    new String[] {"Basic ground budget", page.getToBillRange()
                            + " (estimated shared + lodging cost per person " + page.getEstimatedGroundPerPerson()
                            + ")"}));
            out.section("Shared Expenses");
            out.table(SHARED, shared(page.getSharedRows()), new String[] {"Shared expenses total", "", "", "",
                page.getSharedTotal()});
            lodging(out, page);
            out.section("Individual (per-person) extras");
            out.table(EXTRAS, extras(page.getIndividualRows()), new String[] {"Per-person extras", "", "",
                page.getIndividualExtras()});
            payments(out, page);
            summary(out, page);
            return out.finish(printed);
        } catch (final IOException ex) {
            throw new UncheckedIOException("Could not draw the budget PDF", ex);
        }
    }

    /** {@code "Spring Demo Trip - Budget.pdf"}, with anything a file system would refuse replaced. */
    public static String fileName(final String tripTitle) {
        return safeTitle(tripTitle) + " - Budget.pdf";
    }

    static String safeTitle(final String tripTitle) {
        final String title = (tripTitle == null || tripTitle.isBlank()) ? "Trip" : tripTitle.trim();
        return title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-");
    }

    private static List<String[]> shared(final List<LineRow> rows) {
        final List<String[]> out = new ArrayList<>();
        for (final LineRow r : rows) {
            out.add(new String[] {r.getName() + (r.isOnSite() ? "" : " (paid before trip)"), r.getCategory(),
                nonNull(r.getPayee()), nonNull(r.getNote()), percent(r.getAmount(), r.getPercentLabel())});
        }
        return out;
    }

    private static List<String[]> extras(final List<LineRow> rows) {
        final List<String[]> out = new ArrayList<>();
        for (final LineRow r : rows) {
            out.add(new String[] {r.getName() + (r.isPerNight() ? " (per night)" : ""), nonNull(r.getNote()),
                nonNull(r.getPayee()), percent(r.getTotal(), r.getPercentLabel())});
        }
        return out;
    }

    private static void lodging(final PdfSheet out, final BudgetPage page) throws IOException {
        out.section("Lodging");
        final List<String[]> rows = new ArrayList<>();
        final List<String[]> perOption = new ArrayList<>();
        for (final LodgingRow l : page.getLodgingRows()) {
            rows.add(new String[] {l.getLabel(), l.getQuantities(), l.isPriceMissing() ? "not set" : l.getPriceLabel(),
                l.isCostMissing() ? "not set" : l.getCostLabel(), l.getRevenue(), l.getHotelCost(), l.getMargin()});
            perOption.add(new String[] {l.getLabel(), l.getPerPersonPrice(), l.getPerPersonCost(),
                nonNull(l.getSingleAddOn()), l.getTotalPerPersonGround(), l.getToBillReference()});
        }
        out.table(LODGING, rows, new String[] {"Lodging total", "", "", "", page.getLodgingRevenue(),
            page.getLodgingCost(), page.getLodgingMargin()});
        if (!perOption.isEmpty()) {
            out.section("Per person by lodging option");
            out.table(PER_OPTION, perOption, null);
        }
    }

    private static void payments(final PdfSheet out, final BudgetPage page) throws IOException {
        out.section("Payees");
        final List<String[]> payees = new ArrayList<>();
        for (final PayeeRow p : page.getPayees()) {
            payees.add(new String[] {p.getName(), p.getCategory(), String.valueOf(p.getItems()), p.getAmount()});
        }
        out.table(PAYEES, payees, new String[] {"Payments During Trip", "", "", page.getOnSiteTotal()});
        out.section("Other Payments");
        final List<String[]> others = new ArrayList<>();
        for (final OtherRow o : page.getOtherRows()) {
            others.add(new String[] {o.getName(), o.getCategory(), nonNull(o.getPayee()), o.getAmount()});
        }
        out.table(OTHER, others, new String[] {"Other Payments", "", "", page.getOtherTotal()});
        out.fields(List.of(new String[] {"Estimated trip total", page.getTripTotal()},
                new String[] {"Estimated money to collect", page.getMoneyToCollect()}));
    }

    private static void summary(final PdfSheet out, final BudgetPage page) throws IOException {
        out.section("Summary", 80);
        out.fields(List.of(new String[] {"Per-person shared budget", page.getPerPersonBudget()},
                new String[] {"Basic ground budget", page.getToBillRange()},
                new String[] {"Lodging revenue", page.getLodgingRevenue()},
                new String[] {"Lodging hotel cost", page.getLodgingCost()},
                new String[] {"Lodging margin", page.getLodgingMargin()}));
        out.bigTotal(nonNull(page.getOverallLabel()), nonNull(page.getOverallAmount()), page.isOverallNegative());
    }

    /** An amount with its percent label after it ("$100.00 (10% of Guide)"). */
    private static String percent(final String amount, final String percentLabel) {
        return (percentLabel == null) ? nonNull(amount) : nonNull(amount) + " (" + percentLabel + ")";
    }

    static String nonNull(final String text) {
        return (text == null) ? "" : text;
    }
}
