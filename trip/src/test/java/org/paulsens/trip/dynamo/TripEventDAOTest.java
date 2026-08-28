package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class TripEventDAOTest {
    private TripEventDAO dao;

    @BeforeMethod
    public void setup() {
        dao = new TripEventDAO(new ObjectMapper().findAndRegisterModules(), DynamoLocal.persistence());
    }

    @Test
    public void saveAndRetrieveTripEvent() {
        final TripEvent te = new TripEvent(UUID.randomUUID().toString(), TripEvent.Type.FLIGHT,
                "PDX -> JFK", "Red eye flight", LocalDateTime.now().plusDays(10), LocalDateTime.now().plusDays(11),
                null, null);
        assertTrue(dao.saveTripEvent(te));
        final TripEvent retrieved = dao.getTripEvent(te.getId());
        assertEquals(retrieved, te);
    }

    @Test
    public void getTripEventReturnsNullForUnknownId() {
        // Fake persistence returns null item for getItem on non-pass tables
        final TripEvent result = dao.getTripEvent("nonexistent-id");
        assertNull(result);
    }

    @Test
    public void getTripEventServesFromCache() {
        final AtomicInteger getItemCount = new AtomicInteger(0);
        final Persistence countingPersistence = new Persistence() {
            @Override
            public GetItemResponse getItem(Consumer<GetItemRequest.Builder> getItemRequest) {
                getItemCount.incrementAndGet();
                return Persistence.super.getItem(getItemRequest);
            }
        };
        final TripEventDAO countingDao = new TripEventDAO(
                new ObjectMapper().findAndRegisterModules(), countingPersistence);
        final TripEvent te = new TripEvent(UUID.randomUUID().toString(), TripEvent.Type.LODGING,
                "Hotel", "Nice place", LocalDateTime.now(), LocalDateTime.now().plusDays(3), null, null);
        countingDao.saveTripEvent(te);
        assertEquals(getItemCount.get(), 0, "Save should not call getItem");
        // Retrieve should come from cache, not from persistence.getItem
        final TripEvent cached = countingDao.getTripEvent(te.getId());
        assertEquals(cached, te);
        assertEquals(getItemCount.get(), 0, "getTripEvent should serve from cache, not call getItem");
    }

    @Test
    public void saveAllTripEventsForTrip() {
        final TripEvent te1 = new TripEvent(UUID.randomUUID().toString(), TripEvent.Type.FLIGHT,
                "Flight 1", "notes", LocalDateTime.now(), null, null, null);
        final TripEvent te2 = new TripEvent(UUID.randomUUID().toString(), TripEvent.Type.LODGING,
                "Hotel", "notes", LocalDateTime.now().plusDays(1), null, null, null);
        final Trip trip = Trip.builder()
                .title("Test Trip")
                .tripEvents(List.of(te1, te2))
                .build();
        assertTrue(dao.saveAllTripEvents(trip));
        assertEquals(dao.getTripEvent(te1.getId()), te1);
        assertEquals(dao.getTripEvent(te2.getId()), te2);
    }

    @Test
    public void saveAllTripEventsWithEmptyList() {
        final Trip trip = Trip.builder()
                .title("Empty Trip")
                .tripEvents(Collections.emptyList())
                .build();
        assertTrue(dao.saveAllTripEvents(trip));
    }

    /**
     * Clearing the cache must not lose data.
     *
     * <p>This asserted the OPPOSITE until the DAO tests moved onto a real engine: that the row was GONE after a
     * clear. That was true only because the fake persistence stored nothing, so the cache WAS the store. The
     * real invariant is that a clear drops the cached copy and the next read is served by the store.
     */
    @Test
    public void clearingTheCacheDoesNotLoseTheRow() {
        final TripEvent te = new TripEvent(UUID.randomUUID().toString(), TripEvent.Type.EVENT,
                "Concert", "notes", LocalDateTime.now(), null, null, null);
        dao.saveTripEvent(te);
        assertEquals(dao.getTripEvent(te.getId()), te);

        dao.clearCache();

        assertEquals(dao.getTripEvent(te.getId()), te);
    }

    @Test
    public void saveTripEventIsIdempotent() {
        final TripEvent te = new TripEvent(UUID.randomUUID().toString(), TripEvent.Type.GROUND,
                "Bus ride", "notes", LocalDateTime.now(), null, null, null);
        assertTrue(dao.saveTripEvent(te));
        assertTrue(dao.saveTripEvent(te));
        assertEquals(dao.getTripEvent(te.getId()), te);
    }

    @Test
    public void saveTripEventUpdatesCache() {
        final String id = UUID.randomUUID().toString();
        final TripEvent original = new TripEvent(id, TripEvent.Type.FLIGHT,
                "Original", "notes", LocalDateTime.now(), null, null, null);
        dao.saveTripEvent(original);
        assertEquals(dao.getTripEvent(id).getTitle(), "Original");
        final TripEvent updated = new TripEvent(id, TripEvent.Type.FLIGHT,
                "Updated", "new notes", LocalDateTime.now(), null, null, null);
        dao.saveTripEvent(updated);
        assertEquals(dao.getTripEvent(id).getTitle(), "Updated");
    }
    }

