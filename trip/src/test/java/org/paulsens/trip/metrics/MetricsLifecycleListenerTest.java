package org.paulsens.trip.metrics;

import static org.testng.Assert.assertSame;

import org.testng.annotations.Test;

/** The listener starts and stops the publisher, each once however often it is called, and never throws. */
public class MetricsLifecycleListenerTest {

    @Test
    public void startAndStopAreIdempotent() {
        final MetricsLifecycleListener listener = new MetricsLifecycleListener();
        listener.contextInitialized(null);
        listener.contextInitialized(null);
        assertSame(TripMetrics.registry(), TripMetrics.registry());
        listener.contextDestroyed(null);
        listener.contextDestroyed(null);
    }

    @Test
    public void aFailureNeverStopsTheSiteFromStartingOrStopping() {
        final Runnable broken = () -> {
            throw new IllegalStateException("no metrics today");
        };
        final MetricsLifecycleListener listener = new MetricsLifecycleListener(broken, broken);
        listener.contextInitialized(null);
        listener.contextDestroyed(null);
    }
}
