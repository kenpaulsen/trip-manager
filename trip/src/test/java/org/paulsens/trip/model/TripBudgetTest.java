package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

public class TripBudgetTest {

    @Test
    public void builderEqualsTheNoArgConstructor() {
        assertEquals(TripBudget.builder().build(), new TripBudget());
        assertTrue(new TripBudget().getLines().isEmpty());
        assertFalse(new TripBudget().hasLodging());
    }

    @Test
    public void inputsAreClampedAndListsCopied() {
        final List<BudgetLine> lines = new ArrayList<>(List.of(new BudgetLine()));
        final TripBudget budget = TripBudget.builder().people(-1).tripNights(-2).perPersonBudgetCents(-3)
                .toBillCents(-4).lines(lines).build();
        assertEquals(budget.getPeople(), 0);
        assertEquals(budget.getTripNights(), 0);
        assertEquals(budget.getPerPersonBudgetCents(), 0L);
        assertEquals(budget.getToBillCents(), 0L);
        assertNotSame(budget.getLines(), lines, "The caller's list never becomes the stored one");
        assertEquals(budget.getLines(), lines);
    }

    @Test
    public void lookupsAndGroupsKeepTheBudgetsOrder() {
        final BudgetLine guide = BudgetLine.builder().id("guide").name("Guide").build();
        final BudgetLine sister = BudgetLine.builder().id("sr").category(BudgetCategory.SCHOLARSHIP).build();
        final BudgetLine tips = BudgetLine.builder().id("tips").name("Tips").build();
        final BudgetLine snacks = BudgetLine.builder().id("snacks").kind(BudgetLineKind.INDIVIDUAL).build();
        final BudgetLodgingLine room = BudgetLodgingLine.builder().id("room").build();
        final TripBudget budget = TripBudget.builder().lines(List.of(guide, sister, tips, snacks))
                .lodging(List.of(room)).build();
        assertSame(budget.line("tips"), tips);
        assertNull(budget.line("nope"));
        assertSame(budget.lodgingLine("room"), room);
        assertNull(budget.lodgingLine("nope"));
        assertEquals(budget.sharedLines(BudgetCategory.GENERAL), List.of(guide, tips));
        assertEquals(budget.sharedLines(BudgetCategory.SCHOLARSHIP), List.of(sister));
        assertTrue(budget.sharedLines(BudgetCategory.LEADER).isEmpty());
        assertEquals(budget.individualLines(), List.of(snacks));
        assertTrue(budget.hasLodging());
    }

    @Test
    public void jsonRoundTrip() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final TripBudget budget = TripBudget.builder().tripId("t").orgId("o").people(46).tripNights(10)
                .perPersonBudgetCents(40_000).toBillCents(100_000)
                .lines(List.of(BudgetLine.builder().id("l").name("Guide").amountCents(144_000).build()))
                .lodging(List.of(BudgetLodgingLine.builder().id("r").offerId("o1").nights(10).people(46).build()))
                .createdBy(Person.Id.from("p")).created(LocalDateTime.of(2026, 10, 26, 9, 0)).version(3).build();
        final String json = mapper.writeValueAsString(budget);
        assertEquals(mapper.readValue(json, TripBudget.class), budget);
        assertFalse(json.contains("hasLodging"), "Derived flags are not stored");
    }
}
