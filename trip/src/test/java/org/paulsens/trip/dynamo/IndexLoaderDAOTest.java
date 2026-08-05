package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * The index REBUILD loaders: {@code PersonDAO.loadAllSearchTokens} and {@code TripDAO.loadAllEntries}.
 *
 * <p>These are the table scans behind a cold {@code SearchIndex}/{@code TripIndex} -- production's first
 * search or listing after a deploy. Like the {@code ScanCachedDAOTest} cases, they only run when the cache is
 * not the in-memory one, so no ordinary test ever executed them.
 */
public class IndexLoaderDAOTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeClass
    public void init() {
        FakeData.initFakeData();
    }

    private static CacheClient valkeyLike() {
        return Mockito.mock(CacheClient.class, AdditionalAnswers.delegatesTo(new InMemoryCacheClient()));
    }

    @Test
    public void aColdPeopleSearchIsBuiltByScanningTheTable() throws Exception {
        final String last = "Scanlast" + RandomData.genAlpha(8);
        final Persistence persistence = DynamoLocal.persistence();
        final PersonDAO writer = new PersonDAO(mapper, persistence);
        final Person person = new Person();
        person.setFirst("Cold");
        person.setLast(last);
        person.setEmail(last.toLowerCase() + "@example.org");
        Assert.assertTrue(writer.savePerson(person).join());
        // A row the console mangled must be skipped by the scan, not kill the whole index build.
        persistence.putItem(b -> b.tableName("people").item(Map.of(
                "id", AttributeValue.builder().s("bad-" + last).build(),
                "content", AttributeValue.builder().s("{ not json").build())));

        final PersonDAO reader = new PersonDAO(mapper, DynamoLocal.persistence(), valkeyLike());
        final List<Person> found = reader.searchPeople(last.toLowerCase(), 10).join();

        Assert.assertEquals(found.size(), 1, "the cold search must scan-build the index: " + found);
        Assert.assertEquals(found.get(0).getId(), person.getId());
    }

    @Test
    public void aColdTripListingIsBuiltByScanningTheTable() throws Exception {
        final String tripId = "idx-trip-" + RandomData.genAlpha(8);
        final TripEventDAO events = new TripEventDAO(mapper, DynamoLocal.persistence());
        final TripDAO writer = new TripDAO(mapper, DynamoLocal.persistence(), events);
        final Trip trip = Trip.builder().id(tripId).title("Idx")
                .startDate(LocalDateTime.now()).endDate(LocalDateTime.now().plusDays(3))
                .people(List.of(Person.Id.from("idx-member"))).build();
        Assert.assertTrue(writer.saveTrip(trip).join());

        final TripDAO reader = new TripDAO(mapper, DynamoLocal.persistence(), events, valkeyLike());

        Assert.assertTrue(reader.getRecentTrips(1_000).join().stream()
                        .anyMatch(t -> tripId.equals(t.getId())),
                "the cold listing must scan-build the trip index");
    }

    /**
     * {@code InMemoryPersistence.scanAll} special-cases the people table so local mode's fake pilgrims are
     * searchable: without it, a cold search index scan-builds from an empty scan and every local search
     * answers nothing.
     */
    @Test
    public void theFakeStoreServesItsFakePeopleToTheIndexScan() {
        final Persistence fake = FakeData.createFakePersistence();
        final InMemoryCacheClient store = new InMemoryCacheClient();
        final PersonDAO dao = new PersonDAO(mapper, fake,
                Mockito.mock(CacheClient.class, AdditionalAnswers.delegatesTo(store)));
        final List<Person> fakePeople = FakeData.getFakePeople();
        Assert.assertNotNull(fakePeople, "local mode must have seeded people for this to mean anything");
        final Person known = fakePeople.get(0);

        // The search triggers the cold build; the build's scan must have produced the fake people's tokens.
        dao.searchPeople(known.getLast().toLowerCase(), 10).join();

        final List<String> entries = store.getSortedSetByPrefix(
                org.paulsens.trip.cache.CacheKeys.PEOPLE_SEARCH,
                known.getLast().toLowerCase(), 10);
        Assert.assertFalse(entries.isEmpty(),
                "the index scan must serve the fake people, or every local search answers nothing");
    }
}
