package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.CacheSupport;
import org.paulsens.trip.cache.PartitionScanCache;
import org.paulsens.trip.model.Organization;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Organizations: the tenancy roots. The table is small (tens, someday low hundreds of rows) and org pickers
 * render on ordinary admin pages, so the whole table lives in ONE cache hash the way {@link MediaDAO}'s does --
 * a list is one HGETALL, and a save is visible to every instance at once.
 *
 * <p>Saves carry {@link FamilyDAO}-style optimistic versioning: version 0 may only CREATE, any other version
 * may only replace that exact stored version, and a lost race throws for the command layer to surface -- two
 * admins editing the same org serialize at this row. There is deliberately no delete: an organization with
 * historical trips and transactions must never vanish (deactivation can come later as a flag).
 */
@Slf4j
public class OrganizationDAO {
    /** Package-visible so {@link InMemoryPersistence} can register the table for local mode. */
    static final String ORG_TABLE = "organizations";
    static final String VERSION_ATTR = "version";
    private static final String ID = "id";
    private static final String CONTENT = "content";

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final PartitionScanCache<Organization> cache;

    protected OrganizationDAO(final ObjectMapper mapper, final Persistence persistence,
            final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cache = PartitionScanCache.<Organization>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.ORG_PREFIX)
                .loadedKey(CacheKeys.ORG_LOADED)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .loader(this::loadAllOrganizations)
                // One partition: pickers and the admin page want the whole (small) table.
                .partitioner(org -> CacheKeys.ORG_PARTITION)
                .fielder(org -> org.getId().getValue())
                .serializer(this::toJson)
                .deserializer(this::parseOrganization)
                .build();
    }

    /**
     * Conditionally persist this organization and, on success, bump the object's version in place and write
     * through the cache. On a version conflict the object is left at its original version (so the caller can
     * re-read and retry) and the {@link ConditionalCheckFailedException} propagates.
     */
    protected Boolean saveOrganization(final Organization org) throws IOException {
        final long expected = org.getVersion();
        org.setVersion(expected + 1);
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(ID, AttributeValue.builder().s(org.getId().getValue()).build());
        map.put(CONTENT, AttributeValue.builder().s(mapper.writeValueAsString(org)).build());
        map.put(VERSION_ATTR, AttributeValue.builder().n(Long.toString(org.getVersion())).build());
        try {
            final boolean saved = persistence.putItem(b -> {
                b.tableName(ORG_TABLE).item(map);
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
                cache.put(org);
            } else {
                org.setVersion(expected);
            }
            return saved;
        } catch (final ConditionalCheckFailedException ex) {
            org.setVersion(expected);
            throw ex;
        }
    }

    protected Optional<Organization> getOrganization(final Organization.Id id) {
        if (id == null) {
            return Optional.empty();
        }
        return cache.getOne(CacheKeys.ORG_PARTITION, id.getValue(), () -> pointReadOrganization(id));
    }

    /** Every organization, unsorted -- the command layer orders for display. */
    protected List<Organization> getOrganizations() {
        return cache.getPartition(CacheKeys.ORG_PARTITION);
    }

    public void clearCache() {
        cache.invalidate();
    }

    private Optional<Organization> pointReadOrganization(final Organization.Id id) {
        final Map<String, AttributeValue> key = Map.of(ID, AttributeValue.builder().s(id.getValue()).build());
        try {
            final AttributeValue content =
                    persistence.getItem(b -> b.key(key).tableName(ORG_TABLE).build()).item().get(CONTENT);
            return Optional.ofNullable(content == null ? null : parseOrganization(content.s()));
        } catch (final RuntimeException ex) {
            log.debug("OrganizationDAO: unable to read organization ({})", id.getValue(), ex);
            return Optional.empty();
        }
    }

    private List<Organization> loadAllOrganizations() {
        return persistence.scanAll(b -> b.consistentRead(false).limit(1000).tableName(ORG_TABLE).build())
                .stream()
                .map(item -> item.get(CONTENT))
                .filter(content -> content != null)
                .map(content -> parseOrganization(content.s()))
                .filter(org -> org != null)
                .toList();
    }

    private Organization parseOrganization(final String json) {
        try {
            return mapper.readValue(json, Organization.class);
        } catch (final IOException ex) {
            log.error("Unable to parse organization record: " + json, ex);
            return null;
        }
    }

    private String toJson(final Organization org) {
        try {
            return mapper.writeValueAsString(org);
        } catch (final IOException ex) {
            log.error("Unable to serialize organization: " + org.getId(), ex);
            return null;
        }
    }
}
