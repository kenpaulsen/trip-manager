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
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
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
        final Persistence persistence = DynamoLocal.persistence();
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
    public void getTripWithNullIdReturnsEmpty() {
        assertEquals(get(dao.getTrip(null)), Optional.empty());
    }

    @Test
    public void allTripsEmptyInitially() {
        assertTrue(get(dao.getRecentTrips(100)).isEmpty());
    }

    @Test
    public void getRecentTripsReturnsSortedByStartDate() throws IOException {
        get(dao.saveTrip(Trip.builder().title("Later").startDate(LocalDateTime.now().plusDays(30)).build()));
        get(dao.saveTrip(Trip.builder().title("Earlier").startDate(LocalDateTime.now().plusDays(5)).build()));
        get(dao.saveTrip(Trip.builder().title("Middle").startDate(LocalDateTime.now().plusDays(15)).build()));
        final List<Trip> trips = get(dao.getRecentTrips(100));
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
        assertEquals(get(dao.getRecentTrips(100)).size(), 1);
    }

    @Test
    public void saveTripAlsoSavesTripEvents() throws IOException {
        final TripEvent te = new TripEvent(UUID.randomUUID().toString(), TripEvent.Type.FLIGHT,
                "Test Flight", "notes", LocalDateTime.now(), null, null, null);
        final Trip trip = Trip.builder().title("With Events").tripEvents(List.of(te)).build();
        get(dao.saveTrip(trip));
        assertEquals(get(tripEventDao.getTripEvent(te.getId())), te);
    }

    @Test
    public void clearCacheWorks() throws IOException {
        get(dao.saveTrip(Trip.builder().title("Cache Test").build()));
        assertEquals(get(dao.getRecentTrips(100)).size(), 1);
        dao.clearCache();
        assertEquals(get(dao.getRecentTrips(100)).size(), 0);
    }

    @Test
    public void activeAndInactiveSplitByEndDate() throws IOException {
        final Trip active = Trip.builder().title("Upcoming")
                .startDate(LocalDateTime.now().plusDays(10)).endDate(LocalDateTime.now().plusDays(20)).build();
        final Trip past = Trip.builder().title("Finished")
                .startDate(LocalDateTime.now().minusDays(30)).endDate(LocalDateTime.now().minusDays(20)).build();
        get(dao.saveTrip(active));
        get(dao.saveTrip(past));
        final LocalDateTime cutoff = LocalDateTime.now();
        final List<Trip> activeTrips = get(dao.getActiveTrips(cutoff));
        assertEquals(activeTrips.size(), 1);
        assertEquals(activeTrips.get(0).getTitle(), "Upcoming");
        final List<Trip> inactiveTrips = get(dao.getInactiveTrips(cutoff, 0));
        assertEquals(inactiveTrips.size(), 1);
        assertEquals(inactiveTrips.get(0).getTitle(), "Finished");
    }

    @Test
    public void inactiveRespectsLimitAndReturnsNewestFirst() throws IOException {
        for (int i = 1; i <= 3; i++) {
            get(dao.saveTrip(Trip.builder().title("Past " + i)
                    .startDate(LocalDateTime.now().minusDays(30L * i))
                    .endDate(LocalDateTime.now().minusDays(30L * i - 5)).build()));
        }
        final List<Trip> inactive = get(dao.getInactiveTrips(LocalDateTime.now(), 2));
        assertEquals(inactive.size(), 2);
        // Newest-ending first: "Past 1" ended most recently.
        assertEquals(inactive.get(0).getTitle(), "Past 1");
        assertEquals(inactive.get(1).getTitle(), "Past 2");
    }

    @Test
    public void tripsForUserByMembership() throws IOException {
        final Person.Id u1 = Person.Id.newInstance();
        final Person.Id u2 = Person.Id.newInstance();
        final Person.Id u3 = Person.Id.newInstance();
        final Trip trip = Trip.builder().title("Group Trip").people(new java.util.ArrayList<>(List.of(u1, u2))).build();
        get(dao.saveTrip(trip));
        assertEquals(get(dao.getTripsForUser(u1)).size(), 1);
        assertEquals(get(dao.getTripsForUser(u2)).size(), 1);
        assertTrue(get(dao.getTripsForUser(u3)).isEmpty());
        assertTrue(get(dao.getTripsForUser(null)).isEmpty());
    }

    @Test
    public void removingPersonUpdatesReverseIndex() throws IOException {
        final Person.Id u1 = Person.Id.newInstance();
        final Person.Id u2 = Person.Id.newInstance();
        final Trip trip = Trip.builder().id("t-reverse").title("Trip")
                .people(new java.util.ArrayList<>(List.of(u1, u2))).build();
        get(dao.saveTrip(trip));
        assertEquals(get(dao.getTripsForUser(u2)).size(), 1);
        // Re-save with u2 removed
        final Trip updated = Trip.builder().id("t-reverse").title("Trip")
                .people(new java.util.ArrayList<>(List.of(u1))).build();
        get(dao.saveTrip(updated));
        assertTrue(get(dao.getTripsForUser(u2)).isEmpty(), "u2 removed from trip should drop from reverse index");
        assertEquals(get(dao.getTripsForUser(u1)).size(), 1, "u1 remains");
    }

    @Test
    public void getTripIsPointReadNotScan() throws IOException {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final Trip trip = Trip.builder().id("tripA").title("Trip A")
                .startDate(LocalDateTime.now().plusDays(5)).build();
        final String tripJson = mapper.writeValueAsString(trip);
        final AtomicInteger getItemCount = new AtomicInteger(0);
        final AtomicInteger scanCount = new AtomicInteger(0);
        final Persistence pointPersistence = new Persistence() {
            @Override
            public GetItemResponse getItem(final Consumer<GetItemRequest.Builder> req) {
                getItemCount.incrementAndGet();
                final GetItemRequest.Builder b = GetItemRequest.builder();
                req.accept(b);
                final GetItemResponse.Builder resp = GetItemResponse.builder();
                if ("tripA".equals(b.build().key().get("id").s())) {
                    resp.item(Map.of("id", toStrAttr("tripA"), "content", toStrAttr(tripJson)));
                }
                return resp.build();
            }

            @Override
            public ScanResponse scan(final Consumer<ScanRequest.Builder> req) {
                scanCount.incrementAndGet();
                return Persistence.super.scan(req);
            }
        };
        final TripDAO pointDao = new TripDAO(mapper, pointPersistence, new TripEventDAO(mapper, pointPersistence));
        final Optional<Trip> found = get(pointDao.getTrip("tripA"));
        assertTrue(found.isPresent());
        assertEquals(found.get().getTitle(), "Trip A");
        assertEquals(getItemCount.get(), 1, "Miss should do exactly one point read");
        assertEquals(scanCount.get(), 0, "getTrip must never scan the table");
        // Cached
        assertTrue(get(pointDao.getTrip("tripA")).isPresent());
        assertEquals(getItemCount.get(), 1, "Cached read should not hit the database");
    }

    @Test
    public void multipleTripsCanBeStored() throws IOException {
        for (int i = 0; i < 5; i++) {
            get(dao.saveTrip(Trip.builder().title("Trip " + i).startDate(LocalDateTime.now().plusDays(i)).build()));
        }
        assertEquals(get(dao.getRecentTrips(100)).size(), 5);
    }

    private <T> T get(final CompletableFuture<T> future) {
        try {
            return future.get(1_000, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException | ExecutionException | TimeoutException ex) {
            throw new RuntimeException(ex);
        }
    }
}
