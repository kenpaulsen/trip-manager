package org.paulsens.trip.pay;

import org.paulsens.trip.dynamo.FakeData;
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

    /**
     * THE duplicate-row race (found in the 2026-11 idempotency review): two writers record the same SHARED
     * payment at once -- the webhook and the return page, each in its own ECS task -- and both look the
     * members' rows up BEFORE either has written one. With rows looked up by group and minted with random
     * ids, each writer created its own row per member: the ledger double-credited. Deterministic row ids make
     * both writers put the SAME rows. Simulated by a second writer whose lookup still sees nothing.
     */
    @Test
    public void twoConcurrentRecordersOfASharedPaymentWriteTheSameRows() {
        final Person.Id joe = person("Joe");
        final Person.Id bob = person("Bob");
        final Payment payment = payment(joe, List.of(
                new Payment.Allocation(joe, 47500L), new Payment.Allocation(bob, 47500L)),
                0L, FeesPaidBy.PAYER, 3487L, 0L, 98487L);
        final TransactionsCommands staleLookup = new TransactionsCommands() {
            @Override
            public java.util.Optional<Transaction> getGroupTransactionForUser(final Person.Id userId,
                    final String groupId) {
                return java.util.Optional.empty();   // "I looked before you wrote"
            }
        };
        final PaymentRecorder first = recorder();
        final PaymentRecorder second = new PaymentRecorder(staleLookup, id -> personName(id));

        final PaymentRecorder.Result a = first.record(payment, "CFPW", "Stripe");
        final PaymentRecorder.Result b = second.record(payment, "CFPW", "Stripe");
        assertEquals(a.txIds(), b.txIds(), "both writers name the same rows");
        assertEquals(a.txIds(), List.of(payment.getPaymentId() + "-pay-" + joe.getValue(),
                payment.getPaymentId() + "-pay-" + bob.getValue()), "ids derive from the payment and the member");
        assertEquals(txCmds.getTransactions(joe).size(), 1, "ONE row for Joe, not one per writer");
        assertEquals(txCmds.getTransactions(bob).size(), 1);
        assertEquals(txCmds.getBalance(joe), 475.0);
        assertEquals(txCmds.getBalance(bob), 475.0);
        final Transaction row = txCmds.getGroupTransactionForUser(bob, payment.getPaymentId() + "-pay").orElseThrow();
        assertTrue(row.isShared());
        assertEquals(row.getGroupPeople(), List.of(joe, bob), "membership is stamped (the read-time divisor)");
        assertEquals(row.getOrgId(), FakeData.CFPW_ORG_ID);
    }

    /** A group row the generic writer created earlier (a random id) keeps its id on a re-record. */
    @Test
    public void aLegacyGroupRowKeepsItsIdOnReRecord() {
        final Person.Id joe = person("Joe");
        final Person.Id bob = person("Bob");
        final Payment payment = payment(joe, List.of(
                new Payment.Allocation(joe, 10000L), new Payment.Allocation(bob, 10000L)),
                0L, FeesPaidBy.ORGANIZATION, 0L, 0L, 20000L);
        final String groupId = payment.getPaymentId() + "-pay";
        assertTrue(txCmds.saveGroupTransaction(groupId, List.of(joe, bob), Transaction.Type.Shared,
                Transaction.TransactionType.Payment, java.time.LocalDateTime.now(), 200f, "Payment", "old row",
                FakeData.FAKE_TRIP_ID, null, List.of(joe, bob)));
        final String legacyId = txCmds.getGroupTransactionForUser(joe, groupId).orElseThrow().getTxId();

        final PaymentRecorder.Result result = recorder().record(payment, "CFPW", "Stripe");
        assertTrue(result.txIds().contains(legacyId), "the existing row is updated, not shadowed");
        assertEquals(txCmds.getTransactions(joe).size(), 1);
        assertTrue(txCmds.getGroupTransactionForUser(joe, groupId).orElseThrow().getNote().contains("id #123447384"));
    }

    // ------------------------------------------------------------------ refunds

    /**
     * A dashboard refund becomes ONE negative row on the payer carrying the cumulative total; a later partial
     * refund or a redelivered event rewrites that same row. The original credit rows are never touched.
     */
    @Test
    public void aRefundIsOneCumulativeRowOnThePayerRewrittenInPlace() {
        final Person.Id joe = person("Joe");
        final Person.Id bob = person("Bob");
        final Payment payment = payment(joe, List.of(
                new Payment.Allocation(joe, 47500L), new Payment.Allocation(bob, 47500L)),
                0L, FeesPaidBy.PAYER, 3487L, 0L, 98487L);
        payment.setCapturedGrossCents(98487L);
        payment.setStatus(Payment.Status.RECORDED);
        final PaymentRecorder recorder = recorder();
        recorder.record(payment, "CFPW", "Stripe");
        final int joeRows = txCmds.getTransactions(joe).size();

        final PaymentRecorder.Line partial = recorder.refundLine(payment, 10000L, "CFPW", "Stripe");
        assertEquals(partial.kind(), PaymentRecorder.Line.Kind.REFUND);
        assertEquals(partial.userId(), joe, "the payer's card was refunded, so the payer's ledger");
        assertEquals(partial.amountCents(), -10000L);
        assertEquals(partial.txType(), Transaction.TransactionType.Payment);
        assertEquals(partial.txOrGroupId(), payment.getPaymentId() + "-refund");
        assertEquals(partial.description(),
                "Stripe (id #123447384) Refund of $100.00 (partial, of the $984.87 payment) from CFPW");

        final String txId = recorder.recordRefund(payment, 10000L, "CFPW", "Stripe");
        assertEquals(txId, payment.getPaymentId() + "-refund");
        assertEquals(txCmds.getTransactions(joe).size(), joeRows + 1);
        final Transaction row = txCmds.getTransaction(joe, txId);
        assertEquals(row.getAmount(), -100.0f);
        assertEquals(row.getCategory(), "Refund");
        assertEquals(row.getOrgId(), FakeData.CFPW_ORG_ID, "stamped with the trip's organization, like every row");

        // The rest comes back: same row, new cumulative amount, no second line.
        assertEquals(recorder.recordRefund(payment, 98487L, "CFPW", "Stripe"), txId);
        assertEquals(txCmds.getTransactions(joe).size(), joeRows + 1, "rewritten in place, never duplicated");
        assertEquals(txCmds.getTransaction(joe, txId).getAmount(), -984.87f);
        assertEquals(recorder.refundLine(payment, 98487L, "CFPW", "Stripe").description(),
                "Stripe (id #123447384) Refund of $984.87 from CFPW", "a full refund is not 'partial'");
        assertEquals(txCmds.getTransactions(bob).size(), 1, "Bob's credit row is untouched");

        assertEquals(recorder.refundLine(payment, 0L, "CFPW", "Stripe").description(),
                "Stripe (id #123447384) Refund of $0.00 (the refund was reversed by the processor) from CFPW");

        payment.setSandbox(true);
        assertEquals(recorder.recordRefund(payment, 500L, "CFPW", "Stripe"), null, "sandbox writes nothing");
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
                .tripId(FakeData.FAKE_TRIP_ID)
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
