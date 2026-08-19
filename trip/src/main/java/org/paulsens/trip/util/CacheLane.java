package org.paulsens.trip.util;

/**
 * Marks the current thread as running spawned background work, so {@code ValkeyCacheClient} can route its
 * commands onto the background connection (small queue, sheds early) instead of the request-path connection.
 * The 2026-08-18 incident was background probes filling the one shared queue until foreground reads were
 * rejected; this lane is the bulkhead that makes that starvation structurally impossible.
 *
 * <p>A {@link ScopedValue} rather than a ThreadLocal for the same reason {@code RequestContext} is one: the
 * binding cannot leak past the task it was made for. The inheritance rules do exactly the right thing --
 * plain virtual-thread spawns do NOT inherit bindings (but every background spawn goes through
 * {@link TripThreads}, which re-binds), while {@code StructuredTaskScope} forks DO inherit, so a request
 * thread's fan-out stays foreground and a background reload's fan-out stays background.</p>
 */
public final class CacheLane {

    private static final ScopedValue<Boolean> BACKGROUND = ScopedValue.newInstance();

    /** Runs {@code task} with the background lane bound; the binding ends when the task returns. */
    public static void runBackground(final Runnable task) {
        ScopedValue.where(BACKGROUND, Boolean.TRUE).run(task);
    }

    /** Whether the current thread is background-spawned work (unbound means foreground). */
    public static boolean isBackground() {
        return BACKGROUND.isBound() && BACKGROUND.get();
    }

    private CacheLane() {
    }
}
