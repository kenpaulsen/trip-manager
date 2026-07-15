package org.paulsens.trip.cache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared worker pool on which every cache (and cache-composed DynamoDB) future completes, and on which Jackson
 * (de)serialization of cached values runs. Completing on a dedicated pool -- never on a Netty event loop -- is a
 * hard correctness rule: callers {@code join()} these futures from within Jackson deserializers (e.g.
 * {@code Trip.TripEventsDeserializer}), which would deadlock an event-loop thread waiting on its own loop.
 * The pool is unbounded (cached) for the same reason: threads on this pool block in those nested joins, and a
 * fixed pool could exhaust itself with joiners waiting on completions that need a thread from the same pool.
 */
public final class PersistenceExecutors {
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final ThreadFactory FACTORY = runnable -> {
        final Thread thread = new Thread(runnable, "trip-persist-" + COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
    private static final ExecutorService POOL = Executors.newCachedThreadPool(FACTORY);

    public static ExecutorService pool() {
        return POOL;
    }

    private PersistenceExecutors() {
    }
}
