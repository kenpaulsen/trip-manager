package org.paulsens.trip.metrics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

import io.micrometer.core.instrument.MockClock;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

/**
 * The listener hands each start and stop to the publisher and never throws; the publisher's lifecycle starts and
 * stops once however often it is asked. Neither test starts the real publisher: its registry, once closed, stays
 * closed for the rest of the JVM.
 */
public class MetricsLifecycleListenerTest {

    @Test
    public void theListenerHandsEachStartAndStopToThePublisher() {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final MetricsLifecycleListener listener = new MetricsLifecycleListener(starts::incrementAndGet,
                stops::incrementAndGet);
        listener.contextInitialized(null);
        listener.contextDestroyed(null);
        assertEquals(starts.get(), 1);
        assertEquals(stops.get(), 1);
        assertNotNull(new MetricsLifecycleListener(), "the production listener wires TripMetrics");
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

    @Test
    public void thePublisherStartsAndStopsOnceHoweverOftenItIsAsked() {
        final MetricsSampler sampler = mock(MetricsSampler.class);
        final ValkeyMeterRegistry registry = new ValkeyMeterRegistry(new MockClock(), sampler,
                () -> mock(MetricsWriter.class), () -> true);
        when(sampler.sample(registry)).thenReturn(Map.of());
        final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        final AtomicInteger made = new AtomicInteger();
        final TripMetrics.Lifecycle lifecycle = new TripMetrics.Lifecycle(registry, () -> made(made, scheduler));

        TripMetrics.start(lifecycle);
        assertFalse(lifecycle.start(), "a second start is a no-op");
        assertEquals(made.get(), 1, "one scheduler, one publish a minute");
        verify(scheduler).scheduleAtFixedRate(any(Runnable.class), anyLong(),
                eq(ValkeyMeterRegistry.STEP.toMillis()), eq(TimeUnit.MILLISECONDS));
        verify(scheduler).execute(any(Runnable.class));

        lifecycle.stop();
        lifecycle.stop();
        verify(scheduler, times(1)).shutdownNow();
        assertTrue(registry.isClosed(), "the last, partial minute is published by the close");
    }

    /**
     * The real publisher's own parts, short of starting it: its writer is built once, metrics are on by default,
     * and a stop before any start does nothing (unit tests never start it).
     */
    @Test
    public void theRealPublishersPartsWithoutStartingIt() {
        assertSame(TripMetrics.writer(), TripMetrics.writer(), "one writer for the server's life");
        assertTrue(TripMetrics.enabled(), "on by default");
        TripMetrics.stop();
        assertFalse(TripMetrics.registry().isClosed(), "a stop before a start leaves the registry open");
    }

    private static ScheduledExecutorService made(final AtomicInteger made, final ScheduledExecutorService scheduler) {
        made.incrementAndGet();
        return scheduler;
    }
}
