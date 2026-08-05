package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Point-read template (trip events): string value + sibling {@code :at} epoch for soft revalidate.
 * Background reload with null removes the entry (out-of-band deletes heal). Soft revalidate is read-triggered.
 * {@link CacheKeys#GC_TTL} is hygiene only. Duplicate refreshes are safe by design.
 *
 * @param <V> The entity type.
 */
@Slf4j
@Builder
public class PointCache<V> {
    private final CacheClient cache;
    private final String keyPrefix;
    @Builder.Default
    private final Duration softTtl = CacheKeys.SOFT_TTL;
    @Builder.Default
    private final boolean softRevalidate = true;
    @Builder.Default
    private final Duration gcTtl = CacheKeys.GC_TTL;
    private final Function<V, String> serializer;
    private final Function<String, V> deserializer;
    @Builder.Default
    private final Supplier<Long> clock = System::currentTimeMillis;
    /** Injectable jitter source in [0,1) for tests; production uses {@link ThreadLocalRandom}. */
    @Builder.Default
    private final Supplier<Double> ttlJitter = PointCache::randomJitter;

    private final ConcurrentHashMap<String, Boolean> refreshing = new ConcurrentHashMap<>();

    public CompletableFuture<Optional<V>> get(final String id, final Function<String, CompletableFuture<V>> loader) {
        final String key = keyPrefix + id;
        final String atKey = CacheKeys.pointAtKey(key);
        return cache.getValue(key).thenCompose(cached -> resolveGet(id, key, atKey, cached, loader));
    }

    public CompletableFuture<Boolean> put(final String id, final V value) {
        final String json = serializer.apply(value);
        if (json == null) {
            return remove(id);
        }
        final String key = keyPrefix + id;
        final String atKey = CacheKeys.pointAtKey(key);
        final String now = String.valueOf(clock.get());
        // Always completes true: callers (DAO save paths) treat this as the operation result, and a cache
        // failure must never fail an operation DynamoDB already accepted -- DynamoDB is the source of truth.
        return cache.putValue(key, json, gcTtl)
                .thenCompose(ok -> afterPutValue(atKey, now, ok))
                .thenApply(ignored -> true);
    }

    public CompletableFuture<Boolean> remove(final String id) {
        final String key = keyPrefix + id;
        return cache.removeKey(key)
                .thenCompose(ignored -> cache.removeKey(CacheKeys.pointAtKey(key)))
                .thenApply(ignored -> true);
    }

    private CompletableFuture<Optional<V>> resolveGet(
            final String id,
            final String key,
            final String atKey,
            final Optional<String> cached,
            final Function<String, CompletableFuture<V>> loader) {
        if (cached.isPresent()) {
            final V value = deserializer.apply(cached.get());
            if (value != null) {
                cache.getValue(atKey).thenAccept(at -> scheduleRefreshFromAt(id, key, at, loader));
                return CompletableFuture.completedFuture(Optional.of(value));
            }
        }
        return loader.apply(id).thenCompose(value -> cacheMissLoad(id, value));
    }

    private void scheduleRefreshFromAt(
            final String id,
            final String key,
            final Optional<String> at,
            final Function<String, CompletableFuture<V>> loader) {
        maybeScheduleRefresh(id, key, at.orElse(null), loader);
    }

    private CompletableFuture<Optional<V>> cacheMissLoad(final String id, final V value) {
        if (value == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return put(id, value).thenApply(ignored -> Optional.of(value));
    }

    /**
     * On success, stamps the freshness epoch. On failure, leaves any existing entry alone: a failed SET
     * means the cache itself is unhealthy (the result mapper cannot fail on a healthy reply), and the old
     * {@code remove(id)} here amplified exactly that state -- during the 2026-08-04 brown-out every
     * timed-out refresh SET issued an UNLINK against the same drowning cache, evicting healthy entries and
     * turning "slow" into a DynamoDB miss-stampede. A stale-but-present entry heals via soft revalidate,
     * and DynamoDB stays the source of truth.
     */
    private CompletableFuture<Boolean> afterPutValue(final String atKey, final String now, final boolean ok) {
        if (!ok) {
            return CompletableFuture.completedFuture(false);
        }
        return cache.putValue(atKey, now, gcTtl).thenApply(ignored -> true);
    }

    private void maybeScheduleRefresh(
            final String id,
            final String key,
            final String loadedAtRaw,
            final Function<String, CompletableFuture<V>> loader) {
        if (!softRevalidate || !isSoftStale(loadedAtRaw)) {
            return;
        }
        if (refreshing.putIfAbsent(id, Boolean.TRUE) != null) {
            return;
        }
        if (!RefreshPermits.tryAcquire()) {
            // At the global refresh cap: skip entirely -- a later read re-triggers (see RefreshPermits).
            refreshing.remove(id);
            return;
        }
        final String lockKey = CacheKeys.refreshLockKey(key);
        cache.tryAcquireLock(lockKey, CacheKeys.REFRESH_LOCK_TTL)
                .thenComposeAsync(acquired -> runBackgroundRefresh(id, key, lockKey, acquired, loader),
                        PersistenceExecutors.pool());
    }

    private CompletableFuture<Void> runBackgroundRefresh(
            final String id,
            final String key,
            final String lockKey,
            final Boolean acquired,
            final Function<String, CompletableFuture<V>> loader) {
        if (!Boolean.TRUE.equals(acquired)) {
            refreshing.remove(id);
            RefreshPermits.release();
            return CompletableFuture.completedFuture(null);
        }
        log.debug("Soft-revalidating point key '{}'", key);
        return loader.apply(id)
                .thenCompose(value -> applyBackgroundLoad(id, value))
                .handle((ignored, ex) -> finishBackgroundRefresh(id, key, lockKey, ex));
    }

    private CompletableFuture<Boolean> applyBackgroundLoad(final String id, final V value) {
        if (value == null) {
            return remove(id);
        }
        return put(id, value);
    }

    private Void finishBackgroundRefresh(
            final String id, final String key, final String lockKey, final Throwable ex) {
        if (ex != null) {
            log.error("Background point revalidate failed for '{}'", key, ex);
        }
        cache.releaseLock(lockKey);
        refreshing.remove(id);
        RefreshPermits.release();
        return null;
    }

    private boolean isSoftStale(final String loadedAtRaw) {
        if (loadedAtRaw == null || loadedAtRaw.isBlank()) {
            return true;
        }
        try {
            final long loadedAt = Long.parseLong(loadedAtRaw.trim());
            return clock.get() - loadedAt >= jitteredSoftTtlMillis();
        } catch (final NumberFormatException ex) {
            return true;
        }
    }

    /**
     * ±10% around {@code softTtl}, drawn per check: entities loaded together (one trip's events) otherwise
     * cross the staleness line together, and that synchronized herd is what triggered the 2026-08-04
     * refresh storm. Per-check jitter also keeps a herd from re-synchronizing on later loads.
     */
    private long jitteredSoftTtlMillis() {
        return (long) (softTtl.toMillis() * (0.9 + 0.2 * ttlJitter.get()));
    }

    private static double randomJitter() {
        return ThreadLocalRandom.current().nextDouble();
    }
}
