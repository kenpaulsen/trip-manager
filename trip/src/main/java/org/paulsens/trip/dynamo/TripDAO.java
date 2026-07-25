package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.PointCache;
import org.paulsens.trip.cache.TripIndex;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Trips are point entries: {@link #getTrip(String)} is a point read (cached). List queries -- active/inactive
 * menu, admin "all trips", and per-user trips -- are served by {@link TripIndex} (a date-scored sorted set plus a
 * person&rarr;trip reverse set), so there is no whole-table scan on the request path.
 */
@Slf4j
public class TripDAO {
    private static final String ID = "id";
    private static final String CONTENT = "content";
    private static final String TRIP_TABLE = "trips";
    /** Display order for resolved trip lists (matches the pre-index whole-table cache ordering). */
    private static final Comparator<Trip> TRIP_ORDER =
            Comparator.comparing(Trip::getStartDate).thenComparing(Trip::getId);
    /** Trips with no end date sort as "ongoing" (always active, never inactive) rather than crashing date math. */
    private static final long NO_END_DATE = Long.MAX_VALUE / 2;

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final TripEventDAO tripEventDao;
    private final CacheClient cacheClient;
    private final PointCache<Trip> cache;
    private final TripIndex index;

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
        this.cacheClient = cacheClient;
        this.cache = PointCache.<Trip>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.TRIP_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .serializer(this::toJson)
                .deserializer(this::parseTrip)
                .build();
        this.index = TripIndex.builder()
                .cache(cacheClient)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .loader(this::loadAllEntries)
                .build();
    }

    protected CompletableFuture<Boolean> saveTrip(final Trip trip) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, persistence.toStrAttr(trip.getId()));
        map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(trip)));
        // NOTE: trip + events are N independent putItems -- not atomic. Consciously unchanged by the cache port.
        final CompletableFuture<Boolean> saveTripEvents = tripEventDao.saveAllTripEvents(trip);
        // Fetch the prior version first so the index can diff the membership list (drop removed people).
        final CompletableFuture<Boolean> saveTrip = getTrip(trip.getId())
                .thenCompose(prev -> persistence.putItem(b -> b.tableName(TRIP_TABLE).item(map))
                        .thenCompose(resp -> resp.sdkHttpResponse().isSuccessful()
                                ? updateCaches(prev.orElse(null), trip)
                                : CompletableFuture.completedFuture(false)));
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
        return cache.get(id, this::loadTripById);
    }

    /** Active trips (endDate at/after {@code cutoff}), in display order. */
    protected CompletableFuture<List<Trip>> getActiveTrips(final LocalDateTime cutoff) {
        return index.activeTripIds(toEpochMillis(cutoff), 0).thenCompose(ids -> resolveTrips(ids, true));
    }

    /** Up to {@code limit} most-recent inactive trips (endDate before {@code cutoff}), newest first. */
    protected CompletableFuture<List<Trip>> getInactiveTrips(final LocalDateTime cutoff, final int limit) {
        return index.inactiveTripIds(toEpochMillis(cutoff), limit).thenCompose(ids -> resolveTrips(ids, false));
    }

    /** The {@code limit} most-recently-ending trips (admin pickers), in display order; non-positive = no cap. */
    protected CompletableFuture<List<Trip>> getRecentTrips(final int limit) {
        return index.allTripIds(limit).thenCompose(ids -> resolveTrips(ids, true));
    }

    /** Trips the user is a member of, in display order. */
    protected CompletableFuture<List<Trip>> getTripsForUser(final Person.Id userId) {
        if (userId == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return index.tripIdsForUser(userId.getValue(), 0).thenCompose(ids -> resolveTrips(ids, true));
    }

    public void clearCache() {
        cacheClient.clearNamespace(CacheKeys.TRIP_PREFIX).join();
        index.invalidate().join();
    }

    private CompletableFuture<Boolean> updateCaches(final Trip prev, final Trip trip) {
        final TripIndex.Entry prevEntry = (prev == null) ? null : entryOf(prev);
        return cache.put(trip.getId(), trip)
                .thenCompose(ignored -> index.update(prevEntry, entryOf(trip), false));
    }

    private CompletableFuture<List<Trip>> resolveTrips(final List<String> ids, final boolean displayOrder) {
        final List<CompletableFuture<Optional<Trip>>> futures = ids.stream()
                .map(id -> cache.get(id, this::loadTripById))
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    final List<Trip> trips = futures.stream()
                            .map(CompletableFuture::join)
                            .flatMap(Optional::stream)
                            .collect(Collectors.toList());
                    if (displayOrder) {
                        trips.sort(TRIP_ORDER);
                    }
                    return trips;
                });
    }

    private CompletableFuture<Trip> loadTripById(final String id) {
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id).build());
        return persistence.getItem(b -> b.key(key).tableName(TRIP_TABLE).build())
                .thenApply(resp -> toTrip(resp.item().get(CONTENT)));
    }

    private CompletableFuture<List<TripIndex.Entry>> loadAllEntries() {
        return persistence.scanAll(b -> b.consistentRead(false).limit(1000).tableName(TRIP_TABLE).build())
                .thenApply(items -> items.stream()
                        .map(it -> toTrip(it.get(CONTENT)))
                        .filter(trip -> trip != null)
                        .map(TripDAO::entryOf)
                        .toList());
    }

    private static TripIndex.Entry entryOf(final Trip trip) {
        final Set<String> userIds = trip.getPeople().stream().map(Person.Id::getValue).collect(Collectors.toSet());
        return new TripIndex.Entry(trip.getId(), toEpochMillis(trip.getEndDate()), userIds);
    }

    private static long toEpochMillis(final LocalDateTime dateTime) {
        return (dateTime == null) ? NO_END_DATE : dateTime.toInstant(ZoneOffset.UTC).toEpochMilli();
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
