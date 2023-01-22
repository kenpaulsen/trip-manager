package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

@Slf4j
public class RegistrationDAO {
    private static final String CONTENT = "content";
    private static final String TRIP_ID = "tripId";
    private static final String USER_ID = "userId";
    private static final String DELETED = "deleted";
    private static final String REGISTRATION_TABLE = "registrations";

    private final Map<String, Map<Person.Id, Registration>> regCache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Persistence persistence;

    protected RegistrationDAO(final ObjectMapper mapper, final Persistence persistence) {
        this.mapper = mapper;
        this.persistence = persistence;
    }

    protected CompletableFuture<Boolean> saveRegistration(final Registration reg) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(TRIP_ID, persistence.toStrAttr(reg.getTripId()));
        map.put(USER_ID, persistence.toStrAttr(reg.getUserId().getValue()));
        map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(reg)));
        final CompletableFuture<Map<Person.Id, Registration>> futTripRegs = getTripRegistrationCache(reg.getTripId());
        return persistence.putItem(b -> b.tableName(REGISTRATION_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .thenCombine(futTripRegs, (success, tripRegs) -> success ? tripRegs : null)
                .thenApply(tripRegs -> persistence.cacheOne(tripRegs, reg, reg.getUserId(), tripRegs != null))
                .exceptionally(ex -> {
                    log.error("Failed to save registration!", ex);
                    return false;
                });
    }

    protected CompletableFuture<List<Registration>> getRegistrations(final String tripId) {
        return getTripRegistrationCache(tripId)
                .thenApply(map -> new ArrayList<>(map.values()));
    }

    protected CompletableFuture<Optional<Registration>> getRegistration(final String tripId, final Person.Id userId) {
        return getTripRegistrationCache(tripId)     // Ensure registrations for this trip are loaded into memory
                .thenApply(map -> map.get(userId))  // Read from cache
                .thenApply(Optional::ofNullable);
    }

    public void clearCache() {
        regCache.clear();
    }

    private CompletableFuture<Map<Person.Id, Registration>> getTripRegistrationCache(final String tripId) {
        final Map<Person.Id, Registration> result = regCache.get(tripId);
        return (result == null) ? cacheTripRegData(tripId) : CompletableFuture.completedFuture(result);
    }

    private CompletableFuture<Map<Person.Id, Registration>> cacheTripRegData(final String tripId) {
        return loadTripRegData(tripId)
                .thenApply(cache -> {
                    regCache.put(tripId, cache);
                    return cache;
                })
                .exceptionally(ex -> {
                    log.error("Unable to load and cache Trip Registration data!", ex);
                    throw new IllegalStateException(ex);
                });
    }

    private CompletableFuture<Map<Person.Id, Registration>> loadTripRegData(final String tripId) {
        log.info("Cache miss for registration data for tripId: {}", tripId);
        // Use a map that preserves order for sorting
        final Map<Person.Id, Registration> result = new ConcurrentSkipListMap<>();
        return persistence.query(qb -> registrationsByTripId(qb, tripId))
                .thenApply(resp -> resp.items().stream()
                        .map(m -> toRegistration(m.get(CONTENT)))
                        .filter(reg -> (reg != null) && !DELETED.equals(reg.getStatus()))
                        .sorted(Comparator.comparing(Registration::getCreated))
                        .toList())
                .thenAccept(list -> persistence.cacheAll(result, list, Registration::getUserId))
                .thenApply(v -> result);
    }

    private void registrationsByTripId(final QueryRequest.Builder qb, final String tripId) {
        qb.tableName(REGISTRATION_TABLE)
                .keyConditionExpression(TRIP_ID + " = :tripIdVal")
                .expressionAttributeValues(
                        Map.of(":tripIdVal", AttributeValue.builder().s(tripId).build()));
    }

    private Registration toRegistration(final AttributeValue content) {
        try {
            return mapper.readValue(content.s(), Registration.class);
        } catch (IOException ex) {
            log.error("Unable to parse registration record: " + content, ex);
            return null;
        }
    }
}
