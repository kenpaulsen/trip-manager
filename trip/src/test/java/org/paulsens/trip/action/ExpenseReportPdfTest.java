package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.action.ExpenseReportPdf.ReceiptDoc;
import org.paulsens.trip.action.ExpenseViews.ExpensePage;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripExpenseReport;
import org.paulsens.trip.cache.Cached;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
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
        header.setCashAdvance(1000.0);
        header.setCashAdvanceDetails("Check 1001\nplus 200 EUR cash");
        assertTrue(admin.saveHeader(tripId, header));
        assertTrue(admin.saveLine(tripId, ExpenseCommandsTest.line(LocalDate.of(2026, 9, 29), "GUIDE", "Guide",
                "USD", 1440.0)));
        assertTrue(admin.saveLine(tripId, ExpenseCommandsTest.line(null, null, "Party 🎉 supplies",
                "USD", 106.44)));
        final TripExpenseReport report = DAO.getInstance().getTripExpenseReport(tripId, Cached.NO).orElseThrow();
        final String guide = report.getLines().get(0).getId();
        assertTrue(receipts.store(tripId, null, PdfText.pdf(1, "Advance slip"), "advance.pdf").ok());
        assertTrue(receipts.store(tripId, guide, PhotoFixtures.jpeg(900, 600), "guide.jpg").ok());
        assertTrue(receipts.store(tripId, guide, PdfText.pdf(2, "Guide invoice"), "invoice.pdf").ok());

        final List<String> withReceipts = PdfText.pages(admin.reportPdf(tripId, true));
        final String text = String.join("\n", withReceipts);
        for (final String expected : List.of("Holy Angels: Expense Report", "Sep 20, 2026 to Oct 5, 2026",
                "Status: Draft", "Ken Paulsen", "Check 1001", "plus 200 EUR cash", "Budget", "Not in budget",
                "Expenses", "R2, R3", "Party ? supplies", "Total spent", "$1,546.44", "Due to Ken Paulsen",
                "$546.44", "Signature", "Receipts", "R1", "The report", "advance.pdf", "PDF, 1 page", "image",
                "PDF, 2 pages", "R2: Guide (guide.jpg)", "R1: The report (advance.pdf)", "Advance slip page 1",
                "R3: Guide (invoice.pdf)", "Guide invoice page 2")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in:\n" + text);
        }
        final List<String> without = PdfText.pages(admin.reportPdf(tripId, false));
        assertEquals(withReceipts.size(), without.size() + 4, "the image is one page, the two PDFs three");
        assertTrue(withReceipts.get(withReceipts.size() - 1).contains("Page " + withReceipts.size() + " of "),
                "an appended receipt page is numbered too");
        assertNull(new ExpenseCommands(() -> TestCallers.person(Person.Id.newInstance())).reportPdf(tripId, true),
                "no PDF for a caller who may not read the report");
    }

    @Test
    public void paidStatusesAndPaymentsPrint() throws IOException {
        final String tripId = newTrip();
        final ExpenseViews.HeaderForm header = new ExpenseViews.HeaderForm();
        header.setSubmittedBy("Ken Paulsen");
        header.setCashAdvance(100.0);
        assertTrue(admin.saveHeader(tripId, header));
        final ExpenseViews.ExpenseForm hotel = ExpenseCommandsTest.line(null, null, "Hotel", "USD", 500.0);
        hotel.setPaidStatus("ORG");
        assertTrue(admin.saveLine(tripId, hotel));
        final ExpenseViews.ExpenseForm taxi = ExpenseCommandsTest.line(null, null, "Taxi", "USD", 60.0);
        assertTrue(admin.saveLine(tripId, taxi));
        final String before = String.join("\n", PdfText.pages(admin.reportPdf(tripId, false)));
        assertTrue(before.contains("Org paid") && before.contains("Out of pocket"), before);
        assertTrue(!before.contains("Payments recorded"), "No payments section yet");

        final ExpenseViews.PaymentForm payment = admin.newPaymentForm();
        payment.setDate(LocalDate.of(2026, 10, 20));
        payment.setAmount(-40.0);
        payment.setMethod("Zelle");
        payment.setNote("Advance left over");
        payment.setLineIds(new ArrayList<>(List.of(taxi.getId())));
        assertTrue(admin.recordPayment(tripId, payment), payment.getProblem());
        final String text = String.join("\n", PdfText.pages(admin.reportPdf(tripId, false)));
        for (final String expected : List.of("Status: Paid", "Org paid", "Paid Oct 20", "Paid by organization",
                "$500.00", "Out of pocket", "$60.00", "Due to", "$40.00", "Payments", "-$40.00", "Zelle", "Taxi",
                "Advance left over", "Payments recorded", "Settled")) {
            assertTrue(text.contains(expected), "missing '" + expected + "' in:\n" + text);
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
}
