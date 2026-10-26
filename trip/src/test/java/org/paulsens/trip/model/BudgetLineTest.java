package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class BudgetLineTest {

    @Test
    public void builderMatchesTheNoArgConstructorOnceTheIdIsShared() {
        final BudgetLine built = BudgetLine.builder().build();
        final BudgetLine plain = new BudgetLine();
        assertNotNull(built.getId(), "An id is minted");
        plain.setId(built.getId());
        assertEquals(built, plain);
        assertEquals(built.getKind(), BudgetLineKind.SHARED);
        assertEquals(built.getCategory(), BudgetCategory.GENERAL);
    }

    @Test
    public void sharedLinesHaveACategoryAndAreNeverPerNight() {
        final BudgetLine line = BudgetLine.builder().kind(BudgetLineKind.SHARED).perNight(true).amountCents(-5)
                .name("  Guide  ").note(" ").payee("  ").build();
        assertEquals(line.getCategory(), BudgetCategory.GENERAL);
        assertFalse(line.isPerNight());
        assertEquals(line.getAmountCents(), 0L, "Never negative");
        assertEquals(line.getName(), "Guide");
        assertNull(line.getNote(), "Blank is null");
        assertNull(line.getPayee());
        assertTrue(line.isShared());
        assertEquals(line.totalCents(10), 0L);
    }

    @Test
    public void individualLinesDropTheCategoryAndMayBePerNight() {
        final BudgetLine line = BudgetLine.builder().id("l1").kind(BudgetLineKind.INDIVIDUAL)
                .category(BudgetCategory.LEADER).perNight(true).amountCents(1000).build();
        assertNull(line.getCategory());
        assertFalse(line.isShared());
        assertEquals(line.totalCents(10), 10_000L);
        assertEquals(line.totalCents(-3), 0L);
        line.setPerNight(false);
        assertEquals(line.totalCents(10), 1000L);
    }

    @Test
    public void percentLinesClampAndAPlainLineIsOnSite() {
        final BudgetLine plain = new BudgetLine();
        assertFalse(plain.isPercent());
        assertTrue(plain.isOnSite(), "on site unless marked otherwise");
        assertEquals(BudgetLine.builder().percentBps(500).build().getPercentBps(), 0, "no base, no percentage");
        final BudgetLine tips = BudgetLine.builder().percentOf(" guide ").percentBps(250_000).offSite(true).build();
        assertTrue(tips.isPercent());
        assertEquals(tips.getPercentOf(), "guide");
        assertEquals(tips.getPercentBps(), BudgetLine.MAX_PERCENT_BPS);
        assertEquals(BudgetLine.builder().percentOf("g").percentBps(-5).build().getPercentBps(), 0);
        assertFalse(tips.isOnSite());
    }

    @Test
    public void aRowWrittenBeforeTheFlagsReadsAsATypedOnSiteLine() throws Exception {
        final BudgetLine old = DAO.getInstance().getMapper().readValue(
                "{\"id\":\"l\",\"kind\":\"SHARED\",\"category\":\"GENERAL\",\"name\":\"Van\",\"amountCents\":5}",
                BudgetLine.class);
        assertTrue(old.isOnSite());
        assertFalse(old.isPercent());
        assertEquals(old.getCategory(), BudgetCategory.GENERAL);
        assertEquals(old.getCategory().getLabel(), "Other");
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final BudgetLine line = BudgetLine.builder().id("l1").kind(BudgetLineKind.SHARED)
                .category(BudgetCategory.SCHOLARSHIP).name("Sr. Dorothy").amountCents(184_853).note("air")
                .payee("Airline").percentOf("base").percentBps(1_250).offSite(true).build();
        final BudgetLine back = mapper.readValue(mapper.writeValueAsString(line), BudgetLine.class);
        assertEquals(back, line);
        assertFalse(mapper.writeValueAsString(line).contains("shared"), "Derived flags are not stored");
    }
}
