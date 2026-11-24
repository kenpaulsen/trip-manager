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
import org.paulsens.trip.cache.PartitionScanCache;
import org.paulsens.trip.model.Privilege;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Privileges are partitioned by trip. A privilege is either global or trip-scoped (its {@code id} ends with the
 * trip id -- see {@link Privilege}), so listing "all privileges" is replaced by listing one partition: the global
 * partition or a single trip's. The {@link PartitionScanCache} holds one Valkey hash per partition (rebuilt by a
 * rare whole-table scan), so a trip's privileges are one {@code HGETALL}; single-privilege reads are point reads by
 * the DynamoDB {@code name} key.
 */
@Slf4j
public class PrivilegesDAO {
    static final String NAME = "name";
    private static final String CONTENT = "content";
    static final String PRIVILEGE_TABLE = "privs";
    protected static final Comparator<Privilege> PRIV_SORTER = (a, b) ->
            a.getName().compareToIgnoreCase(b.getName());

    private final ObjectMapper mapper;
    private final JsonRows rows;
    private final Persistence persistence;
    private final PartitionScanCache<Privilege> cache;

    protected PrivilegesDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected PrivilegesDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.rows = new JsonRows(mapper);
        this.persistence = persistence;
        this.cache = PartitionScanCache.<Privilege>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.PRIV_PREFIX)
                .loadedKey(CacheKeys.PRIV_LOADED)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .loader(this::loadAllPrivileges)
                .partitioner(PrivilegesDAO::partitionOf)
                .fielder(Privilege::getName)
                .serializer(value -> rows.toJson(value))
                .deserializer(json -> rows.parse(Privilege.class, json))
                .build();
    }

    protected Boolean savePrivilege(final Privilege priv) {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(NAME, persistence.toStrAttr(priv.getId()));
        try {
            map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(priv)));
        } catch (final IOException ex) {
            final String error = "Unable to serialize privilege named: " + priv.getId();
            log.warn(error);
            throw new IllegalStateException(error);
        }
        final boolean saved = persistence.putItem(b -> b.tableName(PRIVILEGE_TABLE).item(map))
                .sdkHttpResponse().isSuccessful();
        return saved && cache.put(priv);
    }

    /**
     * Hard-deletes one privilege ROW by id (== DynamoDB name), holders and all. Exists for the trip delete
     * cascade -- trip-scoped rows fuse the tripId into their key, so a deleted trip would otherwise strand
     * them forever. Cache removal is {@code removeOne} on purpose: in local mode the scan cache IS the store.
     */
    protected Boolean deletePrivilege(final String id) {
        final Map<String, AttributeValue> key = Map.of(NAME, AttributeValue.builder().s(id).build());
        final boolean deleted = persistence.deleteItem(b -> b.tableName(PRIVILEGE_TABLE).key(key))
                .sdkHttpResponse().isSuccessful();
        cache.removeOne(partitionOf(id), baseNameOf(id));
        return deleted;
    }

    /** Global (non-trip) privileges. */
    protected List<Privilege> getGlobalPrivileges() {
        return cache.getPartition(CacheKeys.PRIV_GLOBAL_PARTITION);
    }

    /** Privileges scoped to the given trip. */
    protected List<Privilege> getTripPrivileges(final String tripId) {
        if (tripId == null) {
            return getGlobalPrivileges();
        }
        return cache.getPartition(tripId);
    }

    /**
     * A single privilege by id (== DynamoDB name). Stays cheap: served from its partition hash, or a point read of
     * the single row on a miss -- never a table scan.
     */
    protected Optional<Privilege> getPrivilege(final String id) {
        if (id == null) {
            return Optional.empty();
        }
        return cache.getOne(partitionOf(id), baseNameOf(id), () -> pointReadPrivilege(id));
    }

    public void clearCache() {
        cache.invalidate();
    }

    private static String partitionOf(final Privilege priv) {
        return priv.isGlobal() ? CacheKeys.PRIV_GLOBAL_PARTITION : priv.getTripId();
    }

    private static String partitionOf(final String id) {
        final Privilege probe = new Privilege(id, null, null);
        return partitionOf(probe);
    }

    private static String baseNameOf(final String id) {
        return new Privilege(id, null, null).getName();
    }

    private Optional<Privilege> pointReadPrivilege(final String id) {
        final Map<String, AttributeValue> key = Map.of(NAME, AttributeValue.builder().s(id).build());
        try {
            final AttributeValue content =
                    persistence.getItem(b -> b.key(key).tableName(PRIVILEGE_TABLE).build()).item().get(CONTENT);
            return Optional.ofNullable(content == null ? null : rows.parse(Privilege.class, content.s()));
        } catch (final RuntimeException ex) {
            return logAndReturnEmpty(ex, id);
        }
    }

    private List<Privilege> loadAllPrivileges() {
        // A consistent scan: the rebuild REMOVES what this does not return (PartitionScanCache's loader).
        return persistence.scanAll(b -> b.consistentRead(true).limit(1000).tableName(PRIVILEGE_TABLE).build())
                .stream()
                .map(it -> it.get(CONTENT))
                .filter(content -> content != null)
                .map(content -> rows.parse(Privilege.class, content.s()))
                .filter(priv -> priv != null)
                .toList();
    }

    private Optional<Privilege> logAndReturnEmpty(final Throwable ex, final String id) {
        log.debug("PrivilegesDAO: Unable to retrieve Privilege (" + id + ")!", ex);
        return Optional.empty();
    }
}
