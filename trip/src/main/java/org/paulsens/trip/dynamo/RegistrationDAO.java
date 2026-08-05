package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.PartitionCache;
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

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final CacheClient cacheClient;
    private final PartitionCache<Person.Id, Registration> cache;

    protected RegistrationDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected RegistrationDAO(final ObjectMapper mapper, final Persistence persistence,
            final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cacheClient = cacheClient;
        this.cache = PartitionCache.<Person.Id, Registration>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.REG_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .idGetter(Registration::getUserId)
                .idFormatter(Person.Id::getValue)
                .serializer(this::toJson)
                .deserializer(this::parseRegistration)
                // The old per-JVM cache was a skip-list map keyed by user id, so that was the caller-visible order.
                .order(Comparator.comparing(Registration::getUserId))
                .build();
    }

    protected Boolean saveRegistration(final Registration reg) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(TRIP_ID, persistence.toStrAttr(reg.getTripId()));
        map.put(USER_ID, persistence.toStrAttr(reg.getUserId().getValue()));
        map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(reg)));
        try {
            final boolean saved = persistence.putItem(b -> b.tableName(REGISTRATION_TABLE).item(map))
                    .sdkHttpResponse().isSuccessful();
            if (!saved) {
                return false;
            }
            return cache.put(reg.getTripId(), reg);
        } catch (final RuntimeException ex) {
            return logSaveRegistrationFailure(ex);
        }
    }

    private Boolean logSaveRegistrationFailure(final Throwable ex) {
        log.error("Failed to save registration!", ex);
        return false;
    }

    protected List<Registration> getRegistrations(final String tripId) {
        try {
            return cache.getAll(tripId, () -> loadTripRegData(tripId));
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    protected Optional<Registration> getRegistration(final String tripId, final Person.Id userId) {
        try {
            return cache.getOne(tripId, userId, () -> loadTripRegData(tripId));
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    public void clearCache() {
        cacheClient.clearNamespace(CacheKeys.REG_PREFIX);
    }

    private List<Registration> loadTripRegData(final String tripId) {
        log.info("Cache miss for registration data for tripId: {}", tripId);
        return persistence.queryAll(qb -> registrationsByTripId(qb, tripId)).stream()
                .map(m -> toRegistration(m.get(CONTENT)))
                .filter(reg -> (reg != null) && !DELETED.equals(reg.getStatus()))
                .toList();
    }

    private void registrationsByTripId(final QueryRequest.Builder qb, final String tripId) {
        qb.tableName(REGISTRATION_TABLE)
                .keyConditionExpression(TRIP_ID + " = :tripIdVal")
                .expressionAttributeValues(
                        Map.of(":tripIdVal", AttributeValue.builder().s(tripId).build()));
    }

    private Registration toRegistration(final AttributeValue content) {
        return (content == null) ? null : parseRegistration(content.s());
    }

    private Registration parseRegistration(final String json) {
        try {
            return mapper.readValue(json, Registration.class);
        } catch (final IOException ex) {
            log.error("Unable to parse registration record: " + json, ex);
            return null;
        }
    }

    private String toJson(final Registration reg) {
        try {
            return mapper.writeValueAsString(reg);
        } catch (final IOException ex) {
            log.error("Unable to serialize registration: " + reg.getTripId() + "/" + reg.getUserId(), ex);
            return null;
        }
    }
}
