package org.paulsens.trip.pay;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.TransactionsCommands;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Payment;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;

/**
 * Turns a CAPTURED {@link Payment} into ledger {@link Transaction}s -- the exact user-locked spec of
 * 2026-08-17. Entered amounts are the amounts credited; fees ride in DESCRIPTIONS, never as separate fee
 * transactions:
 *
 * <ul>
 *   <li>Two or more EQUAL credits &rarr; ONE SHARED group (groupId {@code {paymentId}-pay}, amount = the
 *       credit total; the existing read-time division shows each member their share). Unequal credits &rarr;
 *       one plain row each ({@code {paymentId}-pay-{personId}}); a single credit &rarr; one plain row.</li>
 *   <li>A donation adds TWO rows on the payer: {@code +D} (txType Payment, "before $X fee") and {@code -D}
 *       (txType Donation, "Thank you for your donation to {org}!") -- visible, balance-neutral.</li>
 *   <li>Every row's description carries the processor's CAPTURE ID (the id searchable in the processor's
 *       own console) and is stamped with the payment's org.</li>
 * </ul>
 *
 * <p>Transaction/group ids derive from the paymentId, so re-recording after a crash UPDATES the same rows --
 * never duplicates (the reconciliation "Retry recording" button depends on this). A SANDBOX payment computes
 * the same lines but writes nothing: the lines themselves are the "would have created" answer.
 */
@Slf4j
public class PaymentRecorder {
    private final TransactionsCommands txCmds;
    private final Function<Person.Id, String> names;

    public PaymentRecorder() {
        this(new TransactionsCommands(), PaymentRecorder::preferredName);
    }

    /** Test seam: ledger writer + name resolver handed in. */
    public PaymentRecorder(final TransactionsCommands txCmds, final Function<Person.Id, String> names) {
        this.txCmds = txCmds;
        this.names = names;
    }

    /** One ledger row this payment produces (or would produce, in sandbox). */
    public record Line(Kind kind, Person.Id userId, String txOrGroupId, long amountCents,
            Transaction.TransactionType txType, String description) {
        public enum Kind {
            SHARED_CREDIT, CREDIT, DONATION, DONATION_OFFSET
        }
    }

    /** The applied outcome: the computed lines, and the row ids actually written (empty for sandbox). */
    public record Result(List<Line> lines, List<String> txIds, boolean applied) {
    }

    /**
     * Computes the exact rows for this payment -- pure, and the golden-example unit test's subject. The
     * payment must already carry its capture id.
     */
    public List<Line> computeLines(final Payment payment, final String orgName, final String processorName) {
        final List<Payment.Allocation> selected = payment.getAllocations().stream()
                .filter(allocation -> allocation.getAmountCents() > 0)
                .toList();
        final List<Line> lines = new ArrayList<>();
        if (!selected.isEmpty()) {
            creditLines(lines, payment, selected, orgName, processorName);
        }
        if (payment.getDonationCents() > 0) {
            donationLines(lines, payment, orgName, processorName);
        }
        return lines;
    }

    /**
     * Applies the lines (idempotently) unless the payment is sandbox, in which case the lines are returned
     * unapplied as the dry-run summary. Throws {@link IllegalStateException} when a live write fails --
     * money has moved by now, so the caller must keep the payment in CAPTURED and surface the problem.
     */
    public Result record(final Payment payment, final String orgName, final String processorName) {
        final List<Line> lines = computeLines(payment, orgName, processorName);
        if (payment.isSandbox()) {
            return new Result(lines, List.of(), false);
        }
        final List<String> txIds = new ArrayList<>();
        for (final Line line : lines) {
            if (line.kind() == Line.Kind.SHARED_CREDIT) {
                txIds.addAll(applySharedLine(payment, line));
            } else {
                txIds.add(applyPlainLine(payment, line));
            }
        }
        return new Result(lines, txIds, true);
    }

    // ------------------------------------------------------------------ line computation

    private void creditLines(final List<Line> lines, final Payment payment,
            final List<Payment.Allocation> selected, final String orgName, final String processorName) {
        final long creditTotal = selected.stream().mapToLong(Payment.Allocation::getAmountCents).sum();
        final boolean equal = selected.stream()
                .allMatch(allocation -> allocation.getAmountCents() == selected.get(0).getAmountCents());
        if (selected.size() >= 2 && equal) {
            lines.add(new Line(Line.Kind.SHARED_CREDIT, payment.getPayerId(),
                    payment.getPaymentId() + "-pay", creditTotal, Transaction.TransactionType.Payment,
                    creditDescription(payment, creditTotal, orgName, processorName,
                            memberNames(payment, selected))));
        } else if (selected.size() == 1) {
            lines.add(new Line(Line.Kind.CREDIT, selected.get(0).getPersonId(),
                    payment.getPaymentId() + "-pay", selected.get(0).getAmountCents(),
                    Transaction.TransactionType.Payment,
                    creditDescription(payment, creditTotal, orgName, processorName, null)));
        } else {
            for (final Payment.Allocation allocation : selected) {
                lines.add(new Line(Line.Kind.CREDIT, allocation.getPersonId(),
                        payment.getPaymentId() + "-pay-" + allocation.getPersonId().getValue(),
                        allocation.getAmountCents(), Transaction.TransactionType.Payment,
                        creditDescription(payment, creditTotal, orgName, processorName,
                                memberNames(payment, selected))));
            }
        }
    }

