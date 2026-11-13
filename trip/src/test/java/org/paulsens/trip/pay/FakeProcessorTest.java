package org.paulsens.trip.pay;

import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ProcessorType;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class FakeProcessorTest {

    @Test
    public void ordersRoundTripThroughTheFakeCheckout() {
        final FakeProcessor fake = new FakeProcessor();
        assertEquals(fake.type(), ProcessorType.FAKE);

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

        assertEquals(fake.reprice(ref, 10100L, null), PaymentProcessor.Repriced.REPRICED);
        assertEquals(fake.capture(ref).grossCents(), 10100L, "the capture answers the re-priced amount");
        assertEquals(fake.capture(ref).methodType(), "card");
        org.testng.Assert.assertThrows(PaymentProcessor.ProcessorException.class,
                () -> fake.reprice("FAKE-unknown", 5L, null));
        org.testng.Assert.assertThrows(PaymentProcessor.ProcessorException.class, () -> fake.reprice(ref, 0L, null));
    }

    /** A bank price is locked to bank debits, the way Stripe refuses a card on a bank-only intent. */
    @Test
    public void aBankPriceIsLockedToBankDebits() {
        final FakeProcessor fake = new FakeProcessor(true);
        final Payment payment = Payment.builder().payerId(Person.Id.newInstance()).totalChargedCents(10349L).build();
        final String ref = fake.createOrder(payment, "desc", "http://r", "http://c").orderRef();
        assertEquals(fake.reprice(ref, 10081L, "us_bank_account"), PaymentProcessor.Repriced.REPRICED_LOCKED);
        final PaymentProcessor.CaptureResult card = fake.capture(ref);
        assertEquals(card.status(), PaymentProcessor.CaptureResult.Status.PENDING, "a card cannot pay it");
        assertFalse(card.isSettling());

        assertTrue(FakeProcessor.markAsync(ref));
        assertEquals(fake.cancel(ref), PaymentProcessor.OrderState.SETTLING, "a debit in flight cannot be cancelled");
        assertTrue(fake.capture(ref).isSettling());
        assertEquals(FakeProcessor.settle(ref), payment.getPaymentId());
        final PaymentProcessor.CaptureResult bank = fake.capture(ref);
        assertEquals(bank.status(), PaymentProcessor.CaptureResult.Status.COMPLETED);
        assertEquals(bank.grossCents(), 10081L);
        assertEquals(bank.methodType(), "us_bank_account");

        // Back to the card estimate lifts the lock; an open order is released by a cancel.
        final String other = fake.createOrder(payment, "desc", "http://r", "http://c").orderRef();
        fake.reprice(other, 10081L, "us_bank_account");
        assertEquals(fake.reprice(other, 10349L, null), PaymentProcessor.Repriced.REPRICED);
        assertEquals(fake.capture(other).status(), PaymentProcessor.CaptureResult.Status.COMPLETED);
        assertEquals(fake.cancel(other), PaymentProcessor.OrderState.OPEN);
        assertEquals(fake.capture(other).status(), PaymentProcessor.CaptureResult.Status.FAILED, "released");
        assertEquals(fake.cancel(null), PaymentProcessor.OrderState.OPEN);
    }
}
