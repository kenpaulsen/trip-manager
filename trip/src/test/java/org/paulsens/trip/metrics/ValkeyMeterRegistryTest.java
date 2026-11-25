package org.paulsens.trip.metrics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;

import io.micrometer.core.instrument.MockClock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.annotations.Test;

/**
 * The once-a-minute publish: it always drains the minute, writes it only while metrics are on, labels it with the
 * minute that just ended (the current one at shutdown), and drops a write the cache refuses.
 */
public class ValkeyMeterRegistryTest {

    private static final Map<String, Map<String, Number>> RECORDS = Map.of("jvm.heap", Map.of("used", 1.0));

    @Test
    public void aMinuteIsWrittenUnderTheMinuteThatJustEnded() {
        final MockClock clock = new MockClock();
        clock.add(Duration.ofMinutes(10).plusSeconds(3));
        final MetricsSampler sampler = mock(MetricsSampler.class);
        final MetricsWriter writer = mock(MetricsWriter.class);
        final AtomicBoolean enabled = new AtomicBoolean(true);
        final ValkeyMeterRegistry registry = new ValkeyMeterRegistry(clock, sampler, () -> writer, enabled::get);
        when(sampler.sample(registry)).thenReturn(RECORDS);
        when(writer.write(anyLong(), any())).thenReturn(true);

        registry.publish();
        verify(writer).write(9 * 60L, RECORDS);
        assertEquals(registry.dropped(), 0L, "a minute that landed");

        enabled.set(false);
        clock.add(Duration.ofMinutes(1));
        registry.publish();
        verify(writer, never()).write(eq(10 * 60L), any());

        enabled.set(true);
        doThrow(new IllegalStateException("cache gone")).when(writer).write(anyLong(), any());
        registry.publish();
        assertEquals(registry.dropped(), 1L, "dropped, never thrown at Micrometer");

        doReturn(false).when(writer).write(anyLong(), any());
        registry.publish();
        assertEquals(registry.dropped(), 2L, "a write the cache refused is counted too");

        registry.close();
        assertEquals(registry.minuteStart(), 11 * 60L, "at shutdown, the current partial minute");
    }

    @Test
    public void theFirstPublishHoldsTheCurrentMinuteAndTheNextOneTheWholeMinuteBefore() {
        final MockClock clock = new MockClock();
        clock.add(Duration.ofMinutes(10).plusSeconds(3));
        final MetricsSampler sampler = mock(MetricsSampler.class);
        final MetricsWriter writer = mock(MetricsWriter.class);
        final ValkeyMeterRegistry registry = new ValkeyMeterRegistry(clock, sampler, () -> writer, () -> true);
        when(sampler.sample(registry)).thenReturn(RECORDS);
        registry.publishFirst();
        verify(writer).write(10 * 60L, RECORDS);
        clock.add(Duration.ofMinutes(1));
        registry.publish();
        verify(writer, times(2)).write(10 * 60L, RECORDS);
        assertEquals(registry.minuteStart(), 10 * 60L);
    }

    @Test
    public void aScheduledPublishNeverThrows() {
        final MetricsSampler sampler = mock(MetricsSampler.class);
        final ValkeyMeterRegistry registry = new ValkeyMeterRegistry(new MockClock(), sampler,
                () -> mock(MetricsWriter.class), () -> true);
        when(sampler.sample(registry)).thenThrow(new IllegalStateException("a meter gone bad"));
        registry.publishSafely();
        registry.publishFirst();
        assertEquals(registry.dropped(), 2L);
    }

    @Test
    public void thePublishRunsJustPastEachMinute() {
        assertEquals(TripMetrics.delayToNextMinute(120_000L), 61_000L, "on the minute: the next one, plus a second");
        assertEquals(TripMetrics.delayToNextMinute(150_500L), 30_500L);
        assertEquals(TripMetrics.delayToNextMinute(179_999L), 1_001L);
    }

    @Test
    public void aMinuteWithNothingToSayWritesNothing() {
        final MetricsSampler sampler = mock(MetricsSampler.class);
        final MetricsWriter writer = mock(MetricsWriter.class);
        final ValkeyMeterRegistry registry = new ValkeyMeterRegistry(new MockClock(), sampler, () -> writer,
                () -> true);
        when(sampler.sample(registry)).thenReturn(Map.of());
        registry.publish();
        verify(writer, never()).write(anyLong(), any());
    }
}
