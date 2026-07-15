package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Builder;

/**
 * Read-through/write-through template for the "whole table scanned into memory" access shape (people, trips,
 * privs): a single shared-cache hash for the entire table. Same contract as {@link PartitionCache} -- this is that
 * template with one fixed partition.
 *
 * @param <K> The entity id type (hash field).
 * @param <V> The entity type.
 */
public class FullTableCache<K, V> {
    private final PartitionCache<K, V> delegate;

    @Builder
    private FullTableCache(final CacheClient cache, final String key, final Duration ttl,
            final Function<V, K> idGetter, final Function<K, String> idFormatter,
            final Function<V, String> serializer, final Function<String, V> deserializer,
            final Comparator<V> order) {
        this.delegate = PartitionCache.<K, V>builder()
                .cache(cache)
                .keyPrefix(key)
                .ttl(ttl == null ? CacheKeys.DEFAULT_TTL : ttl)
                .idGetter(idGetter)
                .idFormatter(idFormatter)
                .serializer(serializer)
                .deserializer(deserializer)
                .order(order)
                .build();
    }

    /** Returns every entity in the table, loading (and caching) via the full scan on a cache miss. */
    public CompletableFuture<List<V>> getAll(final Supplier<CompletableFuture<List<V>>> loader) {
        return delegate.getAll("", loader);
    }

    /** Returns one entity by id (loaded hash answers "not found" without a database call). */
    public CompletableFuture<Optional<V>> getOne(final K id, final Supplier<CompletableFuture<List<V>>> loader) {
        return delegate.getOne("", id, loader);
    }

    /** Writes one entity through to the cache (call only after the database write succeeded). */
    public CompletableFuture<Boolean> put(final V value) {
        return delegate.put("", value);
    }

    /** Removes one entity from the cache (soft-deletes that callers filter out). */
    public CompletableFuture<Boolean> remove(final K id) {
        return delegate.remove("", id);
    }

    /** Drops the whole table hash (next read rescans the database). */
    public CompletableFuture<Boolean> invalidate() {
        return delegate.invalidate("");
    }
}
