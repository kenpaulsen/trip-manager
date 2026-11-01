package org.paulsens.trip.pay;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.action.TransactionsCommands;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;

/**
 * The one writer of DETERMINISTIC Bill rows (docs/payments.md, "The money rules"): a row whose id is derived from
 * what it bills, so a recompute or a re-record rewrites the same row in place and never duplicates it. Shared by
 * lodging ({@code LodgingBiller}) and priced checkout ({@code CheckoutCommands}, docs/checkout.md).
 *
 * <p>Compare-then-write: an unchanged amount and note writes nothing; zero soft-deletes an existing row; every
 * written row is org-stamped through {@code saveTransaction(tx, tripId)} and bound to its trip (and event). A failed
 * write throws, because the caller (a recorded payment, a reservation save) must not report success over a gap.
 */
public final class Bills {

    /** What one {@link #apply} did. */
    public enum Outcome {
        WRITTEN, UNCHANGED, REMOVED, NONE
    }

    private Bills() {
    }

    /**
     * Writes, rewrites or removes the Bill row {@code txId} for {@code person}. {@code signedCents} is the row's
     * stored sign: a charge is NEGATIVE (a balance is the plain sum of a person's rows), a credit positive.
     */
    public static Outcome apply(final TransactionsCommands txCmds, final String txId, final Person.Id person,
            final long signedCents, final String category, final String description, final String tripId,
            final String eventId) {
        final Transaction existing = DAO.getInstance().getTransaction(person, txId, Cached.NO).orElse(null);
        if (signedCents == 0L) {
            if (existing == null) {
                return Outcome.NONE;
            }
            existing.delete();
            save(txCmds, existing, tripId, txId);
            return Outcome.REMOVED;
        }
        if (existing != null && existing.getAmount() != null && cents(existing.getAmount()) == signedCents
                && description.equals(existing.getNote())) {
            return Outcome.UNCHANGED;
        }
        final Transaction tx = new Transaction(txId, person, null, Transaction.Type.Tx,
                Transaction.TransactionType.Bill, existing == null ? LocalDateTime.now() : existing.getTxDate(),
                MoneyMath.toFloatDollars(signedCents), category, description);
        if (existing != null) {
            tx.setOrgId(existing.getOrgId());
        }
        save(txCmds, tx, tripId, txId);
        if (tripId != null && !tripId.isBlank()) {
            final String key = txCmds.getBind().key(person.getValue(), txId);
            txCmds.getBind().setBindings(key, BindingType.TRANSACTION, BindingType.TRIP, List.of(tripId), true);
            if (eventId != null && !eventId.isBlank()) {
                txCmds.getBind().setBindings(key, BindingType.TRANSACTION, BindingType.TRIP_EVENT, List.of(eventId),
                        true);
            }
        }
        return Outcome.WRITTEN;
    }

    /** Signed cents from a row's Float (bills are negative, so {@code MoneyMath.toCents}'s guard does not fit). */
    public static long cents(final Float dollars) {
        return BigDecimal.valueOf(dollars.doubleValue()).movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static void save(final TransactionsCommands txCmds, final Transaction tx, final String tripId,
            final String txId) {
        if (!txCmds.saveTransaction(tx, tripId)) {
            throw new IllegalStateException("Ledger write failed for bill " + txId);
        }
    }
}
