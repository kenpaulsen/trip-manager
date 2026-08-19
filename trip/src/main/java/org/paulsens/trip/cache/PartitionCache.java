package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-through/write-through template for the "query by partition key" access shape (registrations by trip,
 * transactions by user, ...): one shared-cache hash per partition, field = entity id, value = entity JSON, plus a
 * {@link CacheKeys#LOADED_SENTINEL} field marking the hash as fully loaded from the database and
 * {@link CacheKeys#LOADED_AT} for soft stale-while-revalidate. Blocking since the virtual-threads port;
 * background refreshes run on spawned virtual threads.
 *
 * <p>Contract highlights:</p>
 * <ul>
 *     <li>Only a hash carrying the sentinel may answer "list all" or "not found". A hash without it may still hold
 *     valid write-through entries (read-your-writes across instances).</li>
 *     <li>Full loads <em>overlay</em> loader fields, then <em>reconcile-delete</em> entity fields that were present
 *     in a pre-load snapshot, are absent from the loader result, and still equal the snapshot value — so out-of-band
 *     Dynamo deletes heal on soft revalidate without wiping concurrent write-through of new or updated ids.</li>
 *     <li>After {@link CacheKeys#SOFT_TTL} from the last full load only (when {@code softRevalidate} is enabled),
 *     hits still return immediately and a background reload may run. Write-through does not refresh
 *     {@link CacheKeys#LOADED_AT}. Soft revalidate is read-triggered: idle keys can stay soft-stale until next
 *     access (admin clear for immediate reload). Disable soft revalidate when the cache is the sole store
 *     (local {@code InMemoryCacheClient} + empty FakeData loaders).</li>
 *     <li>{@link CacheKeys#GC_TTL} hard expire is hygiene for abandoned keys only.</li>
 *     <li>Duplicate background refreshes are safe by design (overlay + snapshot reconcile; {@code LOADED_AT} LWW).</li>
 *     <li>Cache failures never fail the operation: DynamoDB is the source of truth.</li>
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
    /** Soft revalidate age; after this, hits still serve cache and may trigger background reload. */
    @Builder.Default
    private final Duration softTtl = CacheKeys.SOFT_TTL;
    /**
     * When false, never background-revalidate (used when the cache is the sole store, e.g. local InMemory +
     * empty FakeData loaders — soft revalidate would wipe seeded data after {@link #softTtl}).
     */
    @Builder.Default
    private final boolean softRevalidate = true;
    /** Idle hygiene TTL applied after successful full loads and write-through. */
    @Builder.Default
    private final Duration gcTtl = CacheKeys.GC_TTL;
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
    /** Injectable clock for tests (epoch millis). */
    @Builder.Default
    private final Supplier<Long> clock = System::currentTimeMillis;
    /** Injectable jitter source in [0,1) for tests; production uses {@link Revalidator#randomJitter()}. */
    @Builder.Default
    private final Supplier<Double> ttlJitter = Revalidator::randomJitter;

    /** In-process single-flight for background refresh (complements distributed locks). */
    private final ConcurrentHashMap<String, Boolean> refreshing = new ConcurrentHashMap<>();

    /**
     * Returns every entity in the partition, loading (and caching) from the database on a cache miss.
     *
     * @param partition The partition id (appended to the key prefix).
     * @param loader    Runs the database query; its result list must already be filtered as callers expect.
     */
    public List<V> getAll(final String partition, final Supplier<List<V>> loader) {
        final String key = keyPrefix + partition;
        final Map<String, String> hash = cache.getHash(key);
        if (!hash.containsKey(CacheKeys.LOADED_SENTINEL)) {
            return loadAndMerge(key, loader);
        }
        maybeScheduleRefresh(key, hash.get(CacheKeys.LOADED_AT), loader);
        return toValues(hash);
    }

    /**
     * Returns one entity by id. A loaded hash answers both hits and authoritative "not found" without blocking on
     * the database; an unloaded hash can still answer a hit from a write-through entry.
     */
    public Optional<V> getOne(final String partition, final K id, final Supplier<List<V>> loader) {
        final String key = keyPrefix + partition;
        final String field = idFormatter.apply(id);
        final Map<String, String> found =
                cache.getHashFields(key, List.of(CacheKeys.LOADED_SENTINEL, CacheKeys.LOADED_AT, field));
        final boolean loaded = found.containsKey(CacheKeys.LOADED_SENTINEL);
        if (loaded) {
            maybeScheduleRefresh(key, found.get(CacheKeys.LOADED_AT), loader);
        }
        final String json = found.get(field);
        if (json != null) {
            final V value = deserializer.apply(json);
            if (value != null) {
                return Optional.of(value);
            }
        }
        if (loaded) {
            return Optional.empty();
        }
        return findById(getAll(partition, loader), id);
    }

    private Optional<V> findById(final List<V> list, final K id) {
        return list.stream().filter(value -> id.equals(idGetter.apply(value))).findAny();
    }

    /** Writes one entity through to the cache (call only after the database write succeeded). */
    public boolean put(final String partition, final V value) {
        final String key = keyPrefix + partition;
        final String json = serializer.apply(value);
        if (json == null) {
            return invalidate(partition);
        }
        final String field = idFormatter.apply(idGetter.apply(value));
        afterWriteThrough(key, cache.putHashField(key, field, json));
        return true;
    }

    /** Removes one entity from the cache (soft-deletes that callers filter out). */
    public boolean remove(final String partition, final K id) {
        final String key = keyPrefix + partition;
        return afterWriteThrough(key, cache.removeHashField(key, idFormatter.apply(id)));
    }

    /** Drops the partition's hash entirely (next read reloads from the database). */
    public boolean invalidate(final String partition) {
        return cache.removeKey(keyPrefix + partition);
    }

    private boolean afterWriteThrough(final String key, final boolean ok) {
        if (!ok) {
            return recover(key);
        }
        // Slide GC idle clock only — does not update LOADED_AT (soft revalidate age).
        cache.expire(key, gcTtl);
        return true;
    }

    private void maybeScheduleRefresh(final String key, final String loadedAtRaw, final Supplier<List<V>> loader) {
        final Revalidator revalidator = new Revalidator(cache, softTtl, clock, ttlJitter);
        if (!softRevalidate || !revalidator.isSoftStale(loadedAtRaw)) {
            return;
        }
        // Duplicate refreshes after lock TTL are safe: overlay + snapshot reconcile; LOADED_AT is LWW.
        revalidator.schedule(refreshing, key, key, "partition", () -> loadAndMerge(key, loader));
    }

    private List<V> loadAndMerge(final String key, final Supplier<List<V>> loader) {
        // Snapshot before Dynamo so concurrent write-through of new fields is not reconcile-deleted.
        // Reconcile-delete only when the hash was already fully loaded (sentinel present); a first load over
        // write-through-only fields must overlay without HDEL (local FakeData / empty scan must not wipe cache).
        final Map<String, String> preHash = cache.getHash(key);
        final List<V> list = loader.get();
        return mergeLoaderResult(key, list, preHash);
    }

    private Map<String, String> entitySnapshot(final Map<String, String> hash) {
        final Map<String, String> snapshot = new HashMap<>();
        for (final Map.Entry<String, String> entry : hash.entrySet()) {
            if (!isMetaField(entry.getKey())) {
                snapshot.put(entry.getKey(), entry.getValue());
            }
        }
        return snapshot;
    }

    private static boolean isMetaField(final String field) {
        return CacheKeys.LOADED_SENTINEL.equals(field) || CacheKeys.LOADED_AT.equals(field);
    }

    private List<V> mergeLoaderResult(final String key, final List<V> list, final Map<String, String> preHash) {
        final boolean wasFullyLoaded = preHash.containsKey(CacheKeys.LOADED_SENTINEL);
        final Map<String, String> preSnapshot = wasFullyLoaded ? entitySnapshot(preHash) : Map.of();
        final Map<String, String> fields = new HashMap<>();
        fields.put(CacheKeys.LOADED_SENTINEL, CacheKeys.LOADED_VALUE);
        fields.put(CacheKeys.LOADED_AT, String.valueOf(clock.get()));
        final Map<String, String> loaderFields = new HashMap<>();
        for (final V value : list) {
            final String json = serializer.apply(value);
            if (json != null) {
                final String field = idFormatter.apply(idGetter.apply(value));
                fields.put(field, json);
                loaderFields.put(field, json);
            }
        }
        boolean ok = cache.putHashFields(key, fields);
        if (ok) {
            ok = maybeReconcileDeletes(key, wasFullyLoaded, preSnapshot, loaderFields);
        } else {
            cache.removeKey(key);
        }
        if (!ok) {
            return sorted(list);
        }
        cache.expire(key, gcTtl);
        final Map<String, String> merged = cache.getHash(key);
        return merged.containsKey(CacheKeys.LOADED_SENTINEL) ? toValues(merged) : sorted(list);
    }

    private boolean maybeReconcileDeletes(
            final String key,
            final boolean wasFullyLoaded,
            final Map<String, String> preSnapshot,
            final Map<String, String> loaderFields) {
        if (!wasFullyLoaded) {
            return true;
        }
        return reconcileDeletes(key, preSnapshot, loaderFields);
    }

    /**
     * HDEL entity fields that were in the pre-load snapshot, are absent from the loader result, and still hold the
     * snapshot value (so concurrent write-through of the same id is not deleted).
     */
    private boolean reconcileDeletes(
            final String key,
            final Map<String, String> preSnapshot,
            final Map<String, String> loaderFields) {
        final List<String> candidates = new ArrayList<>();
        for (final String field : preSnapshot.keySet()) {
            if (!loaderFields.containsKey(field)) {
                candidates.add(field);
            }
        }
        if (candidates.isEmpty()) {
            return true;
        }
        final Map<String, String> current = cache.getHashFields(key, candidates);
        for (final String field : candidates) {
            final String cur = current.get(field);
            if (cur != null && Objects.equals(preSnapshot.get(field), cur)) {
                cache.removeHashField(key, field);
            }
        }
        return true;
    }

    private List<V> toValues(final Map<String, String> hash) {
        final List<V> result = new ArrayList<>(hash.size());
        for (final Map.Entry<String, String> entry : hash.entrySet()) {
            appendEntityField(entry.getKey(), entry.getValue(), result);
        }
        result.sort(order);
        return result;
    }

    private void appendEntityField(final String field, final String json, final List<V> result) {
        if (isMetaField(field)) {
            return;
        }
        final V value = deserializer.apply(json);
        if (value != null) {
            result.add(value);
        }
    }

    private List<V> sorted(final List<V> list) {
        final List<V> result = new ArrayList<>(list);
        result.sort(order);
        return result;
    }

    private boolean recover(final String key) {
        log.error("Cache write-through failed for '{}'; dropping the key as a precaution.", key);
        cache.removeKey(key);
        // Always true, faithfully: a cache-side failure never fails the operation (DynamoDB already has it).
        return true;
    }
}
