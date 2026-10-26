package org.paulsens.trip.pay;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.paulsens.trip.model.BudgetLine;
import org.paulsens.trip.model.BudgetLineKind;
import org.paulsens.trip.model.BudgetLodgingLine;
import org.paulsens.trip.model.ExpenseLine;
import org.paulsens.trip.model.TripBudget;
import org.paulsens.trip.model.TripExpenseReport;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class ExpenseMathTest {

    @Test
    public void theSheetsForeignLinesConvertToTheCent() {
        final TripExpenseReport report = ExpenseOracle.report();
        assertEquals(ExpenseMath.lineUsd(report, report.line("candles")), 2_771L, "23 EUR at 0.83");
        assertEquals(ExpenseMath.lineUsd(report, report.line("gradnici")), 26_506L, "220 EUR at 0.83");
        assertEquals(ExpenseMath.lineUsd(report, report.line("van")), 48_193L, "800 BAM at 1.66");
        assertEquals(ExpenseMath.lineUsd(report, report.line("tips-bam")), 12_048L, "200 BAM at 1.66");
        final long tips = ExpenseMath.lineUsd(report, report.line("tips-eur"))
                + ExpenseMath.lineUsd(report, report.line("tips-bam"))
                + ExpenseMath.lineUsd(report, report.line("tips-usd"));
        assertEquals(tips, 18_867L, "The sheet's mixed tips row: $188.67");
        assertEquals(ExpenseMath.lineUsd(report, report.line("guide")), 144_000L, "USD as typed");
    }

    @Test
    public void theSheetsTotalAndDue() {
        final TripExpenseReport report = ExpenseOracle.report();
        assertEquals(ExpenseMath.total(report), ExpenseOracle.TOTAL);
        assertEquals(ExpenseMath.due(report), ExpenseOracle.DUE, "Negative: owed to the submitter");
    }

    @Test
    public void conversionRoundsHalfUpAndNeedsARate() {
        assertEquals(ExpenseMath.usdCents(1L, 2_000_000L), 1L, "0.5 cent rounds up");
        assertEquals(ExpenseMath.usdCents(1L, 3_000_000L), 0L, "0.33 cent rounds down");
        assertEquals(ExpenseMath.usdCents(100L, 0L), 0L);
        final TripExpenseReport report = ExpenseOracle.report();
        final ExpenseLine yen = ExpenseLine.builder().currency("JPY").amountMinor(10_000L).build();
        report.getLines().add(yen);
        assertTrue(ExpenseMath.rateMissing(report, yen));
        assertEquals(ExpenseMath.lineUsd(report, yen), 0L);
        assertEquals(ExpenseMath.total(report), ExpenseOracle.TOTAL, "An unpriced line counts nothing");
        assertFalse(ExpenseMath.rateMissing(report, report.line("guide")));
        assertFalse(ExpenseMath.rateMissing(report, report.line("van")));
        report.currency("BAM").setPerUsdMicros(0L);
        assertTrue(ExpenseMath.rateMissing(report, report.line("van")), "A zero rate is no rate");
    }

    @Test
    public void aFineTunedRateRepricesItsLines() {
        final TripExpenseReport report = ExpenseOracle.report();
        report.currency("EUR").setPerUsdMicros(850_000L);
        assertEquals(ExpenseMath.lineUsd(report, report.line("gradnici")), 25_882L, "220 / 0.85");
    }

    @Test
    public void perCurrencySubtotals() {
        final TripExpenseReport report = ExpenseOracle.report();
        assertEquals(ExpenseMath.currencyTotal(report, "EUR"), 4_000L + 2_300L + 22_000L);
        assertEquals(ExpenseMath.currencyUsd(report, "EUR"), 4_819L + 2_771L + 26_506L);
        assertEquals(ExpenseMath.currencyTotal(report, "BAM"), 100_000L);
        assertEquals(ExpenseMath.currencyUsd(report, "BAM"), 12_048L + 48_193L);
        assertEquals(ExpenseMath.currencyTotal(report, "GBP"), 0L);
    }

    @Test
    public void spentAgainstBudgetLinesAddsUpToTheTotal() {
        final TripExpenseReport report = ExpenseOracle.report();
        report.line("guide").setBudgetLineId("b-guide");
        report.line("tips-eur").setBudgetLineId("b-tips");
        report.line("tips-usd").setBudgetLineId("b-tips");
        report.line("left").setBudgetLineId("b-gone");
        assertEquals(ExpenseMath.spentOn(report, "b-guide"), 144_000L);
        assertEquals(ExpenseMath.spentOn(report, "b-tips"), 4_819L + 2_000L);
        assertEquals(ExpenseMath.spentOn(report, null), 0L);
        final Set<String> known = Set.of("b-guide", "b-tips");
        final long notInBudget = ExpenseMath.notInBudget(report, known);
        assertEquals(notInBudget + 144_000L + 6_819L, ExpenseOracle.TOTAL,
                "A line whose budget line is gone counts as not in budget, so the column still adds up");
    }

    @Test
    public void ratesReadBothWays() {
        assertEquals(ExpenseMath.micros(new BigDecimal("0.83")), 830_000L);
        assertEquals(ExpenseMath.micros(new BigDecimal("1.6600004")), 1_660_000L);
        assertEquals(ExpenseMath.micros(null), 0L);
        assertEquals(ExpenseMath.rate(830_000L).toPlainString(), "0.83");
        assertEquals(ExpenseMath.rate(117_000_000L).toPlainString(), "117");
        assertEquals(ExpenseMath.usdPerUnit(830_000L).toPlainString(), "1.2048");
        assertEquals(ExpenseMath.usdPerUnit(1_660_000L).toPlainString(), "0.6024");
        assertNull(ExpenseMath.usdPerUnit(0L));
    }

    @Test
    public void receiptsMissingCountsLinesWithoutOne() {
        final TripExpenseReport report = ExpenseOracle.report();
        assertEquals(ExpenseMath.linesWithoutReceipt(report), report.getLines().size());
        report.line("guide").getReceiptIds().add("r1");
        assertEquals(ExpenseMath.linesWithoutReceipt(report), report.getLines().size() - 1);
    }

    @Test
    public void budgetedAmountsAreWholeTripCosts() {
        final BudgetLine guide = BudgetLine.builder().amountCents(144_000L).build();
        final BudgetLine badge = BudgetLine.builder().kind(BudgetLineKind.INDIVIDUAL).amountCents(1_000L).build();
        final TripBudget budget = TripBudget.builder().people(46).tripNights(10).lines(List.of(guide, badge)).build();
        assertEquals(ExpenseMath.budgeted(budget, guide), 144_000L);
        assertEquals(ExpenseMath.budgeted(budget, badge), 46_000L, "Per person x people");
        assertEquals(ExpenseMath.budgeted((BudgetLodgingLine) null, null), 0L, "A gone option budgets nothing");
    }
}
