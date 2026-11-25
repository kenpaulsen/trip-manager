package org.paulsens.trip.metrics;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;
import software.amazon.awssdk.http.HttpMetric;
import software.amazon.awssdk.metrics.MetricCollection;

/** One minute's records from the meters, a metric with nothing behind it left out, and the catalog's lookups. */
public class MetricsSamplerTest {

    @Test
    public void aMinuteHoldsEveryMetricThatHasNumbers() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new JvmMemoryMetrics().bindTo(registry);
        Timer.builder("jvm.gc.pause").register(registry).record(Duration.ofMillis(8));
        final PoolMetricPublisher pool = new PoolMetricPublisher();
        final MetricsSampler sampler = new MetricsSampler(pool);

        final Map<String, Map<String, Number>> minute = sampler.sample(registry);
        assertFalse(minute.containsKey("ddb.pool"), "no DynamoDB client (local mode): no pool record, not zeros");
        assertTrue(minute.get("jvm.heap").get("used").doubleValue() > 0);
        assertTrue(minute.get("jvm.heap").get("max").doubleValue() >= minute.get("jvm.heap").get("used")
                .doubleValue());
        assertEquals(minute.get("jvm.gc").get("pauses"), 1L);
        assertEquals(minute.get("http.requests").get("count"), 0L, "no request timer in this registry");

        assertFalse(sampler.sample(new SimpleMeterRegistry()).containsKey("jvm.heap"), "no heap meters, no record");
    }

    /** A pool report the publisher could not read shows in the log: the first minute, then at most hourly. */
    @Test
    public void unreadPoolReportsAreLoggedTheFirstMinuteAndThenHourly() {
        final PoolMetricPublisher pool = new PoolMetricPublisher();
        final MetricCollection broken = mock(MetricCollection.class);
        when(broken.metricValues(HttpMetric.LEASED_CONCURRENCY)).thenThrow(new IllegalStateException("new SDK"));
        final MetricsSampler sampler = new MetricsSampler(pool);
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final Logger samplerLog = (Logger) LoggerFactory.getLogger(MetricsSampler.class);
        final ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        samplerLog.addAppender(logged);
        try {
            sampler.sample(registry);
            assertEquals(logged.list.size(), 0, "no failures, nothing to say");
            pool.publish(broken);
            sampler.sample(registry);
            assertEquals(logged.list.size(), 1, "the first minute with a failure");
            assertTrue(logged.list.get(0).getFormattedMessage().contains("new SDK"), "says why");
            for (int minute = 1; minute < ValkeyMeterRegistry.LOG_EVERY; minute++) {
                pool.publish(broken);
                sampler.sample(registry);
            }
            assertEquals(logged.list.size(), 1, "quiet for the rest of the hour");
            pool.publish(broken);
            sampler.sample(registry);
            assertEquals(logged.list.size(), 2, "an hour on, still failing");
        } finally {
            samplerLog.detachAppender(logged);
        }
    }

    @Test
    public void theCatalogNamesItsMetricsAndFields() {
        assertEquals(MetricCatalog.metric("ddb.pool").orElseThrow(), MetricCatalog.DDB_POOL);
        assertTrue(MetricCatalog.metric("nope").isEmpty());
        assertEquals(MetricCatalog.DDB_POOL.field("max").orElseThrow().limit(), "limit");
        assertTrue(MetricCatalog.HTTP_REQUESTS.field("count").orElseThrow().isCount());
        assertFalse(MetricCatalog.HTTP_REQUESTS.field("maxMs").orElseThrow().isCount());
        assertTrue(MetricCatalog.JVM_GC.field("nope").isEmpty());
        final MinuteMax peak = new MinuteMax();
        peak.record(3);
        peak.record(1);
        assertEquals(peak.drain(), 3L);
        assertEquals(peak.drain(), 0L);
    }
}
