package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

public class TripExpenseReportTest {

    @Test
    public void builderMatchesTheNoArgConstructor() {
        final TripExpenseReport built = TripExpenseReport.builder().build();
        assertEquals(built, new TripExpenseReport());
        assertTrue(built.getLines().isEmpty());
        assertTrue(built.getCurrencies().isEmpty());
        assertTrue(built.getReceiptIds().isEmpty());
        assertTrue(built.getPayments().isEmpty());
        assertEquals(built.getStatus(), ExpenseStatus.DRAFT);
    }

    @Test
    public void theConstructorNormalizes() {
        final TripExpenseReport report = TripExpenseReport.builder().cashAdvanceCents(-1L).submittedBy("  ")
                .cashAdvanceDetails(" Wire ").paymentInstructions("").build();
        assertEquals(report.getCashAdvanceCents(), 0L);
        assertNull(report.getSubmittedBy());
        assertEquals(report.getCashAdvanceDetails(), "Wire");
        assertNull(report.getPaymentInstructions());
    }

    @Test
    public void statusFollowsTheStampsAndPayments() {
        final ExpenseLine taxi = ExpenseLine.builder().id("taxi").amountMinor(2_000L).build();
        final ExpenseLine stamps = ExpenseLine.builder().id("stamps").amountMinor(500L).build();
        final ExpenseLine hotel = ExpenseLine.builder().id("hotel").paidStatus(ExpensePaidStatus.ORG).build();
        final TripExpenseReport report = TripExpenseReport.builder().lines(List.of(taxi, stamps, hotel)).build();
        report.setSubmittedOn(LocalDateTime.of(2026, 10, 7, 9, 0));
        assertEquals(report.getStatus(), ExpenseStatus.SUBMITTED);
        assertEquals(report.unpaidLines(), List.of(taxi, stamps), "An org-paid line is never unpaid");
        final ExpensePayment first = ExpensePayment.builder().id("p1").lineIds(List.of("taxi")).build();
        report.getPayments().add(first);
        assertEquals(report.getStatus(), ExpenseStatus.PARTLY_PAID);
        assertSame(report.paymentFor("taxi"), first);
        assertTrue(report.isPaid("taxi"));
        assertFalse(report.isPaid("stamps"));
        assertNull(report.paymentFor("hotel"));
        report.getPayments().add(ExpensePayment.builder().id("p2").lineIds(List.of("stamps")).build());
        assertEquals(report.getStatus(), ExpenseStatus.PAID);
        assertTrue(report.unpaidLines().isEmpty());
        report.setSubmittedOn(null);
        assertEquals(report.getStatus(), ExpenseStatus.PAID, "Paid wins even without a submit stamp");
        assertSame(report.payment("p2"), report.getPayments().get(1));
        assertNull(report.payment("p9"));
    }

    @Test
    public void aPaymentWithNoLinesSettlesAReportWithNothingUnpaid() {
        final TripExpenseReport report = TripExpenseReport.builder().lines(List.of(ExpenseLine.builder()
                .paidStatus(ExpensePaidStatus.REIMBURSED).build())).build();
        report.getPayments().add(new ExpensePayment());
        assertEquals(report.getStatus(), ExpenseStatus.PAID);
        assertEquals(ExpenseStatus.PARTLY_PAID.getLabel(), "Partly paid");
    }

    @Test
    public void lookupsFindLinesCurrenciesAndReceipts() {
        final ExpenseLine eur = ExpenseLine.builder().id("a").currency("EUR").receiptIds(List.of("r1")).build();
        final ExpenseLine usd = ExpenseLine.builder().id("b").build();
        final CurrencyRate rate = CurrencyRate.builder().code("EUR").perUsdMicros(830_000L).build();
        final TripExpenseReport report = TripExpenseReport.builder().lines(List.of(eur, usd))
                .currencies(List.of(new CurrencyRate(), rate)).receiptIds(List.of("r0")).build();
        assertSame(report.line("b"), usd);
        assertNull(report.line("zz"));
        assertSame(report.currency("EUR"), rate);
        assertNull(report.currency("USD"));
        assertEquals(report.linesUsing("EUR"), List.of(eur));
        assertSame(report.lineWithReceipt("r1"), eur);
        assertNull(report.lineWithReceipt("r0"));
        assertTrue(report.holdsReceipt("r0"));
        assertTrue(report.holdsReceipt("r1"));
        assertFalse(report.holdsReceipt("r9"));
        assertEquals(report.allReceiptIds(), List.of("r0", "r1"));
    }

    @Test
    public void jsonRoundTripStoresNoDerivedFields() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final TripExpenseReport report = TripExpenseReport.builder().tripId("t").orgId("o").submittedBy("Ken")
                .submittedById(new Person.Id("p")).submittedOn(LocalDateTime.of(2026, 10, 7, 9, 30))
                .cashAdvanceCents(4_576_500L).cashAdvanceDetails("Check").paymentInstructions("Zelle")
                .emailedTo("treasurer@example.org")
                .emailedOn(LocalDateTime.of(2026, 10, 21, 9, 0))
                .currencies(List.of(CurrencyRate.builder().code("EUR").perUsdMicros(830_000L).build()))
                .lines(List.of(ExpenseLine.builder().id("l").amountMinor(100L).build()))
                .payments(List.of(ExpensePayment.builder().id("p").date(LocalDate.of(2026, 10, 20))
                        .amountCents(-500L).method("Zelle").lineIds(List.of("l")).recordedBy("Tess").build()))
                .receiptIds(List.of("r")).createdBy(new Person.Id("p")).created(LocalDateTime.of(2026, 10, 1, 8, 0))
                .version(3L).build();
        final String json = mapper.writeValueAsString(report);
        assertFalse(json.contains("\"status\""));
        assertFalse(json.contains("allReceiptIds"));
        assertFalse(json.contains("unpaidLines"));
        assertEquals(mapper.readValue(json, TripExpenseReport.class), report);
    }
}
