package org.paulsens.trip.metrics;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.testng.annotations.Test;

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
