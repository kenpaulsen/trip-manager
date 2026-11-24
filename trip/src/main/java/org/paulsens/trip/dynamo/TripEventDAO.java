package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final JsonRows rows;
    private final Persistence persistence;
    private final CacheClient cacheClient;
    private final PointCache<TripEvent> cache;

    protected TripEventDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected TripEventDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.rows = new JsonRows(mapper);
        this.persistence = persistence;
        this.cacheClient = cacheClient;
        this.cache = PointCache.<TripEvent>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.TRIP_EVENT_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .serializer(value -> rows.toJson(value))
                .deserializer(json -> rows.parse(TripEvent.class, json))
                .build();
    }

    protected Boolean saveAllTripEvents(final Trip trip) {
        final List<TripEvent> events = trip.getResolvedTripEvents();
        if (events == null) {
            // This instance never materialized its events: unresolved copies cannot carry mutations, and
            // resolving here just to rewrite identical rows would reintroduce the per-save fan-out.
            return true;
        }
        // Sequential on purpose for now; the Phase 7 structured-concurrency pass may re-fan-out.
        boolean allSaved = true;
        for (final TripEvent te : events) {
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
        final boolean saved = persistence.putItem(b -> b.tableName(TRIP_EVENT_TABLE).item(map))
                .sdkHttpResponse().isSuccessful();
        return saved && cache.put(te.getId(), te);
    }

    protected TripEvent getTripEvent(final String id) {
        return cache.get(id, this::loadTripEvent).orElse(null);
    }

    /**
     * Every event row's stored JSON, by id, straight from the table (no cache): what a one-time data repair reads,
     * so it can write back only rows that still hold exactly this text ({@link #replaceTripEventContent}).
     */
    protected Map<String, String> rawTripEventContents() {
        return new RawRows(persistence).read(RawRows.TRIP_EVENTS);
    }

    /**
     * Writes {@code replacement} as this event's stored JSON only if the row still holds {@code expected}, and
     * drops the cached copy. False, writing nothing, when the row changed since it was read (someone saved the
     * event): a repair must never overwrite an edit it did not see.
     */
    protected boolean replaceTripEventContent(final String id, final String expected, final String replacement) {
        final boolean replaced = new RawRows(persistence).replace(RawRows.TRIP_EVENTS, id, expected, replacement);
        if (replaced) {
            cache.remove(id);
        }
        return replaced;
    }

    /** Hard-deletes one event row. Events carry no tripId, so the caller must resolve ids via the Trip FIRST. */
    protected Boolean deleteTripEvent(final String id) {
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id).build());
        final boolean deleted = persistence.deleteItem(b -> b.tableName(TRIP_EVENT_TABLE).key(key))
                .sdkHttpResponse().isSuccessful();
        return cache.remove(id) && deleted;
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
        return rows.parse(TripEvent.class, content.s());
    }
}
