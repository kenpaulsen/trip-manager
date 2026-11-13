package org.paulsens.trip.pay;

import java.math.BigDecimal;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class MoneyMathTest {

    @Test
    public void centsParsingIsStrict() {
        assertEquals(MoneyMath.toCents(new BigDecimal("475")), 47500L);
        assertEquals(MoneyMath.toCents(new BigDecimal("475.25")), 47525L);
        assertEquals(MoneyMath.toCents(new BigDecimal("0.10")), 10L);
        assertEquals(MoneyMath.toCents(new BigDecimal("475.250")), 47525L, "Trailing zeros are fine");
        assertEquals(MoneyMath.toCents(null), 0L);
        assertThrows(IllegalArgumentException.class, () -> MoneyMath.toCents(new BigDecimal("-1")));
        assertThrows(IllegalArgumentException.class, () -> MoneyMath.toCents(new BigDecimal("1.001")));
    }

    @Test
    public void formattingIsDollarsWithGrouping() {
        assertEquals(MoneyMath.formatCents(250000L), "$2,500.00");
        assertEquals(MoneyMath.formatCents(7500L), "$75.00");
        assertEquals(MoneyMath.formatCents(5L), "$0.05");
        assertEquals(MoneyMath.formatCents(0L), "$0.00");
        assertEquals(MoneyMath.toFloatDollars(47500L), 475f);
    }

    /** A negative amount leads with its sign; under a dollar it used to lose the sign altogether. */
    @Test
    public void aNegativeAmountLeadsWithItsSign() {
        assertEquals(MoneyMath.formatCents(-5L), "-$0.05");
        assertEquals(MoneyMath.formatCents(-50L), "-$0.50");
        assertEquals(MoneyMath.formatCents(-2500L), "-$25.00");
        assertEquals(MoneyMath.formatCents(-123456L), "-$1,234.56");
    }

    @Test
    public void aChangeIsAlwaysSigned() {
        assertEquals(MoneyMath.formatSigned(1200L), "+$12.00");
        assertEquals(MoneyMath.formatSigned(-1000L), "-$10.00");
        assertEquals(MoneyMath.formatSigned(0L), "+$0.00");
    }

    /** The user's worked example: flat 5%, credits $1,425 gross up to $1,500 with a $75 fee. */
    @Test
    public void theWorkedExampleFeeMathIsExact() {
        assertEquals(MoneyMath.creditFeeCents(142500L, 500, 0), 7500L);
        assertEquals(MoneyMath.donationFeeCents(100000L, 500), 5000L);
        assertEquals(MoneyMath.totalChargedCents(142500L, 7500L, 100000L, true), 250000L);
        assertEquals(MoneyMath.totalChargedCents(142500L, 7500L, 100000L, false), 242500L,
                "Org-pays adds nothing on top");
    }

    /** The gross-up property: after the processor takes rate x charged + fixed, the org nets >= credits. */
    @Test
    public void grossUpAlwaysNetsTheCredits() {
        final int[][] rates = {{349, 49}, {290, 30}, {500, 0}, {1, 1}};
        final long[] credits = {1L, 99L, 100L, 142500L, 999999L, 123457L};
        for (final int[] rate : rates) {
            for (final long credit : credits) {
                final long fee = MoneyMath.creditFeeCents(credit, rate[0], rate[1]);
                final long charged = credit + fee;
                // Processor takes ceil-at-most rate*charged/10000 + fixed; the org must still net >= credit.
                final long processorTake = (charged * rate[0] + 9999) / 10000 + rate[1];
                assertTrue(charged - processorTake >= credit - 1,
                        "credits=" + credit + " rate=" + rate[0] + "/" + rate[1]
                                + " charged=" + charged + " take=" + processorTake);
            }
        }
        assertEquals(MoneyMath.creditFeeCents(0L, 349, 49), 0L, "No credits, no credit fee");
        assertThrows(IllegalArgumentException.class, () -> MoneyMath.creditFeeCents(100L, 10000, 0));
    }

    @Test
    public void donationFeeIsInformationalHalfUp() {
        assertEquals(MoneyMath.donationFeeCents(0L, 349), 0L);
        assertEquals(MoneyMath.donationFeeCents(100L, 349), 3L, "3.49 rounds half-up to 3");
        assertEquals(MoneyMath.donationFeeCents(1000L, 349), 35L, "34.9 rounds half-up to 35");
    }

    @Test
    public void splitEvenlyIsDeterministicWithLeadingRemainder() {
        assertEquals(MoneyMath.splitEvenly(100L, 3), new long[]{34L, 33L, 33L});
        assertEquals(MoneyMath.splitEvenly(99L, 3), new long[]{33L, 33L, 33L});
        assertEquals(MoneyMath.splitEvenly(1L, 2), new long[]{1L, 0L});
        assertThrows(IllegalArgumentException.class, () -> MoneyMath.splitEvenly(10L, 0));
    }
    /** Stripe's ACH pricing: 0.8% grossed up, then capped at $5.00; the cap binds from about $620 of credits. */
    @Test
    public void bankFeesAreGrossedUpThenCapped() {
        assertEquals(MoneyMath.bankCreditFeeCents(2000L, 80, 500), 17L, "ceil(2000 x 80 / 9920)");
        assertEquals(MoneyMath.bankCreditFeeCents(3000L, 80, 500), 25L);
        assertEquals(MoneyMath.bankCreditFeeCents(250000L, 80, 500), 500L, "the cap binds on $2,500");
        assertEquals(MoneyMath.bankCreditFeeCents(250000L, 80, 0), 2017L, "no cap = the plain gross-up");
        assertEquals(MoneyMath.bankCreditFeeCents(0L, 80, 500), 0L);
        assertEquals(MoneyMath.bankDonationFeeCents(100000L, 80, 500), 500L, "$8.00 share clamped to $5.00");
        assertEquals(MoneyMath.bankDonationFeeCents(2000L, 80, 500), 16L);
        assertEquals(MoneyMath.bankDonationFeeCents(0L, 80, 500), 0L);
    }
}
