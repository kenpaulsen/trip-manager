package org.paulsens.trip.cache;

import java.util.concurrent.Semaphore;

/**
 * Global cap on concurrently running background cache refreshes and index rebuilds.
 *
 * <p>Exists because unbounded refresh fan-out is what OOM-killed production on 2026-08-04: one admin page
 * view after a quiet hour found a whole trip's entities soft-stale at once, scheduled hundreds of
 * simultaneous revalidates, and the worker pool ballooned to ~900 platform threads. Refreshes are optional
 * freshness work, so the correct behavior at the cap is to SKIP (a later read re-triggers) -- never to
 * queue or block, which would just rebuild the backlog the cap exists to prevent.</p>
 *
 * <p>A compile-time constant, not a KnownSettings entry: settings reads go through ConfigDAO, which sits on
 * top of these caches, so consulting a setting from inside refresh scheduling would be a dependency cycle.</p>
 */
final class RefreshPermits {
    /** Plenty for freshness; small enough that a herd cannot hurt. */
    static final int MAX_CONCURRENT = 8;

    private static final Semaphore PERMITS = new Semaphore(MAX_CONCURRENT);

    /** True if a refresh slot was claimed; the caller must later call {@link #release()} exactly once. */
    static boolean tryAcquire() {
        return PERMITS.tryAcquire();
    }

    static void release() {
        PERMITS.release();
    }

    /** Test hook: how many slots are currently free. */
    static int available() {
        return PERMITS.availablePermits();
    }

    private RefreshPermits() {
    }
}
