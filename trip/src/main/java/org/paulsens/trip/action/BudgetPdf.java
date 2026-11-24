package org.paulsens.trip.action;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.paulsens.trip.action.BudgetViews.BudgetPage;
import org.paulsens.trip.action.BudgetViews.LineRow;
import org.paulsens.trip.action.BudgetViews.LodgingRow;
import org.paulsens.trip.action.BudgetViews.OtherRow;
import org.paulsens.trip.action.BudgetViews.PayeeRow;
import org.paulsens.trip.action.PdfSheet.Table;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.i18n.Text;

import static org.paulsens.trip.action.PdfSheet.nonNull;
import static org.paulsens.trip.action.PdfSheet.safeTitle;

/**
 * The trip's budget as a PDF ({@code docs/budget.md}, "PDF"): the same {@link BudgetPage} the page renders,
 * section for section in the page's order (inputs, Shared Expenses, Lodging, per-person extras, per person by
 * lodging option, Payees, Other Payments, the totals, the Summary), for printing or handing to someone without
 * an account. Pure: a page in, bytes out; {@code BudgetPdfTest} reads the text back.
 *
 * <p>The sheet's own words (headings, column titles, labels) come from the {@code pdf} bundle in the READER's
 * language. The budget is only ever drawn for the person asking for it, so {@link #render(BudgetPage, String,
 * String)} reads the request's language; the page's values (categories, quantities, the bottom line's label)
 * arrive already worded by whoever built the page.
 */
public final class BudgetPdf {

    private static final float[] SHARED_WEIGHTS = {150, 90, 90, 130, 72};
    private static final boolean[] SHARED_RIGHT = {false, false, false, false, true};
    private static final float[] LODGING_WEIGHTS = {120, 110, 62, 62, 60, 60, 58};
    private static final boolean[] LODGING_RIGHT = {false, false, false, false, true, true, true};
    private static final float[] EXTRAS_WEIGHTS = {170, 170, 100, 92};
    private static final boolean[] EXTRAS_RIGHT = {false, false, false, true};
    private static final float[] PER_OPTION_WEIGHTS = {152, 76, 76, 76, 76, 76};
    private static final boolean[] PER_OPTION_RIGHT = {false, true, true, true, true, true};
    private static final float[] PAYEES_WEIGHTS = {200, 200, 50, 82};
    private static final boolean[] PAYEES_RIGHT = {false, false, true, true};
    private static final float[] OTHER_WEIGHTS = {190, 130, 130, 82};
    private static final boolean[] OTHER_RIGHT = {false, false, false, true};

    private BudgetPdf() {
    }

    /** The whole document, printed today, in the language of the request asking for it. */
    public static byte[] render(final BudgetPage page, final String tripTitle, final String orgName) {
        return render(page, tripTitle, orgName, RequestContext.currentLocale());
    }

    /** The whole document, printed today, for a reader of {@code locale}. */
    public static byte[] render(final BudgetPage page, final String tripTitle, final String orgName,
            final Locale locale) {
        return render(page, tripTitle, orgName, LocalDate.now(), locale);
    }

    static byte[] render(final BudgetPage page, final String tripTitle, final String orgName,
            final LocalDate printed) {
        return render(page, tripTitle, orgName, printed, RequestContext.currentLocale());
    }

