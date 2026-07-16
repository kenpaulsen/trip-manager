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
import org.paulsens.trip.cache.FullTableCache;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.Privilege;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

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
    private final FullTableCache<String, Privilege> cache;

    protected PrivilegesDAO(final ObjectMapper mapper, final Persistence persistence) {
        this(mapper, persistence, new InMemoryCacheClient());
    }

    protected PrivilegesDAO(final ObjectMapper mapper, final Persistence persistence, final CacheClient cacheClient) {
        this.mapper = mapper;
        this.persistence = persistence;
        this.cacheClient = cacheClient;
        this.cache = FullTableCache.<String, Privilege>builder()
                .cache(cacheClient)
                .key(CacheKeys.PRIVS)
                .softRevalidate(CacheSupport.softRevalidateEnabled(cacheClient))
                .idGetter(Privilege::getName)
                .idFormatter(name -> name)
                .serializer(this::toJson)
                .deserializer(this::parsePrivilege)
                .order(privSorter)
                .build();
    }

    protected CompletableFuture<Boolean> savePrivilege(final Privilege priv) {
        final Map<String, AttributeValue> map = new HashMap<>();
        map.put(NAME, persistence.toStrAttr(priv.getName()));
        try {
            map.put(CONTENT, persistence.toStrAttr(mapper.writeValueAsString(priv)));
        } catch (final IOException ex) {
            final String error = "Unable to serialize privilege named: " + priv.getName();
            log.warn(error);
            throw new IllegalStateException(error);
        }
        return persistence.putItem(b -> b.tableName(PRIVILEGE_TABLE).item(map))
                .thenCompose(resp -> resp.sdkHttpResponse().isSuccessful()
                        ? cache.put(priv)
                        : CompletableFuture.completedFuture(false));
    }

    protected CompletableFuture<List<Privilege>> getPrivileges() {
        return cache.getAll(this::loadPrivileges);
    }

    /**
     * A privilege check must stay cheap: on a cache miss this does a point read of the single privilege rather
     * than scanning the whole table (a loaded cache still answers hits and "not found" without any database call).
     */
    protected CompletableFuture<Optional<Privilege>> getPrivilege(final String name) {
        return cacheClient.getHashFields(CacheKeys.PRIVS, List.of(CacheKeys.LOADED_SENTINEL, name))
                .thenCompose(found -> {
                    final String json = found.get(name);
                    if (json != null) {
                        final Privilege priv = parsePrivilege(json);
                        if (priv != null) {
                            return CompletableFuture.completedFuture(Optional.of(priv));
                        }
                    }
                    if (found.containsKey(CacheKeys.LOADED_SENTINEL)) {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    return pointReadPrivilege(name);
                });
    }

    public void clearCache() {
        cache.invalidate().join();
    }

    private CompletableFuture<Optional<Privilege>> pointReadPrivilege(final String name) {
        final Map<String, AttributeValue> key = Map.of(NAME, AttributeValue.builder().s(name).build());
        return persistence.getItem(b -> b.key(key).tableName(PRIVILEGE_TABLE).build())
                .thenApply(resp -> resp.item().get(CONTENT))
                .thenCompose(content -> {
                    final Privilege priv = (content == null) ? null : parsePrivilege(content.s());
                    if (priv == null) {
                        return CompletableFuture.completedFuture(Optional.<Privilege>empty());
                    }
                    return cache.put(priv).thenApply(ignored -> Optional.of(priv));
                })
                .exceptionally(ex -> logAndReturnEmpty(ex, name));
    }

    private CompletableFuture<List<Privilege>> loadPrivileges() {
        return persistence.scanAll(b -> b.consistentRead(false).limit(1000).tableName(PRIVILEGE_TABLE).build())
                .thenApply(items -> items.stream()
                        .map(it -> toPrivilege(it.get(CONTENT)).get())
                        .toList());
    }

    private Optional<Privilege> toPrivilege(final AttributeValue content) {
        if (content == null) {
            log.debug("No content returned for privilege from db, perhaps it doesn't exist?");
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(mapper.readValue(content.s(), Privilege.class));
        } catch (final IOException ex) {
            throw new IllegalStateException("Unable to parse Privilege content!");
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
            log.error("Unable to serialize privilege: " + priv.getName(), ex);
            return null;
        }
    }

    private Optional<Privilege> logAndReturnEmpty(final Throwable ex, final String name) {
        log.debug("PrivilegesDAO: Unable to retrieve Privilege (" + name + ")!");
        return Optional.empty();
    }
}
