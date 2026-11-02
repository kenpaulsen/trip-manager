package org.paulsens.trip.pay;

import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ProcessorType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class FakeProcessorTest {

    @Test
    public void ordersRoundTripThroughTheFakeCheckout() {
        final FakeProcessor fake = new FakeProcessor();
        assertEquals(fake.type(), ProcessorType.FAKE);
        assertEquals(fake.displayName(), "Test Processor");

        final Payment payment = Payment.builder()
                .payerId(Person.Id.newInstance()).totalChargedCents(250000L).build();
        final PaymentProcessor.CreatedOrder order = fake.createOrder(payment, "desc",
                "http://localhost/return?trip=t&payment=p", "http://localhost/cancel");
        assertTrue(order.orderRef().startsWith("FAKE-"));
        assertTrue(order.approvalUrl().startsWith("/trip/fakeCheckout.jsf?token=FAKE-"));
        assertTrue(order.approvalUrl().contains("return="), "The checkout page needs both URLs");

        final PaymentProcessor.CaptureResult result = fake.capture(order.orderRef());
        assertEquals(result.status(), PaymentProcessor.CaptureResult.Status.COMPLETED);
        assertEquals(result.grossCents(), 250000L, "The capture answers what the ORDER was created for");
        assertTrue(result.captureId().startsWith("CAP-"));
        assertTrue(result.actualFeeCents().isPresent());
    }

    @Test
    public void theInlineFakeAnswersAClientSecretAndCanSettleLater() {
        final FakeProcessor fake = new FakeProcessor(true);
        final Payment payment = Payment.builder()
                .payerId(Person.Id.newInstance()).totalChargedCents(5000L).build();
        final PaymentProcessor.CreatedOrder order = fake.createOrder(payment, "desc", "http://r", "http://c");
        assertTrue(order.isInline());
        assertEquals(order.inline().publishableKey(), "pk_fake");
        assertEquals(order.inline().clientSecret(), order.orderRef());
        assertEquals(order.inline().scriptUrl(), null, "null = the page's own fake Stripe.js");

        assertTrue(FakeProcessor.markAsync(order.orderRef()));
        final PaymentProcessor.CaptureResult settling = fake.capture(order.orderRef());
        assertEquals(settling.status(), PaymentProcessor.CaptureResult.Status.PENDING);
        assertTrue(settling.isSettling());
        assertEquals(FakeProcessor.settle(order.orderRef()), payment.getPaymentId());
        assertEquals(fake.capture(order.orderRef()).status(), PaymentProcessor.CaptureResult.Status.COMPLETED);

        assertEquals(FakeProcessor.settle("FAKE-unknown"), null);
        assertTrue(!FakeProcessor.markAsync(null));
        fake.cancel(order.orderRef());
        assertEquals(fake.capture(order.orderRef()).status(), PaymentProcessor.CaptureResult.Status.FAILED,
                "a cancelled order is gone");
        fake.cancel(null);
    }

    @Test
    public void inlineIsAConfigFlag() {
        final org.paulsens.trip.model.PaymentProcessorConfig inline =
                org.paulsens.trip.model.PaymentProcessorConfig.builder().type(ProcessorType.FAKE)
                        .publicConfig(java.util.Map.of("inline", "TRUE")).build();
        assertTrue(FakeProcessor.isInline(inline));
        assertTrue(!FakeProcessor.isInline(new org.paulsens.trip.model.PaymentProcessorConfig()));
        assertTrue(!FakeProcessor.isInline(null));
    }

    @Test
    public void processorExceptionsCarryPayerSafeMessages() {
        assertEquals(new PaymentProcessor.ProcessorException("refused").getMessage(), "refused");
        final PaymentProcessor.ProcessorException chained = new PaymentProcessor.ProcessorException(
                "refused", new IllegalStateException("io"));
        assertEquals(chained.getCause().getMessage(), "io");
    }

    @Test
    public void anUnknownOrderFailsInsteadOfInventingMoney() {
        assertEquals(new FakeProcessor().capture("FAKE-never-created").status(),
                PaymentProcessor.CaptureResult.Status.FAILED);
    }
    /** The refund and re-pricing stand-ins behave like Stripe's: cumulative refunds, re-price only a live order. */
    @Test
    public void refundsAreCumulativeAndRePricingMovesTheOrder() {
        final FakeProcessor fake = new FakeProcessor(true);
        final Payment payment = Payment.builder()
                .payerId(Person.Id.newInstance()).totalChargedCents(10000L).build();
        final String ref = fake.createOrder(payment, "desc", "http://r", "http://c").orderRef();
        assertEquals(fake.refundedCents(ref).getAsLong(), 0L, "nothing refunded yet reads as zero, not unknown");
        assertTrue(fake.refundedCents("FAKE-unknown").isEmpty(), "an unknown order is unknown");
        assertEquals(FakeProcessor.refund(ref, 2500L), payment.getPaymentId());
        assertEquals(fake.refundedCents(ref).getAsLong(), 2500L);
        assertEquals(FakeProcessor.refund(ref, 10000L), payment.getPaymentId(), "the full amount replaces it");
        assertEquals(fake.refundedCents(ref).getAsLong(), 10000L);
        assertEquals(FakeProcessor.refund(ref, 10001L), null, "never more than was charged");
        assertEquals(FakeProcessor.refund(ref, -1L), null);
        assertEquals(FakeProcessor.refund("FAKE-unknown", 1L), null);

        assertTrue(fake.updateAmount(ref, 10017L));
        assertEquals(fake.capture(ref).grossCents(), 10017L, "the capture answers the re-priced amount");
        org.testng.Assert.assertThrows(PaymentProcessor.ProcessorException.class,
                () -> fake.updateAmount("FAKE-unknown", 5L));
        org.testng.Assert.assertThrows(PaymentProcessor.ProcessorException.class, () -> fake.updateAmount(ref, 0L));
    }
}
