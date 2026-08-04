package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class TransactionDAOTest {
    private TransactionDAO dao;

    @BeforeMethod
    public void setup() {
        dao = new TransactionDAO(new ObjectMapper().findAndRegisterModules(), DynamoLocal.persistence());
    }

    @Test
    public void transactionsAreServedFromCacheAfterFirstLoad() {
        final Person.Id userId = Person.Id.from(RandomData.genAlpha(5));
        final List<Integer> queryCount = new ArrayList<>();
        final Persistence persistence =
                FakeData.createFakePersistenceWithQueryMonitor(qb -> queryCount.add(1));
        final TransactionDAO localDao =
                new TransactionDAO(new ObjectMapper().findAndRegisterModules(), persistence);
        assertTrue(get(localDao.getTransactions(userId)).isEmpty());
        assertEquals(queryCount.size(), 1, "First read should query the database");
        assertTrue(get(localDao.getTransactions(userId)).isEmpty());
        assertEquals(queryCount.size(), 1, "Second read should be served by the cache");
    }

    @Test
    public void saveAndRetrieveTransaction() throws IOException {
        final Person.Id userId = Person.Id.newInstance();
        final Transaction tx = new Transaction(
                RandomData.genAlpha(8), userId, RandomData.genAlpha(5),
                Transaction.Type.Tx, Transaction.TransactionType.Payment,
                LocalDateTime.now(), 100.0f, "food", "lunch");
        assertTrue(get(dao.saveTransaction(tx)));
        final Optional<Transaction> found = get(dao.getTransaction(userId, tx.getTxId()));
        assertTrue(found.isPresent());
        assertEquals(found.get(), tx);
    }

    @Test
    public void getTransactionsReturnsEmptyForUnknownUser() {
        assertTrue(get(dao.getTransactions(Person.Id.newInstance())).isEmpty());
    }

    @Test
    public void getTransactionReturnsEmptyForUnknownTxId() {
        final Person.Id userId = Person.Id.newInstance();
        assertTrue(get(dao.getTransaction(userId, "nonexistent")).isEmpty());
    }

    @Test
    public void multipleTransactionsForSameUser() throws IOException {
        final Person.Id userId = Person.Id.newInstance();
        for (int i = 0; i < 5; i++) {
            get(dao.saveTransaction(new Transaction(
                    RandomData.genAlpha(8), userId, RandomData.genAlpha(5),
                    Transaction.Type.Tx, Transaction.TransactionType.Payment,
                    LocalDateTime.now().plusMinutes(i), (float) (i * 10), "cat", "note")));
        }
        assertEquals(get(dao.getTransactions(userId)).size(), 5);
    }

    @Test
    public void transactionsForDifferentUsersAreIsolated() throws IOException {
        final Person.Id u1 = Person.Id.newInstance();
        final Person.Id u2 = Person.Id.newInstance();
        get(dao.saveTransaction(new Transaction(u1, "g1", Transaction.Type.Tx)));
        get(dao.saveTransaction(new Transaction(u1, "g2", Transaction.Type.Tx)));
        get(dao.saveTransaction(new Transaction(u2, "g3", Transaction.Type.Tx)));
        assertEquals(get(dao.getTransactions(u1)).size(), 2);
        assertEquals(get(dao.getTransactions(u2)).size(), 1);
    }

    @Test
    public void saveTransactionIsIdempotent() throws IOException {
        final Person.Id userId = Person.Id.newInstance();
        final Transaction tx = new Transaction(
                "fixed-id", userId, "g1", Transaction.Type.Tx,
                Transaction.TransactionType.Payment, LocalDateTime.now(), 50f, "cat", "note");
        get(dao.saveTransaction(tx));
        get(dao.saveTransaction(tx));
        assertEquals(get(dao.getTransactions(userId)).size(), 1);
    }

    @Test
    public void deletedTransactionIsRemovedFromCache() throws IOException {
        final Person.Id userId = Person.Id.newInstance();
        final Transaction tx = new Transaction(
                RandomData.genAlpha(8), userId, "g1", Transaction.Type.Tx,
                Transaction.TransactionType.Payment, LocalDateTime.now(), 75f, "cat", "note");
        get(dao.saveTransaction(tx));
        assertEquals(get(dao.getTransactions(userId)).size(), 1);
        tx.delete();
        get(dao.saveTransaction(tx));
        assertEquals(get(dao.getTransactions(userId)).size(), 0);
    }

    @Test
    public void transactionsAreSortedByDate() throws IOException {
        final Person.Id userId = Person.Id.newInstance();
        final LocalDateTime now = LocalDateTime.now();
        get(dao.saveTransaction(new Transaction(
                "tx3", userId, "g", Transaction.Type.Tx, Transaction.TransactionType.Payment,
                now.plusHours(3), 30f, "c", "third")));
        get(dao.saveTransaction(new Transaction(
                "tx1", userId, "g", Transaction.Type.Tx, Transaction.TransactionType.Payment,
                now.plusHours(1), 10f, "c", "first")));
        get(dao.saveTransaction(new Transaction(
                "tx2", userId, "g", Transaction.Type.Tx, Transaction.TransactionType.Payment,
                now.plusHours(2), 20f, "c", "second")));
        final List<Transaction> txs = get(dao.getTransactions(userId));
        assertEquals(txs.get(0).getNote(), "first");
        assertEquals(txs.get(1).getNote(), "second");
        assertEquals(txs.get(2).getNote(), "third");
    }

    /**
     * Clearing the cache must not lose data.
     *
     * <p>This asserted the OPPOSITE until the DAO tests moved onto a real engine: that the row was GONE after a
     * clear. That was true only because the fake persistence stored nothing, so the cache WAS the store. The
     * real invariant is that a clear drops the cached copy and the next read is served by the store.
     */
    @Test
    public void clearingTheCacheDoesNotLoseTheRow() throws IOException {
        final Person.Id userId = Person.Id.newInstance();
        get(dao.saveTransaction(new Transaction(userId, "g", Transaction.Type.Tx)));
        assertEquals(get(dao.getTransactions(userId)).size(), 1);

        dao.clearCache();

        assertEquals(get(dao.getTransactions(userId)).size(), 1);
    }

    private <T> T get(final CompletableFuture<T> future) {
        try {
            return future.get(1_000, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException | ExecutionException | TimeoutException ex) {
            throw new RuntimeException(ex);
        }
    }
}