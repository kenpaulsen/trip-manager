package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.model.Privilege;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@Slf4j
public class PrivilegesDAO {
    private static final String NAME = "name";
    private static final String CONTENT = "content";
    private static final String PRIVILEGE_TABLE = "privs";
    protected static final Comparator<Privilege> privSorter = (a, b) ->
            a.getName().compareToIgnoreCase(b.getName());

    private final Map<String, Privilege> privCache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final Persistence persistence;
    private final AtomicBoolean hasScanned = new AtomicBoolean(false);

    protected PrivilegesDAO(final ObjectMapper mapper, final Persistence persistence) {
        this.mapper = mapper;
        this.persistence = persistence;
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
                .thenApply(resp -> resp.sdkHttpResponse().isSuccessful())
                .thenApply(r -> r ?
                        persistence.cacheOne(privCache, priv, priv.getName(), true) :
                        persistence.clearCache(privCache, false));
    }

    protected CompletableFuture<List<Privilege>> getPrivileges() {
        if (hasScanned.getAndSet(true)) {
            return CompletableFuture.completedFuture(privCache.values().stream().sorted(privSorter).toList());
        }
        return persistence.scan(b -> b.consistentRead(false).limit(1000).tableName(PRIVILEGE_TABLE).build())
                .thenApply(resp -> resp.items().stream()
                        .map(it -> toPrivilege(it.get(CONTENT)).get())
                        .sorted(privSorter)
                        .toList())
                .thenApply(list -> persistence.cacheAll(privCache, list, Privilege::getName));
    }

    protected CompletableFuture<Optional<Privilege>> getPrivilege(final String name) {
        final Privilege priv = privCache.get(name);
        if (priv != null) {
            return CompletableFuture.completedFuture(Optional.of(priv));
        }
        final Map<String, AttributeValue> key = Map.of(NAME, AttributeValue.builder().s(name).build());
        return persistence.getItem(b -> b.key(key).tableName(PRIVILEGE_TABLE).build())
                .thenApply(resp -> resp.item().get(CONTENT))
                .thenApply(this::toPrivilege)
                .exceptionally(ex -> logAndReturnEmpty(ex, name));
    }

    public void clearCache() {
        privCache.clear();
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

    private Optional<Privilege> logAndReturnEmpty(final Throwable ex, final String name) {
        log.debug("PrivilegesDAO: Unable to retrieve Privilege (" + name + ")!");
        return Optional.empty();
    }
}
