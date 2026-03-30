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
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class PersonDAOTest {
    private PersonDAO dao;

    @BeforeMethod
    public void setup() {
        dao = new PersonDAO(new ObjectMapper().findAndRegisterModules(), FakeData.createFakePersistence());
    }

    @Test
    public void saveAndRetrievePerson() throws IOException {
        final Person person = Person.builder()
                .id(Person.Id.newInstance())
                .first("Alice")
                .last("Smith")
                .build();
        assertTrue(get(dao.savePerson(person)));
        assertEquals(get(dao.getPerson(person.getId())), Optional.of(person));
    }

    @Test
    public void getPersonWithNullIdReturnsEmpty() {
        assertEquals(get(dao.getPerson(null)), Optional.empty());
    }

    @Test
    public void getPeopleReturnsEmptyListInitially() {
        final List<Person> people = get(dao.getPeople());
        assertTrue(people.isEmpty());
    }

    @Test
    public void getPeopleReturnsSortedList() throws IOException {
        final Person zach = Person.builder().first("Zach").last("Zeta").build();
        final Person alice = Person.builder().first("Alice").last("Alpha").build();
        final Person middle = Person.builder().first("Mike").last("Middle").build();
        get(dao.savePerson(zach));
        get(dao.savePerson(alice));
        get(dao.savePerson(middle));
        final List<Person> people = get(dao.getPeople());
        assertEquals(people.size(), 3);
        assertEquals(people.get(0), alice);
        assertEquals(people.get(1), middle);
        assertEquals(people.get(2), zach);
    }

    @Test
    public void getPeopleServesFromCache() throws IOException {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final Person person = Person.builder().first("Cached").last("Person").build();
        final AtomicInteger scanCount = new AtomicInteger(0);
        final Persistence countingPersistence = new Persistence() {
            @Override
            public CompletableFuture<ScanResponse> scan(Consumer<ScanRequest.Builder> scanRequest) {
                scanCount.incrementAndGet();
                try {
                    return CompletableFuture.completedFuture(ScanResponse.builder().items(List.of(
                            Map.of("id", toStrAttr(person.getId().getValue()),
                                    "content", toStrAttr(mapper.writeValueAsString(person)))
                    )).build());
                } catch (final IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        };
        final PersonDAO countingDao = new PersonDAO(mapper, countingPersistence);
        // First call triggers a scan
        final List<Person> first = get(countingDao.getPeople());
        assertEquals(first.size(), 1);
        assertEquals(scanCount.get(), 1, "First call should scan");
        // Second call should serve from cache — no additional scan
        final List<Person> second = get(countingDao.getPeople());
        assertEquals(second.size(), 1);
        assertEquals(scanCount.get(), 1, "Second call should use cache, not scan again");
    }

    @Test
    public void getPersonByEmailFindsMatch() throws IOException {
        final String email = RandomData.genAlpha(8) + "@test.com";
        final Person person = Person.builder().first("Test").last("User").email(email).build();
        get(dao.savePerson(person));
        final Person found = get(dao.getPersonByEmail(email));
        assertEquals(found, person);
    }

    @Test
    public void getPersonByEmailIsCaseInsensitive() throws IOException {
        final String email = "TestUser@Example.COM";
        final Person person = Person.builder().first("Test").last("User").email(email).build();
        get(dao.savePerson(person));
        final Person found = get(dao.getPersonByEmail("testuser@example.com"));
        assertEquals(found, person);
    }

    @Test
    public void getPersonByEmailReturnsNullWhenNotFound() throws IOException {
        final Person person = Person.builder().first("A").last("B").email("exists@test.com").build();
        get(dao.savePerson(person));
        assertNull(get(dao.getPersonByEmail("nope@test.com")));
    }

    @Test
    public void deletedPersonIsFilteredFromGetPeople() throws IOException {
        final Person person = Person.builder().first("Del").last("Eted").build();
        get(dao.savePerson(person));
        assertEquals(get(dao.getPeople()).size(), 1);
        person.delete();
        get(dao.savePerson(person));
        // After clearing cache, deleted person should not appear
        dao.clearCache();
        assertEquals(get(dao.getPeople()).size(), 0);
    }

    @Test
    public void deletedPersonIsRemovedFromCache() throws IOException {
        final Person person = Person.builder().first("Del").last("Eted").build();
        get(dao.savePerson(person));
        assertTrue(get(dao.getPerson(person.getId())).isPresent());
        person.delete();
        get(dao.savePerson(person));
        // Should be gone from cache immediately
        assertEquals(get(dao.getPerson(person.getId())), Optional.empty());
    }

    @Test
    public void clearCacheWorks() throws IOException {
        final Person person = Person.builder().first("Clear").last("Me").build();
        get(dao.savePerson(person));
        assertEquals(get(dao.getPeople()).size(), 1);
        dao.clearCache();
        // After clear, getPeople scans again - fake persistence returns empty
        assertEquals(get(dao.getPeople()).size(), 0);
    }

    @Test
    public void saveMultiplePeopleAndRetrieveIndividually() throws IOException {
        final Person p1 = Person.builder().first("One").last("Person").build();
        final Person p2 = Person.builder().first("Two").last("Person").build();
        get(dao.savePerson(p1));
        get(dao.savePerson(p2));
        assertEquals(get(dao.getPerson(p1.getId())), Optional.of(p1));
        assertEquals(get(dao.getPerson(p2.getId())), Optional.of(p2));
    }

    @Test
    public void getPersonLoadsPeopleCacheOnMiss() throws IOException {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final Person alice = Person.builder().id(Person.Id.from("alice")).first("Alice").last("Alpha").build();
        final Person bob = Person.builder().id(Person.Id.from("bob")).first("Bob").last("Beta").build();
        // Build a persistence whose scan returns both people
        final AtomicInteger scanCount = new AtomicInteger(0);
        final Persistence scanPersistence = new Persistence() {
            @Override
            public CompletableFuture<ScanResponse> scan(Consumer<ScanRequest.Builder> scanRequest) {
                scanCount.incrementAndGet();
                try {
                    return CompletableFuture.completedFuture(ScanResponse.builder().items(
                            List.of(
                                    Map.of("id", toStrAttr(alice.getId().getValue()),
                                            "content", toStrAttr(mapper.writeValueAsString(alice))),
                                    Map.of("id", toStrAttr(bob.getId().getValue()),
                                            "content", toStrAttr(mapper.writeValueAsString(bob)))
                            )).build());
                } catch (final IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        };
        final PersonDAO scanDao = new PersonDAO(mapper, scanPersistence);
        // Cache is empty. Request Alice — this should trigger getPeople(), loading both Alice and Bob.
        final Optional<Person> foundAlice = get(scanDao.getPerson(Person.Id.from("alice")));
        assertTrue(foundAlice.isPresent());
        assertEquals(foundAlice.get().getFirst(), "Alice");
        assertEquals(scanCount.get(), 1, "Should have scanned exactly once");
        // Now Bob should also be in cache — no additional scan needed
        final Optional<Person> foundBob = get(scanDao.getPerson(Person.Id.from("bob")));
        assertTrue(foundBob.isPresent());
        assertEquals(foundBob.get().getFirst(), "Bob");
        assertEquals(scanCount.get(), 1, "Should still be 1 — Bob was loaded by the first scan");
    }

    private <T> T get(final CompletableFuture<T> future) {
        try {
            return future.get(1_000, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException | ExecutionException | TimeoutException ex) {
            throw new RuntimeException(ex);
        }
    }
}
