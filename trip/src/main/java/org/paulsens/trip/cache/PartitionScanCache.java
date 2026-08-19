package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.util.TripThreads;

/**
 * A partition-keyed cache (one Valkey hash per partition, field = entity id, value = entity JSON) whose partitions
 * are all populated together by a single whole-table scan. Reading one partition is a single {@code HGETALL}.
 * Blocking since the virtual-threads port; background rebuilds run on spawned virtual threads.
 *
 * <p>Unlike {@link PartitionCache} -- which loads each partition on demand and therefore needs an efficient
 * per-partition database query -- this template is for data whose partition key is <em>not</em> the database key
 * (so a single partition cannot be queried directly). The whole table is scanned once on a cold cache (foreground,
 * lock-guarded, answered from the loaded snapshot so it works even when cache writes are discarded), and once per
 * {@link CacheKeys#INDEX_SOFT_TTL} in the background thereafter. A single {@code loaded} marker covers every
 * partition. Write-through ({@link #put}) keeps partitions current between scans; point lookups
 * ({@link #getOne}) fall back to a supplied loader (a database key lookup) on a miss.</p>
 *
 * <p>Deletion is not reconciled (this template's users never delete entities in-app); out-of-band deletes are
 * healed only by {@link CacheKeys#GC_TTL} expiry or an explicit {@link #invalidate()}.</p>
 *
 * @param <V> the cached entity type.
 */
@Slf4j
@Builder
public final class PartitionScanCache<V> {
    private final CacheClient cache;
    /** Prefix for the per-partition hash keys ({@code keyPrefix + partition}). */
    private final String keyPrefix;
    /** Key of the single "loaded at" marker covering all partitions. */
    private final String loadedKey;
    @Builder.Default
    private final Duration softTtl = CacheKeys.INDEX_SOFT_TTL;
    @Builder.Default
    private final boolean softRevalidate = true;
    @Builder.Default
    private final Duration gcTtl = CacheKeys.GC_TTL;
    /** Full build: every live entity. */
    private final Supplier<List<V>> loader;
    /** Entity -> its partition. */
    private final Function<V, String> partitioner;
    /** Entity -> its field id within the partition hash. */
    private final Function<V, String> fielder;
    private final Function<V, String> serializer;
    private final Function<String, V> deserializer;
    @Builder.Default
    private final Supplier<Long> clock = System::currentTimeMillis;
    /** Injectable jitter source in [0,1) for tests; production uses {@link ThreadLocalRandom}. */
    @Builder.Default
    private final Supplier<Double> ttlJitter = PartitionScanCache::randomJitter;

    private final AtomicBoolean refreshing = new AtomicBoolean();

    /** All entities in the given partition (unordered; caller applies display order). */
    public List<V> getPartition(final String partition) {
        return ensureAndQuery(
                all -> all.stream().filter(v -> partition.equals(partitioner.apply(v))).toList(),
                () -> deserializeValues(cache.getHash(keyPrefix + partition)));
    }

    /**
     * A single entity by (partition, field). Served from the partition hash; on a miss it consults {@code loadedKey}
     * (present == authoritative "not found") and otherwise falls back to {@code pointLoader} (a database key lookup),
     * caching the result.
     */
    public Optional<V> getOne(
            final String partition, final String field, final Supplier<Optional<V>> pointLoader) {
        final String json = cache.getHashFields(keyPrefix + partition, List.of(field)).get(field);
        if (json != null) {
            return Optional.ofNullable(deserializer.apply(json));
        }
        if (cache.getValue(loadedKey).isPresent()) {
            return Optional.empty();
        }
        final Optional<V> loaded = pointLoader.get();
        loaded.ifPresent(this::put);
        return loaded;
    }

    /** Write-through of a single entity into its partition hash. */
    public boolean put(final V value) {
        final String key = keyPrefix + partitioner.apply(value);
        final String json = serializer.apply(value);
        if (json == null) {
            // Serializer regression (the DAO serializers answer null on failure, like the other cache
            // templates' contracts). Drop the possibly-stale cached entry surgically rather than NPE after
            // the database write already succeeded -- invalidate() is unsafe in local mode (see removeOne).
            log.warn("Serializer returned null for '{}'; dropping the cached entry instead", key);
            cache.removeHashField(key, fielder.apply(value));
            return false;
        }
        final boolean ok = cache.putHashField(key, fielder.apply(value), json);
        cache.expire(key, gcTtl);
        return ok;
    }

    /**
     * Whether the partitions are known to hold the WHOLE table, i.e. a scan populated them and left the loaded
     * marker behind. Without the marker the hashes hold only whatever write-throughs happened to land in them,
     * which is an incomplete answer for any caller that must see every row: with soft revalidate off (local
     * mode) nothing ever rebuilds them, so one save after a cache clear leaves a partition holding exactly that
     * one entity. Such a caller reads this and falls back to its own table scan.
     */
    public boolean isAuthoritative() {
        return cache.getValue(loadedKey).isPresent();
    }

