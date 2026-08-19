package org.paulsens.trip.pay;

import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.TransactionsCommands;
import org.paulsens.trip.model.FeesPaidBy;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ProcessorType;
import org.paulsens.trip.model.Transaction;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * The ledger spec, pinned by the user's worked example (2026-08-17): $2,500 charged by Joe for three equal
 * $475 credits (payer covers the $75 grossed-up fee on the $1,500 portion) plus a $1,000 donation whose $50
 * fee share the org absorbs.
 */
public class PaymentRecorderTest {
    private final TransactionsCommands txCmds = new TransactionsCommands();
    private final PersonCommands people = new PersonCommands();

    private static final Map<String, String> NAMES = Map.of();

    // ------------------------------------------------------------------ the golden example

    @Test
    public void theWorkedExampleProducesExactlyThreeRows() {
        final Person.Id joe = person("Joe");
        final Person.Id bob = person("Bob");
        final Person.Id mike = person("Mike");
        final Payment payment = payment(joe, List.of(
                new Payment.Allocation(joe, 47500L),
                new Payment.Allocation(bob, 47500L),
                new Payment.Allocation(mike, 47500L)),
                100000L, FeesPaidBy.PAYER, 7500L, 5000L, 250000L);

        final List<PaymentRecorder.Line> lines =
                recorder().computeLines(payment, "CFPW", "PayPal");
        assertEquals(lines.size(), 3, "SHARED credits + donation + donation offset");

        final PaymentRecorder.Line shared = lines.get(0);
        assertEquals(shared.kind(), PaymentRecorder.Line.Kind.SHARED_CREDIT);
        assertEquals(shared.amountCents(), 142500L, "The SHARED amount is the credit total");
        assertEquals(shared.txType(), Transaction.TransactionType.Payment);
        assertEquals(shared.description(),
                "PayPal (id #123447384) Payment of $1,500.00 (minus $75.00 PayPal fee) to CFPW "
                        + "split with Joe, Bob, and Mike");

        final PaymentRecorder.Line donation = lines.get(1);
        assertEquals(donation.kind(), PaymentRecorder.Line.Kind.DONATION);
        assertEquals(donation.amountCents(), 100000L);
        assertEquals(donation.txType(), Transaction.TransactionType.Payment);
        assertEquals(donation.description(),
                "PayPal (id #123447384) Payment to CFPW (before $50.00 PayPal fee)");

        final PaymentRecorder.Line offset = lines.get(2);
        assertEquals(offset.kind(), PaymentRecorder.Line.Kind.DONATION_OFFSET);
        assertEquals(offset.amountCents(), -100000L);
        assertEquals(offset.txType(), Transaction.TransactionType.Donation);
        assertEquals(offset.description(), "Thank you for your donation to CFPW!");
    }

    @Test
    public void applyingTheWorkedExampleWritesTheLedger() {
        final Person.Id joe = person("Joe");
        final Person.Id bob = person("Bob");
        final Person.Id mike = person("Mike");
        final Payment payment = payment(joe, List.of(
                new Payment.Allocation(joe, 47500L),
                new Payment.Allocation(bob, 47500L),
                new Payment.Allocation(mike, 47500L)),
                100000L, FeesPaidBy.PAYER, 7500L, 5000L, 250000L);

        final PaymentRecorder.Result result = recorder().record(payment, "CFPW", "PayPal");
        assertTrue(result.applied());
        assertEquals(result.txIds().size(), 5, "3 shared member rows + donation pair");

        // Every member sees a $475 share of the ONE shared row (the read-time division).
        for (final Person.Id member : List.of(joe, bob, mike)) {
            final Transaction row =
                    txCmds.getGroupTransactionForUser(member, payment.getPaymentId() + "-pay").orElseThrow();
            assertTrue(row.isShared());
            assertEquals((float) txCmds.getUserAmount(row), 475f);
            assertTrue(row.getNote().contains("id #123447384"),
                    "Every row carries the processor-searchable capture id");
        }
        // Joe's balance: +475 (share) +1000 (donation) -1000 (offset) = +475.
        assertEquals(txCmds.getBalance(joe), 475.0);
        assertEquals(txCmds.getBalance(bob), 475.0);
        // The donation pair sits on the payer only.
        assertEquals(txCmds.getTransactions(joe).size(), 3);
        assertEquals(txCmds.getTransactions(bob).size(), 1);

        // Idempotent re-record: same rows, same ids, no duplicates (the Retry-recording path).
        final PaymentRecorder.Result again = recorder().record(payment, "CFPW", "PayPal");
        assertEquals(again.txIds(), result.txIds());
        assertEquals(txCmds.getTransactions(joe).size(), 3);
    }

    // ------------------------------------------------------------------ variants

