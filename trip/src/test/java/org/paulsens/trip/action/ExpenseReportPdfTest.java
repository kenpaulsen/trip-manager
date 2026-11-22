package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.action.ExpenseReportPdf.ReceiptDoc;
import org.paulsens.trip.action.ExpenseViews.ExpensePage;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripExpenseReport;
import org.paulsens.trip.cache.Cached;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** The expense report PDF: the page's sections, and the receipts appendix in every shape a receipt comes in. */
public class ExpenseReportPdfTest {

    private final ExpenseCommands admin = new ExpenseCommands(TestCallers::siteAdmin);
    private final ExpenseReceipts receipts = new ExpenseReceipts(TestCallers::siteAdmin);

    @BeforeClass
    public void seed() {
        FakeData.initFakeData();
        FakeData.addFakeData();
    }

    private String newTrip() throws IOException {
        final Trip trip = Trip.builder().id(UUID.randomUUID().toString()).title("Holy Angels")
                .startDate(LocalDateTime.of(2026, 9, 20, 8, 0)).endDate(LocalDateTime.of(2026, 10, 5, 8, 0))
                .people(new ArrayList<>()).build();
        trip.setOrgId(FakeData.CFPW_ORG_ID);
        assertTrue(DAO.getInstance().saveTrip(trip));
        return trip.getId();
    }

    @Test
    public void theReportAndItsReceiptsInOneDocument() throws IOException {
        final String tripId = newTrip();
        final ExpenseViews.HeaderForm header = new ExpenseViews.HeaderForm();
        header.setSubmittedBy("Ken Paulsen");
        assertTrue(admin.saveHeader(tripId, header));
        final ExpenseViews.AdvanceForm cash = ExpenseCommandsTest.advance("CASH", 1000.0, "CFPW");
        cash.setNote("Check 1001");
        assertTrue(admin.saveAdvance(tripId, cash), cash.getProblem());
        assertTrue(admin.saveLine(tripId, ExpenseCommandsTest.line(LocalDate.of(2026, 9, 29), "GUIDE", "Guide",
                "USD", 1440.0)));
        assertTrue(admin.saveLine(tripId, ExpenseCommandsTest.line(null, null, "Party 🎉 supplies",
                "USD", 106.44)));
        final TripExpenseReport report = DAO.getInstance().getTripExpenseReport(tripId, Cached.NO).orElseThrow();
        final String guide = report.getLines().get(0).getId();
        assertTrue(receipts.store(tripId, null, PdfText.pdf(1, "Advance slip"), "advance.pdf").ok());
        assertTrue(receipts.store(tripId, guide, PhotoFixtures.jpeg(900, 600), "guide.jpg").ok());
        assertTrue(receipts.store(tripId, guide, PdfText.pdf(2, "Guide invoice"), "invoice.pdf").ok());

        final List<String> withReceipts = PdfText.pages(admin.reportPdf(tripId, true, Locale.ENGLISH));
        final String text = String.join("\n", withReceipts);
        for (final String expected : List.of("Holy Angels: Expense Report", "Sep 20, 2026 to Oct 5, 2026",
                "Status: Draft", "Ken Paulsen", "Cash advanced", "Total advanced", "CFPW", "Check 1001",
                "Budget", "Not in budget",
                "Expenses", "R2, R3", "Party ? supplies", "Total spent", "$1,546.44", "Due to Ken Paulsen",
                "$546.44", "Signature", "Receipts", "R1", "The report", "advance.pdf", "PDF, 1 page", "image",
                "PDF, 2 pages", "R2: Guide (guide.jpg)", "R1: The report (advance.pdf)", "Advance slip page 1",
                "R3: Guide (invoice.pdf)", "Guide invoice page 2")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in:\n" + text);
        }
        final List<String> without = PdfText.pages(admin.reportPdf(tripId, false, Locale.ENGLISH));
        assertEquals(withReceipts.size(), without.size() + 4, "the image is one page, the two PDFs three");
        assertTrue(withReceipts.get(withReceipts.size() - 1).contains("Page " + withReceipts.size() + " of "),
                "an appended receipt page is numbered too");
        assertNull(new ExpenseCommands(() -> TestCallers.person(Person.Id.newInstance()))
                .reportPdf(tripId, true, Locale.ENGLISH),
                "no PDF for a caller who may not read the report");
    }

