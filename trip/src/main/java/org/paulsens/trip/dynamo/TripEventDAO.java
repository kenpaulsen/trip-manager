package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

@Slf4j
public class TripEventDAO {
    private static final String ID = "id";
    private static final String CONTENT = "content";
    private static final String TRIP_EVENT_TABLE = "trip_events";

    private final Map<String, TripEvent> tripEventCache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Persistence persistence;

    protected TripEventDAO(final ObjectMapper mapper, final Persistence persistence) {
        this.mapper = mapper;
        this.persistence = persistence;
    }

    protected CompletableFuture<Boolean> saveAllTripEvents(final Trip trip) {
        final CompletableFuture<?>[] saves = trip.getTripEvents().stream()
                .map(this::saveTripEvent).toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(saves)
                // If any returned false, then fail
                .thenApply((v) -> Arrays.stream(saves).allMatch(fut -> (Boolean) fut.join()));
    }

    protected CompletableFuture<Boolean> saveTripEvent(final TripEvent te) {
        // FIXME: should we check if we need to save?
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, persistence.toStrAttr(te.getId()));
        try {
            map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(te)));
        } catch (final IOException ex) {
            throw new RuntimeException(ex);
        }
        return persistence.putItem(b -> b.tableName(TRIP_EVENT_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .thenApply(r -> r ? persistence.cacheOne(tripEventCache, te, te.getId(), true) : persistence.clearCache(tripEventCache, false));
    }

    protected CompletableFuture<TripEvent> getTripEvent(final String id) {
        final TripEvent te = tripEventCache.get(id);
        if (te != null) {
            return CompletableFuture.completedFuture(te);
        }
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id).build());
        return persistence.getItem(b -> b.key(key).tableName(TRIP_EVENT_TABLE).build())
                .thenApply(item -> toTripEvent(item, id));
    }

    public void clearCache() {
        tripEventCache.clear();
    }

    private TripEvent toTripEvent(final GetItemResponse resp, final String teId) {
        if (!resp.hasItem()) {
            log.warn("TripEvent (" + teId + ") not found!");
            return null;
        }
        final AttributeValue content = resp.item().get(CONTENT);
        if (content == null) {
            log.error("TripEvent (" + teId + ") is missing content!!");
            return null;
        }
        try {
            return mapper.readValue(content.s(), TripEvent.class);
        } catch (final IOException ex) {
            log.error("Unable to parse TripEvent record: " + content, ex);
            return null;
        }
    }
}
