package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-through/write-through template for the point-read access shape (trip events): one shared-cache string entry
 * per entity. There is no "loaded" concept -- a miss always consults the database, and a database null is returned
 * without being cached (negative results are not cached).
 *
 * @param <V> The entity type.
 */
@Slf4j
@Builder
public class PointCache<V> {
    private final CacheClient cache;
    /** Full cache-key prefix for an entity, e.g. {@code t1:trip_event:} (entity id is appended). */
    private final String keyPrefix;
    @Builder.Default
    private final Duration ttl = CacheKeys.POINT_TTL;
    /** Serializes an entity to its cache JSON; must return null (never throw) on failure. */
    private final Function<V, String> serializer;
    /** Parses cache JSON back to an entity; must return null (never throw) on failure. */
    private final Function<String, V> deserializer;

    /**
     * Returns the entity by id, loading (and caching) from the database on a miss. The loader's null result is
     * passed through as empty.
     */
    public CompletableFuture<Optional<V>> get(final String id, final Function<String, CompletableFuture<V>> loader) {
        final String key = keyPrefix + id;
        return cache.getValue(key).thenCompose(cached -> {
            if (cached.isPresent()) {
                final V value = deserializer.apply(cached.get());
                if (value != null) {
                    return CompletableFuture.completedFuture(Optional.of(value));
                }
            }
            return loader.apply(id).thenCompose(value -> {
                if (value == null) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                final String json = serializer.apply(value);
                if (json == null) {
                    return CompletableFuture.completedFuture(Optional.of(value));
                }
                return cache.putValue(key, json, ttl).thenApply(ignored -> Optional.of(value));
            });
        });
    }

    /** Writes one entity through to the cache (call only after the database write succeeded). */
    public CompletableFuture<Boolean> put(final String id, final V value) {
        final String json = serializer.apply(value);
        if (json == null) {
            return remove(id);
        }
        return cache.putValue(keyPrefix + id, json, ttl).thenApply(ignored -> true);
    }

    /** Drops the entity's cache entry. */
    public CompletableFuture<Boolean> remove(final String id) {
        return cache.removeKey(keyPrefix + id).thenApply(ignored -> true);
    }
}