    /**
     * The credit rows' description, per the worked example: payer-pays names the CHARGED portion and the
     * fee deducted from nobody's credit ("Payment of $1,500 (minus $75 PayPal fee)"); org-pays names the
     * credit total with the fee commented ("before" wording), never deducted.
     */
    private String creditDescription(final Payment payment, final long creditTotal, final String orgName,
            final String processorName, final String splitWith) {
        final StringBuilder description = new StringBuilder(processorName)
                .append(" (id #").append(payment.getCaptureId()).append(") Payment of ");
        if (payment.isPayerPaysFee() && payment.getCreditFeeCents() > 0) {
            description.append(MoneyMath.formatCents(creditTotal + payment.getCreditFeeCents()))
                    .append(" (minus ").append(MoneyMath.formatCents(payment.getCreditFeeCents()))
                    .append(' ').append(processorName).append(" fee)");
        } else {
            description.append(MoneyMath.formatCents(creditTotal));
            if (payment.getCreditFeeCents() > 0) {
                description.append(" (").append(orgName).append(" covered the ")
                        .append(MoneyMath.formatCents(payment.getCreditFeeCents()))
                        .append(' ').append(processorName).append(" fee)");
            }
        }
        description.append(" to ").append(orgName);
        if (splitWith != null) {
            description.append(" split with ").append(splitWith);
        }
        return description.toString();
    }

    private void donationLines(final List<Line> lines, final Payment payment, final String orgName,
            final String processorName) {
        final StringBuilder plus = new StringBuilder(processorName)
                .append(" (id #").append(payment.getCaptureId()).append(") Payment to ").append(orgName);
        if (payment.getDonationFeeCents() > 0) {
            plus.append(" (before ").append(MoneyMath.formatCents(payment.getDonationFeeCents()))
                    .append(' ').append(processorName).append(" fee)");
        }
        lines.add(new Line(Line.Kind.DONATION, payment.getPayerId(), payment.getPaymentId() + "-don",
                payment.getDonationCents(), Transaction.TransactionType.Payment, plus.toString()));
        lines.add(new Line(Line.Kind.DONATION_OFFSET, payment.getPayerId(),
                payment.getPaymentId() + "-donx", -payment.getDonationCents(),
                Transaction.TransactionType.Donation, "Thank you for your donation to " + orgName + "!"));
    }

    /**
     * "Joe", "Joe and Bob", "Joe, Bob, and Mike" -- the worked example's wording. The PAYER leads, the rest
     * are alphabetical: allocations sort by person id, which would shuffle the names every run.
     */
    private String memberNames(final Payment payment, final List<Payment.Allocation> selected) {
        String payerName = null;
        final List<String> rest = new ArrayList<>();
        for (final Payment.Allocation allocation : selected) {
            final String name = names.apply(allocation.getPersonId());
            if (allocation.getPersonId().equals(payment.getPayerId())) {
                payerName = name;
            } else {
                rest.add(name);
            }
        }
        rest.sort(String.CASE_INSENSITIVE_ORDER);
        final List<String> all = new ArrayList<>();
        if (payerName != null) {
            all.add(payerName);
        }
        all.addAll(rest);
        if (all.size() == 1) {
            return all.get(0);
        }
        if (all.size() == 2) {
            return all.get(0) + " and " + all.get(1);
        }
        return String.join(", ", all.subList(0, all.size() - 1)) + ", and " + all.get(all.size() - 1);
    }

    // ------------------------------------------------------------------ application

    private List<String> applySharedLine(final Payment payment, final Line line) {
        final List<Person.Id> members = payment.getAllocations().stream()
                .filter(allocation -> allocation.getAmountCents() > 0)
                .map(Payment.Allocation::getPersonId)
                .toList();
        // origPeople == members so a re-run updates the same group rows in place (never deletes/duplicates).
        final boolean saved = txCmds.saveGroupTransaction(line.txOrGroupId(), members,
                Transaction.Type.Shared, line.txType(), LocalDateTime.now(),
                MoneyMath.toFloatDollars(line.amountCents()), "Payment", line.description(),
                payment.getTripId(), null, members);
        if (!saved) {
            throw new IllegalStateException("Ledger write failed for payment " + payment.getPaymentId()
                    + " (shared group " + line.txOrGroupId() + ")");
        }
        return members.stream()
                .map(member -> txCmds.getGroupTransactionForUser(member, line.txOrGroupId())
                        .map(Transaction::getTxId).orElse(null))
                .filter(txId -> txId != null)
                .toList();
    }

    private String applyPlainLine(final Payment payment, final Line line) {
        final Transaction tx = new Transaction(line.txOrGroupId(), line.userId(), null,
                Transaction.Type.Tx, line.txType(), LocalDateTime.now(),
                MoneyMath.toFloatDollars(line.amountCents()),
                line.txType() == Transaction.TransactionType.Donation ? "Donation" : "Payment",
                line.description());
        tx.setOrgId(payment.getOrgId());
        if (!txCmds.saveTransaction(tx, payment.getTripId())) {
            throw new IllegalStateException("Ledger write failed for payment " + payment.getPaymentId()
                    + " (tx " + line.txOrGroupId() + ")");
        }
        if (payment.getTripId() != null && !payment.getTripId().isBlank()) {
            txCmds.getBind().setBindings(
                    txCmds.getBind().key(line.userId().getValue(), tx.getTxId()),
                    BindingType.TRANSACTION, BindingType.TRIP, List.of(payment.getTripId()), true);
        }
        return tx.getTxId();
    }

    private static String preferredName(final Person.Id personId) {
        return DAO.getInstance().getPerson(personId, Cached.YES)
                .map(Person::getPreferredName).orElse(personId.getValue());
    }
}