    /** Drops every partition and the loaded marker (next read rebuilds). Admin/test path. */
    public boolean invalidate() {
        cache.clearNamespace(keyPrefix);
        return cache.removeKey(loadedKey);
    }

    /**
     * Surgical removal of one entity from its partition hash — the delete-path counterpart of {@link #put}.
     *
     * <p>This exists because {@link #invalidate()} is NOT safe everywhere: in local mode the cache client is
     * the in-memory datastore itself (soft revalidate is off precisely because a rebuild would read an empty
     * loader and wipe it), so a delete that invalidates throws away every OTHER entity too. Removing exactly
     * the deleted field is correct in both modes, and in production the shared hash propagates it to every
     * instance at once.
     */
    public boolean removeOne(final String partition, final String field) {
        return cache.removeHashField(keyPrefix + partition, field);
    }

    private List<V> deserializeValues(final Map<String, String> hash) {
        final List<V> result = new ArrayList<>(hash.size());
        for (final String json : hash.values()) {
            final V value = deserializer.apply(json);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private List<V> ensureAndQuery(
            final Function<List<V>, List<V>> inMemoryAnswer, final Supplier<List<V>> cacheAnswer) {
        final Optional<String> loadedAt = cache.getValue(loadedKey);
        if (loadedAt.isEmpty() && softRevalidate) {
            return buildAndAnswer(inMemoryAnswer);
        }
        if (softRevalidate && isSoftStale(loadedAt.orElse(null))) {
            maybeScheduleRebuild();
        }
        return cacheAnswer.get();
    }

    private List<V> buildAndAnswer(final Function<List<V>, List<V>> inMemoryAnswer) {
        final String lockKey = CacheKeys.refreshLockKey(loadedKey);
        final List<V> all = loader.get();
        if (cache.tryAcquireLock(lockKey, CacheKeys.REFRESH_LOCK_TTL)) {
            try {
                populate(all);
            } catch (final RuntimeException ex) {
                logIfFailed(ex);
            } finally {
                cache.releaseLock(lockKey);
            }
        }
        return inMemoryAnswer.apply(all);
    }

    private boolean populate(final List<V> all) {
        final Map<String, Map<String, String>> byPartition = new HashMap<>();
        for (final V v : all) {
            byPartition.computeIfAbsent(keyPrefix + partitioner.apply(v), k -> new HashMap<>())
                    .put(fielder.apply(v), serializer.apply(v));
        }
        for (final Map.Entry<String, Map<String, String>> e : byPartition.entrySet()) {
            cache.putHashFields(e.getKey(), e.getValue());
            cache.expire(e.getKey(), gcTtl);
        }
        return markLoaded();
    }

    private boolean markLoaded() {
        return cache.putValue(loadedKey, String.valueOf(clock.get()), gcTtl);
    }

    private void maybeScheduleRebuild() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        if (!RefreshPermits.tryAcquire()) {
            // At the global refresh cap: skip entirely -- a later read re-triggers (see RefreshPermits).
            refreshing.set(false);
            return;
        }
        TripThreads.start(this::runBackgroundRebuild);
    }

    private void runBackgroundRebuild() {
        try {
            final String lockKey = CacheKeys.refreshLockKey(loadedKey);
            if (!cache.tryAcquireLock(lockKey, CacheKeys.REFRESH_LOCK_TTL)) {
                return;
            }
            try {
                log.debug("Soft-revalidating partition-scan cache '{}'", keyPrefix);
                populate(loader.get());
            } catch (final RuntimeException ex) {
                logIfFailed(ex);
            } finally {
                cache.releaseLock(lockKey);
            }
        } finally {
            refreshing.set(false);
            RefreshPermits.release();
        }
    }

    private boolean isSoftStale(final String loadedAtRaw) {
        if (loadedAtRaw == null || loadedAtRaw.isBlank()) {
            return true;
        }
        try {
            return clock.get() - Long.parseLong(loadedAtRaw.trim()) >= jitteredSoftTtlMillis();
        } catch (final NumberFormatException ex) {
            return true;
        }
    }

    /** ±10% per check -- see {@code PointCache#jitteredSoftTtlMillis} for why the herd must be broken. */
    private long jitteredSoftTtlMillis() {
        return (long) (softTtl.toMillis() * (0.9 + 0.2 * ttlJitter.get()));
    }

    private static double randomJitter() {
        return ThreadLocalRandom.current().nextDouble();
    }

    private void logIfFailed(final Throwable ex) {
        if (ex != null) {
            log.error("Partition-scan cache build failed for '{}'", keyPrefix, ex);
        }
    }
}
