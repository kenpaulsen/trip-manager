package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.paulsens.trip.model.Address;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Passport;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Transaction.Type;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class DynamoUtilsTest {
    private static final DynamoUtils DB_UTILS = DynamoUtils.getInstance();
    private static final long MONTH_IN_MILLIS = 86_400L * 31L * 1_000L;

    @BeforeMethod
    public void setupTest() {
        DB_UTILS.clearAllCaches();
    }

    @Test
    public void testSavePerson() throws IOException {
        final Person.Id id = Person.Id.from(RandomData.genAlpha(7));
        final String first = RandomData.genAlpha(5);
        final String last = RandomData.genAlpha(9);
        final Person person = new Person(id, null, first, null, last,
                null, null, null, null, null, null, null, null, null, null);
        Assert.assertEquals(Boolean.TRUE, DB_UTILS.savePerson(person).join());
        final Person samePerson = DB_UTILS.getPerson(id).join().orElse(null);
        Assert.assertEquals(person, samePerson);
    }

    @Test
    public void testGetPeople() throws IOException {
        final Person person1 = new Person(Person.Id.from("1"), "nick", "n1", "middle", "l1", LocalDate.now(),
                "cell", "email", "tsa", new Address(), new Passport(), "notes", null, null, null);
        final Person person2 = new Person(Person.Id.from("2"), null, "n2", null, "l2", null,
                null, null, null, null, null, null, null, null, null);
        final Person person3 = new Person(Person.Id.from("3"), "n3", "n3", null, "l3", null,
                null, null, null, null, null, null, null, null, null);
        Assert.assertEquals(Boolean.TRUE, DB_UTILS.savePerson(person2).join());
        Assert.assertEquals(Boolean.TRUE, DB_UTILS.savePerson(person1).join());
        Assert.assertEquals(Boolean.TRUE, DB_UTILS.savePerson(person3).join());
        final List<Person> people = DB_UTILS.getPeople().join();
        Assert.assertEquals(people.size(), 3);
        final Person person = people.stream().filter(p -> Person.Id.from("1").equals(p.getId())).findAny().orElse(null);
        Assert.assertEquals(person1, person);
    }

    @Test
    public void testGetTrips() throws IOException {
        final String id = RandomData.genAlpha(7);
        final String title = RandomData.genAlpha(5);
        final String desc = RandomData.genAlpha(9);
        final LocalDateTime start = LocalDateTime.now();
        final LocalDateTime end = LocalDateTime.now().plusDays(2);
        final Map<Person.Id, String> peopleTripEventStatus = Collections.singletonMap(
                Person.Id.from("admin"), "Conf #abc123");
        final List<TripEvent> te = Collections.singletonList(new TripEvent(UUID.randomUUID().toString(),
                "NY Flight", "description", start, null, peopleTripEventStatus));
        final Trip trip = new Trip(
                id, title, true, desc, start, end, Collections.singletonList(Person.Id.from("joe")), te,
                FakeData.getDefaultOptions());

        Assert.assertEquals(0, DB_UTILS.getTrips().join().size(), "Should start w/ no trips.");
        Assert.assertEquals(Boolean.TRUE, DB_UTILS.saveTrip(trip).join());
        Assert.assertEquals(1, DB_UTILS.getTrips().join().size(), "Expected 1 to be added.");
        Assert.assertEquals(Boolean.TRUE, DB_UTILS.saveTrip(trip).join()); // Verify idempotency, should still be 1
        Assert.assertEquals(1, DB_UTILS.getTrips().join().size(), "Expected only 1 still.");
        trip.setId(RandomData.genAlpha(10));
        Assert.assertEquals(Boolean.TRUE, DB_UTILS.saveTrip(trip).join()); // Verify idempotency, should still be 1
        Assert.assertEquals(2, DB_UTILS.getTrips().join().size(), "Expected 2 now.");
        final Trip sameTrip = DB_UTILS.getTrip(id).join().orElse(null);
        Assert.assertEquals(trip, sameTrip, "Getting trip should be equal.");
    }

    @Test
    public void nullEmailOrPassReturnsNothing() {
        Assert.assertNull(DB_UTILS.getCredsByEmailAndPass(null, RandomData.genAlpha(5)).join());
        Assert.assertNull(DB_UTILS.getCredsByEmailAndPass(RandomData.genAlpha(5), null).join());
        Assert.assertNull(DB_UTILS.getCredsByEmailAndPass(null, null).join());
        Assert.assertNull(DB_UTILS.getCredsByEmailAndPass(RandomData.genAlpha(5), RandomData.genAlpha(4)).join());
    }

    @Test
    public void adminCanLogin() {
        final String adminUN = "admin" + RandomData.genAlpha(8);
        final Creds creds = DB_UTILS.getCredsByEmailAndPass(adminUN, "admin").join();
        Assert.assertEquals(creds.getPriv(), "admin");
    }

    @Test
    public void userCanLogin() {
        final String userUN = "user" + RandomData.genAlpha(8);
        final Creds creds = DB_UTILS.getCredsByEmailAndPass(userUN, "user").join();
        Assert.assertEquals(creds.getPriv(), "user");
    }

    @Test
    public void adminPasswordIsChecked() {
        final String adminUN = "admin" + RandomData.genAlpha(8);
        final String adminPW = RandomData.genAlpha(4);
        Assert.assertNull(DB_UTILS.getCredsByEmailAndPass(adminUN, adminPW).join());
    }

    @Test
    public void userPasswordIsChecked() {
        final String userUN = "user" + RandomData.genAlpha(8);
        final String userPW = RandomData.genAlpha(4);
        Assert.assertNull(DB_UTILS.getCredsByEmailAndPass(userUN, userPW).join());
    }

    @Test
    public void testGetTransactions() throws IOException {
        final String id = RandomData.genAlpha(7);
        final Person.Id userId = Person.Id.from(RandomData.genAlpha(8));
        final String groupId = RandomData.genAlpha(17);
        final String category = RandomData.genAlpha(9);
        final String note = RandomData.genAlpha(6);
        final LocalDateTime txDate = LocalDateTime.now();
        final Transaction tx = new Transaction(id, userId, groupId, Type.Shared, txDate, 0.45f, category, note);
        final Transaction tx2 = new Transaction(userId, groupId, Type.Shared);
        Assert.assertEquals(DB_UTILS.getTransactions(userId).join().size(), 0, "Should start w/ no txs.");
        Assert.assertEquals(Boolean.TRUE, DB_UTILS.saveTransaction(tx).join());
        Assert.assertEquals(DB_UTILS.getTransactions(userId).join().size(), 1, "Expected 1 to be added.");
        Assert.assertEquals(Boolean.TRUE, DB_UTILS.saveTransaction(tx).join()); // Verify idempotency, should be 1
        Assert.assertEquals(DB_UTILS.getTransactions(userId).join().size(), 1, "Expected only 1 still.");
        Assert.assertEquals(Boolean.TRUE, DB_UTILS.saveTransaction(tx2).join()); // Now should be 2
        Assert.assertEquals(DB_UTILS.getTransactions(userId).join().size(), 2, "Expected 2 now.");
        final Transaction sameTx = DB_UTILS.getTransaction(userId, id).join().orElse(null);
        Assert.assertEquals(tx, sameTx, "Getting tx should be equal.");
    }

    @Test
    public void testCacheAll() {
        final Person.Id userId = Person.Id.from(RandomData.genAlpha(5));
        final String groupId = RandomData.genAlpha(4);
        final Transaction tx = new Transaction(userId, groupId, Type.Shared);
        final Transaction tx2 = new Transaction(userId, groupId, Type.Shared);
        final List<Transaction> txs = new ArrayList<>();
        txs.add(tx);
        txs.add(tx2);
        final Map<String, Transaction> userTxs = DB_UTILS.getTxCacheForUser(userId).join();
        Assert.assertEquals(userTxs.size(), 0, "Expected cache to start at 0.");
        DB_UTILS.cacheAll(userTxs, txs, Transaction::getTxId);
        Assert.assertEquals(userTxs.size(), 2, "Expected cache to add 2 items!");

        final Map<String, Transaction> verifySave = DB_UTILS.getTxCacheForUser(userId).join();
        Assert.assertEquals(verifySave.size(), 2, "Expected cache to start at 2 this time!");
        DB_UTILS.cacheAll(verifySave, txs, Transaction::getTxId);
        Assert.assertEquals(verifySave.size(), 2, "Expected cache to add 2 items!");
    }

    @Test
    public void transactionsAreReturnedInAscDateOrder() {
        final Person.Id person = Person.Id.from(RandomData.genAlpha(8));
        final Map<String, Transaction> txs = new HashMap<>();
        Stream.generate(() -> createRandomTx(person))
                .limit(20)
                .peek(this::saveTransaction)
                .forEach(tx -> txs.put(tx.getTxId(), tx));
        final List<Transaction> sortedTxs = DB_UTILS.getTransactions(person).join();
        Assert.assertEquals(sortedTxs.size(), 20, "Should have 20 txs.");
        for (int idx = 0; idx < sortedTxs.size() - 1; idx++) {
            final Transaction currTx = sortedTxs.get(idx);
            Assert.assertTrue(currTx.getTxDate().isBefore(sortedTxs.get(idx + 1).getTxDate()), "Not in order.");
            Assert.assertEquals(currTx, txs.get(currTx.getTxId()));
            txs.remove(currTx.getTxId());
        }
        Assert.assertEquals(1, txs.size());
        Assert.assertEquals(sortedTxs.get(sortedTxs.size() - 1), txs.values().iterator().next());
    }

    private void saveTransaction(final Transaction tx) {
        try {
            DB_UTILS.saveTransaction(tx).join();
        } catch (final IOException ex) {
            throw new RuntimeException("failed to save tx", ex);
        }
    }

    private Transaction createRandomTx(final Person.Id personId) {
        final String id = RandomData.genAlpha(7);
        final Person.Id userId = personId == null ? Person.Id.from(RandomData.genAlpha(8)) : personId;
        final String groupId = RandomData.genAlpha(17);
        final Transaction.Type type = RandomData.randomEnum(Transaction.Type.class);
        final LocalDateTime txDate = LocalDateTime.now()
                .plus(RandomData.randomLong(MONTH_IN_MILLIS) - MONTH_IN_MILLIS, ChronoUnit.MILLIS);
        final float amount = RandomData.randomFloat(1_000_000f);
        final String category = RandomData.genAlpha(9);
        final String note = RandomData.genAlpha(6);
        return new Transaction(id, userId, groupId, type, txDate, amount, category, note);
    }
}
