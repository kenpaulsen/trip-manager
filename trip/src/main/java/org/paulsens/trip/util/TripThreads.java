package org.paulsens.trip.util;

import java.util.concurrent.ThreadFactory;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.RequestContext;

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
 *
 * <p>Every spawn runs inside {@link CacheLane#runBackground}, so its Valkey commands ride the background
 * connection (small queue, sheds early) and can never starve the request path. This deliberately includes
 * chat nudge delivery (the pub/sub hand-off spawns here): a shed nudge degrades to the client's poll
 * fallback, which is the designed behavior under cache pressure.</p>
 */
public final class TripThreads {
    private static final ThreadFactory FACTORY = Thread.ofVirtual().name("trip-persist-", 1).factory();

    /** Fire-and-forget: runs {@code task} on a fresh named virtual thread, in the background cache lane. */
    public static Thread start(final Runnable task) {
        final Thread thread = newBackgroundThread(task);
        thread.start();
        return thread;
    }

    private static Thread newBackgroundThread(final Runnable task) {
        return FACTORY.newThread(() -> CacheLane.runBackground(task));
    }

    /**
     * Fire-and-forget with an explicit actor: plain spawns do NOT inherit ScopedValues (only
     * StructuredTaskScope forks do), so work that outlives its request re-binds the identity it should be
     * audited under. Background work nobody asked for binds {@link AuditActor#system()}.
     */
    public static Thread startAs(final AuditActor actor, final Runnable task) {
        return start(() -> runBound(actor, task));
    }

    private static void runBound(final AuditActor actor, final Runnable task) {
        ScopedValue.where(RequestContext.SCOPE, RequestContext.of(actor)).run(task);
    }

    /**
     * The named virtual-thread factory, for the rare caller that manages its own lifecycle. Threads it
     * creates run in the background cache lane too -- there is deliberately no unbound side door.
     */
    public static ThreadFactory factory() {
        return TripThreads::newBackgroundThread;
    }

    private TripThreads() {
    }
}
