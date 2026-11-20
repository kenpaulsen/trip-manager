package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.paulsens.trip.model.Transaction;

/**
 * The three totals of a trip's books, by the ONE bucket rule the trip transactions page lists its rows with
 * ({@code trip/tripTransactions.xhtml}): of the rows reached through the trip's TRIP to TRANSACTION bindings, a row
 * whose {@code userId} is the trip's own id is a "trip" row (money to or from the trip itself, a bill or not),
 * any other {@code Bill} is an "invoice" row, and everything else is a "user" row. Each total is the fold, in
 * double, of the per-user amount ({@code TransactionsCommands.getUserAmount}: a Shared row counts this person's
 * share), in date order as the page renders them. Nothing is rounded per row; the cents accessors round the
 * finished total once, which is what a footer shows.
 *
 * <p>This class knows nothing about which SITE is asking. The page reads its rows through
 * {@code TransactionsCommands.getTransaction}, which hides a row whose organization the request's host does not
 * list, and renders its three footers from {@link TransactionsCommands#getTripLedgerTotals}, which folds those same
 * listed rows: a footer is the total of exactly the rows its table lists on that host. The ledger
 * migration ({@code migrate/LedgerBindingCli}) hands in every live bound row instead (system reach). The two agree
 * wherever the host lists the trip's organization, because a row's organization is its trip's.
 *
 * @param trip    the fold of the trip's own rows
 * @param invoice the fold of the bills to people
 * @param user    the fold of everything else (payments, refunds, donations)
 * @param rows    how many rows the three lists hold together, a row without an amount included
 */
public record TripLedgerTotals(double trip, double invoice, double user, int rows) {

    /** Which of the page's three lists a row lands in. */
    public enum Bucket {
        TRIP, INVOICE, USER
    }

    /** The page's rule for one row of this trip's books. */
    public static Bucket bucketOf(final String tripId, final Transaction tx) {
        if (tx.getUserId().getValue().equals(tripId)) {
            return Bucket.TRIP;
        }
        return tx.isBill() ? Bucket.INVOICE : Bucket.USER;
    }

    /**
     * The totals of these rows, which the caller has already resolved from the trip's bindings (live rows only),
     * sorted into the three lists by {@link #bucketOf}.
     *
     * @param tripId the trip whose books these are
     * @param bound  the trip's rows, in any order
     * @param share  the per-user amount of a row ({@code TransactionsCommands::getUserAmount}); null adds nothing
     */
    public static TripLedgerTotals of(final String tripId, final Collection<Transaction> bound,
            final Function<Transaction, Float> share) {
        final Map<Bucket, List<Transaction>> lists = new EnumMap<>(Bucket.class);
        for (final Transaction tx : bound) {
            lists.computeIfAbsent(bucketOf(tripId, tx), na -> new ArrayList<>()).add(tx);
        }
        return ofLists(lists.getOrDefault(Bucket.TRIP, List.of()), lists.getOrDefault(Bucket.INVOICE, List.of()),
                lists.getOrDefault(Bucket.USER, List.of()), share);
    }

    /**
     * The totals of three lists as given, each folded into its own footer: what a page that has already sorted its
     * rows into its three tables shows, so a footer is by construction the total of the table above it.
     */
    public static TripLedgerTotals ofLists(final Collection<Transaction> tripRows,
            final Collection<Transaction> invoiceRows, final Collection<Transaction> userRows,
            final Function<Transaction, Float> share) {
        return new TripLedgerTotals(fold(tripRows, share), fold(invoiceRows, share), fold(userRows, share),
                tripRows.size() + invoiceRows.size() + userRows.size());
    }

    private static double fold(final Collection<Transaction> rows, final Function<Transaction, Float> share) {
        final List<Transaction> sorted = new ArrayList<>(rows);
        // The page sorts each list by date before its footer folds it; a stable sort keeps ties as given.
        sorted.sort(Comparator.comparing(Transaction::getTxDate, Comparator.nullsFirst(Comparator.naturalOrder())));
        double total = 0;
        for (final Transaction tx : sorted) {
            final Float amount = share.apply(tx);
            if (amount != null) {
                total += amount;
            }
        }
        return total;
    }

    /** One bucket's total. */
    public double total(final Bucket bucket) {
        return switch (bucket) {
            case TRIP -> trip;
            case INVOICE -> invoice;
            case USER -> user;
        };
    }

    /** One bucket's total in whole cents, rounded once: what the page's footer displays. */
    public long cents(final Bucket bucket) {
        return LedgerStatements.cents(total(bucket));
    }
}
