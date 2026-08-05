package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

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
                .thenCompose(prev -> putTripItem(map, prev.orElse(null), trip));
        return CompletableFuture.allOf(saveTrip, saveTripEvents)
                .thenApply(ignore -> saveTrip.join() && saveTripEvents.join())
                .exceptionally(this::logSaveTripFailure);
    }

    private CompletableFuture<Boolean> putTripItem(
            final Map<String, AttributeValue> map, final Trip prev, final Trip trip) {
        final boolean saved = persistence.putItem(b -> b.tableName(TRIP_TABLE).item(map))
                .sdkHttpResponse().isSuccessful();
        return CompletableFuture.completedFuture(saved && updateCaches(prev, trip));
    }

    private Boolean logSaveTripFailure(final Throwable ex) {
        log.error("Failed to save trip!", ex);
        return false;
    }

    protected CompletableFuture<Optional<Trip>> getTrip(final String id) {
        if (id == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            return CompletableFuture.completedFuture(cache.get(id, this::loadTripById));
        } catch (final RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /** Active trips (endDate at/after {@code cutoff}), in display order. */
    protected CompletableFuture<List<Trip>> getActiveTrips(final LocalDateTime cutoff) {
        try {
            return CompletableFuture.completedFuture(
                    resolveTrips(index.activeTripIds(toEpochMillis(cutoff), 0), true));
        } catch (final RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /** Up to {@code limit} most-recent inactive trips (endDate before {@code cutoff}), newest first. */
    protected CompletableFuture<List<Trip>> getInactiveTrips(final LocalDateTime cutoff, final int limit) {
        try {
            return CompletableFuture.completedFuture(
                    resolveTrips(index.inactiveTripIds(toEpochMillis(cutoff), limit), false));
        } catch (final RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /** The {@code limit} most-recently-ending trips (admin pickers), in display order; non-positive = no cap. */
    protected CompletableFuture<List<Trip>> getRecentTrips(final int limit) {
        try {
            return CompletableFuture.completedFuture(resolveTrips(index.allTripIds(limit), true));
        } catch (final RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /** Trips the user is a member of, in display order. */
    protected CompletableFuture<List<Trip>> getTripsForUser(final Person.Id userId) {
        if (userId == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        try {
            return CompletableFuture.completedFuture(
                    resolveTrips(index.tripIdsForUser(userId.getValue(), 0), true));
        } catch (final RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    public void clearCache() {
        cacheClient.clearNamespace(CacheKeys.TRIP_PREFIX);
        index.invalidate();
    }

    private boolean updateCaches(final Trip prev, final Trip trip) {
        final TripIndex.Entry prevEntry = (prev == null) ? null : entryOf(prev);
        cache.put(trip.getId(), trip);
        return index.update(prevEntry, entryOf(trip), false);
    }

    // Sequential point reads: each is a cache hit in the common case, and page renders that need many
    // trips resolve them from one request thread -- the Phase 7 structured-concurrency pass may re-fan-out.
    private List<Trip> resolveTrips(final List<String> ids, final boolean displayOrder) {
        final List<Trip> trips = new ArrayList<>();
        for (final String id : ids) {
            cache.get(id, this::loadTripById).ifPresent(trips::add);
        }
        if (displayOrder) {
            trips.sort(TRIP_ORDER);
        }
        return trips;
    }

    private Trip loadTripById(final String id) {
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id).build());
        final GetItemResponse resp = persistence.getItem(b -> b.key(key).tableName(TRIP_TABLE).build());
        return toTrip(resp.item().get(CONTENT));
    }

    private List<TripIndex.Entry> loadAllEntries() {
        return persistence.scanAll(b -> b.consistentRead(false).limit(1000).tableName(TRIP_TABLE).build())
                .stream()
                .map(it -> toTrip(it.get(CONTENT)))
                .filter(trip -> trip != null)
                .map(TripDAO::entryOf)
                .toList();
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