    static byte[] render(final BudgetPage page, final String tripTitle, final String orgName,
            final LocalDate printed, final Locale locale) {
        final String title = Text.forLocale(locale, "pdf.budget.title",
                (tripTitle == null) ? Text.forLocale(locale, "common.trip") : tripTitle);
        try {
            final PdfSheet out = new PdfSheet(title, locale);
            out.title(title, List.of(nonNull(orgName), Text.forLocale(locale, "pdf.budget.peopleAndNights",
                    page.getPeople(), page.getTripNights())));
            for (final String warning : page.getWarnings()) {
                out.paragraph(Text.forLocale(locale, "pdf.shared.note", warning), true);
            }
            out.section(Text.forLocale(locale, "pdf.budget.inputs"), 60);
            out.fields(List.of(
                    new String[] {Text.forLocale(locale, "common.people"), String.valueOf(page.getPeople())},
                    new String[] {Text.forLocale(locale, "pdf.budget.tripNights"),
                        String.valueOf(page.getTripNights())},
                    new String[] {Text.forLocale(locale, "pdf.budget.perPersonSharedBudget"),
                        Text.forLocale(locale, "pdf.budget.sharedBudgetWithEstimate", page.getPerPersonBudget(),
                                page.getEstimatedPerPerson())},
                    new String[] {Text.forLocale(locale, "pdf.budget.basicGroundBudget"),
                        Text.forLocale(locale, "pdf.budget.groundBudgetWithEstimate", page.getToBillRange(),
                                page.getEstimatedGroundPerPerson())}));
            out.section(Text.forLocale(locale, "pdf.budget.sharedExpenses"));
            out.table(sharedTable(locale), shared(page.getSharedRows(), locale), new String[] {
                Text.forLocale(locale, "pdf.budget.sharedExpensesTotal"), "", "", "", page.getSharedTotal()});
            lodging(out, page, locale);
            out.section(Text.forLocale(locale, "pdf.budget.individualExtras"));
            out.table(extrasTable(locale), extras(page.getIndividualRows(), locale), new String[] {
                Text.forLocale(locale, "pdf.budget.perPersonExtras"), "", "", page.getIndividualExtras()});
            payments(out, page, locale);
            summary(out, page, locale);
            return out.finish(printed);
        } catch (final IOException ex) {
            throw new UncheckedIOException("Could not draw the budget PDF", ex);
        }
    }

    private static Table sharedTable(final Locale locale) {
        return Table.of(new String[] {Text.forLocale(locale, "pdf.budget.name"),
            Text.forLocale(locale, "common.category"), Text.forLocale(locale, "common.payee"),
            Text.forLocale(locale, "common.note"), Text.forLocale(locale, "common.amount")},
                SHARED_WEIGHTS, SHARED_RIGHT);
    }

    private static Table lodgingTable(final Locale locale) {
        return Table.of(new String[] {Text.forLocale(locale, "common.option"),
            Text.forLocale(locale, "pdf.budget.quantities"), Text.forLocale(locale, "common.price"),
            Text.forLocale(locale, "pdf.budget.hotelRate"), Text.forLocale(locale, "common.revenue"),
            Text.forLocale(locale, "common.hotelCost"), Text.forLocale(locale, "common.margin")},
                LODGING_WEIGHTS, LODGING_RIGHT);
    }

    private static Table extrasTable(final Locale locale) {
        return Table.of(new String[] {Text.forLocale(locale, "pdf.budget.name"),
            Text.forLocale(locale, "common.note"), Text.forLocale(locale, "common.payee"),
            Text.forLocale(locale, "common.perPerson")}, EXTRAS_WEIGHTS, EXTRAS_RIGHT);
    }

    private static Table perOptionTable(final Locale locale) {
        return Table.of(new String[] {Text.forLocale(locale, "common.option"),
            Text.forLocale(locale, "pdf.budget.lodgingPrice"), Text.forLocale(locale, "common.hotelCost"),
            Text.forLocale(locale, "pdf.budget.singleAddOn"), Text.forLocale(locale, "pdf.budget.groundCost"),
            Text.forLocale(locale, "pdf.budget.groundBudget")}, PER_OPTION_WEIGHTS, PER_OPTION_RIGHT);
    }

    private static Table payeesTable(final Locale locale) {
        return Table.of(new String[] {Text.forLocale(locale, "common.payee"),
            Text.forLocale(locale, "common.category"), Text.forLocale(locale, "pdf.budget.lines"),
            Text.forLocale(locale, "common.amount")}, PAYEES_WEIGHTS, PAYEES_RIGHT);
    }

    private static Table otherTable(final Locale locale) {
        return Table.of(new String[] {Text.forLocale(locale, "common.item"),
            Text.forLocale(locale, "common.category"), Text.forLocale(locale, "common.payee"),
            Text.forLocale(locale, "common.amount")}, OTHER_WEIGHTS, OTHER_RIGHT);
    }

    /** {@code "Spring Demo Trip - Budget.pdf"}, with anything a file system would refuse replaced. */
    public static String fileName(final String tripTitle) {
        return safeTitle(tripTitle) + " - Budget.pdf";
    }

    private static List<String[]> shared(final List<LineRow> rows, final Locale locale) {
        final List<String[]> out = new ArrayList<>();
        for (final LineRow r : rows) {
            final String name = r.isOnSite() ? r.getName()
                    : Text.forLocale(locale, "pdf.budget.paidBeforeTrip", r.getName());
            out.add(new String[] {name, r.getCategory(),
                nonNull(r.getPayee()), nonNull(r.getNote()), percent(r.getAmount(), r.getPercentLabel())});
        }
        return out;
    }

