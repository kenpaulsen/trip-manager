package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-through/write-through template for the "query by partition key" access shape (registrations by trip,
 * transactions by user, ...): one shared-cache hash per partition, field = entity id, value = entity JSON, plus a
 * {@link CacheKeys#LOADED_SENTINEL} field marking the hash as fully loaded from the database.
 *
 * <p>Contract highlights:</p>
 * <ul>
 *     <li>Only a hash carrying the sentinel may answer "list all" or "not found". A hash without it may still hold
 *     valid write-through entries (which is what preserves read-your-writes across instances).</li>
 *     <li>Loads <em>overlay</em> the hash (no clear-first): a concurrent write-through entry is never wiped by an
 *     eventually-consistent scan/query that predates it. Phantom fields age out with the TTL.</li>
 *     <li>Cache failures never fail the operation: reads fall back to the loader's result, writes log and continue
 *     (DynamoDB is the source of truth; staleness is bounded by the TTL).</li>
 * </ul>
 *
 * @param <K> The entity id type (hash field).
 * @param <V> The entity type.
 */
@Slf4j
@Builder
public class PartitionCache<K, V> {
    private final CacheClient cache;
    /** Full cache-key prefix for a partition's hash, e.g. {@code t1:reg:} (partition id is appended). */
    private final String keyPrefix;
    @Builder.Default
    private final Duration ttl = CacheKeys.DEFAULT_TTL;
    /** Extracts the entity id used as the hash field. */
    private final Function<V, K> idGetter;
    /** Renders the entity id as a hash field name. */
    private final Function<K, String> idFormatter;
    /** Serializes an entity to its cache JSON; must return null (never throw) on failure. */
    private final Function<V, String> serializer;
    /** Parses cache JSON back to an entity; must return null (never throw) on failure. */
    private final Function<String, V> deserializer;
    /** Caller-visible ordering of {@link #getAll}. */
    private final Comparator<V> order;

    /**
     * Returns every entity in the partition, loading (and caching) from the database on a cache miss.
     *
     * @param partition The partition id (appended to the key prefix).
     * @param loader    Runs the database query; its result list must already be filtered as callers expect.
     */
    public CompletableFuture<List<V>> getAll(final String partition, final Supplier<CompletableFuture<List<V>>> loader) {
        final String key = keyPrefix + partition;
        return cache.getHash(key).thenCompose(hash -> hash.containsKey(CacheKeys.LOADED_SENTINEL)
                ? CompletableFuture.completedFuture(toValues(hash))
                : loadAndMerge(key, loader));
    }

    /**
     * Returns one entity by id. A loaded hash answers both hits and (crucially) authoritative "not found" without
     * touching the database; an unloaded hash can still answer a hit from a write-through entry.
     */
    public CompletableFuture<Optional<V>> getOne(
            final String partition, final K id, final Supplier<CompletableFuture<List<V>>> loader) {
        final String key = keyPrefix + partition;
        final String field = idFormatter.apply(id);
        return cache.getHashFields(key, List.of(CacheKeys.LOADED_SENTINEL, field)).thenCompose(found -> {
            final String json = found.get(field);
            if (json != null) {
                final V value = deserializer.apply(json);
                if (value != null) {
                    return CompletableFuture.completedFuture(Optional.of(value));
                }
            }
            if (found.containsKey(CacheKeys.LOADED_SENTINEL)) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            return getAll(partition, loader).thenApply(list -> list.stream()
                    .filter(value -> id.equals(idGetter.apply(value)))
                    .findAny());
        });
    }

    /** Writes one entity through to the cache (call only after the database write succeeded). */
    public CompletableFuture<Boolean> put(final String partition, final V value) {
        final String key = keyPrefix + partition;
        final String json = serializer.apply(value);
        if (json == null) {
            // Can't mirror the write; drop the whole hash so nothing stale is served.
            return invalidate(partition);
        }
        return cache.putHashField(key, idFormatter.apply(idGetter.apply(value)), json)
                .thenCompose(ok -> ok ? cache.expireIfNoTtl(key, ttl) : recover(key))
                .thenApply(ignored -> true);
    }

    /** Removes one entity from the cache (soft-deletes that callers filter out). */
    public CompletableFuture<Boolean> remove(final String partition, final K id) {
        final String key = keyPrefix + partition;
        return cache.removeHashField(key, idFormatter.apply(id))
                .thenCompose(ok -> ok ? CompletableFuture.completedFuture(true) : recover(key));
    }

    /** Drops the partition's hash entirely (next read reloads from the database). */
    public CompletableFuture<Boolean> invalidate(final String partition) {
        return cache.removeKey(keyPrefix + partition);
    }

    private CompletableFuture<List<V>> loadAndMerge(final String key, final Supplier<CompletableFuture<List<V>>> loader) {
        return loader.get().thenCompose(list -> {
            final Map<String, String> fields = new HashMap<>();
            fields.put(CacheKeys.LOADED_SENTINEL, CacheKeys.LOADED_VALUE);
            for (final V value : list) {
                final String json = serializer.apply(value);
                if (json != null) {
                    fields.put(idFormatter.apply(idGetter.apply(value)), json);
                }
            }
            return cache.putHashFields(key, fields)
                    // A loaded hash must never outlive its TTL refresh: if EXPIRE can't be confirmed, drop the
                    // key rather than risk an immortal sentinel that would hide future database changes.
                    .thenCompose(ok -> ok ? cache.expire(key, ttl) : CompletableFuture.completedFuture(false))
                    .thenCompose(ok -> ok
                            // Re-read so write-through entries that beat this load are merged into the answer.
                            ? cache.getHash(key)
                            : cache.removeKey(key).thenApply(ignored -> Map.<String, String>of()))
                    .thenApply(hash -> hash.containsKey(CacheKeys.LOADED_SENTINEL) ? toValues(hash) : sorted(list));
        });
    }

    private List<V> toValues(final Map<String, String> hash) {
        final List<V> result = new ArrayList<>(hash.size());
        hash.forEach((field, json) -> {
            if (!CacheKeys.LOADED_SENTINEL.equals(field)) {
                final V value = deserializer.apply(json);
                if (value != null) {
                    result.add(value);
                }
            }
        });
        result.sort(order);
        return result;
    }

    private List<V> sorted(final List<V> list) {
        final List<V> result = new ArrayList<>(list);
        result.sort(order);
        return result;
    }

    private CompletableFuture<Boolean> recover(final String key) {
        // The cache write failed (cache down or partitioned). Best effort: drop the key so a half-updated hash is
        // never served; if that fails too, the TTL bounds the staleness. The database write already succeeded.
        log.error("Cache write-through failed for '{}'; dropping the key as a precaution.", key);
        return cache.removeKey(key).thenApply(ignored -> true);
    }
}
