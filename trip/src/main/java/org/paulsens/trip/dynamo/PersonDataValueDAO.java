package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

@Slf4j
public class PersonDataValueDAO {
    private static final String PERSON_DATA_VALUE_TABLE = "person_data";
    private static final String CONTENT = "content";
    private static final String DATA_ID = "dataId";
    private static final String TYPE = "type";
    private static final String USER_ID = "userId";

    private final Map<Person.Id, Map<DataId, PersonDataValue>> pdvCache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Persistence persistence;

    protected PersonDataValueDAO(final ObjectMapper mapper, final Persistence persistence) {
        this.mapper = mapper;
        this.persistence = persistence;
    }

    protected CompletableFuture<Boolean> savePersonDataValue(final PersonDataValue pdv) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(USER_ID, persistence.toStrAttr(pdv.getUserId().getValue()));
        map.put(DATA_ID, persistence.toStrAttr(pdv.getDataId().getValue()));
        map.put(TYPE, persistence.toStrAttr(pdv.getType()));
        map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(pdv)));
        final CompletableFuture<Map<DataId, PersonDataValue>> futUserData = getPersonDataValueCache(pdv.getUserId());
        return persistence.putItem(b -> b.tableName(PERSON_DATA_VALUE_TABLE).item(map))
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .thenCombine(futUserData, (success, userData) -> success ? userData : null)
                .thenApply(userData -> persistence.cacheOne(userData, pdv, pdv.getDataId(), userData != null))
                .exceptionally(ex -> {
                    log.error("Failed to save PDV '" + pdv.getDataId() + "': (" + pdv.getContent() + ")!", ex);
                    return false;
                });
    }

    protected CompletableFuture<Map<DataId, PersonDataValue>> getPersonDataValues(final Person.Id pid) {
        return getPersonDataValueCache(pid);
    }

    protected CompletableFuture<Optional<PersonDataValue>> getPersonDataValue(final Person.Id pid, final DataId pdvId) {
        return getPersonDataValueCache(pid)         // Ensure data for this person is loaded into memory
                .thenApply(map -> map.get(pdvId))   // Read from cache
                .thenApply(Optional::ofNullable);
    }

    public void clearCache() {
        pdvCache.clear();
    }

    private CompletableFuture<Map<DataId, PersonDataValue>> getPersonDataValueCache(final Person.Id pid) {
        final Map<DataId, PersonDataValue> result = pdvCache.get(pid);
        return (result == null) ? cachePersonDataValues(pid) : CompletableFuture.completedFuture(result);
    }

    private CompletableFuture<Map<DataId, PersonDataValue>> cachePersonDataValues(final Person.Id pid) {
        return loadPersonDataValues(pid)
                .thenApply(cache -> {
                    pdvCache.put(pid, cache);
                    return cache;
                })
                .exceptionally(ex -> {
                    log.error("Unable to load and cache person data values for '" + pid + "'!", ex);
                    throw new IllegalStateException(ex);
                });
    }

    private CompletableFuture<Map<DataId, PersonDataValue>> loadPersonDataValues(final Person.Id pid) {
        log.info("Cache miss for person data values for person id: {}", pid);
        final Map<DataId, PersonDataValue> result = new ConcurrentHashMap<>();
        return persistence.query(qb -> queryPersonDataValuesByPerson(qb, pid))
                .thenApply(resp -> resp.items().stream()
                        .map(m -> toPersonDataValue(m.get(CONTENT)))
                        .filter(Objects::nonNull)
                        .toList())
                .thenAccept(list -> persistence.cacheAll(result, list, PersonDataValue::getDataId))
                .thenApply(v -> result);
    }

    private void queryPersonDataValuesByPerson(final QueryRequest.Builder qb, final Person.Id pid) {
        qb.tableName(PERSON_DATA_VALUE_TABLE)
                .keyConditionExpression(USER_ID + " = :pid")
                .expressionAttributeValues(
                        Map.of(":pid", AttributeValue.builder().s(pid.getValue()).build()));
    }

    private PersonDataValue toPersonDataValue(final AttributeValue content) {
        try {
            return mapper.readValue(content.s(), PersonDataValue.class);
        } catch (final IOException ex) {
            log.error("Unable to parse Person Data Value record: " + content, ex);
            return null;
        }
    }
}