    private static List<String[]> extras(final List<LineRow> rows, final Locale locale) {
        final List<String[]> out = new ArrayList<>();
        for (final LineRow r : rows) {
            final String name = r.isPerNight() ? Text.forLocale(locale, "pdf.budget.perNight", r.getName())
                    : r.getName();
            out.add(new String[] {name, nonNull(r.getNote()),
                nonNull(r.getPayee()), percent(r.getTotal(), r.getPercentLabel())});
        }
        return out;
    }

    private static void lodging(final PdfSheet out, final BudgetPage page, final Locale locale)
            throws IOException {
        out.section(Text.forLocale(locale, "common.lodging"));
        final String notSet = Text.forLocale(locale, "common.notSet");
        final List<String[]> rows = new ArrayList<>();
        final List<String[]> perOption = new ArrayList<>();
        for (final LodgingRow l : page.getLodgingRows()) {
            rows.add(new String[] {l.getLabel(), l.getQuantities(), l.isPriceMissing() ? notSet : l.getPriceLabel(),
                l.isCostMissing() ? notSet : l.getCostLabel(), l.getRevenue(), l.getHotelCost(), l.getMargin()});
            perOption.add(new String[] {l.getLabel(), l.getPerPersonPrice(), l.getPerPersonCost(),
                nonNull(l.getSingleAddOn()), l.getTotalPerPersonGround(), l.getToBillReference()});
        }
        out.table(lodgingTable(locale), rows, new String[] {Text.forLocale(locale, "pdf.budget.lodgingTotal"), "",
            "", "", page.getLodgingRevenue(), page.getLodgingCost(), page.getLodgingMargin()});
        if (!perOption.isEmpty()) {
            out.section(Text.forLocale(locale, "pdf.budget.perPersonByOption"));
            out.table(perOptionTable(locale), perOption, null);
        }
    }

    private static void payments(final PdfSheet out, final BudgetPage page, final Locale locale)
            throws IOException {
        out.section(Text.forLocale(locale, "common.payees"));
        final List<String[]> payees = new ArrayList<>();
        for (final PayeeRow p : page.getPayees()) {
            payees.add(new String[] {p.getName(), p.getCategory(), String.valueOf(p.getItems()), p.getAmount()});
        }
        out.table(payeesTable(locale), payees, new String[] {
            Text.forLocale(locale, "pdf.budget.paymentsDuringTrip"), "", "", page.getOnSiteTotal()});
        final String otherPayments = Text.forLocale(locale, "pdf.budget.otherPayments");
        out.section(otherPayments);
        final List<String[]> others = new ArrayList<>();
        for (final OtherRow o : page.getOtherRows()) {
            others.add(new String[] {o.getName(), o.getCategory(), nonNull(o.getPayee()), o.getAmount()});
        }
        out.table(otherTable(locale), others, new String[] {otherPayments, "", "", page.getOtherTotal()});
        out.fields(List.of(
                new String[] {Text.forLocale(locale, "pdf.budget.estimatedTripTotal"), page.getTripTotal()},
                new String[] {Text.forLocale(locale, "pdf.budget.estimatedMoneyToCollect"),
                    page.getMoneyToCollect()}));
    }

    private static void summary(final PdfSheet out, final BudgetPage page, final Locale locale)
            throws IOException {
        out.section(Text.forLocale(locale, "common.summary"), 80);
        out.fields(List.of(
                new String[] {Text.forLocale(locale, "pdf.budget.perPersonSharedBudget"), page.getPerPersonBudget()},
                new String[] {Text.forLocale(locale, "pdf.budget.basicGroundBudget"), page.getToBillRange()},
                new String[] {Text.forLocale(locale, "pdf.budget.lodgingRevenue"), page.getLodgingRevenue()},
                new String[] {Text.forLocale(locale, "pdf.budget.lodgingHotelCost"), page.getLodgingCost()},
                new String[] {Text.forLocale(locale, "pdf.budget.lodgingMargin"), page.getLodgingMargin()}));
        out.bigTotal(nonNull(page.getOverallLabel()), nonNull(page.getOverallAmount()), page.isOverallNegative());
    }

    /** An amount with its percent label after it ("$100.00 (10% of Guide)"). */
    private static String percent(final String amount, final String percentLabel) {
        return (percentLabel == null) ? nonNull(amount) : nonNull(amount) + " (" + percentLabel + ")";
    }
}
