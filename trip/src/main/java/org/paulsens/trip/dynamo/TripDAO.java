package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.FullTableCache;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.Trip;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Slf4j
public class TripDAO {
    private static final String ID = "id";
    private static final String CONTENT = "content";
    private static final String TRIP_TABLE = "trips";

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final TripEventDAO tripEventDao;
    private final FullTableCache<String, Trip> cache;

    protected TripDAO(
            final ObjectMapper mapper,
            final Persistence persistence,
            final TripEventDAO tripEventDao) {
        this(mapper, persistence, tripEventDao, new InMemoryCacheClient());
    }

    protected TripDAO(
            final ObjectMapper mapper,
            final Persistence persistence,
            final TripEventDAO tripEventDao,
            final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.tripEventDao = tripEventDao;
        this.cache = FullTableCache.<String, Trip>builder()
                .cache(cacheClient)
                .key(CacheKeys.TRIPS)
                .idGetter(Trip::getId)
                .idFormatter(id -> id)
                .serializer(this::toJson)
                .deserializer(this::parseTrip)
                .order(Comparator.comparing(Trip::getStartDate).thenComparing(Trip::getId))
                .build();
    }

    protected CompletableFuture<Boolean> saveTrip(final Trip trip) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, persistence.toStrAttr(trip.getId()));
        map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(trip)));
        // NOTE: trip + events are N independent putItems -- not atomic. Consciously unchanged by the cache port.
        final CompletableFuture<Boolean> saveTripEvents = tripEventDao.saveAllTripEvents(trip);
        final CompletableFuture<Boolean> saveTrip = persistence.putItem(b -> b.tableName(TRIP_TABLE).item(map))
                .thenCompose(resp -> resp.sdkHttpResponse().isSuccessful()
                        ? cache.put(trip)
                        : CompletableFuture.completedFuture(false));
        return CompletableFuture.allOf(saveTrip, saveTripEvents)
                .thenApply(ignore -> saveTrip.join() && saveTripEvents.join())
                .exceptionally(ex -> {
                    log.error("Failed to save trip!", ex);
                    return false;
                });
    }

    protected CompletableFuture<Optional<Trip>> getTrip(final String id) {
        if (id == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return cache.getOne(id, this::loadTrips);
    }

    protected CompletableFuture<List<Trip>> getTrips() {
        return cache.getAll(this::loadTrips);
    }

    public void clearCache() {
        cache.invalidate().join();
    }

    private CompletableFuture<List<Trip>> loadTrips() {
        return persistence.scanAll(b -> b.consistentRead(false).limit(1000).tableName(TRIP_TABLE).build())
                .thenApply(items -> items.stream()
                        .map(it -> toTrip(it.get(CONTENT)))
                        .filter(trip -> trip != null)
                        .toList());
    }

    private Trip toTrip(final AttributeValue content) {
        return (content == null) ? null : parseTrip(content.s());
    }

    private Trip parseTrip(final String json) {
        try {
            return mapper.readValue(json, Trip.class);
        } catch (final IOException ex) {
            log.error("Unable to parse trip record: " + json, ex);
            return null;
        }
    }

    private String toJson(final Trip trip) {
        try {
            return mapper.writeValueAsString(trip);
        } catch (final IOException ex) {
            log.error("Unable to serialize trip: " + trip.getId(), ex);
            return null;
        }
    }
}
