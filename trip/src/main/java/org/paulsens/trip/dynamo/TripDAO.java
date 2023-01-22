package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Slf4j
public class TripDAO {
    private static final String ID = "id";
    private static final String CONTENT = "content";
    private static final String TRIP_TABLE = "trips";

    private final Map<String, Trip> tripCache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final PersonDAO personDao;
    private final TripEventDAO tripEventDao;

    protected TripDAO(
            final ObjectMapper mapper,
            final Persistence persistence,
            final PersonDAO personDao,
            final TripEventDAO tripEventDao) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.personDao = personDao;
        this.tripEventDao = tripEventDao;
    }

    protected CompletableFuture<Boolean> saveTrip(final Trip trip) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, persistence.toStrAttr(trip.getId()));
        map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(trip)));
        final CompletableFuture<Boolean> saveTripEvents = tripEventDao.saveAllTripEvents(trip);
        final CompletableFuture<Boolean> saveTrip = persistence.putItem(b -> b.tableName(TRIP_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .thenApply(r -> r ?
                        persistence.cacheOne(tripCache, trip, trip.getId(), true) :
                        persistence.clearCache(tripCache, false));
        return CompletableFuture.allOf(saveTrip, saveTripEvents)
                .thenApply(v -> saveTrip.join() && saveTripEvents.join())
                .exceptionally(ex -> {
                    log.error("Failed to save trip!", ex);
                    return false;
                });
    }

    protected CompletableFuture<Optional<Trip>> getTrip(final String id) {
        final Trip trip = tripCache.get(id);
        if (trip != null) {
            return CompletableFuture.completedFuture(Optional.of(trip));
        }
        return getTrips().thenApply(trips -> Optional.ofNullable(tripCache.get(id))); // Load all the trips
    }

    protected CompletableFuture<List<Trip>> getTrips() {
        if (!tripCache.isEmpty()) {
            return CompletableFuture.completedFuture(tripCache.values().stream()
                    .sorted(Comparator.comparing(Trip::getStartDate)).toList());
        }
        return persistence.scan(b -> b.consistentRead(false).limit(1000).tableName(TRIP_TABLE).build())
                .thenApply(resp -> resp.items().stream()
                        .map(it -> toTrip(it.get(CONTENT)))
                        .sorted(Comparator.comparing(Trip::getStartDate))
                        .toList())
                .thenApply(list -> persistence.cacheAll(tripCache, list, Trip::getId));
    }

    public void clearCache() {
        tripCache.clear();
    }

    private Trip toTrip(final AttributeValue content) {
        if (content == null) {
            return null;
        }
        try {
            return sortTripPeople(mapper.readValue(content.s(), Trip.class));
        } catch (final IOException ex) {
            log.error("Unable to parse trip record: " + content, ex);
            return null;
        }
    }

    private Trip sortTripPeople(final Trip trip) {
        final List<Person.Id> sortedIdList =
                trip.getPeople().stream()
                        .map(id -> personDao.getPerson(id).join())
                        .map(opt -> opt.orElse(null))
                        .filter(Objects::nonNull)
                        .sorted(PersonDAO.peopleSorter)
                        .map(Person::getId)
                        .toList();
        trip.setPeople(new ArrayList<>(sortedIdList));
        return trip;
    }
}
