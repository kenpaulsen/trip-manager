package org.paulsens.trip.util;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class CacheLaneTest {

    @Test
    public void unboundIsForeground() {
        assertFalse(CacheLane.isBackground(), "a plain (request-like) thread is foreground");
    }

    @Test
    public void theBindingCoversExactlyTheTask() {
        final AtomicBoolean inside = new AtomicBoolean();
        CacheLane.runBackground(() -> inside.set(CacheLane.isBackground()));
        assertTrue(inside.get(), "inside the task the lane is background");
        assertFalse(CacheLane.isBackground(), "the binding must not leak past the task");
    }

    /** StructuredTaskScope forks inherit: a background reload's fan-out stays background. */
    @Test
    public void forksInheritTheBackgroundBinding() {
        final AtomicBoolean forkSawBackground = new AtomicBoolean();
        CacheLane.runBackground(() -> forkAndRecord(forkSawBackground));
        assertTrue(forkSawBackground.get());
    }

    /** And a foreground thread's forks stay foreground -- Trip's event fan-out must keep the big queue. */
    @Test
    public void foregroundForksStayForeground() {
        final AtomicBoolean forkSawBackground = new AtomicBoolean(true);
        forkAndRecord(forkSawBackground);
        assertFalse(forkSawBackground.get());
    }

    private static void forkAndRecord(final AtomicBoolean sawBackground) {
        try (var scope = StructuredTaskScope.open()) {
            final StructuredTaskScope.Subtask<Boolean> sub = scope.fork(CacheLane::isBackground);
            scope.join();
            sawBackground.set(sub.get());
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
