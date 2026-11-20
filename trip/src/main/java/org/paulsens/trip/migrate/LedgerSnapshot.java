package org.paulsens.trip.migrate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.paulsens.trip.action.TripLedgerTotals;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.Transaction;

/**
 * The ledger's figures at one moment, by TODAY'S arithmetic (docs/migrations/ledger-trip-binding.md): the "before"
 * every check of {@link LedgerBindingVerifier} compares against. Partitions come from the transactions themselves,
 * not from the people table, so a trip's own rows are counted. Balances are kept as the unrounded double the app
 * folds; a comparison rounds to cents once, at the end.
 *
 * @param asOf          the day "has this trip ended" was asked on
 * @param allRows       every stored row, soft-deleted ones included
 * @param liveHashes    row key to the hash of its stored JSON, live rows
 * @param deletedHashes the same for soft-deleted rows
 * @param balance       partition to its balance over every organization
 * @param balanceByOrg  partition to organization ("" for a row carrying none) to balance
 * @param tripTotals    trip to the three totals of its books
 * @param tripRows      trip to the live rows on its books (its TRIP to TRANSACTION bindings)
 * @param tripBound     trip that had not ended to person to the money bound to it (what reminders, tickets and
 *                      check-in read)
 * @param unboundLive   the live rows bound to no trip
 */
public record LedgerSnapshot(String asOf, int allRows, Map<String, String> liveHashes,
        Map<String, String> deletedHashes, Map<String, Double> balance,
        Map<String, Map<String, Double>> balanceByOrg, Map<String, TripLedgerTotals> tripTotals,
        Map<String, List<String>> tripRows, Map<String, Map<String, TripMoney>> tripBound, List<String> unboundLive) {

    /** One person's money on one trip, as {@code CheckinCommands.balanceOf} counts it: cents, rounded per row. */
    public record TripMoney(long paidCents, long billedCents) {
        TripMoney plus(final LedgerData.Row row) {
            final long cents = Math.round(row.share() * 100d);
            return row.tx().isPayment() ? new TripMoney(paidCents + cents, billedCents)
                    : new TripMoney(paidCents, billedCents - cents);
        }
    }

    /** The figures of this ledger, with "ended" judged on {@code today}. */
    public static LedgerSnapshot of(final LedgerData data, final LocalDate today) {
        final Map<String, String> liveHashes = new TreeMap<>();
        final Map<String, String> deletedHashes = new TreeMap<>();
        final List<String> unbound = new ArrayList<>();
        for (final LedgerData.Row row : data.rows()) {
            (row.live() ? liveHashes : deletedHashes).put(row.key(), row.hash());
            if (row.live() && data.tripsOf(row.key()).isEmpty()) {
                unbound.add(row.key());
            }
        }
        unbound.sort(null);
        final Map<String, Double> balance = new TreeMap<>();
        final Map<String, Map<String, Double>> byOrg = new TreeMap<>();
        for (final String partition : data.partitions()) {
            balance.put(partition, LedgerData.fold(data.liveRowsOf(partition)));
            byOrg.put(partition, balanceByOrg(data.liveRowsOf(partition)));
        }
        final Map<String, TripLedgerTotals> totals = new TreeMap<>();
        final Map<String, List<String>> tripRows = new TreeMap<>();
        final List<String> open = new ArrayList<>();
        for (final LedgerData.TripInfo trip : data.trips().values()) {
            totals.put(trip.id(), totals(data, trip.id(), row -> true));
            tripRows.put(trip.id(), data.rowsOf(trip.id()).stream().filter(key -> data.liveRow(key) != null)
                    .sorted().toList());
            if (!trip.ended(today)) {
                open.add(trip.id());
            }
        }
        return new LedgerSnapshot(today.toString(), data.rows().size(), liveHashes, deletedHashes, balance, byOrg,
                totals, tripRows, tripBound(data, open), unbound);
    }

    /** One partition's rows folded per organization, each in the order the rows are read. */
    static Map<String, Double> balanceByOrg(final Collection<LedgerData.Row> rows) {
        final Map<String, Double> byOrg = new TreeMap<>();
        for (final LedgerData.Row row : rows) {
            if (row.share() != null) {
                byOrg.merge(row.orgId(), (double) row.share(), Double::sum);
            } else {
                byOrg.putIfAbsent(row.orgId(), 0d);
            }
        }
        return byOrg;
    }

    /**
     * A trip's three totals over the live rows its TRIP to TRANSACTION bindings reach, at system reach (no site
     * filter), by the page's own rule ({@link TripLedgerTotals}).
     *
     * @param include narrows the rows, so the verifier can total a trip without its newly bound ones
     */
    static TripLedgerTotals totals(final LedgerData data, final String tripId,
            final Predicate<LedgerData.Row> include) {
        final List<Transaction> bound = new ArrayList<>();
        for (final String key : data.rowsOf(tripId)) {
            final LedgerData.Row row = data.liveRow(key);
            if (row != null && include.test(row)) {
                bound.add(row.tx());
            }
        }
        // Each row's share was computed once, at load, by TransactionsCommands.getUserAmount itself.
        return TripLedgerTotals.of(tripId, bound,
                tx -> data.row(CompositeKey.join(tx.getUserId().getValue(), tx.getTxId())).share());
    }

    /** Each of these trips' people with the money their rows bind to it (TRANSACTION to TRIP, as check-in reads). */
    static Map<String, Map<String, TripMoney>> tripBound(final LedgerData data, final Collection<String> tripIds) {
        final Map<String, Map<String, TripMoney>> bound = new TreeMap<>();
        tripIds.forEach(tripId -> bound.put(tripId, new TreeMap<>()));
        for (final LedgerData.Row row : data.liveRows()) {
            if (row.share() == null) {
                continue;
            }
            for (final String tripId : data.tripsOf(row.key())) {
                final Map<String, TripMoney> people = bound.get(tripId);
                if (people != null) {
                    people.put(row.userId(), people.getOrDefault(row.userId(), new TripMoney(0, 0)).plus(row));
                }
            }
        }
        return bound;
    }

    /** How many live rows the snapshot holds. */
    @JsonIgnore
    public int liveRows() {
        return liveHashes.size();
    }
}
