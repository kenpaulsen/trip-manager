package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class ExpenseLineTest {

    @Test
    public void builderMatchesTheNoArgConstructorOnceTheIdIsShared() {
        final ExpenseLine built = ExpenseLine.builder().build();
        final ExpenseLine plain = new ExpenseLine();
        assertNotNull(built.getId(), "An id is minted");
        plain.setId(built.getId());
        assertEquals(built, plain);
        assertEquals(built.getCategory(), ExpenseCategory.GENERAL);
        assertEquals(built.getCurrency(), ExpenseLine.USD);
        assertTrue(built.isUsd());
        assertTrue(built.getReceiptIds().isEmpty());
        assertNull(built.getDate(), "No date reads Various");
        assertEquals(built.getPaidStatus(), ExpensePaidStatus.NOT_PAID);
        assertTrue(built.isOutOfPocket());
    }

    @Test
    public void theConstructorNormalizes() {
        final ExpenseLine line = ExpenseLine.builder().id(" ").currency(" eur").amountMinor(-1L)
                .description("  Candles ").budgetLineId(" ").note("").category(ExpenseCategory.SUPPLIES)
                .date(LocalDate.of(2026, 9, 25)).receiptIds(List.of("m1")).build();
        assertNotNull(line.getId());
        assertFalse(line.getId().isBlank());
        assertEquals(line.getCurrency(), "EUR");
        assertFalse(line.isUsd());
        assertEquals(line.getAmountMinor(), 0L);
        assertEquals(line.getDescription(), "Candles");
        assertNull(line.getBudgetLineId());
        assertNull(line.getNote());
        assertEquals(line.getReceiptIds(), List.of("m1"));
        line.getReceiptIds().add("m2"); // A mutable copy, never the caller's list
        assertEquals(ExpenseLine.builder().currency("nope").build().getCurrency(), ExpenseLine.USD);
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final ExpenseLine line = ExpenseLine.builder().id("x1").date(LocalDate.of(2026, 10, 4))
                .category(ExpenseCategory.TRANSPORTATION).description("Rental van").currency("BAM")
                .amountMinor(80_000L).budgetLineId("b1").note("Dragan").receiptIds(List.of("r1", "r2"))
                .paidStatus(ExpensePaidStatus.REIMBURSED).build();
        assertFalse(line.isOutOfPocket());
        final String json = mapper.writeValueAsString(line);
        assertFalse(json.contains("\"usd\""), "Derived flags are not stored");
        assertFalse(json.contains("outOfPocket"), "Derived flags are not stored");
        assertEquals(mapper.readValue(json, ExpenseLine.class), line);
    }

    @Test
    public void categoriesMapFromTheBudgetByName() {
        for (final BudgetCategory category : BudgetCategory.values()) {
            assertEquals(ExpenseCategory.ofBudget(category).name(), category.name());
            assertEquals(ExpenseCategory.ofBudget(category).getLabel(), category.getLabel());
        }
        assertEquals(ExpenseCategory.ofBudget(null), ExpenseCategory.GENERAL);
        assertEquals(ExpenseCategory.POSTAGE.getLabel(), "Postage");
        assertEquals(ExpenseStatus.SUBMITTED.getLabel(), "Submitted");
    }

    @Test
    public void paidStatusesNameTheirChips() {
        assertNull(ExpensePaidStatus.NOT_PAID.getChip());
        assertFalse(ExpensePaidStatus.NOT_PAID.isSettled());
        assertEquals(ExpensePaidStatus.ORG.getLabel(), "Paid by organization");
        assertEquals(ExpensePaidStatus.ORG.getChip(), "Org paid");
        assertEquals(ExpensePaidStatus.REIMBURSED.getChip(), "Reimbursed");
        assertTrue(ExpensePaidStatus.REIMBURSED.isSettled());
    }
}
