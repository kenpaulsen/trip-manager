package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.util.TripThreads;

/**
 * Point-read template (trip events): string value + sibling {@code :at} epoch for soft revalidate.
 * Blocking since the virtual-threads port; a hit still returns without waiting for the freshness check,
 * which runs on a spawned virtual thread. Background reload with null removes the entry (out-of-band
 * deletes heal). Soft revalidate is read-triggered. {@link CacheKeys#GC_TTL} is hygiene only. Duplicate
 * refreshes are safe by design.
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

    public Optional<V> get(final String id, final Function<String, V> loader) {
        final String key = keyPrefix + id;
        final Optional<String> cached = cache.getValue(key);
        if (cached.isPresent()) {
            final V value = deserializer.apply(cached.get());
            if (value != null) {
                // The freshness check costs a second cache read; a spawned thread pays it so the hit
                // returns immediately -- and a degraded cache cannot double a reader's timeout exposure.
                if (softRevalidate) {
                    TripThreads.start(() -> checkFreshness(id, key, loader));
                }
                return Optional.of(value);
            }
        }
        final V value = loader.apply(id);
        if (value == null) {
            return Optional.empty();
        }
        put(id, value);
        return Optional.of(value);
    }

    /**
     * Always returns true: callers (DAO save paths) treat this as the operation result, and a cache
     * failure must never fail an operation DynamoDB already accepted -- DynamoDB is the source of truth.
     *
     * <p>On a failed SET the existing entry is left alone: a failed put means the cache itself is
     * unhealthy, and the old {@code remove(id)} here amplified exactly that state -- during the 2026-08-04
     * brown-out every timed-out refresh SET issued an UNLINK against the same drowning cache, evicting
     * healthy entries and turning "slow" into a DynamoDB miss-stampede. A stale-but-present entry heals
     * via soft revalidate.</p>
     */
    public boolean put(final String id, final V value) {
        final String json = serializer.apply(value);
        if (json == null) {
            return remove(id);
        }
        final String key = keyPrefix + id;
        if (cache.putValue(key, json, gcTtl)) {
            cache.putValue(CacheKeys.pointAtKey(key), String.valueOf(clock.get()), gcTtl);
        }
        return true;
    }

    public boolean remove(final String id) {
        final String key = keyPrefix + id;
        cache.removeKey(key);
        cache.removeKey(CacheKeys.pointAtKey(key));
        return true;
    }

    /** Runs on a spawned thread: reads the freshness epoch and, when soft-stale, refreshes in place. */
    private void checkFreshness(final String id, final String key, final Function<String, V> loader) {
        final String loadedAtRaw = cache.getValue(CacheKeys.pointAtKey(key)).orElse(null);
        if (!isSoftStale(loadedAtRaw)) {
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
        runBackgroundRefresh(id, key, loader);
    }

    private void runBackgroundRefresh(final String id, final String key, final Function<String, V> loader) {
        try {
            final String lockKey = CacheKeys.refreshLockKey(key);
            // Lock loss means another instance is refreshing; release of THEIR lock must not happen here.
            if (!cache.tryAcquireLock(lockKey, CacheKeys.REFRESH_LOCK_TTL)) {
                return;
            }
            try {
                log.debug("Soft-revalidating point key '{}'", key);
                applyBackgroundLoad(id, loader.apply(id));
            } catch (final RuntimeException ex) {
                log.error("Background point revalidate failed for '{}'", key, ex);
            } finally {
                cache.releaseLock(lockKey);
            }
        } finally {
            refreshing.remove(id);
            RefreshPermits.release();
        }
    }

    private void applyBackgroundLoad(final String id, final V value) {
        if (value == null) {
            remove(id);
        } else {
            put(id, value);
        }
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
