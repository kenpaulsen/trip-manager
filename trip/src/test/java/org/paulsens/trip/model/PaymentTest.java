package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class PaymentTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    public void builderWithNoValuesEqualsNewInstance() {
        final Payment built = Payment.builder().build();
        final Payment fresh = new Payment();
        built.setPaymentId(fresh.getPaymentId());
        assertEquals(built, fresh);
        assertEquals(fresh.getStatus(), Payment.Status.CREATED);
        assertEquals(fresh.getFeesPaidBy(), FeesPaidBy.ORGANIZATION);
    }

    @Test
    public void jacksonRoundTripPreservesEverything() throws Exception {
        final Payment payment = Payment.builder()
                .tripId("trip-1").orgId("org-1").payerId(Person.Id.newInstance())
                .processorConfigId("cfg-1").processorType(ProcessorType.PAYPAL)
                .allocations(List.of(new Payment.Allocation(Person.Id.newInstance(), 47500L)))
                .donationCents(100000L).feesPaidBy(FeesPaidBy.PAYER)
                .creditFeeCents(7500L).donationFeeCents(5000L).totalChargedCents(250000L)
                .sandbox(true).orderRef("ORDER-1").captureId("CAP-1")
                .capturedGrossCents(250000L).actualFeeCents(12500L)
                .status(Payment.Status.CAPTURED)
                .createdAt(LocalDateTime.of(2026, 8, 17, 12, 0))
                .capturedAt(LocalDateTime.of(2026, 8, 17, 12, 5))
                .txIds(List.of("t1", "t2"))
                .build();
        assertEquals(MAPPER.readValue(MAPPER.writeValueAsString(payment), Payment.class), payment);
    }

    @Test
    public void derivedViewsAnswer() {
        final Person.Id a = Person.Id.newInstance();
        final Payment payment = Payment.builder()
                .allocations(List.of(new Payment.Allocation(a, 100L), new Payment.Allocation(a, 250L)))
                .feesPaidBy(FeesPaidBy.PAYER)
                .build();
        assertEquals(payment.getCreditTotalCents(), 350L);
        assertTrue(payment.isPayerPaysFee());
        assertFalse(Payment.builder().build().isPayerPaysFee());
    }
}
