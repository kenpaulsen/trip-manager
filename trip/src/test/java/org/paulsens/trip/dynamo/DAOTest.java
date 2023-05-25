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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.paulsens.trip.model.Address;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Passport;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Person.Sex;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.TodoItem;
import org.paulsens.trip.model.Transaction;
import org.paulsens.trip.model.Transaction.Type;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class DAOTest {
    private static final DAO DB_UTILS = DAO.getInstance();
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
        final Person person = new Person(id, null, first, null, last, null,
                null, null, null, null, null, null, null, null, null, null);
        assertTrue(DB_UTILS.savePerson(person).join());
        final Person samePerson = DB_UTILS.getPerson(id).join().orElse(null);
        assertEquals(samePerson, person);
    }

    @Test
    public void testGetPeople() throws IOException {
        final Person person1 = new Person(Person.Id.from("1"), "nick", "n1", "middle", "l1", Sex.Male, LocalDate.now(),
                "cell", "email", "tsa", new Address(), new Passport(), "notes", null, null, null);
        final Person person2 = new Person(Person.Id.from("2"), null, "n2", null, "l2", null, null,
                null, null, null, null, null, null, null, null, null);
        final Person person3 = new Person(Person.Id.from("3"), "n3", "n3", null, "l3", null, null,
                null, null, null, null, null, null, null, null, null);
        assertTrue(DB_UTILS.savePerson(person2).join());
        assertTrue(DB_UTILS.savePerson(person1).join());
        assertTrue(DB_UTILS.savePerson(person3).join());
        final List<Person> people = DB_UTILS.getPeople().join();
        assertEquals(people.size(), 3);
        final Person person = people.stream().filter(p -> Person.Id.from("1").equals(p.getId())).findAny().orElse(null);
        assertEquals(person, person1);
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

        assertEquals(DB_UTILS.getTrips().join().size(), 0, "Should start w/ no trips.");
        assertTrue(DB_UTILS.saveTrip(trip).join());
        assertEquals(DB_UTILS.getTrips().join().size(), 1, "Expected 1 to be added.");
        assertTrue(DB_UTILS.saveTrip(trip).join()); // Verify idempotency, should still be 1
        assertEquals(DB_UTILS.getTrips().join().size(), 1, "Expected only 1 still.");
        trip.setId(RandomData.genAlpha(10));
        assertTrue(DB_UTILS.saveTrip(trip).join()); // Verify idempotency, should still be 1
        assertEquals(DB_UTILS.getTrips().join().size(), 2, "Expected 2 now.");
        final Trip sameTrip = DB_UTILS.getTrip(id).join().orElse(null);
        assertEquals(sameTrip, trip, "Getting trip should be equal.");
    }

    @Test
    public void canSaveAndRetrieveTodo() throws IOException {
        final TodoItem todo = TodoItem.builder()
                .tripId(RandomData.genAlpha(17))
                .dataId(DataId.newInstance())
                .description(RandomData.genAlpha(19))
                .build();
        assertTrue(DB_UTILS.saveTodo(todo).join());
        final TodoItem restoredItem = DB_UTILS.getTodoItem(todo.getTripId(), todo.getDataId()).join().orElse(null);
        assertEquals(restoredItem, todo);
    }

    @Test
    public void canRetreiveMultipleTodos() {
        final int todoInsertSize = 25;
        final String tripId = RandomData.genAlpha(13);
        final List<TodoItem> goodValues = new ArrayList<>();
        Stream.generate(() -> TodoItem.builder()
                        .tripId(RandomData.genAlpha(22))
                        .dataId(DataId.newInstance())
                        .description(RandomData.genAlpha(19))
                        .moreDetails(RandomData.genAlpha(59))
                        .created(LocalDateTime.now())
                        .build())
                .limit(3)
                .peek(this::saveTodo)
                .forEach(todo -> {});
        Stream.generate(() -> TodoItem.builder()
                        .tripId(tripId)
                        .dataId(DataId.newInstance())
                        .description(RandomData.genAlpha(12))
                        .moreDetails(RandomData.genAlpha(29))
                        .build())
                .limit(todoInsertSize)
                .peek(this::saveTodo)
                .forEach(goodValues::add);
        assertEquals(goodValues.size(), todoInsertSize);
        final List<TodoItem> result = DB_UTILS.getTodoItems(tripId).join();
        assertEquals(goodValues.size(), result.size());
        for (int idx = 0; idx < todoInsertSize; idx++) {
            assertTrue(goodValues.contains(result.get(idx)));
        }
    }

    @Test
    public void canSaveAndRetrievePersonDataValue() throws IOException {
        final PersonDataValue pdv = PersonDataValue.builder()
                .dataId(DataId.newInstance())
                .userId(Person.Id.newInstance())
                .type(RandomData.genAlpha(13))
                .content(Map.of(RandomData.genAlpha(3), RandomData.genAlpha(33)))
                .build();
        assertEquals(DB_UTILS.getPersonDataValue(pdv.getUserId(), pdv.getDataId()).join(), Optional.empty());
        DB_UTILS.savePersonDataValue(pdv);
        final PersonDataValue restoredPDV = DB_UTILS.getPersonDataValue(pdv.getUserId(), pdv.getDataId())
                .join().orElse(null);
        assertEquals(restoredPDV, pdv);
    }

    @Test
    public void canRetreiveMultiplePersonDataValues() {
        final int pdvInsertSize = 13;
        final Person.Id pid = Person.Id.newInstance();
        final List<PersonDataValue> goodValues = new ArrayList<>();
        Stream.generate(() -> PersonDataValue.builder()
                        .dataId(DataId.newInstance())
                        .userId(Person.Id.newInstance())
                        .type(RandomData.genAlpha(3))
                        .content(RandomData.genAlpha(29))
                        .build())
                .limit(3)
                .peek(this::savePersonDataValue)
                .forEach(pdv -> {});
        Stream.generate(() -> PersonDataValue.builder()
                        .dataId(DataId.newInstance())
                        .userId(pid)
                        .type(RandomData.genAlpha(12))
                        .content(RandomData.genAlpha(29))
                        .build())
                .limit(pdvInsertSize)
                .peek(this::savePersonDataValue)
                .forEach(goodValues::add);
        assertEquals(pdvInsertSize, goodValues.size());
        final Map<DataId, PersonDataValue> result = DB_UTILS.getPersonDataValues(pid).join();
        assertEquals(goodValues.size(), result.size());
        for (final PersonDataValue good : goodValues) {
            assertTrue(result.containsKey(good.getDataId()));
        }
    }

    @Test
    public void nullEmailOrPassReturnsNothing() {
        assertNull(DB_UTILS.getCredsByEmailAndPass(null, RandomData.genAlpha(5)).join());
        assertNull(DB_UTILS.getCredsByEmailAndPass(RandomData.genAlpha(5), null).join());
        assertNull(DB_UTILS.getCredsByEmailAndPass(null, null).join());
        assertNull(DB_UTILS.getCredsByEmailAndPass(RandomData.genAlpha(5), RandomData.genAlpha(4)).join());
    }

    @Test
    public void adminCanLogin() {
        final String adminUN = "admin" + RandomData.genAlpha(8);
        final Creds creds = DB_UTILS.getCredsByEmailAndPass(adminUN, "admin").join();
        assertEquals(creds.getPriv(), "admin");
    }

    @Test
    public void userCanLogin() {
        final String userUN = "user" + RandomData.genAlpha(8);
        final Creds creds = DB_UTILS.getCredsByEmailAndPass(userUN, "user").join();
        assertEquals(creds.getPriv(), "user");
    }

    @Test
    public void adminPasswordIsChecked() {
        final String adminUN = "admin" + RandomData.genAlpha(8);
        final String adminPW = RandomData.genAlpha(4);
        assertNull(DB_UTILS.getCredsByEmailAndPass(adminUN, adminPW).join());
    }

    @Test
    public void userPasswordIsChecked() {
        final String userUN = "user" + RandomData.genAlpha(8);
        final String userPW = RandomData.genAlpha(4);
        assertNull(DB_UTILS.getCredsByEmailAndPass(userUN, userPW).join());
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
        assertEquals(DB_UTILS.getTransactions(userId).join().size(), 0, "Should start w/ no txs.");
        assertTrue(DB_UTILS.saveTransaction(tx).join());
        assertEquals(DB_UTILS.getTransactions(userId).join().size(), 1, "Expected 1 to be added.");
        assertTrue(DB_UTILS.saveTransaction(tx).join()); // Verify idempotency, should be 1
        assertEquals(DB_UTILS.getTransactions(userId).join().size(), 1, "Expected only 1 still.");
        assertTrue(DB_UTILS.saveTransaction(tx2).join()); // Now should be 2
        assertEquals(DB_UTILS.getTransactions(userId).join().size(), 2, "Expected 2 now.");
        final Transaction sameTx = DB_UTILS.getTransaction(userId, id).join().orElse(null);
        assertEquals(sameTx, tx, "Getting tx should be equal.");
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
        assertEquals(sortedTxs.size(), 20, "Should have 20 txs.");
        for (int idx = 0; idx < sortedTxs.size() - 1; idx++) {
            final Transaction currTx = sortedTxs.get(idx);
            assertTrue(currTx.getTxDate().isBefore(sortedTxs.get(idx + 1).getTxDate()), "Not in order.");
            assertEquals(currTx, txs.get(currTx.getTxId()));
            txs.remove(currTx.getTxId());
        }
        assertEquals(txs.size(), 1);
        assertEquals(txs.values().iterator().next(), sortedTxs.get(sortedTxs.size() - 1));
    }

    private void saveTodo(final TodoItem todo) {
        try {
            DB_UTILS.saveTodo(todo).join();
        } catch (final IOException ex) {
            throw new RuntimeException("failed to save TodoItem: " + todo, ex);
        }
    }

    private void savePersonDataValue(final PersonDataValue pdv) {
        try {
            DB_UTILS.savePersonDataValue(pdv).join();
        } catch (final IOException ex) {
            throw new RuntimeException("failed to save PersonDataValue: " + pdv, ex);
        }
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
