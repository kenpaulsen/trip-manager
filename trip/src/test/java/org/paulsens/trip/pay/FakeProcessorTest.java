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
}