    @Test
    public void unequalCreditsBecomePerPersonRows() {
        final Person.Id payer = person("Payer");
        final Person.Id other = person("Other");
        final Payment payment = payment(payer, List.of(
                new Payment.Allocation(payer, 10000L),
                new Payment.Allocation(other, 20000L)),
                0L, FeesPaidBy.PAYER, 1100L, 0L, 31100L);

        final PaymentRecorder.Result result = recorder().record(payment, "CFPW", "PayPal");
        assertEquals(result.lines().size(), 2, "Unequal amounts: one plain row each, never SHARED");
        assertTrue(result.lines().stream()
                .allMatch(line -> line.kind() == PaymentRecorder.Line.Kind.CREDIT));

        final Transaction payerRow = txCmds
                .getTransaction(payer, payment.getPaymentId() + "-pay-" + payer.getValue());
        assertEquals(payerRow.getAmount(), 100f);
        assertNull(payerRow.getGroupId());
        assertEquals(txCmds.getBalance(other), 200.0);
    }

    @Test
    public void aSinglePersonGetsAPlainRowAndZeroAllocationsAreExcluded() {
        final Person.Id payer = person("Solo");
        final Person.Id skipped = person("Skipped");
        final Payment payment = payment(payer, List.of(
                new Payment.Allocation(payer, 47500L),
                new Payment.Allocation(skipped, 0L)),
                0L, FeesPaidBy.PAYER, 1700L, 0L, 49200L);

        final PaymentRecorder.Result result = recorder().record(payment, "CFPW", "PayPal");
        assertEquals(result.lines().size(), 1, "An empty box is not part of the payment");
        assertEquals(result.lines().get(0).kind(), PaymentRecorder.Line.Kind.CREDIT);
        assertEquals(txCmds.getBalance(payer), 475.0);
        assertEquals(txCmds.getBalance(skipped), 0.0);
    }

    @Test
    public void orgPaysFeeKeepsCreditsWholeAndCommentsTheFee() {
        final Person.Id a = person("A");
        final Person.Id b = person("B");
        final Payment payment = payment(a, List.of(
                new Payment.Allocation(a, 47500L),
                new Payment.Allocation(b, 47500L)),
                0L, FeesPaidBy.ORGANIZATION, 3400L, 0L, 95000L);

        final List<PaymentRecorder.Line> lines = recorder().computeLines(payment, "CFPW", "PayPal");
        assertEquals(lines.size(), 1);
        assertEquals(lines.get(0).amountCents(), 95000L);
        assertEquals(lines.get(0).description(),
                "PayPal (id #123447384) Payment of $950.00 (CFPW covered the $34.00 PayPal fee) to CFPW "
                        + "split with A and B");
    }

    @Test
    public void donationOnlyPaymentsWriteJustThePair() {
        final Person.Id donor = person("Donor");
        final Payment payment = payment(donor, List.of(), 50000L, FeesPaidBy.PAYER, 0L, 1750L, 50000L);

        final PaymentRecorder.Result result = recorder().record(payment, "CFPW", "PayPal");
        assertEquals(result.lines().size(), 2);
        assertEquals(txCmds.getBalance(donor), 0.0, "The pair is balance-neutral");
        assertEquals(txCmds.getTransactions(donor).size(), 2);
        assertTrue(txCmds.getTransactions(donor).stream().anyMatch(
                tx -> tx.getTxType() == Transaction.TransactionType.Donation));
    }

    @Test
    public void sandboxComputesButNeverWrites() {
        final Person.Id payer = person("Sandboxer");
        final Payment payment = payment(payer, List.of(new Payment.Allocation(payer, 47500L)),
                0L, FeesPaidBy.PAYER, 1700L, 0L, 49200L);
        payment.setSandbox(true);

        final PaymentRecorder.Result result = recorder().record(payment, "CFPW", "PayPal");
        assertEquals(result.lines().size(), 1, "The dry run still answers what WOULD have been written");
        assertTrue(result.txIds().isEmpty());
        assertTrue(txCmds.getTransactions(payer).isEmpty(), "...but the ledger is untouched");
    }

    // ------------------------------------------------------------------ helpers

    private PaymentRecorder recorder() {
        return new PaymentRecorder(txCmds, id -> personName(id));
    }

    private final Map<Person.Id, String> createdNames = new java.util.HashMap<>();

    private String personName(final Person.Id id) {
        return createdNames.getOrDefault(id, id.getValue());
    }

    private Person.Id person(final String first) {
        final Person.Id id = Person.Id.newInstance();
        people.savePerson(new Person(id, first, first, null, "Tester", Person.Sex.Male,
                java.time.LocalDate.of(1980, 1, 1), null, null, null, null, null, null, null, null, null,
                null));
        createdNames.put(id, first);
        return id;
    }

    private static Payment payment(final Person.Id payer, final List<Payment.Allocation> allocations,
            final long donation, final FeesPaidBy feesPaidBy, final long creditFee, final long donationFee,
            final long total) {
        return Payment.builder()
                .tripId("faketrip")
                .payerId(payer)
                .processorType(ProcessorType.PAYPAL)
                .allocations(allocations)
                .donationCents(donation)
                .feesPaidBy(feesPaidBy)
                .creditFeeCents(creditFee)
                .donationFeeCents(donationFee)
                .totalChargedCents(total)
                .captureId("123447384")
                .status(Payment.Status.CAPTURED)
                .build();
    }
}
