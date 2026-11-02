package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
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
    public void anOfflineReceiptRoundTripsThroughJacksonAndKeepsItsLabel() throws Exception {
        final Payment payment = Payment.builder().processorType(ProcessorType.OFFLINE).captureId("CHECK 1042")
                .capturedAt(LocalDateTime.of(2026, 10, 30, 0, 0)).build();
        payment.setOffline(new Payment.OfflineReceipt(" check ", " 1042 ", LocalDate.of(2026, 10, 30)));
        final Payment read = MAPPER.readValue(MAPPER.writeValueAsString(payment), Payment.class);
        assertEquals(read, payment);
        final Payment.OfflineReceipt receipt = read.getOfflineReceipt();
        assertEquals(receipt.method(), "CHECK", "normalized on the way in");
        assertEquals(receipt.reference(), "1042");
        assertEquals(receipt.label(), "CHECK 1042", "byte for byte the capture id");
        assertEquals(receipt.summary(), "Check 1042");
        assertEquals(receipt.describe(), "Check 1042, received 10/30/2026");
        assertFalse(MAPPER.writeValueAsString(payment).contains("offlineReceipt"), "the derived view is not stored");
    }

    @Test
    public void anOlderOfflineRowReadsItsReceiptFromTheLabel() {
        final LocalDateTime captured = LocalDateTime.of(2026, 10, 30, 9, 15);
        final Payment check = Payment.builder().processorType(ProcessorType.OFFLINE).captureId("CHECK 1042")
                .capturedAt(captured).build();
        assertEquals(check.getOfflineReceipt(),
                new Payment.OfflineReceipt("CHECK", "1042", LocalDate.of(2026, 10, 30)));
        final Payment free = Payment.builder().processorType(ProcessorType.OFFLINE).captureId("FREE")
                .capturedAt(captured).build();
        assertEquals(free.getOfflineReceipt(), new Payment.OfflineReceipt("FREE", null, LocalDate.of(2026, 10, 30)));
        assertEquals(free.getOfflineReceipt().describe(), "Free, received 10/30/2026");
        final Payment multiWord = Payment.builder().processorType(ProcessorType.OFFLINE)
                .captureId("other  money order 77").build();
        assertEquals(multiWord.getOfflineReceipt(), new Payment.OfflineReceipt("OTHER", "money order 77", null),
                "the rest of the label is the reference, whatever it holds; no capture date, no received date");
        assertEquals(multiWord.getOfflineReceipt().describe(), "Other money order 77");
        assertNull(Payment.builder().processorType(ProcessorType.OFFLINE).captureId("BITCOIN 1").build()
                .getOfflineReceipt(), "a label naming no method is no receipt");
        assertNull(Payment.builder().processorType(ProcessorType.OFFLINE).captureId(" ").build().getOfflineReceipt());
        assertNull(Payment.builder().processorType(ProcessorType.OFFLINE).build().getOfflineReceipt());
        assertNull(Payment.builder().processorType(ProcessorType.PAYPAL).captureId("CHECK 1").build()
                .getOfflineReceipt(), "a processor payment has no receipt, whatever its capture id says");
        assertNull(Payment.builder().captureId("CHECK 1").build().getOfflineReceipt());
        assertEquals(new Payment.OfflineReceipt(null, "", null).methodLabel(), "");
        assertEquals(new Payment.OfflineReceipt("CASH", null, null).label(), "CASH");
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
