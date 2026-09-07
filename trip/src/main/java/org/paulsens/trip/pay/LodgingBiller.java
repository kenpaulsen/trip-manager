package org.paulsens.trip.pay;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.TransactionsCommands;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Transaction;

/**
 * Writes lodging into the ledger -- the {@link PaymentRecorder} shape. There is no invoice model: a charge is a
 * {@link Transaction.TransactionType#Bill} row, written NEGATIVE (a balance is the plain sum of a person's
 * rows and a negative total renders "owes"; payments are positive), one per occupant per reservation, id
 * {@code {reservationId}-lodging-{personId}} so a recompute rewrites the same row in place -- idempotent, never
 * duplicated. A cancellation credit is a POSITIVE Bill {@code {reservationId}-cancel-{personId}} for what was
 * billed minus that person's share of the fee; the original rows stay, so the ledger reads as history.
 *
 * <p>Rules an operator can rely on: a line that prices to zero writes nothing and soft-deletes a stale row;
 * an unchanged amount and description is left alone. Every row is bound to the trip and to the offer's
 * LODGING event (the transaction pages list by those bindings). NB a soft-deleted transaction reads as ABSENT
 * through the DAO, so a lodging bill an admin deletes by hand comes back on the next recompute: the way to
 * stop billing a stay is to cancel the reservation, not to delete its row.
 */
@Slf4j
public class LodgingBiller {
    public static final String CATEGORY = "Lodging";
    static final String BILL_SUFFIX = "-lodging-";
    static final String CANCEL_SUFFIX = "-cancel-";

    private final TransactionsCommands txCmds;

    public LodgingBiller() {
        this(new TransactionsCommands());
    }

    /** Test seam: the ledger writer handed in. */
    public LodgingBiller(final TransactionsCommands txCmds) {
        this.txCmds = txCmds;
    }

    /** What one apply did, for the audit message and the admin's growl. */
    public record Result(int written, int unchanged, int removed) {
        public Result plus(final Result other) {
            return new Result(written + other.written, unchanged + other.unchanged, removed + other.removed);
        }

        public static Result none() {
            return new Result(0, 0, 0);
        }

        public String summary() {
            return written + " written, " + unchanged + " unchanged, " + removed + " removed";
        }
    }

    public static String billTxId(final Reservation.Id reservationId, final Person.Id personId) {
        return reservationId.getValue() + BILL_SUFFIX + personId.getValue();
    }

    public static String cancelTxId(final Reservation.Id reservationId, final Person.Id personId) {
        return reservationId.getValue() + CANCEL_SUFFIX + personId.getValue();
    }

    /**
     * Writes (or rewrites, or removes) the Bill row behind each line. Throws {@link IllegalStateException}
     * when a write fails: the caller is mid-way through a recompute and must surface that, not swallow it.
     */
    public Result applyBills(final Reservation reservation, final ReservationOffer offer,
            final List<LodgingPricing.Line> lines) {
        Result result = Result.none();
        for (final LodgingPricing.Line line : lines) {
            result = result.plus(applyOne(billTxId(reservation.getId(), line.personId()), line.personId(),
                    -line.amountCents(), line.description(), reservation.getTripId(), offer.getTripEventId()));
        }
        return result;
    }

    /**
     * The cancellation credits: for each occupant, a positive Bill of (what they were billed minus their
     * share of the fee). Zero credits (nothing billed, or the fee eats it) write nothing.
     *
     * @param billedByPerson what the ORIGINAL Bill rows say, per occupant ({@link #billedByPerson}).
     */
    public Result applyCancelCredits(final Reservation reservation, final ReservationOffer offer,
            final Map<Person.Id, Long> billedByPerson, final long feeCents, final String description) {
        final List<Person.Id> occupants = reservation.sortedOccupants();
        if (occupants.isEmpty()) {
            return Result.none();
        }
        final long[] shares = LodgingPricing.feeShares(feeCents, occupants.size());
        Result result = Result.none();
        for (int i = 0; i < occupants.size(); i++) {
            final Person.Id person = occupants.get(i);
            final long credit = Math.max(0L, billedByPerson.getOrDefault(person, 0L) - shares[i]);
            result = result.plus(applyOne(cancelTxId(reservation.getId(), person), person, credit, description,
                    reservation.getTripId(), offer == null ? null : offer.getTripEventId()));
        }
        return result;
    }

    /** What each occupant is currently billed for this reservation (live Bill rows only), in positive cents. */
    public Map<Person.Id, Long> billedByPerson(final Reservation reservation) {
        final Map<Person.Id, Long> billed = new LinkedHashMap<>();
        for (final Person.Id person : reservation.sortedOccupants()) {
            final Transaction existing = DAO.getInstance()
                    .getTransaction(person, billTxId(reservation.getId(), person), Cached.NO).orElse(null);
            final long cents = (existing == null || existing.getAmount() == null) ? 0L : -cents(existing.getAmount());
            billed.put(person, Math.max(0L, cents));
        }
        return billed;
    }

    private Result applyOne(final String txId, final Person.Id person, final long signedCents,
            final String description, final String tripId, final String eventId) {
        final Transaction existing = DAO.getInstance().getTransaction(person, txId, Cached.NO).orElse(null);
        if (signedCents == 0L) {
            if (existing == null) {
                return Result.none();
            }
            existing.delete();
            save(existing, tripId, txId);
            return new Result(0, 0, 1);
        }
        if (existing != null && existing.getAmount() != null && cents(existing.getAmount()) == signedCents
                && description.equals(existing.getNote())) {
            return new Result(0, 1, 0);
        }
        final Transaction tx = new Transaction(txId, person, null, Transaction.Type.Tx,
                Transaction.TransactionType.Bill, existing == null ? LocalDateTime.now() : existing.getTxDate(),
                MoneyMath.toFloatDollars(signedCents), CATEGORY, description);
        if (existing != null) {
            tx.setOrgId(existing.getOrgId());
        }
        save(tx, tripId, txId);
        if (tripId != null && !tripId.isBlank()) {
            final String key = txCmds.getBind().key(person.getValue(), txId);
            txCmds.getBind().setBindings(key, BindingType.TRANSACTION, BindingType.TRIP, List.of(tripId), true);
            if (eventId != null && !eventId.isBlank()) {
                txCmds.getBind().setBindings(key, BindingType.TRANSACTION, BindingType.TRIP_EVENT, List.of(eventId),
                        true);
            }
        }
        return new Result(1, 0, 0);
    }

    private void save(final Transaction tx, final String tripId, final String txId) {
        if (!txCmds.saveTransaction(tx, tripId)) {
            throw new IllegalStateException("Ledger write failed for lodging row " + txId);
        }
    }

    /** Signed cents from the row's Float (bills are negative, so {@code MoneyMath.toCents}'s guard does not fit). */
    static long cents(final Float dollars) {
        return BigDecimal.valueOf(dollars.doubleValue()).movePointRight(2)
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
}
