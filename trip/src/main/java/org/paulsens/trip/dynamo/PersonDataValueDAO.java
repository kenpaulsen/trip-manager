package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.PartitionCache;
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

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final CacheClient cacheClient;
    private final PartitionCache<DataId, PersonDataValue> cache;

    protected PersonDataValueDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected PersonDataValueDAO(final ObjectMapper mapper, final Persistence persistence,
            final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cacheClient = cacheClient;
        this.cache = PartitionCache.<DataId, PersonDataValue>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.PDV_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .idGetter(PersonDataValue::getDataId)
                .idFormatter(DataId::getValue)
                .serializer(this::toJson)
                .deserializer(this::parsePersonDataValue)
                .order(Comparator.comparing(PersonDataValue::getDataId))
                .build();
    }

    protected CompletableFuture<Boolean> savePersonDataValue(final PersonDataValue pdv) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(USER_ID, persistence.toStrAttr(pdv.getUserId().getValue()));
        map.put(DATA_ID, persistence.toStrAttr(pdv.getDataId().getValue()));
        map.put(TYPE, persistence.toStrAttr(pdv.getType()));
        map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(pdv)));
        try {
            final boolean saved = persistence.putItem(b -> b.tableName(PERSON_DATA_VALUE_TABLE).item(map))
                    .sdkHttpResponse().isSuccessful();
            if (!saved) {
                return CompletableFuture.completedFuture(false);
            }
            return CompletableFuture.completedFuture(cache.put(pdv.getUserId().getValue(), pdv));
        } catch (final RuntimeException ex) {
            return CompletableFuture.completedFuture(logSavePdvFailure(pdv, ex));
        }
    }

    private Boolean logSavePdvFailure(final PersonDataValue pdv, final Throwable ex) {
        log.error("Failed to save PDV '" + pdv.getDataId() + "': (" + pdv.getContent() + ")!", ex);
        return false;
    }

    protected CompletableFuture<Map<DataId, PersonDataValue>> getPersonDataValues(final Person.Id pid) {
        try {
            final Map<DataId, PersonDataValue> result = new LinkedHashMap<>();
            cache.getAll(pid.getValue(), () -> loadPersonDataValues(pid))
                    .forEach(pdv -> result.put(pdv.getDataId(), pdv));
            return CompletableFuture.completedFuture(result);
        } catch (final RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    protected CompletableFuture<Optional<PersonDataValue>> getPersonDataValue(final Person.Id pid, final DataId pdvId) {
        try {
            return CompletableFuture.completedFuture(
                    cache.getOne(pid.getValue(), pdvId, () -> loadPersonDataValues(pid)));
        } catch (final RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    public void clearCache() {
        cacheClient.clearNamespace(CacheKeys.PDV_PREFIX);
    }

    private List<PersonDataValue> loadPersonDataValues(final Person.Id pid) {
        log.info("Cache miss for person data values for person id: {}", pid);
        return persistence.queryAll(qb -> queryPersonDataValuesByPerson(qb, pid)).stream()
                .map(m -> toPersonDataValue(m.get(CONTENT)))
                .filter(Objects::nonNull)
                .toList();
    }

    private void queryPersonDataValuesByPerson(final QueryRequest.Builder qb, final Person.Id pid) {
        qb.tableName(PERSON_DATA_VALUE_TABLE)
                .keyConditionExpression(USER_ID + " = :pid")
                .expressionAttributeValues(
                        Map.of(":pid", AttributeValue.builder().s(pid.getValue()).build()));
    }

    private PersonDataValue toPersonDataValue(final AttributeValue content) {
        return (content == null) ? null : parsePersonDataValue(content.s());
    }

    private PersonDataValue parsePersonDataValue(final String json) {
        try {
            return mapper.readValue(json, PersonDataValue.class);
        } catch (final IOException ex) {
            log.error("Unable to parse Person Data Value record: " + json, ex);
            return null;
        }
    }

    private String toJson(final PersonDataValue pdv) {
        try {
            return mapper.writeValueAsString(pdv);
        } catch (final IOException ex) {
            log.error("Unable to serialize PDV: " + pdv.getDataId(), ex);
            return null;
        }
    }
}
