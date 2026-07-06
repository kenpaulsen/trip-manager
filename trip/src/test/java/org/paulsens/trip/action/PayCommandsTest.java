package org.paulsens.trip.action;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import org.paulsens.trip.model.AdmissionOption;
import org.paulsens.trip.model.DiscountCode;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
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

    // -------------------------------------------------------------------------
    // registrationDetail — transaction-note label built from the caller's
    // in-memory registration (the registration is not yet persisted at capture
    // time, so this must NOT re-read the DB or fabricate a default label).
    // -------------------------------------------------------------------------

    private static final AdmissionOption FULL =
            new AdmissionOption("full", "Full Weekend Admission", new BigDecimal("340.00"),
                    List.of("FRI", "SAT", "SUN"), false, true);
    private static final AdmissionOption FRI =
            new AdmissionOption("fri", "Friday Only", new BigDecimal("120.00"),
                    List.of("FRI"), false, true);
    private static final DiscountCode CFPW =
            new DiscountCode("code3", "CFPW-25", "CFPW discount code", null,
                    DiscountCode.DiscountType.DISCOUNT_BY, new BigDecimal("25"), List.of(), true);

    private static PayCommands payWithReg() {
        final PayCommands pc = new PayCommands();
        try {
            final Field f = PayCommands.class.getDeclaredField("reg");
            f.setAccessible(true);
            f.set(pc, new RegistrationCommands());
        } catch (final ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return pc;
    }

    private static Trip tripWithOptions() {
        return Trip.builder()
                .admissionOptions(List.of(FULL, FRI))
                .discountCodes(List.of(CFPW))
                .build();
    }

    private static Registration regChoosing(final String admissionId, final String discountCodeId) {
        final Registration reg = new Registration(RandomData.genAlpha(6), Person.Id.newInstance());
        if (admissionId != null) {
            reg.getOptions().put(Registration.OPT_ADMISSION, admissionId);
        }
        if (discountCodeId != null) {
            reg.getOptions().put(Registration.OPT_DISCOUNT_CODE, discountCodeId);
        }
        return reg;
    }

    @Test
    public void registrationDetail_usesActualChosenOption_notDefaultFullWeekend() {
        // The bug: a $120 "Friday Only" purchase was mislabeled "Full Weekend Admission".
        final String detail = payWithReg().registrationDetail(tripWithOptions(), regChoosing("fri", null));
        assertEquals(detail, "Admission: Friday Only");
    }

    @Test
    public void registrationDetail_fullWeekendReportsFullWeekend() {
        final String detail = payWithReg().registrationDetail(tripWithOptions(), regChoosing("full", null));
        assertEquals(detail, "Admission: Full Weekend Admission");
    }

    @Test
    public void registrationDetail_includesDiscountCode() {
        final String detail = payWithReg().registrationDetail(tripWithOptions(), regChoosing("fri", "code3"));
        assertEquals(detail, "Admission: Friday Only\nDiscount Code: CFPW-25 — CFPW discount code");
    }

    @Test
    public void registrationDetail_noAdmissionChosen_producesNoFabricatedLabel() {
        // No _admission on the registration: must NOT invent "Full Weekend Admission".
        final String detail = payWithReg().registrationDetail(tripWithOptions(), regChoosing(null, null));
        assertEquals(detail, "");
    }

    @Test
    public void registrationDetail_nullTripOrReg_isEmpty() {
        final PayCommands pc = payWithReg();
        assertEquals(pc.registrationDetail(null, regChoosing("fri", null)), "");
        assertEquals(pc.registrationDetail(tripWithOptions(), null), "");
    }
}