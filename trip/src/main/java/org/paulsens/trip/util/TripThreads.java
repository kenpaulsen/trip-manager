package org.paulsens.trip.util;

import java.util.concurrent.ThreadFactory;

/**
 * Virtual-thread spawner for background work -- the successor of the old {@code PersistenceExecutors}
 * cached platform pool, which existed only because blocking in nested joins needed somewhere unbounded to
 * be survivable (it grew ~900 platform threads during the 2026-08-04 refresh storm and OOM-killed the
 * task). Virtual threads make blocking free, so "unbounded" stops being a hazard: each spawn costs a few
 * KB of heap, and admission control lives where the load actually lands (the DynamoDB connection pool,
 * the Lettuce request queue, {@code RefreshPermits}).
 *
 * <p>Threads keep the {@code trip-persist-} name prefix so thread dumps (and the Valkey integration
 * tests) can still tell cache/persistence work from everything else.</p>
 */
public final class TripThreads {
    private static final ThreadFactory FACTORY = Thread.ofVirtual().name("trip-persist-", 1).factory();

    /** Fire-and-forget: runs {@code task} on a fresh named virtual thread. */
    public static Thread start(final Runnable task) {
        final Thread thread = FACTORY.newThread(task);
        thread.start();
        return thread;
    }

    /** The named virtual-thread factory, for the rare caller that manages its own lifecycle. */
    public static ThreadFactory factory() {
        return FACTORY;
    }

    private TripThreads() {
    }
}
