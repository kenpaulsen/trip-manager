package org.paulsens.trip.migrate;

import java.util.List;
import java.util.Optional;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.CompositeKey;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Trip;

/**
 * The little of the store an apply or a rollback touches (docs/migrations/ledger-trip-binding.md): a fresh read
 * of one row, of its bindings in each direction and of a trip's organization, and the binding writes. {@link Live}
 * is the deployment through the DAO; {@link Memory} is a {@link LedgerData}'s own bindings, which is how a
 * rehearsal runs the real applier on a dump without a store.
 */
interface LedgerStore {
    /** The row as stored now, absent once it is soft-deleted or gone. */
    Optional<Transaction> liveRow(String userId, String txId);

    /** The trips the row is bound to (TRANSACTION to TRIP). */
    List<String> tripsOf(String rowKey);

    /** The row keys on the trip's books (TRIP to TRANSACTION). */
    List<String> rowsOf(String tripId);

    /** The trip's organization; absent when there is no such trip, "" when it has none. */
    Optional<String> tripOrg(String tripId);

    /** Writes whichever of the two directions is missing; false when the store refused. */
    boolean bind(String rowKey, String tripId);

    boolean unbindForward(String rowKey, String tripId);

    boolean unbindReverse(String tripId, String rowKey);

    /**
     * The deployment's tables through the DAO. Every read is uncached, and the command line runs with the shared
     * cache off, so nothing here is answered from a copy.
     */
    final class Live implements LedgerStore {
        private final DAO dao;

        Live(final DAO dao) {
            this.dao = dao;
        }

        @Override
        public Optional<Transaction> liveRow(final String userId, final String txId) {
            return dao.getTransaction(Person.Id.from(userId), txId, Cached.NO)
                    .filter(tx -> tx.getDeleted() == null);
        }

        @Override
        public List<String> tripsOf(final String rowKey) {
            return dao.getBindings(rowKey, BindingType.TRANSACTION, BindingType.TRIP, Cached.NO);
        }

        @Override
        public List<String> rowsOf(final String tripId) {
            return dao.getBindings(tripId, BindingType.TRIP, BindingType.TRANSACTION, Cached.NO);
        }

        @Override
        public Optional<String> tripOrg(final String tripId) {
            return dao.getTrip(tripId, Cached.NO).map(Live::orgOf);
        }

        private static String orgOf(final Trip trip) {
            return trip.getOrgId() == null ? "" : trip.getOrgId();
        }

        /**
         * {@code DAO.saveBinding} and its answer, never {@code BindingCommands.setBindings}: that one REPLACES a
         * row's bindings (it would drop a trip somebody bound meanwhile) and only logs a failed write.
         */
        @Override
        public boolean bind(final String rowKey, final String tripId) {
            return Boolean.TRUE.equals(
                    dao.saveBinding(rowKey, BindingType.TRANSACTION, tripId, BindingType.TRIP, true));
        }

        @Override
        public boolean unbindForward(final String rowKey, final String tripId) {
            return Boolean.TRUE.equals(
                    dao.removeBinding(rowKey, BindingType.TRANSACTION, tripId, BindingType.TRIP, false));
        }

        @Override
        public boolean unbindReverse(final String tripId, final String rowKey) {
            return Boolean.TRUE.equals(
                    dao.removeBinding(tripId, BindingType.TRIP, rowKey, BindingType.TRANSACTION, false));
        }
    }

    /** A ledger read from a dump, written to in memory only. */
    final class Memory implements LedgerStore {
        private final LedgerData data;

        Memory(final LedgerData data) {
            this.data = data;
        }

        @Override
        public Optional<Transaction> liveRow(final String userId, final String txId) {
            return Optional.ofNullable(data.liveRow(CompositeKey.join(userId, txId))).map(LedgerData.Row::tx);
        }

        @Override
        public List<String> tripsOf(final String rowKey) {
            return List.copyOf(data.tripsOf(rowKey));
        }

        @Override
        public List<String> rowsOf(final String tripId) {
            return List.copyOf(data.rowsOf(tripId));
        }

        @Override
        public Optional<String> tripOrg(final String tripId) {
            return Optional.ofNullable(data.trips().get(tripId)).map(Memory::orgOf);
        }

        private static String orgOf(final LedgerData.TripInfo trip) {
            return trip.orgId() == null ? "" : trip.orgId();
        }

        @Override
        public boolean bind(final String rowKey, final String tripId) {
            data.bindForward(rowKey, tripId);
            data.bindReverse(tripId, rowKey);
            return true;
        }

        @Override
        public boolean unbindForward(final String rowKey, final String tripId) {
            return data.unbindForward(rowKey, tripId);
        }

        @Override
        public boolean unbindReverse(final String tripId, final String rowKey) {
            return data.unbindReverse(tripId, rowKey);
        }
    }
}
