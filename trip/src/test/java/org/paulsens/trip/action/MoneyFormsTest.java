package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.model.ExpenseCategory;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;

public class MoneyFormsTest {

    @Test
    public void dollarsAndCentsRoundTrip() {
        assertEquals(MoneyForms.cents(null), 0L);
        assertEquals(MoneyForms.cents(45_765.0), 4_576_500L);
        assertEquals(MoneyForms.cents(0.005), 1L, "HALF_UP to the cent");
        assertEquals(MoneyForms.dollars(4_576_500L), 45_765.0);
        assertEquals(MoneyForms.money(-979_595L), "-$9,795.95");
    }

    @Test
    public void amountsAndTextAreChecked() {
        assertNull(MoneyForms.dollarsProblem("The amount", null));
        assertNull(MoneyForms.dollarsProblem("The amount", 12.5));
        assertEquals(MoneyForms.dollarsProblem("The amount", -1.0),
                "The amount must be between $0 and $10,000,000.00.");
        assertEquals(MoneyForms.dollarsProblem("The amount", Double.NaN),
                MoneyForms.dollarsProblem("The amount", -1.0));
        assertEquals(MoneyForms.dollarsProblem("The amount", 20_000_000.0),
                MoneyForms.dollarsProblem("The amount", -1.0));
        assertNull(MoneyForms.tooLong("A note", null, 3));
        assertNull(MoneyForms.tooLong("A note", " abc ", 3), "Trimmed first");
        assertEquals(MoneyForms.tooLong("A note", "abcd", 3), "A note is at most 3 characters.");
    }

    @Test
    public void signedAmountsRunEitherWayButMustBeThere() {
        assertNull(MoneyForms.signedDollarsProblem("The amount", -500.0));
        assertNull(MoneyForms.signedDollarsProblem("The amount", 0.0));
        assertNull(MoneyForms.signedDollarsProblem("The amount", 10_000_000.0));
        final String message = "The amount must be between -$10,000,000.00 and $10,000,000.00.";
        assertEquals(MoneyForms.signedDollarsProblem("The amount", null), message);
        assertEquals(MoneyForms.signedDollarsProblem("The amount", Double.NaN), message);
        assertEquals(MoneyForms.signedDollarsProblem("The amount", Double.NEGATIVE_INFINITY), message);
        assertEquals(MoneyForms.signedDollarsProblem("The amount", -10_000_000.01), message);
    }

    @Test
    public void enumsParseLeniently() {
        assertEquals(MoneyForms.parse(ExpenseCategory.class, " MEALS "), ExpenseCategory.MEALS);
        assertNull(MoneyForms.parse(ExpenseCategory.class, "nope"));
        assertNull(MoneyForms.parse(ExpenseCategory.class, " "));
        assertNull(MoneyForms.parse(ExpenseCategory.class, null));
    }

    @Test
    public void refusedHandsOverTheMessage() {
        final List<String> got = new ArrayList<>();
        assertFalse(MoneyForms.refused(got::add, "No."));
        assertEquals(got, List.of("No."));
    }
}
