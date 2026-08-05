package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class PersonDAOTest {
    private PersonDAO dao;

    @BeforeMethod
    public void setup() {
        dao = new PersonDAO(new ObjectMapper().findAndRegisterModules(), DynamoLocal.persistence());
    }

    @Test
    public void saveAndRetrievePerson() throws IOException {
        final Person person = Person.builder()
                .id(Person.Id.newInstance())
                .first("Alice")
                .last("Smith")
                .build();
        assertTrue(dao.savePerson(person));
        assertEquals(dao.getPerson(person.getId()), Optional.of(person));
    }

    @Test
    public void getPersonWithNullIdReturnsEmpty() {
        assertEquals(dao.getPerson(null), Optional.empty());
    }

    @Test
    public void searchFindsPeopleByNamePrefix() throws IOException {
        final Person zach = Person.builder().first("Zach").last("Zeta").build();
        final Person alice = Person.builder().first("Alice").last("Alpha").build();
        final Person middle = Person.builder().first("Mike").last("Alphonse").build();
        dao.savePerson(zach);
        dao.savePerson(alice);
        dao.savePerson(middle);
        final List<Person> found = dao.searchPeople("alph", 10);
        assertEquals(found.size(), 2);
        assertTrue(found.contains(alice));
        assertTrue(found.contains(middle));
        assertEquals(dao.searchPeople("zeta", 10), List.of(zach));
    }

    @Test
    public void searchMatchesEmailAndCell() throws IOException {
        final Person person = Person.builder().first("Pat").last("Smith")
                .email("Pat.Smith@Example.com").cell("555-1234").build();
        dao.savePerson(person);
        assertEquals(dao.searchPeople("pat.smith@", 10), List.of(person));
        assertEquals(dao.searchPeople("555", 10), List.of(person));
    }

    @Test
    public void multiWordSearchNarrowsResults() throws IOException {
        final Person a = Person.builder().first("Ken").last("Paulsen").build();
        final Person b = Person.builder().first("Kevin").last("Paulsen").build();
        dao.savePerson(a);
        dao.savePerson(b);
        assertEquals(dao.searchPeople("paulsen", 10).size(), 2);
        assertEquals(dao.searchPeople("paulsen kevin", 10), List.of(b));
    }

    @Test
    public void emptyOrBlankSearchReturnsNothing() {
        assertTrue(dao.searchPeople(null, 10).isEmpty());
        assertTrue(dao.searchPeople("   ", 10).isEmpty());
    }

    @Test
    public void renamedPersonIsFoundUnderNewNameOnly() throws IOException {
        final Person person = Person.builder().first("Old").last("Name").build();
        dao.savePerson(person);
        assertEquals(dao.searchPeople("name", 10).size(), 1);
        final Person renamed = Person.builder().id(person.getId()).first("Old").last("Renamed").build();
        dao.savePerson(renamed);
        // Old token gone ("name" is not a prefix of "renamed"), new token present
        assertTrue(dao.searchPeople("name", 10).isEmpty());
        assertEquals(dao.searchPeople("renamed", 10), List.of(renamed));
    }

    @Test
    public void getPersonByEmailFindsMatch() throws IOException {
        final String email = RandomData.genAlpha(8) + "@test.com";
        final Person person = Person.builder().first("Test").last("User").email(email).build();
        dao.savePerson(person);
        final Person found = dao.getPersonByEmail(email);
        assertEquals(found, person);
    }

    @Test
    public void getPersonByEmailIsCaseInsensitive() throws IOException {
        final String email = "TestUser@Example.COM";
        final Person person = Person.builder().first("Test").last("User").email(email).build();
        dao.savePerson(person);
        final Person found = dao.getPersonByEmail("testuser@example.com");
        assertEquals(found, person);
    }

    @Test
    public void getPersonByEmailReturnsNullWhenNotFound() throws IOException {
        final Person person = Person.builder().first("A").last("B").email("exists@test.com").build();
        dao.savePerson(person);
        assertNull(dao.getPersonByEmail("nope@test.com"));
    }

    @Test
    public void changedEmailNoLongerResolvesOldAddress() throws IOException {
        final Person person = Person.builder().first("Move").last("Email").email("old@test.com").build();
        dao.savePerson(person);
        assertNotNull(dao.getPersonByEmail("old@test.com"));
        final Person updated = Person.builder().id(person.getId()).first("Move").last("Email")
                .email("new@test.com").build();
        dao.savePerson(updated);
        assertNull(dao.getPersonByEmail("old@test.com"));
        assertEquals(dao.getPersonByEmail("new@test.com"), updated);
    }

    @Test
    public void deletedPersonIsRemovedEverywhere() throws IOException {
        final Person person = Person.builder().first("Del").last("Eted").email("del@test.com").build();
        dao.savePerson(person);
        assertTrue(dao.getPerson(person.getId()).isPresent());
        assertEquals(dao.searchPeople("eted", 10).size(), 1);
        person.delete();
        dao.savePerson(person);
        assertEquals(dao.getPerson(person.getId()), Optional.empty());
        assertTrue(dao.searchPeople("eted", 10).isEmpty());
        assertNull(dao.getPersonByEmail("del@test.com"));
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
        final Person person = Person.builder().first("Clear").last("Me").build();
        dao.savePerson(person);
        assertTrue(dao.getPerson(person.getId()).isPresent());

        dao.clearCache();

        assertTrue(dao.getPerson(person.getId()).isPresent(),
                "the store still has it after the cached copy is dropped");
    }

    @Test
    public void saveMultiplePeopleAndRetrieveIndividually() throws IOException {
        final Person p1 = Person.builder().first("One").last("Person").build();
        final Person p2 = Person.builder().first("Two").last("Person").build();
        dao.savePerson(p1);
        dao.savePerson(p2);
        assertEquals(dao.getPerson(p1.getId()), Optional.of(p1));
        assertEquals(dao.getPerson(p2.getId()), Optional.of(p2));
    }

    @Test
    public void getPersonMissDoesPointReadNotScan() throws IOException {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final Person alice = Person.builder().id(Person.Id.from("alice")).first("Alice").last("Alpha").build();
        final String aliceJson = mapper.writeValueAsString(alice);
        final AtomicInteger getCount = new AtomicInteger(0);
        final AtomicInteger scanCount = new AtomicInteger(0);
        final Persistence pointPersistence = new Persistence() {
            @Override
            public GetItemResponse getItem(final Consumer<GetItemRequest.Builder> req) {
                getCount.incrementAndGet();
                final GetItemRequest.Builder builder = GetItemRequest.builder();
                req.accept(builder);
                final String id = builder.build().key().get("id").s();
                final GetItemResponse.Builder resp = GetItemResponse.builder();
                if ("alice".equals(id)) {
                    resp.item(Map.of("id", toStrAttr(id), "content", toStrAttr(aliceJson)));
                }
                return resp.build();
            }

            @Override
            public software.amazon.awssdk.services.dynamodb.model.ScanResponse scan(
                    final Consumer<software.amazon.awssdk.services.dynamodb.model.ScanRequest.Builder> req) {
                scanCount.incrementAndGet();
                return Persistence.super.scan(req);
            }
        };
        final PersonDAO pointDao = new PersonDAO(mapper, pointPersistence);
        final Optional<Person> found = pointDao.getPerson(Person.Id.from("alice"));
        assertTrue(found.isPresent());
        assertEquals(found.get().getFirst(), "Alice");
        assertEquals(getCount.get(), 1, "Miss should do exactly one point read");
        assertEquals(scanCount.get(), 0, "getPerson must never scan the table");
        // Second read served from cache
        assertTrue(pointDao.getPerson(Person.Id.from("alice")).isPresent());
        assertEquals(getCount.get(), 1, "Cached read should not hit the database");
    }

    @Test
    public void getPersonByEmailUsesGsiOnCacheMissThenCaches() throws IOException {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final Person bob = Person.builder().id(Person.Id.from("bob")).first("Bob").last("Beta")
                .email("bob@test.com").build();
        final String bobJson = mapper.writeValueAsString(bob);
        final AtomicInteger queryCount = new AtomicInteger(0);
        final Persistence gsiPersistence = new Persistence() {
            @Override
            public QueryResponse query(final Consumer<QueryRequest.Builder> req) {
                queryCount.incrementAndGet();
                final QueryRequest.Builder builder = QueryRequest.builder();
                req.accept(builder);
                final QueryRequest built = builder.build();
                assertEquals(built.indexName(), "email-index");
                final AttributeValue emailArg = built.expressionAttributeValues().get(":e");
                final QueryResponse.Builder resp = QueryResponse.builder();
                if (emailArg != null && "bob@test.com".equals(emailArg.s())) {
                    resp.items(List.of(Map.of("id", toStrAttr("bob"), "content", toStrAttr(bobJson))));
                } else {
                    resp.items(List.of());
                }
                return resp.build();
            }
        };
        final PersonDAO gsiDao = new PersonDAO(mapper, gsiPersistence);
        assertEquals(gsiDao.getPersonByEmail("Bob@Test.com"), bob);
        assertEquals(queryCount.get(), 1, "Cache miss should query the email GSI");
        assertEquals(gsiDao.getPersonByEmail("bob@test.com"), bob);
        assertEquals(queryCount.get(), 1, "Second lookup should be served by the cached email mapping");
    }
    }

