package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.paulsens.trip.action.BudgetViews.BudgetPage;
import org.paulsens.trip.action.BudgetViews.LineRow;
import org.paulsens.trip.action.BudgetViews.LodgingRow;
import org.paulsens.trip.action.BudgetViews.OtherRow;
import org.paulsens.trip.action.BudgetViews.PayeeRow;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** The budget PDF: every section of the page, the bottom line, page breaks with repeated headings. */
public class BudgetPdfTest {

    private static LineRow line(final String name, final String category, final String amount) {
        final LineRow row = new LineRow();
        row.setName(name);
        row.setCategory(category);
        row.setAmount(amount);
        row.setTotal(amount);
        row.setOnSite(true);
        return row;
    }

    private static BudgetPage page() {
        final BudgetPage page = new BudgetPage();
        page.setPeople(46);
        page.setTripNights(10);
        page.setPerPersonBudget("$400.00");
        page.setEstimatedPerPerson("$345.70");
        page.setToBillRange("$1,000.00 to $1,200.00");
        page.setEstimatedGroundPerPerson("$650.31");
        page.getSharedRows().add(line("Medjugorje Guide (Marija)", "Guide", "$1,440.00"));
        final LineRow tips = line("Tips", "Tips", "$144.00");
        tips.setPercentLabel("10% of Medjugorje Guide (Marija)");
        tips.setOnSite(false);
        tips.setPayee("Bus drivers");
        page.getSharedRows().add(tips);
        page.setSharedTotal("$1,584.00");
        final LineRow snacks = line("Snacks", "", "$5.00");
        snacks.setPerNight(true);
        snacks.setTotal("$5.00 × 10 nights = $50.00");
        page.getIndividualRows().add(snacks);
        page.setIndividualExtras("$50.00");
        final LodgingRow lodging = new LodgingRow();
        lodging.setLabel("Pansion Dragićević · Double room");
        lodging.setQuantities("10 nights × 46 people, 28 rooms");
        lodging.setPriceLabel("$60.00 per person per night");
        lodging.setCostMissing(true);
        lodging.setRevenue("$29,600.00");
        lodging.setHotelCost("$0.00");
        lodging.setMargin("$29,600.00");
        lodging.setPerPersonPrice("$600.00");
        lodging.setPerPersonCost("$0.00");
        lodging.setTotalPerPersonGround("$395.70");
        lodging.setToBillReference("$1,000.00");
        page.getLodgingRows().add(lodging);
        page.setLodgingRevenue("$29,600.00");
        page.setLodgingCost("$0.00");
        page.setLodgingMargin("$29,600.00");
        final PayeeRow payee = new PayeeRow();
        payee.setName("[Medjugorje Guide (Marija)]");
        payee.setCategory("Guide");
        payee.setItems(1);
        payee.setAmount("$1,440.00");
        page.getPayees().add(payee);
        page.setOnSiteTotal("$1,440.00");
        final OtherRow other = new OtherRow();
        other.setName("Tips");
        other.setCategory("Tips");
        other.setAmount("$144.00");
        page.getOtherRows().add(other);
        page.setOtherTotal("$144.00");
        page.setTripTotal("$1,584.00");
        page.setMoneyToCollect("$48,000.00");
        page.setOverallLabel("Overall deficit");
        page.setOverallAmount("$2,000.00");
        page.setOverallNegative(true);
        page.getWarnings().add("A lodging option has no hotel cost yet.");
        return page;
    }

    @Test
    public void everySectionOfThePagePrints() throws IOException {
        final byte[] pdf = BudgetPdf.render(page(), "Holy Angels", "CFPW", LocalDate.of(2026, 10, 26));
        final String text = String.join("\n", PdfText.pages(pdf));
        for (final String expected : List.of("Holy Angels: Budget", "CFPW", "46 people, 10 trip nights",
                "Note: A lodging option has no hotel cost yet.", "Inputs", "$1,000.00 to $1,200.00",
                "Shared Expenses", "Medjugorje Guide (Marija)", "Tips (paid before trip)",
                "$144.00 (10% of Medjugorje Guide (Marija))", "Shared expenses total", "$1,584.00",
                "Lodging", "Pansion Dragićević", "not set", "Lodging total", "$29,600.00",
                "Snacks (per night)", "Per person by lodging option", "Payees", "Payments During Trip",
                "Other Payments", "Estimated money to collect", "$48,000.00", "Summary", "Overall deficit",
                "$2,000.00", "Holy Angels: Budget, printed Oct 26, 2026", "Page 1 of")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in:\n" + text);
        }
    }

