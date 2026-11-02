package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.util.List;
import org.paulsens.trip.model.FeesPaidBy;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** The payment state machine's persistence rules, against the in-memory fake's honest conditional puts. */
public class PaymentDAOTest {

    @Test
    public void createIsOnceOnly() throws IOException {
        final Payment payment = payment();
        assertTrue(DAO.getInstance().createPayment(payment));
        assertThrows(ConditionalCheckFailedException.class,
                () -> DAO.getInstance().createPayment(payment));
        assertEquals(DAO.getInstance().getPayment(payment.getPaymentId()).orElseThrow(), payment);
    }

    @Test
    public void transitionsAreConditionalOnTheStoredStatus() throws IOException {
        final Payment payment = payment();
        assertTrue(DAO.getInstance().createPayment(payment));

        payment.setStatus(Payment.Status.CAPTURED);
        payment.setCaptureId("CAP-1");
        assertTrue(DAO.getInstance().transitionPayment(payment, Payment.Status.CREATED));

        // A concurrent return-page retry still holds CREATED expectations: it must lose, not double-write.
        final Payment racer = DAO.getInstance().getPayment(payment.getPaymentId()).orElseThrow();
        racer.setStatus(Payment.Status.CAPTURED);
        assertThrows(ConditionalCheckFailedException.class,
                () -> DAO.getInstance().transitionPayment(racer, Payment.Status.CREATED));

        payment.setStatus(Payment.Status.RECORDED);
        assertTrue(DAO.getInstance().transitionPayment(payment, Payment.Status.CAPTURED));
        assertEquals(DAO.getInstance().getPayment(payment.getPaymentId()).orElseThrow().getStatus(),
                Payment.Status.RECORDED);
    }

    @Test
    public void listingAndMissesBehave() throws IOException {
        final Payment payment = payment();
        assertTrue(DAO.getInstance().createPayment(payment));
        assertTrue(DAO.getInstance().getAllPayments().contains(payment));
        assertTrue(DAO.getInstance().getPayment("no-such-payment").isEmpty());
        assertTrue(DAO.getInstance().getPayment("  ").isEmpty());
        assertTrue(DAO.getInstance().getPayment(null).isEmpty());
    }

    private static Payment payment() {
        return Payment.builder()
                .tripId(FakeData.FAKE_TRIP_ID)
                .payerId(Person.Id.newInstance())
                .allocations(List.of(new Payment.Allocation(Person.Id.newInstance(), 47500L)))
                .feesPaidBy(FeesPaidBy.PAYER)
                .totalChargedCents(49200L)
                .build();
    }
    /** The content compare-and-set: a row changed under the reader (by anyone, in any task) refuses the replace. */
    @Test
    public void replaceIsConditionalOnTheExactStoredContent() throws IOException {
        final Payment payment = Payment.builder().payerId(Person.Id.from("cas-payer")).totalChargedCents(100L)
                .status(Payment.Status.RECORDED).build();
        DAO.getInstance().createPayment(payment);
        final PaymentDAO.Stored stored = DAO.getInstance().getPaymentStored(payment.getPaymentId()).orElseThrow();
        assertEquals(stored.payment().getPaymentId(), payment.getPaymentId());

        final Payment mine = stored.payment();
        mine.setRefundedCents(25L);
        assertTrue(DAO.getInstance().replacePaymentIfUnchanged(mine, stored.content()));
        assertEquals(DAO.getInstance().getPayment(payment.getPaymentId()).orElseThrow().getRefundedCents(), 25L);

        // A second writer holding the ORIGINAL read loses, even though the status is unchanged.
        final Payment theirs = stored.payment();
        theirs.setRefundedCents(10L);
        assertThrows(ConditionalCheckFailedException.class,
                () -> DAO.getInstance().replacePaymentIfUnchanged(theirs, stored.content()));
        assertEquals(DAO.getInstance().getPayment(payment.getPaymentId()).orElseThrow().getRefundedCents(), 25L);
        assertTrue(DAO.getInstance().getPaymentStored("no-such").isEmpty());
        assertTrue(DAO.getInstance().getPaymentStored(" ").isEmpty());
    }
}
