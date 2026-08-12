package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.PointCache;
import org.paulsens.trip.model.Family;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Families are point entries -- lookups are by id only (the reverse edge lives on {@code Person.familyId}), so
 * there is deliberately no scan, no index, and no list-all path.
 *
 * <p>Every save is a conditional put keyed on the {@code version} the caller's object was loaded with: version 0
 * may only CREATE (attribute_not_exists), any other version may only replace that exact stored version. A lost
 * race throws {@link ConditionalCheckFailedException} for the command layer to surface as "family changed,
 * retry" -- never retried silently here, because the caller must recompute its membership deltas against the
 * winning row. The version attribute is duplicated top-level (beside the content JSON) so the condition can see
 * it; it is always expression-aliased, sidestepping any reserved-word concern.
 */
@Slf4j
public class FamilyDAO {
    static final String FAMILY_TABLE = "family";
    static final String VERSION_ATTR = "version";
    private static final String ID = "id";
    private static final String CONTENT = "content";

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final PointCache<Family> cache;

    protected FamilyDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cache = PointCache.<Family>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.FAMILY_PREFIX)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .serializer(this::toJson)
                .deserializer(this::parseFamily)
                .build();
    }

    /**
     * Conditionally persist this family and, on success, bump the object's version in place and write through
     * the cache. On a version conflict the object is left at its original version (so the caller can re-read and
     * retry) and the {@link ConditionalCheckFailedException} propagates.
     */
    protected Boolean saveFamily(final Family family) throws IOException {
        final long expected = family.getVersion();
        family.setVersion(expected + 1);
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, AttributeValue.builder().s(family.getId().getValue()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(family)).build());
        map.put(VERSION_ATTR, AttributeValue.builder().n(Long.toString(family.getVersion())).build());
        try {
            final boolean saved = persistence.putItem(b -> {
                b.tableName(FAMILY_TABLE).item(map);
                if (expected == 0L) {
                    b.conditionExpression("attribute_not_exists(#i)")
                            .expressionAttributeNames(Map.of("#i", ID));
                } else {
                    b.conditionExpression("#v = :expected")
                            .expressionAttributeNames(Map.of("#v", VERSION_ATTR))
                            .expressionAttributeValues(Map.of(
                                    ":expected", AttributeValue.builder().n(Long.toString(expected)).build()));
                }
            }).sdkHttpResponse().isSuccessful();
            if (saved) {
                cache.put(family.getId().getValue(), family);
            } else {
                family.setVersion(expected);
            }
            return saved;
        } catch (final ConditionalCheckFailedException ex) {
            family.setVersion(expected);
            throw ex;
        }
    }

    protected Optional<Family> getFamily(final Family.Id id) {
        if (id == null) {
            return Optional.empty();
        }
        return cache.get(id.getValue(), this::loadFamilyById);
    }

    /** Removes the row (used when the last member is unlinked). No version condition: deleting a family twice is idempotent. */
    protected Boolean deleteFamily(final Family.Id id) {
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id.getValue()).build());
        final boolean deleted = persistence.deleteItem(b -> b.tableName(FAMILY_TABLE).key(key))
                .sdkHttpResponse().isSuccessful();
        return cache.remove(id.getValue()) && deleted;
    }

    private Family loadFamilyById(final String id) {
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id).build());
        final AttributeValue content =
                persistence.getItem(b -> b.key(key).tableName(FAMILY_TABLE).build()).item().get(CONTENT);
        return (content == null) ? null : parseFamily(content.s());
    }

    private Family parseFamily(final String json) {
        try {
            return mapper.readValue(json, Family.class);
        } catch (final IOException ex) {
            log.error("Unable to parse family record: " + json, ex);
            return null;
        }
    }

    private String toJson(final Family family) {
        try {
            return mapper.writeValueAsString(family);
        } catch (final IOException ex) {
            log.error("Unable to serialize family: " + family.getId(), ex);
            return null;
        }
    }
}
