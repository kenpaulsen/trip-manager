package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.PointCache;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

@Slf4j
public class TripEventDAO {
    private static final String ID = "id";
    private static final String CONTENT = "content";
    private static final String TRIP_EVENT_TABLE = "trip_events";

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final CacheClient cacheClient;
    private final PointCache<TripEvent> cache;

    protected TripEventDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected TripEventDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cacheClient = cacheClient;
        this.cache = PointCache.<TripEvent>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.TRIP_EVENT_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .serializer(this::toJson)
                .deserializer(this::parseTripEvent)
                .build();
    }

    protected Boolean saveAllTripEvents(final Trip trip) {
        // Sequential on purpose for now; the Phase 7 structured-concurrency pass may re-fan-out.
        boolean allSaved = true;
        for (final TripEvent te : trip.getTripEvents()) {
            allSaved &= saveTripEvent(te);
        }
        return allSaved;
    }

    protected Boolean saveTripEvent(final TripEvent te) {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, persistence.toStrAttr(te.getId()));
        try {
            map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(te)));
        } catch (final IOException ex) {
            throw new RuntimeException(ex);
        }
        try {
            final boolean saved = persistence.putItem(b -> b.tableName(TRIP_EVENT_TABLE).item(map))
                    .sdkHttpResponse().isSuccessful();
            return saved && cache.put(te.getId(), te);
        } catch (final RuntimeException ex) {
            // Shim until Phase 5: callers still consume a CompletableFuture, so a (now synchronous)
            // persistence failure must arrive as a failed future, not a throw at call time.
            throw ex;
        }
    }

    protected TripEvent getTripEvent(final String id) {
        try {
            return cache.get(id, this::loadTripEvent).orElse(null);
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    public void clearCache() {
        cacheClient.clearNamespace(CacheKeys.TRIP_EVENT_PREFIX);
    }

    private TripEvent loadTripEvent(final String id) {
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id).build());
        return toTripEvent(persistence.getItem(b -> b.key(key).tableName(TRIP_EVENT_TABLE).build()), id);
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
        return parseTripEvent(content.s());
    }

    private TripEvent parseTripEvent(final String json) {
        try {
            return mapper.readValue(json, TripEvent.class);
        } catch (final IOException ex) {
            log.error("Unable to parse TripEvent record: " + json, ex);
            return null;
        }
    }

    private String toJson(final TripEvent te) {
        try {
            return mapper.writeValueAsString(te);
        } catch (final IOException ex) {
            log.error("Unable to serialize trip event: " + te.getId(), ex);
            return null;
        }
    }
}
