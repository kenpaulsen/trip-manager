package org.paulsens.trip.cache;

import java.time.Duration;

/**
 * Whether a cache can actually hold state, asked by writing a value and reading it back. "A cache is
 * configured" is not the same question: {@code NoopCacheClient} grants every lock and remembers nothing, and a
 * cache that is down refuses every lock. The daily schedulers ask before trusting what a lock request answered.
 */
public final class CacheProbes {

    private CacheProbes() {
    }

    /** True when {@code probeKey} can be written and read back; the value expires after a minute. */
    public static boolean remembers(final CacheClient cache, final String probeKey) {
        final String token = Long.toString(System.nanoTime());
        return cache.putValue(probeKey, token, Duration.ofMinutes(1))
                && cache.getValue(probeKey).filter(token::equals).isPresent();
    }
}
