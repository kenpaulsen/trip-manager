package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    protected Boolean savePersonDataValue(final PersonDataValue pdv) throws IOException {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(USER_ID, persistence.toStrAttr(pdv.getUserId().getValue()));
        map.put(DATA_ID, persistence.toStrAttr(pdv.getDataId().getValue()));
        map.put(TYPE, persistence.toStrAttr(pdv.getType()));
        map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(pdv)));
        try {
            final boolean saved = persistence.putItem(b -> b.tableName(PERSON_DATA_VALUE_TABLE).item(map))
                    .sdkHttpResponse().isSuccessful();
            if (!saved) {
                return false;
            }
            return cache.put(pdv.getUserId().getValue(), pdv);
        } catch (final RuntimeException ex) {
            return logSavePdvFailure(pdv, ex);
        }
    }

    private Boolean logSavePdvFailure(final PersonDataValue pdv, final Throwable ex) {
        log.error("Failed to save PDV '" + pdv.getDataId() + "': (" + pdv.getContent() + ")!", ex);
        return false;
    }

    protected Map<DataId, PersonDataValue> getPersonDataValues(final Person.Id pid) {
        try {
            final Map<DataId, PersonDataValue> result = new LinkedHashMap<>();
            cache.getAll(pid.getValue(), () -> loadPersonDataValues(pid))
                    .forEach(pdv -> result.put(pdv.getDataId(), pdv));
            return result;
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    protected Optional<PersonDataValue> getPersonDataValue(final Person.Id pid, final DataId pdvId) {
        try {
            return 
                    cache.getOne(pid.getValue(), pdvId, () -> loadPersonDataValues(pid));
        } catch (final RuntimeException ex) {
            throw ex;
        }
    }

    /**
     * Hard-deletes one person-data row (a todo status or a per-trip room assignment). Answers true when the
     * row is gone -- including when it never existed, so a cleanup sweep can call this blindly.
     */
    protected Boolean deletePersonDataValue(final Person.Id pid, final DataId pdvId) {
        final Map<String, AttributeValue> key = Map.of(
                USER_ID, AttributeValue.builder().s(pid.getValue()).build(),
                DATA_ID, AttributeValue.builder().s(pdvId.getValue()).build());
        final boolean deleted = persistence.deleteItem(b -> b.tableName(PERSON_DATA_VALUE_TABLE).key(key))
                .sdkHttpResponse().isSuccessful();
        cache.remove(pid.getValue(), pdvId);
        return deleted;
    }

    /**
     * Deletes every person-data row whose dataId is in {@code targets}, whoever it belongs to -- the trip
     * delete's sweep for todo statuses (keyed by the trip's todo dataIds) and room rows ({@code room{tripId}}).
     * Two passes, because the partition key is the PERSON and nothing else indexes these rows: the
     * {@code candidates} (people the trip knew about) are swept through the cached read path -- which is the
     * store itself in local mode -- and then one raw table scan catches production rows for anyone else,
     * since room rows are created lazily by mere page reads and no roster predicts who has one. A rare admin
     * action, so completeness beats the scan's cost.
     *
     * @return how many rows were deleted.
     */
    protected int deleteAllByDataIds(final Set<DataId> targets, final Set<Person.Id> candidates) {
        if (targets == null || targets.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (final Person.Id pid : (candidates == null) ? Set.<Person.Id>of() : candidates) {
            for (final DataId dataId : getPersonDataValues(pid).keySet()) {
                if (targets.contains(dataId) && deletePersonDataValue(pid, dataId)) {
                    removed++;
                }
            }
        }
        final Set<String> raw = new HashSet<>();
        targets.forEach(id -> raw.add(id.getValue()));
        final List<Map<String, AttributeValue>> rows = persistence.scanAll(b ->
                b.consistentRead(false).limit(1000).tableName(PERSON_DATA_VALUE_TABLE).build());
        for (final Map<String, AttributeValue> row : rows) {
            final AttributeValue dataId = row.get(DATA_ID);
            final AttributeValue userId = row.get(USER_ID);
            if (dataId != null && userId != null && raw.contains(dataId.s())
                    && deletePersonDataValue(Person.Id.from(userId.s()), DataId.from(dataId.s()))) {
                removed++;
            }
        }
        return removed;
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
