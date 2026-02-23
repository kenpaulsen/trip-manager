package org.paulsens.trip.action;

import java.math.BigDecimal;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class PayCommandsTest {
    private final PayCommands pay = new PayCommands();

    // -------------------------------------------------------------------------
    // estimateFee
    // -------------------------------------------------------------------------

    @Test
    public void estimateFee_nullAmount_returnsZero() {
        assertEquals(pay.estimateFee(null), BigDecimal.ZERO);
    }

    @Test
    public void estimateFee_zeroAmount_returnsZero() {
        assertEquals(pay.estimateFee(BigDecimal.ZERO), BigDecimal.ZERO);
    }

    @Test
    public void estimateFee_negativeAmount_returnsZero() {
        assertEquals(pay.estimateFee(BigDecimal.ONE.subtract(BigDecimal.TEN)), BigDecimal.ZERO);
    }

    @Test
    public void estimateFee_100dollars_matchesFormula() {
        final BigDecimal amount = BigDecimal.valueOf(100f);
        final BigDecimal expected = PayCommands.FEE_RATE.multiply(amount).add(PayCommands.FEE_FIXED);
        assertEquals(pay.estimateFee(amount), expected);
    }

    @Test
    public void estimateFee_roundsToCents() {
        // $50 → 50 * 0.0349 + 0.49 = 1.745 + 0.49 = 2.235 → rounds to 2.24
        final BigDecimal amount = BigDecimal.valueOf(50f);
        final BigDecimal expected = amount.multiply(PayCommands.FEE_RATE).add(PayCommands.FEE_FIXED);
        assertEquals(pay.estimateFee(amount), expected);
    }

    @Test
    public void estimateFee_largeAmount_correctFee() {
        final BigDecimal amount = BigDecimal.valueOf(1500);
        final BigDecimal expected = amount.multiply(PayCommands.FEE_RATE).add(PayCommands.FEE_FIXED);
        assertEquals(pay.estimateFee(amount), expected);
    }

    // -------------------------------------------------------------------------
    // estimateNet
    // -------------------------------------------------------------------------

    @Test
    public void estimateNet_nullAmount_returnsZero() {
        assertEquals(pay.estimateNet(null), BigDecimal.ZERO);
    }

    @Test
    public void estimateNet_zeroAmount_returnsZero() {
        assertEquals(pay.estimateNet(BigDecimal.ZERO), BigDecimal.ZERO);
    }

    @Test
    public void estimateNet_negativeAmount_returnsZero() {
        assertEquals(pay.estimateNet(BigDecimal.valueOf(-5)), BigDecimal.ZERO);
    }

    @Test
    public void estimateNet_100dollars_equalsAmountMinusFee() {
        final BigDecimal amount = BigDecimal.valueOf(100);
        final BigDecimal fee = pay.estimateFee(amount);
        assertEquals(pay.estimateNet(amount), amount.subtract(fee));
    }

    @Test
    public void estimateNet_feeAndNetSumToGross() {
        final BigDecimal amount = BigDecimal.valueOf(250);
        final BigDecimal fee = pay.estimateFee(amount);
        final BigDecimal net = pay.estimateNet(amount);
        assertEquals(fee.add(net).compareTo(amount), 0, "fee + net should equal gross");
    }

    @Test
    public void estimateNet_alwaysLessThanGross() {
        final BigDecimal amount = BigDecimal.valueOf(75);
        final BigDecimal net = pay.estimateNet(amount);
        assertTrue(net.compareTo(amount) < 0, "Net should be less than gross");
    }
}