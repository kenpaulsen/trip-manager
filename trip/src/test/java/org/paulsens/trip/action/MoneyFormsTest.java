package org.paulsens.trip.action;

import org.paulsens.trip.model.ExpenseCategory;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class MoneyFormsTest {

    @Test
    public void aPercentBecomesBasisPointsHalfUp() {
        assertEquals(MoneyForms.bps(null), 0);
        assertEquals(MoneyForms.bps(12.5), 1250);
        assertEquals(MoneyForms.bps(100.0), 10_000);
        // 12.345 * 100 is 1234.4999... in binary: Math.round said 1234, the decimal rule says 1235.
        assertEquals(MoneyForms.bps(12.345), 1235);
        assertEquals(MoneyForms.bps(0.004), 0);
    }

    @Test
    public void dollarsAndCentsRoundTrip() {
        assertEquals(MoneyForms.cents(null), 0L);
        assertEquals(MoneyForms.cents(45_765.0), 4_576_500L);
        assertEquals(MoneyForms.cents(0.005), 1L, "HALF_UP to the cent");
        assertEquals(MoneyForms.dollars(4_576_500L), 45_765.0);
        assertEquals(MoneyForms.money(-979_595L), "-$9,795.95");
    }

    private static final String AMOUNT = "feedback.moneyForms.amountRange";
    private static final String SIGNED = "feedback.moneyForms.signedAmountRange";
    private static final String NOTE = "feedback.moneyForms.noteTooLong";

    @Test
    public void amountsAndTextAreChecked() {
        assertNull(MoneyForms.dollarsProblem(AMOUNT, null));
        assertNull(MoneyForms.dollarsProblem(AMOUNT, 12.5));
        assertEquals(MoneyForms.dollarsProblem(AMOUNT, -1.0),
                "The amount must be between $0 and $10,000,000.00.");
        assertEquals(MoneyForms.dollarsProblem(AMOUNT, Double.NaN),
                MoneyForms.dollarsProblem(AMOUNT, -1.0));
        assertEquals(MoneyForms.dollarsProblem(AMOUNT, 20_000_000.0),
                MoneyForms.dollarsProblem(AMOUNT, -1.0));
        assertNull(MoneyForms.tooLong(NOTE, null, 3));
        assertNull(MoneyForms.tooLong(NOTE, " abc ", 3), "Trimmed first");
        assertEquals(MoneyForms.tooLong(NOTE, "abcd", 3), "A note is at most 3 characters.");
    }

    @Test
    public void signedAmountsRunEitherWayButMustBeThere() {
        assertNull(MoneyForms.signedDollarsProblem(SIGNED, -500.0));
        assertNull(MoneyForms.signedDollarsProblem(SIGNED, 0.0));
        assertNull(MoneyForms.signedDollarsProblem(SIGNED, 10_000_000.0));
        final String message = "The amount must be between -$10,000,000.00 and $10,000,000.00.";
        assertEquals(MoneyForms.signedDollarsProblem(SIGNED, null), message);
        assertEquals(MoneyForms.signedDollarsProblem(SIGNED, Double.NaN), message);
        assertEquals(MoneyForms.signedDollarsProblem(SIGNED, Double.NEGATIVE_INFINITY), message);
        assertEquals(MoneyForms.signedDollarsProblem(SIGNED, -10_000_000.01), message);
    }

    @Test
    public void enumsParseLeniently() {
        assertEquals(MoneyForms.parse(ExpenseCategory.class, " MEALS "), ExpenseCategory.MEALS);
        assertNull(MoneyForms.parse(ExpenseCategory.class, "nope"));
        assertNull(MoneyForms.parse(ExpenseCategory.class, " "));
        assertNull(MoneyForms.parse(ExpenseCategory.class, null));
    }
}