    @Test
    public void paidStatusesAndPaymentsPrint() throws IOException {
        final String tripId = newTrip();
        final ExpenseViews.HeaderForm header = new ExpenseViews.HeaderForm();
        header.setSubmittedBy("Ken Paulsen");
        assertTrue(admin.saveHeader(tripId, header));
        assertTrue(admin.saveAdvance(tripId, ExpenseCommandsTest.advance("CASH", 400.0, null)));
        assertTrue(admin.saveAdvance(tripId, ExpenseCommandsTest.advance("RETURNED", 300.0, "CFPW")));
        final ExpenseViews.ExpenseForm hotel = ExpenseCommandsTest.line(null, null, "Hotel", "USD", 500.0);
        hotel.setPaidStatus("ORG");
        assertTrue(admin.saveLine(tripId, hotel));
        final ExpenseViews.ExpenseForm taxi = ExpenseCommandsTest.line(null, null, "Taxi", "USD", 60.0);
        assertTrue(admin.saveLine(tripId, taxi));
        final String before = String.join("\n", PdfText.pages(admin.reportPdf(tripId, false, Locale.ENGLISH)));
        assertTrue(before.contains("Org paid") && before.contains("Not yet reimbursed"), before);
        assertTrue(before.contains("Money returned") && before.contains("-$300.00"), before);
        assertTrue(!before.contains("Payments recorded"), "No payments section yet");

        final ExpenseViews.PaymentForm payment = admin.newPaymentForm();
        payment.setDate(LocalDate.of(2026, 10, 20));
        payment.setAmount(-40.0);
        payment.setMethod("Zelle");
        payment.setNote("Advance left over");
        payment.setLineIds(new ArrayList<>(List.of(taxi.getId())));
        assertTrue(admin.recordPayment(tripId, payment), payment.getProblem());
        final String text = String.join("\n", PdfText.pages(admin.reportPdf(tripId, false, Locale.ENGLISH)));
        for (final String expected : List.of("Status: Paid", "Org paid", "Paid Oct 20", "Paid by organization",
                "$500.00", "Not yet reimbursed", "$60.00", "Due to", "$40.00", "Payments", "-$40.00", "Zelle", "Taxi",
                "Advance left over", "Payments recorded", "Settled")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in:\n" + text);
        }
    }

    /**
     * A download is in the request's language, which the servlet and the REST edge pass in: the labels the page
     * builds (status, what is due, "Various", a paid chip, the receipts index) follow it with the PDF's own.
     */
    @Test
    public void aSpanishDownloadIsSpanishThroughout() throws IOException {
        final String tripId = newTrip();
        final ExpenseViews.HeaderForm header = new ExpenseViews.HeaderForm();
        header.setSubmittedBy("Ken Paulsen");
        assertTrue(admin.saveHeader(tripId, header));
        assertTrue(admin.saveAdvance(tripId, ExpenseCommandsTest.advance("CASH", 100.0, "CFPW")));
        final ExpenseViews.ExpenseForm hotel = ExpenseCommandsTest.line(null, null, "Hotel", "USD", 500.0);
        hotel.setPaidStatus("ORG");
        assertTrue(admin.saveLine(tripId, hotel));
        assertTrue(admin.saveLine(tripId, ExpenseCommandsTest.line(null, null, "Taxi", "USD", 600.0)));
        assertTrue(receipts.store(tripId, null, PdfText.pdf(1, "Advance slip"), "advance.pdf").ok());

        final String text = String.join("\n", PdfText.pages(admin.reportPdf(tripId, true,
                Language.Spanish.getLocale())));
        for (final String expected : List.of("Holy Angels: Informe de gastos", "Borrador", "Varias",
                "Pagó la org.", "Se debe a Ken Paulsen", "Efectivo anticipado", "R1: El informe (advance.pdf)",
                "20 sept 2026 a 5 oct 2026")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in:\n" + text);
        }
        for (final String english : List.of("Expense Report", "Draft", "Various", "Due to", "The report",
                "Sep 20")) {
            assertFalse(text.contains(english), "English '" + english + "' in a Spanish report:\n" + text);
        }
    }

