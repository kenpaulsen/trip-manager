package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class ExpensePaymentTest {

    @Test
    public void builderMatchesTheNoArgConstructorOnceTheIdIsShared() {
        final ExpensePayment built = ExpensePayment.builder().build();
        final ExpensePayment plain = new ExpensePayment();
        assertNotNull(built.getId(), "An id is minted");
        plain.setId(built.getId());
        assertEquals(built, plain);
        assertTrue(built.getLineIds().isEmpty());
        assertEquals(built.getAmountCents(), 0L);
    }

    @Test
    public void theConstructorNormalizesButKeepsTheSign() {
        final List<String> ids = new ArrayList<>(List.of("a"));
        final ExpensePayment payment = ExpensePayment.builder().id(" ").amountCents(-50_000L).method("  Zelle ")
                .note("").recordedBy(" ").lineIds(ids).build();
        assertFalse(payment.getId().isBlank());
        assertEquals(payment.getAmountCents(), -50_000L, "Negative = the submitter paid the organization back");
        assertEquals(payment.getMethod(), "Zelle");
        assertNull(payment.getNote());
        assertNull(payment.getRecordedBy());
        ids.add("b");
        assertEquals(payment.getLineIds(), List.of("a"), "A copy, never the caller's list");
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final ExpensePayment payment = ExpensePayment.builder().id("p1").date(LocalDate.of(2026, 10, 20))
                .amountCents(941_041L).method("Check 2002").note("Less the advance").lineIds(List.of("a", "b"))
                .recordedById(new Person.Id("x")).recordedBy("Tess Treasurer")
                .recordedOn(LocalDateTime.of(2026, 10, 20, 9, 0)).build();
        assertEquals(mapper.readValue(mapper.writeValueAsString(payment), ExpensePayment.class), payment);
    }
}
