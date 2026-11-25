package org.paulsens.trip.metrics;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.SplittableRandom;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.metrics.MetricsStore.Line;
import org.paulsens.trip.metrics.MetricsStore.Series;
import org.testng.annotations.Test;

/** The local demo week: two servers around a deploy, a startup spike, and every catalog metric to draw. */
public class DemoMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-25T12:00:30Z");

    @Test
    public void aWeekOfTwoServersAroundADeploy() {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        DemoMetrics.seed(cache, NOW);
        final MetricsStore store = new MetricsStore(() -> cache, Clock.fixed(NOW, ZoneOffset.UTC));
        final MetricCatalog.Metric pool = MetricCatalog.DDB_POOL;
        final Series week = store.read(pool, pool.field("max").orElseThrow(), Duration.ofDays(7), null, null);
        assertEquals(week.lines().stream().map(Line::task).sorted().toList(),
                List.of(DemoMetrics.OLD_TASK, DemoMetrics.NEW_TASK));
        final Line combined = store.combinedLine(week);
        assertTrue(combined.points().stream().filter(point -> point.value() != null).count() > 900,
                "a sample in nearly every bucket");
        for (final MetricCatalog.Metric metric : MetricCatalog.ALL) {
            assertFalse(store.read(metric, metric.fields().get(0), Duration.ofHours(6), null, null).isEmpty(),
                    metric.name() + " has a demo week");
        }
        DemoMetrics.seedOnce(new InMemoryCacheClient(), NOW);
    }

    @Test
    public void aServerStartsWithASpikeInThePool() {
        final SplittableRandom random = new SplittableRandom(1);
        assertEquals(DemoMetrics.minuteOf(0, 0, random).get("ddb.pool").get("max"), 140L);
        assertEquals(DemoMetrics.minuteOf(0, 1, random).get("ddb.pool").get("max"), 100L);
        assertTrue(((Long) DemoMetrics.minuteOf(0, 10, random).get("ddb.pool").get("max")) < 60);
    }
}
