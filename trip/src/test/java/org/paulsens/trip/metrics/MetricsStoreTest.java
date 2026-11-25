package org.paulsens.trip.metrics;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.metrics.MetricCatalog.Rollup;
import org.paulsens.trip.metrics.MetricsStore.Line;
import org.paulsens.trip.metrics.MetricsStore.Point;
import org.paulsens.trip.metrics.MetricsStore.Series;
import org.testng.annotations.Test;

/**
 * Reading a metric back: the range, the buckets, the servers combined or apart, gaps as nulls, and last week's value
 * in a reused field skipped.
 */
public class MetricsStoreTest {

    /** Wednesday 2026-11-25 12:00 UTC, the reader's "now". */
    private static final Instant NOW = Instant.parse("2026-11-25T12:00:00Z");
    private static final long T = NOW.getEpochSecond();

    @Test
    public void serversCombineByTheFieldsRuleAndGapsComeBackAsNulls() {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        final MetricsWriter one = new MetricsWriter(() -> cache, "one");
        final MetricsWriter two = new MetricsWriter(() -> cache, "two");
        // Minutes 12:00-10 and 12:00-9 for "one", 12:00-10 for "two"; nothing after.
        one.write(T - 600, Map.of("ddb.pool", Map.of("max", 4L, "calls", 10L)));
        one.write(T - 540, Map.of("ddb.pool", Map.of("max", 9L, "calls", 5L)));
        two.write(T - 600, Map.of("ddb.pool", Map.of("max", 7L, "calls", 1L)));
        final MetricsStore store = store(cache);
        final MetricCatalog.Metric pool = MetricCatalog.DDB_POOL;

        final Series peak = store.read(pool, pool.field("max").orElseThrow(), Duration.ofMinutes(30),
                Duration.ofMinutes(5), null, false);
        assertEquals(peak.bucket(), Duration.ofMinutes(5));
        final Line all = peak.lines().get(0);
        assertEquals(all.task(), MetricsStore.COMBINED);
        assertEquals(value(all, T - 600), 9.0, "the 11:50 bucket's highest minute, across servers");
        assertNull(value(all, T - 300), "no sample: a gap, not a zero");
        assertFalse(peak.isEmpty());

        final Series calls = store.read(pool, pool.field("calls").orElseThrow(), Duration.ofMinutes(30),
                Duration.ofMinutes(5), Rollup.MAX, false);
        assertEquals(value(calls.lines().get(0), T - 600), 16.0, "a count always sums, whatever the graph asks");

        final Series apart = store.read(pool, pool.field("max").orElseThrow(), Duration.ofMinutes(30),
                Duration.ofMinutes(1), Rollup.AVG, true);
        assertEquals(apart.lines().stream().map(Line::task).sorted().toList(), List.of("one", "two"));
        assertEquals(value(line(apart, "one"), T - 540), 9.0);
        assertEquals(value(line(apart, "two"), T - 600), 7.0);
    }

    @Test
    public void lastWeeksValueInAReusedFieldIsNotThisWeeks() {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        final long aWeekAgo = T - Duration.ofDays(7).toSeconds() - 60;
        cache.putHashField(CacheKeys.metricKey("jvm.heap", "old"), String.valueOf(MetricsWriter.minuteOfWeek(T - 60)),
                MetricsWriter.record(aWeekAgo, Map.of("used", 500.0)));
        cache.addScoredEntries(CacheKeys.METRICS_TASKS, Map.of("old", (double) T - 60));
        final MetricCatalog.Metric heap = MetricCatalog.JVM_HEAP;
        final Series series = store(cache).read(heap, heap.field("used").orElseThrow(), Duration.ofHours(1), null,
                null, false);
        assertTrue(series.isEmpty(), "the field holds last week's minute");
        assertEquals(series.bucket(), Duration.ofMinutes(1), "an hour fits at the finest bucket");
    }

    @Test
    public void aRangeIsCappedAtAWeekAndAutoPicksTheFinestBucketThatFits() {
        assertEquals(MetricsStore.autoBucket(Duration.ofHours(6)), Duration.ofMinutes(1));
        assertEquals(MetricsStore.autoBucket(Duration.ofHours(24)), Duration.ofMinutes(5));
        assertEquals(MetricsStore.autoBucket(Duration.ofDays(3)), Duration.ofMinutes(5));
        assertEquals(MetricsStore.autoBucket(Duration.ofDays(7)), Duration.ofMinutes(10));
        assertEquals(MetricsStore.autoBucket(Duration.ofDays(365)), Duration.ofHours(1));
        final MetricCatalog.Metric gc = MetricCatalog.JVM_GC;
        final Series series = store(new InMemoryCacheClient()).read(gc, gc.field("pauses").orElseThrow(),
                Duration.ofDays(30), Duration.ofHours(1), null, false);
        assertEquals(series.lines().get(0).points().size(), 7 * 24 + 1, "a week of hours, both ends included");
        assertEquals(MetricsStore.minuteFields(T - Duration.ofDays(8).toSeconds(), T).size(), 10_080,
                "more than a week reads each minute's field once");
    }

    @Test
    public void foldingAndCombining() {
        assertEquals(MetricsStore.fold(List.of(1.0, 4.0, 2.0), Rollup.MIN), 1.0);
        assertEquals(MetricsStore.fold(List.of(1.0, 4.0, 2.0), Rollup.AVG), 7.0 / 3);
        assertEquals(MetricsStore.fold(List.of(1.0, 4.0, 2.0), Rollup.SUM), 7.0);
        assertEquals(MetricsStore.combined(List.of(Map.of(0L, 2.0), Map.of(0L, 3.0, 60L, 1.0)),
                MetricCatalog.Combine.SUM), Map.of(0L, 5.0, 60L, 1.0));
        assertEquals(new MetricsStore().getClass(), MetricsStore.class, "the production constructor");
    }

    private static MetricsStore store(final InMemoryCacheClient cache) {
        return new MetricsStore(() -> cache, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Line line(final Series series, final String task) {
        return series.lines().stream().filter(line -> line.task().equals(task)).findFirst().orElseThrow();
    }

    private static Double value(final Line line, final long at) {
        return line.points().stream().filter(point -> point.at() == at).findFirst().map(Point::value).orElse(null);
    }
}