    @Test
    public void anUnusableReceiptIsListedNotFatal() throws IOException {
        final ExpensePage page = new ExpensePage();
        page.setTripTitle("Rome");
        page.setStatus("Draft");
        page.setCashAdvance("$0.00");
        page.setTotal("$0.00");
        page.setDueLabel("Due to CFPW");
        page.setDue("$0.00");
        page.setBudgetTotal("$0.00");
        page.setSpentTotal("$0.00");
        page.setRemainingTotal("$0.00");
        final List<ReceiptDoc> docs = List.of(
                new ReceiptDoc("R1", "Van", "gone.jpg", "image/jpeg", null),
                new ReceiptDoc("R2", "Van", "broken.pdf", ExpenseReceipts.PDF, new byte[] {1, 2, 3}),
                new ReceiptDoc("R3", "Van", "broken.png", "image/png", new byte[] {1, 2, 3}));
        final String text = String.join("\n", PdfText.pages(ExpenseReportPdf.render(page, Map.of(), docs,
                LocalDate.of(2026, 10, 26))));
        assertTrue(text.contains("gone.jpg") && text.contains("missing"), text);
        assertTrue(text.contains("broken.pdf"), text);
        assertTrue(text.contains("This receipt's image could not be drawn."), text);
        assertEquals(ExpenseReportPdf.pageCount(docs.get(1)), 0, "an unreadable PDF adds no pages");
        assertEquals(ExpenseReportPdf.pageCount(new ReceiptDoc("R", "x", "x", ExpenseReceipts.PDF,
                PdfText.pdf(3, "x"))), 3);
    }

    /** A page with a row in every table, as the page builder would hand it over, for the translated sheets. */
    private static ExpensePage fullPage() {
        final ExpensePage page = new ExpensePage();
        page.setTripTitle("Šibenik 2027");
        page.setTripDates("Sep 20, 2026 to Oct 5, 2026");
        page.setOrgName("CFPW");
        page.setStatus("Draft");
        page.setSubmittedBy("Šibenik Đurđević");
        page.setPaymentInstructions("Zelle");
        final ExpenseViews.AdvanceRow advance = new ExpenseViews.AdvanceRow();
        advance.setNumber(1);
        advance.setTypeLabel("Cash advance");
        advance.setDateText("Sep 1, 2026");
        advance.setParty("CFPW");
        advance.setAmount("$1,000.00");
        advance.setUsd("$1,000.00");
        advance.setNote("Check 1001");
        page.getAdvanceRows().add(advance);
        page.setCashAdvance("$1,000.00");
        final ExpenseViews.CurrencyRow euro = new ExpenseViews.CurrencyRow();
        euro.setCode("EUR");
        euro.setRateText("1 USD = 0.92 EUR");
        euro.setInverseText("1 EUR = $1.0870");
        euro.setLines(1);
        euro.setForeignTotal("€100.00");
        euro.setUsdTotal("$108.70");
        page.getCurrencyRows().add(euro);
        final ExpenseViews.BudgetRow budget = new ExpenseViews.BudgetRow();
        budget.setLabel("Guide");
        budget.setSection("Shared");
        budget.setCategory("Guide");
        budget.setBudget("$1,440.00");
        budget.setSpent("$1,440.00");
        budget.setRemaining("$0.00");
        page.getBudgetRows().add(budget);
        page.setBudgetTotal("$1,440.00");
        page.setSpentTotal("$1,548.70");
        page.setRemainingTotal("-$108.70");
        final ExpenseViews.ExpenseRow guide = new ExpenseViews.ExpenseRow();
        guide.setId("guide");
        guide.setDateText("Sep 29, 2026");
        guide.setCategory("Guide");
        guide.setDescription("Vodič Željko Čačić");
        guide.setAmountText("$1,440.00");
        guide.setUsd("$1,440.00");
        guide.setBudgetLabel("Guide");
        guide.setPaidChip("Paid Oct 20");
        page.getLineRows().add(guide);
        page.setTotal("$1,548.70");
        page.setLinesWithoutReceipt(2);
        page.setHasSettled(true);
        page.setPaidByOrg("$0.00");
        page.setReimbursed("$40.00");
        page.setOutOfPocket("$508.70");
        page.setDueLabel("Due to Šibenik Đurđević");
        page.setDue("$548.70");
        final ExpenseViews.PaymentRow payment = new ExpenseViews.PaymentRow();
        payment.setDateText("Oct 20, 2026");
        payment.setAmount("$40.00");
        payment.setMethod("Zelle");
        payment.setLines("Guide");
        payment.setNote("First part");
        payment.setRecordedLine("Oct 20 by Ken");
        page.getPaymentRows().add(payment);
        page.setPaymentsTotal("$40.00");
        page.setStillDueLabel("Still due to Šibenik Đurđević");
        page.setStillDue("$508.70");
        page.getWarnings().add("EUR has no rate.");
        return page;
    }

