package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * A partition-keyed cache (one Valkey hash per partition, field = entity id, value = entity JSON) whose partitions
 * are all populated together by a single whole-table scan. Reading one partition is a single {@code HGETALL}.
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
    private final Supplier<CompletableFuture<List<V>>> loader;
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
    public CompletableFuture<List<V>> getPartition(final String partition) {
        return ensureAndQuery(
                all -> all.stream().filter(v -> partition.equals(partitioner.apply(v))).toList(),
                () -> cache.getHash(keyPrefix + partition).thenApply(this::deserializeValues));
    }

    /**
     * A single entity by (partition, field). Served from the partition hash; on a miss it consults {@code loadedKey}
     * (present == authoritative "not found") and otherwise falls back to {@code pointLoader} (a database key lookup),
     * caching the result.
     */
    public CompletableFuture<Optional<V>> getOne(
            final String partition, final String field, final Supplier<CompletableFuture<Optional<V>>> pointLoader) {
        return cache.getHashFields(keyPrefix + partition, List.of(field)).thenCompose(found -> {
            final String json = found.get(field);
            if (json != null) {
                return CompletableFuture.completedFuture(Optional.ofNullable(deserializer.apply(json)));
            }
            return cache.getValue(loadedKey).thenCompose(marker -> marker.isPresent()
                    ? CompletableFuture.completedFuture(Optional.<V>empty())
                    : pointLoader.get().thenCompose(this::cacheIfPresent));
        });
    }

    /** Write-through of a single entity into its partition hash. */
    public CompletableFuture<Boolean> put(final V value) {
        final String key = keyPrefix + partitioner.apply(value);
        return cache.putHashField(key, fielder.apply(value), serializer.apply(value))
                .thenCompose(ok -> cache.expire(key, gcTtl).thenApply(ignored -> ok));
    }

    /** Drops every partition and the loaded marker (next read rebuilds). Admin/test path. */
    public CompletableFuture<Boolean> invalidate() {
        return cache.clearNamespace(keyPrefix).thenCompose(ignored -> cache.removeKey(loadedKey));
    }

    private CompletableFuture<Optional<V>> cacheIfPresent(final Optional<V> loaded) {
        return loaded.isPresent()
                ? put(loaded.get()).thenApply(ignored -> loaded)
                : CompletableFuture.completedFuture(loaded);
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

    private CompletableFuture<List<V>> ensureAndQuery(
            final Function<List<V>, List<V>> inMemoryAnswer, final Supplier<CompletableFuture<List<V>>> cacheAnswer) {
        return cache.getValue(loadedKey).thenCompose(loadedAt -> {
            if (loadedAt.isEmpty() && softRevalidate) {
                return buildAndAnswer(inMemoryAnswer);
            }
            if (softRevalidate && isSoftStale(loadedAt.orElse(null))) {
                maybeScheduleRebuild();
            }
            return cacheAnswer.get();
        });
    }

    private CompletableFuture<List<V>> buildAndAnswer(final Function<List<V>, List<V>> inMemoryAnswer) {
        final String lockKey = CacheKeys.refreshLockKey(loadedKey);
        return loader.get().thenCompose(all -> cache.tryAcquireLock(lockKey, CacheKeys.REFRESH_LOCK_TTL)
                .thenCompose(acquired -> acquired ? populate(all).handle((ok, ex) -> {
                    logIfFailed(ex);
                    cache.releaseLock(lockKey);
                    return ok;
                }) : CompletableFuture.completedFuture(false))
                .thenApply(ignored -> inMemoryAnswer.apply(all)));
    }

    private CompletableFuture<Boolean> populate(final List<V> all) {
        final Map<String, Map<String, String>> byPartition = new HashMap<>();
        for (final V v : all) {
            byPartition.computeIfAbsent(keyPrefix + partitioner.apply(v), k -> new HashMap<>())
                    .put(fielder.apply(v), serializer.apply(v));
        }
        CompletableFuture<Boolean> chain = CompletableFuture.completedFuture(true);
        for (final Map.Entry<String, Map<String, String>> e : byPartition.entrySet()) {
            chain = chain.thenCompose(ok -> cache.putHashFields(e.getKey(), e.getValue())
                    .thenCompose(ignored -> cache.expire(e.getKey(), gcTtl)));
        }
        return chain.thenCompose(ok -> markLoaded());
    }

    private CompletableFuture<Boolean> markLoaded() {
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
        final String lockKey = CacheKeys.refreshLockKey(loadedKey);
        cache.tryAcquireLock(lockKey, CacheKeys.REFRESH_LOCK_TTL)
                .thenComposeAsync(this::runBackgroundRebuild, PersistenceExecutors.pool())
                .handle((ignored, ex) -> finishBackgroundRebuild(lockKey, ex));
    }

    private Void finishBackgroundRebuild(final String lockKey, final Throwable ex) {
        logIfFailed(ex);
        cache.releaseLock(lockKey);
        refreshing.set(false);
        RefreshPermits.release();
        return null;
    }

    private CompletableFuture<Boolean> runBackgroundRebuild(final Boolean acquired) {
        if (!Boolean.TRUE.equals(acquired)) {
            return CompletableFuture.completedFuture(false);
        }
        log.debug("Soft-revalidating partition-scan cache '{}'", keyPrefix);
        return loader.get().thenCompose(this::populate);
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
