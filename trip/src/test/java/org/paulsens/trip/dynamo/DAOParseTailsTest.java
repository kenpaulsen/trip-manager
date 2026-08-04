package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The {@code parseX} failure branch of each remaining DAO: a row the console (or a bad deploy) mangled must
 * read as absent -- skipped from listings, an empty point lookup -- never as a null element or an exception
 * that takes the page down. Each test plants a garbage row directly in the table and reads through the DAO.
 */
public class DAOParseTailsTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Persistence persistence = DynamoLocal.persistence();

    @BeforeClass
    public void init() {
        FakeData.initFakeData();
    }

    private void plant(final String table, final Map<String, AttributeValue> key) {
        final java.util.HashMap<String, AttributeValue> item = new java.util.HashMap<>(key);
        item.put("content", AttributeValue.builder().s("{ not json").build());
        persistence.putItem(b -> b.tableName(table).item(item)).join();
    }

    private static AttributeValue s(final String value) {
        return AttributeValue.builder().s(value).build();
    }

    @Test
    public void aMangledTodoReadsAsAbsent() {
        final String tripId = "parse-todo-" + RandomData.genAlpha(6);
        plant("todo_items", Map.of("tripId", s(tripId), "dataId", s("bad")));

        final TodoDAO dao = new TodoDAO(mapper, persistence);
        Assert.assertTrue(dao.getTodoItems(tripId).join().isEmpty());
        Assert.assertTrue(dao.getTodoItem(tripId, DataId.from("bad")).join().isEmpty());
    }

    @Test
    public void aMangledRegistrationReadsAsAbsent() {
        final String tripId = "parse-reg-" + RandomData.genAlpha(6);
        plant("registrations", Map.of("tripId", s(tripId), "userId", s("bad")));

        Assert.assertTrue(new RegistrationDAO(mapper, persistence).getRegistrations(tripId).join().isEmpty());
    }

    @Test
    public void aMangledTransactionReadsAsAbsent() {
        final Person.Id user = Person.Id.from("parse-tx-" + RandomData.genAlpha(6));
        plant("transactions", Map.of("userId", s(user.getValue()), "txId", s("bad")));

        Assert.assertTrue(new TransactionDAO(mapper, persistence).getTransactions(user).join().isEmpty());
    }

    @Test
    public void aMangledPersonDataValueReadsAsAbsent() {
        final Person.Id user = Person.Id.from("parse-pdv-" + RandomData.genAlpha(6));
        plant("person_data", Map.of("userId", s(user.getValue()), "dataId", s("bad")));

        Assert.assertTrue(new PersonDataValueDAO(mapper, persistence)
                .getPersonDataValues(user).join().isEmpty());
    }

    @Test
    public void aMangledTripEventReadsAsAbsent() {
        final String id = "parse-evt-" + RandomData.genAlpha(6);
        plant("trip_events", Map.of("id", s(id)));

        Assert.assertNull(new TripEventDAO(mapper, persistence).getTripEvent(id).join());
    }

    @Test
    public void aMangledTripReadsAsAbsent() {
        final String id = "parse-trip-" + RandomData.genAlpha(6);
        plant("trips", Map.of("id", s(id)));

        final TripEventDAO events = new TripEventDAO(mapper, persistence);
        Assert.assertTrue(new TripDAO(mapper, persistence, events).getTrip(id).join().isEmpty());
    }

    @Test
    public void aMangledPersonReadsAsAbsent() {
        final String id = "parse-person-" + RandomData.genAlpha(6);
        plant("people", Map.of("id", s(id)));

        Assert.assertTrue(new PersonDAO(mapper, persistence).getPerson(Person.Id.from(id)).join().isEmpty());
    }

    @Test
    public void aMangledConfigRowReadsAsAbsent() {
        final String name = "parse-config-" + RandomData.genAlpha(6);
        plant("config", Map.of("name", s(name)));

        Assert.assertTrue(new ConfigDAO(mapper, persistence).getConfig(name).join().isEmpty());
    }

    @Test
    public void aMangledMediaRowReadsAsAbsent() {
        final String id = "parse-media-" + RandomData.genAlpha(6);
        plant("media", Map.of("id", s(id)));

        Assert.assertTrue(new MediaDAO(mapper, persistence).getMedia(id).join().isEmpty());
    }

    @Test
    public void aMangledPrivilegeReadsAsAbsent() {
        final String name = "parseBadPriv" + RandomData.genAlpha(6);
        plant("privs", Map.of("name", s(name)));

        Assert.assertTrue(new PrivilegesDAO(mapper, persistence).getPrivilege(name).join().isEmpty());
    }
}
