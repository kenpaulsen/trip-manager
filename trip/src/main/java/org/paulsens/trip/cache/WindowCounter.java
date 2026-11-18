package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One fixed-window counter: counts a hit on a key in the shared cache, and FAILS OPEN to a per-server map when the
 * cache answers nothing. The counting half of every rate limit ({@code ChatRateLimiter}, {@code PublicRateLimiter}),
 * in one place so a fix to the window's TTL or the fallback reaches both.
 *
 * <p>The window index belongs in the KEY (the caller's {@code CacheKeys} method puts it there), so an expired
 * window is simply a key nobody reads again; the TTL is two windows so a key outlives its own window. The fallback
 * is per server, the honest limit of a local count, so treat its numbers as a ceiling, not a measurement. A limiter
 * holds its counter in a STATIC field, so the per-request instances share it and instance churn cannot hand a
 * caller a fresh allowance.
 */
public final class WindowCounter {

    private final ConcurrentHashMap<String, AtomicInteger> fallback = new ConcurrentHashMap<>();
    /** The fallback forgets everything at this size; 0 for no bound (keys a caller cannot choose). */
    private final int maxFallbackKeys;

    /** @param maxFallbackKeys a bound on the fallback's size, needed when callers choose its keys; 0 for none */
    public WindowCounter(final int maxFallbackKeys) {
        this.maxFallbackKeys = Math.max(0, maxFallbackKeys);
    }

    /** Counts one hit on {@code key} in a window of {@code windowSeconds}; the count so far in that window. */
    public long count(final CacheClient cache, final String key, final int windowSeconds) {
        final Duration ttl = Duration.ofSeconds(Math.max(1L, windowSeconds * 2L));
        return cache.increment(key, 1, ttl).orElseGet(() -> fallbackCount(key));
    }

    /** Drops a key's fallback count (a counter the caller has just spent and reset in the cache). */
    public void forget(final String key) {
        fallback.remove(key);
    }

    /** Clears the fallback. Only for tests: a limiter's counter is static, so it would carry counts across them. */
    public void clearForTest() {
        fallback.clear();
    }

    private long fallbackCount(final String key) {
        if (maxFallbackKeys > 0 && fallback.size() >= maxFallbackKeys) {
            // Bounded, not exact: forgetting every count now and then only ever errs toward serving.
            fallback.clear();
        }
        return fallback.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
    }
}