    @Test
    public void aLongTableBreaksPagesAndRepeatsItsHeadings() throws IOException {
        final BudgetPage page = page();
        for (int i = 0; i < 80; i++) {
            page.getSharedRows().add(line("Line " + i, "Other", "$" + i + ".00"));
        }
        final List<String> pages = PdfText.pages(BudgetPdf.render(page, null, null, LocalDate.of(2026, 10, 26)));
        assertTrue(pages.size() >= 3, "80 more lines run past a page: " + pages.size());
        assertTrue(pages.get(1).contains("Trip: Budget"), "a continuation page names the document");
        assertTrue(pages.get(1).contains("Name") && pages.get(1).contains("Amount"), "and repeats the headings");
        assertTrue(pages.get(0).contains("Page 1 of " + pages.size()));
    }

    private static BudgetPage pageWithACroatianPayee() {
        final BudgetPage page = page();
        final PayeeRow payee = new PayeeRow();
        payee.setName("Šibenik Đurđević");
        payee.setCategory("Guide");
        payee.setItems(2);
        payee.setAmount("$300.00");
        page.getPayees().add(payee);
        return page;
    }

    /** The sheet's own words follow the reader; a name with č, ć, đ, š, ž is drawn, not dropped. */
    @Test
    public void aCroatianReaderGetsCroatianHeadingsAndNamesIntact() throws IOException {
        final String text = String.join("\n", PdfText.pages(BudgetPdf.render(pageWithACroatianPayee(),
                "Šibenik 2027", "CFPW", LocalDate.of(2026, 10, 26), Locale.forLanguageTag("hr"))));
        for (final String expected : List.of("Šibenik 2027: Proračun", "46 osoba, 10 noćenja", "Ulazni podaci",
                "Zajednički troškovi", "Tips (plaćeno prije putovanja)", "Smještaj", "Pansion Dragićević",
                "postavljeno", "Snacks (po noćenju)", "Primatelji", "Šibenik Đurđević",
                "Plaćanja tijekom putovanja", "Sažetak", "Marža na smještaju",
                "Šibenik 2027: Proračun, ispisano: 26. lis 2026.", "Stranica 1 od")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in:\n" + text);
        }
        for (final String english : List.of("Shared Expenses", "Payees", "Summary", "printed", "Page 1")) {
            assertFalse(text.contains(english), "English '" + english + "' in a Croatian sheet:\n" + text);
        }
    }

    @Test
    public void aSpanishReaderGetsSpanishHeadings() throws IOException {
        final String text = String.join("\n", PdfText.pages(BudgetPdf.render(pageWithACroatianPayee(), null,
                "CFPW", LocalDate.of(2026, 10, 26), Locale.forLanguageTag("es"))));
        for (final String expected : List.of("Viaje: Presupuesto", "46 personas, 10 noches de viaje",
                "Gastos compartidos", "Tips (pagado antes del viaje)", "Alojamiento", "sin definir",
                "Beneficiarios", "Šibenik Đurđević", "Pagos durante el viaje", "Resumen",
                "Viaje: Presupuesto, impreso el 26 oct 2026", "Página 1 de")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in:\n" + text);
        }
        assertFalse(text.contains("Shared Expenses"), text);
    }

    @Test
    public void theFileNameIsSafe() {
        assertEquals(BudgetPdf.fileName("Rome: Spring/2027"), "Rome- Spring-2027 - Budget.pdf");
        assertEquals(BudgetPdf.fileName(" "), "Trip - Budget.pdf");
        assertEquals(ExpenseReportPdf.fileName(null), "Trip - Expense Report.pdf");
    }
}
