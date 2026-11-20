package org.paulsens.trip.migrate;

import java.util.List;
import org.paulsens.trip.model.CompositeKey;

/**
 * What {@code plan} decided for every live ledger row bound to no trip
 * (docs/migrations/ledger-trip-binding.md): the rows an apply binds, and the rows it leaves for the owner
 * ({@code uncertain.csv}). Saved as {@code plan.json}; an apply runs exactly this file.
 *
 * @param asOf      the day "has this trip ended" was asked on
 * @param binds     the rows to bind, in the order they were decided
 * @param uncertain the rows that need the owner's decision
 */
public record LedgerBindingPlan(String asOf, List<Bind> binds, List<Uncertain> uncertain) {

    /** Which rule bound a row. */
    public enum Kind {
        /** The row's partition IS a trip. */
        TRIP_OWN,
        /** Every live row of a Shared or Batch group, bound together. */
        GROUP,
        /** The person's one trip whose date window holds the row. */
        DETERMINISTIC,
        /** Several trips could hold it; payments and invoices reconciled. */
        INFERRED,
        /** The owner named the trip in the review file. */
        APPROVED
    }

    /** Why a row is left to the owner. */
    public enum Why {
        NO_ROSTER("the person is on no roster or registration of a trip of this organization"),
        NO_WINDOW("no trip of this person has a date window holding the row"),
        NO_DATE("the row has no date"),
        NOT_ENDED("the trip has not ended"),
        ORG_MISMATCH("the row and the trip belong to different organizations"),
        MISSING_TRIP("the rest of the group is bound to a trip that no longer exists"),
        GROUP_NO_COMMON_TRIP("the group's members share no single in-window trip"),
        GROUP_SEVERAL_TRIPS("the group's members share several in-window trips"),
        GROUP_SPLIT("the group's rows are already on several trips"),
        NOT_RECONCILED("several trips could hold the row and their payments and invoices do not reconcile");

        private final String text;

        Why(final String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /**
     * One row to bind.
     *
     * @param fingerprint the row's content when it was planned; an apply skips a row that reads differently
     */
    public record Bind(String userId, String txId, String tripId, Kind kind, String reason, String groupId,
            String fingerprint) {
        public String key() {
            return CompositeKey.join(userId, txId);
        }
    }

    /**
     * One row for the owner.
     *
     * @param candidateTripIds the trips it could belong to: the in-window ones, else every trip the person is on
     * @param bestGuessTripId  the likeliest trip, or null
     * @param requiredTripId   the only trip an approval may name (the rest of its group is on it), or null
     */
    public record Uncertain(String userId, String txId, String groupId, Why why, String reason,
            List<String> candidateTripIds, String bestGuessTripId, String bestGuessReason, String requiredTripId,
            String fingerprint) {
        public String key() {
            return CompositeKey.join(userId, txId);
        }
    }

    public long count(final Kind kind) {
        return binds.stream().filter(bind -> bind.kind() == kind).count();
    }

    public long count(final Why why) {
        return uncertain.stream().filter(row -> row.why() == why).count();
    }
}