    private static List<ReceiptDoc> twoReceipts() throws IOException {
        return List.of(new ReceiptDoc("R1", "Vodič", "račun.pdf", ExpenseReceipts.PDF, PdfText.pdf(2, "Invoice")),
                new ReceiptDoc("R2", "Vodič", "gone.jpg", "image/jpeg", null));
    }

    /** The sheet's own words follow the reader named; a name with č, ć, đ, š, ž is drawn, not dropped. */
    @Test
    public void aCroatianReaderGetsCroatianHeadingsAndNamesIntact() throws IOException {
        final String text = String.join("\n", PdfText.pages(ExpenseReportPdf.render(fullPage(),
                Map.of("guide", "R1, R2"), twoReceipts(), LocalDate.of(2026, 10, 26), Locale.forLanguageTag("hr"))));
        for (final String expected : List.of("Šibenik 2027: Izvještaj o troškovima", "Status: Draft",
                "Napomena: EUR has no rate.", "Šibenik Đurđević", "Predujam u gotovini", "Predujam ukupno", "Valute",
                "Tečaj", "Proračun", "Troškovi", "Vodič Željko Čačić", "Ukupno potrošeno", "2 troška nemaju račun.",
                "Uplate", "Zabilježene uplate", "Zbrojevi", "Još nije nadoknađeno", "Due to Šibenik Đurđević",
                "Potpis: ", "Datum: ", "Računi", "račun.pdf", "PDF, 2 stranice", "nedostaje",
                "R1: Vodič (račun.pdf)", "ispisano: 26. lis 2026.", "Stranica 1 od 4")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in:\n" + text);
        }
        for (final String english : List.of("Expense Report", "Total spent", "Signature", "printed", "Page 1")) {
            assertFalse(text.contains(english), "English '" + english + "' in a Croatian sheet:\n" + text);
        }
    }

    @Test
    public void aSpanishReaderGetsSpanishHeadings() throws IOException {
        final String text = String.join("\n", PdfText.pages(ExpenseReportPdf.render(fullPage(),
                Map.of("guide", "R1, R2"), twoReceipts(), LocalDate.of(2026, 10, 26), Locale.forLanguageTag("es"))));
        for (final String expected : List.of("Šibenik 2027: Informe de gastos", "Estado: Draft", "Presentado por",
                "Efectivo anticipado", "Monedas", "Presupuesto", "Gastos", "Total gastado",
                "2 gastos no tienen recibo.", "Pagos registrados", "Totales", "Firma: ", "Recibos",
                "PDF, 2 páginas", "falta", "impreso el 26 oct 2026", "Página 1 de 4")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in:\n" + text);
        }
        assertFalse(text.contains("Total spent"), text);
    }

    /** The treasurer's copy is mailed, so no request names its language: without one it is English. */
    @Test
    public void withoutAReaderNamedTheSheetIsEnglish() throws IOException {
        final String text = String.join("\n", PdfText.pages(ExpenseReportPdf.render(fullPage(), Map.of(),
                List.of(), LocalDate.of(2026, 10, 26))));
        assertTrue(text.contains("Šibenik 2027: Expense Report") && text.contains("2 expenses have no receipt."),
                text);
    }
}
