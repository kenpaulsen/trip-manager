package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class TripDAOTest {
    private TripDAO dao;
    private TripEventDAO tripEventDao;

    @BeforeMethod
    public void setup() {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final Persistence persistence = FakeData.createFakePersistence();
        tripEventDao = new TripEventDAO(mapper, persistence);
        dao = new TripDAO(mapper, persistence, tripEventDao);
    }

    @Test
    public void saveAndRetrieveTrip() throws IOException {
        final Trip trip = Trip.builder()
                .title("Test Trip")
                .description("A test trip")
                .startDate(LocalDateTime.now().plusDays(10))
                .endDate(LocalDateTime.now().plusDays(20))
                .build();
        assertTrue(get(dao.saveTrip(trip)));
        assertEquals(get(dao.getTrip(trip.getId())), Optional.of(trip));
    }

    @Test
    public void getTripsReturnsEmptyListInitially() {
        assertTrue(get(dao.getTrips()).isEmpty());
    }

    @Test
    public void getTripWithNullIdReturnsEmpty() {
        assertEquals(get(dao.getTrip(null)), Optional.empty());
    }

    @Test
    public void getTripsReturnsSortedByStartDate() throws IOException {
        final Trip later = Trip.builder()
                .title("Later")
                .startDate(LocalDateTime.now().plusDays(30))
                .build();
        final Trip earlier = Trip.builder()
                .title("Earlier")
                .startDate(LocalDateTime.now().plusDays(5))
                .build();
        final Trip middle = Trip.builder()
                .title("Middle")
                .startDate(LocalDateTime.now().plusDays(15))
                .build();
        get(dao.saveTrip(later));
        get(dao.saveTrip(earlier));
        get(dao.saveTrip(middle));
        final List<Trip> trips = get(dao.getTrips());
        assertEquals(trips.size(), 3);
        assertEquals(trips.get(0).getTitle(), "Earlier");
        assertEquals(trips.get(1).getTitle(), "Middle");
        assertEquals(trips.get(2).getTitle(), "Later");
    }

    @Test
    public void saveTripIsIdempotent() throws IOException {
        final Trip trip = Trip.builder().title("Idempotent").build();
        get(dao.saveTrip(trip));
        get(dao.saveTrip(trip));
        assertEquals(get(dao.getTrips()).size(), 1);
    }

    @Test
    public void saveTripAlsoSavesTripEvents() throws IOException {
        final TripEvent te = new TripEvent(UUID.randomUUID().toString(), TripEvent.Type.FLIGHT,
                "Test Flight", "notes", LocalDateTime.now(), null, null, null);
        final Trip trip = Trip.builder()
                .title("With Events")
                .tripEvents(List.of(te))
                .build();
        get(dao.saveTrip(trip));
        // The trip event should be saved in the TripEventDAO
        final TripEvent retrieved = get(tripEventDao.getTripEvent(te.getId()));
        assertEquals(retrieved, te);
    }

    @Test
    public void clearCacheWorks() throws IOException {
        final Trip trip = Trip.builder().title("Cache Test").build();
        get(dao.saveTrip(trip));
        assertEquals(get(dao.getTrips()).size(), 1);
        dao.clearCache();
        // After clear, scan returns empty (fake persistence)
        assertEquals(get(dao.getTrips()).size(), 0);
    }

    @Test
    public void getTripServesFromCache() throws IOException {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final Trip trip = Trip.builder().title("Cached Trip").startDate(LocalDateTime.now().plusDays(10)).build();
        final AtomicInteger scanCount = new AtomicInteger(0);
        final Persistence countingPersistence = new Persistence() {
            @Override
            public CompletableFuture<ScanResponse> scan(Consumer<ScanRequest.Builder> scanRequest) {
                scanCount.incrementAndGet();
                try {
                    return CompletableFuture.completedFuture(ScanResponse.builder().items(List.of(
                            Map.of("id", toStrAttr(trip.getId()),
                                    "content", toStrAttr(mapper.writeValueAsString(trip)))
                    )).build());
                } catch (final IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        };
        final TripDAO countingDao = new TripDAO(mapper, countingPersistence,
                new TripEventDAO(mapper, countingPersistence));
        // First call triggers a scan
        final List<Trip> trips = get(countingDao.getTrips());
        assertEquals(trips.size(), 1);
        assertEquals(scanCount.get(), 1, "First call should scan");
        // Second call should serve from cache
        final Optional<Trip> cached = get(countingDao.getTrip(trip.getId()));
        assertTrue(cached.isPresent());
        assertEquals(scanCount.get(), 1, "Second call should use cache, not scan again");
    }

    @Test
    public void getTripLoadsAllTripsOnCacheMiss() throws IOException {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final Trip tripA = Trip.builder().id("tripA").title("Trip A").startDate(LocalDateTime.now().plusDays(5)).build();
        final Trip tripB = Trip.builder().id("tripB").title("Trip B").startDate(LocalDateTime.now().plusDays(10)).build();
        final AtomicInteger scanCount = new AtomicInteger(0);
        final Persistence countingPersistence = new Persistence() {
            @Override
            public CompletableFuture<ScanResponse> scan(Consumer<ScanRequest.Builder> scanRequest) {
                scanCount.incrementAndGet();
                try {
                    return CompletableFuture.completedFuture(ScanResponse.builder().items(List.of(
                            Map.of("id", toStrAttr("tripA"),
                                    "content", toStrAttr(mapper.writeValueAsString(tripA))),
                            Map.of("id", toStrAttr("tripB"),
                                    "content", toStrAttr(mapper.writeValueAsString(tripB)))
                    )).build());
                } catch (final IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        };
        final TripDAO countingDao = new TripDAO(mapper, countingPersistence,
                new TripEventDAO(mapper, countingPersistence));
        // Cache is empty. Request tripA — this should trigger getTrips(), loading both trips.
        final Optional<Trip> foundA = get(countingDao.getTrip("tripA"));
        assertTrue(foundA.isPresent());
        assertEquals(foundA.get().getTitle(), "Trip A");
        assertEquals(scanCount.get(), 1, "Should have scanned exactly once");
        // tripB should also be in cache — no additional scan needed
        final Optional<Trip> foundB = get(countingDao.getTrip("tripB"));
        assertTrue(foundB.isPresent());
        assertEquals(foundB.get().getTitle(), "Trip B");
        assertEquals(scanCount.get(), 1, "Should still be 1 — tripB was loaded by the first scan");
    }

    @Test
    public void multipleTripsCanBeStored() throws IOException {
        for (int i = 0; i < 5; i++) {
            final Trip trip = Trip.builder()
                    .title("Trip " + i)
                    .startDate(LocalDateTime.now().plusDays(i))
                    .build();
            get(dao.saveTrip(trip));
        }
        assertEquals(get(dao.getTrips()).size(), 5);
    }

    private <T> T get(final CompletableFuture<T> future) {
        try {
            return future.get(1_000, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException | ExecutionException | TimeoutException ex) {
            throw new RuntimeException(ex);
        }
    }
}
