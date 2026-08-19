package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.util.TripThreads;

/**
 * The one shared implementation of soft-revalidate freshness: staleness math (jittered TTL) and the
 * background-refresh choreography (per-key dedup, then the global {@link RefreshPermits} cap, then a spawned
 * virtual thread, then the distributed refresh lock, then the reload). Before this class each typed cache
 * carried its own copy of both, and the copies drifted -- the 2026-08-18 incident was {@code PointCache}'s
 * copy alone putting a network read in front of the gates. Every gate runs BEFORE any thread is spawned;
 * that ordering is the invariant this class exists to protect.
 *
 * <p>Instances are throwaway views over a cache's builder-injected knobs (client, soft TTL, clock, jitter),
 * constructed per check on paths already doing I/O. Cross-call state deliberately stays with the caller: the
 * dedup map is the cache's own field (single-flight per cache, not per Revalidator instance), and the permit
 * cap is {@link RefreshPermits}' static semaphore.</p>
 */
@Slf4j
final class Revalidator {
    private final CacheClient cache;
    private final Duration softTtl;
    private final Supplier<Long> clock;
    private final Supplier<Double> ttlJitter;

    Revalidator(final CacheClient cache, final Duration softTtl, final Supplier<Long> clock,
            final Supplier<Double> ttlJitter) {
        this.cache = cache;
        this.softTtl = softTtl;
        this.clock = clock;
        this.ttlJitter = ttlJitter;
    }

    /** Marker-string staleness: null, blank, or unparseable (including the legacy {@code "1"}) is stale. */
    boolean isSoftStale(final String loadedAtRaw) {
        if (loadedAtRaw == null || loadedAtRaw.isBlank()) {
            return true;
        }
        try {
            return isSoftStale(Long.parseLong(loadedAtRaw.trim()));
        } catch (final NumberFormatException ex) {
            return true;
        }
    }

    /** Epoch staleness (envelope path); non-positive means unknown provenance and is always stale. */
    boolean isSoftStale(final long loadedAt) {
        return loadedAt <= 0 || clock.get() - loadedAt >= jitteredSoftTtlMillis();
    }

    /** The marker value writers store: the current epoch millis as a string. */
    String nowStamp() {
        return String.valueOf(clock.get());
    }

    /**
     * Gates first, spawn last: per-key dedup, then the global permit cap, and only then a thread. The spawned
     * thread takes the distributed refresh lock for {@code dataKey} (lock loss means another instance is
     * refreshing -- their lock is never released here), runs {@code reload}, and restores dedup + permit in
     * a finally. A reload failure is logged and swallowed: the stale entry keeps serving and a later read
     * re-triggers.
     */
    void schedule(final ConcurrentHashMap<String, Boolean> refreshing, final String dedupKey,
            final String dataKey, final String label, final Runnable reload) {
        if (refreshing.putIfAbsent(dedupKey, Boolean.TRUE) != null) {
            return;
        }
        if (!RefreshPermits.tryAcquire()) {
            // At the global refresh cap: skip entirely -- a later read re-triggers (see RefreshPermits).
            refreshing.remove(dedupKey);
            return;
        }
        TripThreads.start(() -> runLocked(refreshing, dedupKey, dataKey, label, reload));
    }

    private void runLocked(final ConcurrentHashMap<String, Boolean> refreshing, final String dedupKey,
            final String dataKey, final String label, final Runnable reload) {
        try {
            final String lockKey = CacheKeys.refreshLockKey(dataKey);
            if (!cache.tryAcquireLock(lockKey, CacheKeys.REFRESH_LOCK_TTL)) {
                return;
            }
            try {
                log.debug("Soft-revalidating {} '{}'", label, dataKey);
                reload.run();
            } catch (final RuntimeException ex) {
                log.error("Background {} revalidate failed for '{}'", label, dataKey, ex);
            } finally {
                cache.releaseLock(lockKey);
            }
        } finally {
            refreshing.remove(dedupKey);
            RefreshPermits.release();
        }
    }

    /**
     * ±10% around the soft TTL, drawn per check: entities loaded together otherwise cross the staleness line
     * together, and that synchronized herd is what triggered the 2026-08-04 refresh storm. Per-check jitter
     * also keeps a herd from re-synchronizing on later loads.
     */
    private long jitteredSoftTtlMillis() {
        return (long) (softTtl.toMillis() * (0.9 + 0.2 * ttlJitter.get()));
    }

    /** The production jitter source; the typed caches' builders default to this. */
    static double randomJitter() {
        return ThreadLocalRandom.current().nextDouble();
    }
}
