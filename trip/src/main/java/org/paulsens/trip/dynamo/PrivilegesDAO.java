package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
    private static final String NAME = "name";
    private static final String CONTENT = "content";
    private static final String PRIVILEGE_TABLE = "privs";
    protected static final Comparator<Privilege> privSorter = (a, b) ->
            a.getName().compareToIgnoreCase(b.getName());

    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final CacheClient cacheClient;
    private final PartitionScanCache<Privilege> cache;

    protected PrivilegesDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected PrivilegesDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cacheClient = cacheClient;
        this.cache = PartitionScanCache.<Privilege>builder()
                .cache(cacheClient)
                .keyPrefix(CacheKeys.PRIV_PREFIX)
                .loadedKey(CacheKeys.PRIV_LOADED)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .loader(this::loadAllPrivileges)
                .partitioner(PrivilegesDAO::partitionOf)
                .fielder(Privilege::getName)
                .serializer(this::toJson)
                .deserializer(this::parsePrivilege)
                .build();
    }

    protected CompletableFuture<Boolean> savePrivilege(final Privilege priv) {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(NAME, persistence.toStrAttr(priv.getId()));
        try {
            map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(priv)));
        } catch (final IOException ex) {
            final String error = "Unable to serialize privilege named: " + priv.getId();
            log.warn(error);
            throw new IllegalStateException(error);
        }
        try {
            final boolean saved = persistence.putItem(b -> b.tableName(PRIVILEGE_TABLE).item(map))
                    .sdkHttpResponse().isSuccessful();
            return saved ? cache.put(priv) : CompletableFuture.completedFuture(false);
        } catch (final RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    /** Global (non-trip) privileges. */
    protected CompletableFuture<List<Privilege>> getGlobalPrivileges() {
        return cache.getPartition(CacheKeys.PRIV_GLOBAL_PARTITION);
    }

    /** Privileges scoped to the given trip. */
    protected CompletableFuture<List<Privilege>> getTripPrivileges(final String tripId) {
        if (tripId == null) {
            return getGlobalPrivileges();
        }
        return cache.getPartition(tripId);
    }

    /**
     * A single privilege by id (== DynamoDB name). Stays cheap: served from its partition hash, or a point read of
     * the single row on a miss -- never a table scan.
     */
    protected CompletableFuture<Optional<Privilege>> getPrivilege(final String id) {
        if (id == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return cache.getOne(partitionOf(id), baseNameOf(id), () -> pointReadPrivilege(id));
    }

    public void clearCache() {
        cache.invalidate().join();
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

    private CompletableFuture<Optional<Privilege>> pointReadPrivilege(final String id) {
        final Map<String, AttributeValue> key = Map.of(NAME, AttributeValue.builder().s(id).build());
        try {
            final AttributeValue content =
                    persistence.getItem(b -> b.key(key).tableName(PRIVILEGE_TABLE).build()).item().get(CONTENT);
            return CompletableFuture.completedFuture(
                    Optional.ofNullable(content == null ? null : parsePrivilege(content.s())));
        } catch (final RuntimeException ex) {
            return CompletableFuture.completedFuture(logAndReturnEmpty(ex, id));
        }
    }

    private CompletableFuture<List<Privilege>> loadAllPrivileges() {
        try {
            return CompletableFuture.completedFuture(
                    persistence.scanAll(b -> b.consistentRead(false).limit(1000).tableName(PRIVILEGE_TABLE).build())
                            .stream()
                            .map(it -> it.get(CONTENT))
                            .filter(content -> content != null)
                            .map(content -> parsePrivilege(content.s()))
                            .filter(priv -> priv != null)
                            .toList());
        } catch (final RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }

    private Privilege parsePrivilege(final String json) {
        try {
            return mapper.readValue(json, Privilege.class);
        } catch (final IOException ex) {
            log.error("Unable to parse privilege record: " + json, ex);
            return null;
        }
    }

    private String toJson(final Privilege priv) {
        try {
            return mapper.writeValueAsString(priv);
        } catch (final IOException ex) {
            log.error("Unable to serialize privilege: " + priv.getId(), ex);
            return null;
        }
    }

    private Optional<Privilege> logAndReturnEmpty(final Throwable ex, final String id) {
        log.debug("PrivilegesDAO: Unable to retrieve Privilege (" + id + ")!", ex);
        return Optional.empty();
    }
}
