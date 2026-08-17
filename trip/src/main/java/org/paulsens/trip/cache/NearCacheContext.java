package org.paulsens.trip.cache;

import java.util.function.Supplier;

/**
 * Carries the caller's {@link Cached} choice from the {@code DAO} facade down to {@link NearCacheClient}
 * without threading a parameter through the twenty sub-DAOs and six typed caches in between.
 *
 * <p>A {@link ScopedValue} rather than a ThreadLocal for the same reason {@code RequestContext} is one:
 * the binding cannot leak past the read it was made for. Background threads (typed-cache soft
 * revalidates, index rebuilds, the near-cache's own health checks) run UNBOUND, which
 * {@link #cacheAllowed()} treats as {@link Cached#NO} -- exactly right, because background refreshes
 * exist to read the shared cache, not a heap copy of it.
 */
public final class NearCacheContext {

    static final ScopedValue<Cached> SCOPE = ScopedValue.newInstance();

    /** Runs {@code read} with the caller's freshness tolerance bound; the DAO facade's one entry point. */
    public static <T> T call(final Cached cached, final Supplier<T> read) {
        return ScopedValue.where(SCOPE, cached).call(read::get);
    }

    /** Whether the current thread's read may be served from the near-cache. */
    static boolean cacheAllowed() {
        return SCOPE.isBound() && SCOPE.get() == Cached.YES;
    }

    private NearCacheContext() {
    }
}
