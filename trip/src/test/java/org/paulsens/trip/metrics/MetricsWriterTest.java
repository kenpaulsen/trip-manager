package org.paulsens.trip.metrics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertEquals;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.testng.annotations.Test;

/** One minute's records into Valkey: the minute-of-week field, the record shape, the registry, the expiries. */
public class MetricsWriterTest {

    /** Sunday 2026-11-22 00:00 UTC. */
    private static final long SUNDAY = Instant.parse("2026-11-22T00:00:00Z").getEpochSecond();

    @Test
    public void theMinuteOfTheWeekStartsSundayAtMidnightUtc() {
        assertEquals(MetricsWriter.minuteOfWeek(SUNDAY), 0);
        assertEquals(MetricsWriter.minuteOfWeek(SUNDAY + 59), 0, "any second of the minute");
        assertEquals(MetricsWriter.minuteOfWeek(SUNDAY + 60), 1);
        assertEquals(MetricsWriter.minuteOfWeek(SUNDAY + 86_400 + 3_600), 1440 + 60, "Monday 01:00");
        assertEquals(MetricsWriter.minuteOfWeek(SUNDAY + 7 * 86_400 - 60), 10_079, "Saturday 23:59");
        assertEquals(MetricsWriter.minuteOfWeek(SUNDAY + 7 * 86_400), 0, "a week later, the same field");
    }

    @Test
    public void aRecordCarriesItsMinuteFirstAndKeepsItsNumbersSmall() {
        final Map<String, Number> fields = new LinkedHashMap<>();
        fields.put("max", 37L);
        fields.put("meanMs", 12.3456789);
        assertEquals(MetricsWriter.record(SUNDAY, fields), "{\"t\":" + SUNDAY + ",\"max\":37,\"meanMs\":12.346}");
    }

    @Test
    public void aMinuteIsStoredUnderItsFieldAndTheServerIsRegistered() {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        final MetricsWriter writer = new MetricsWriter(() -> cache, "task1");
        writer.write(SUNDAY + 120, Map.of("ddb.pool", Map.of("max", 5L)));
        assertEquals(cache.getHashFields(CacheKeys.metricKey("ddb.pool", "task1"), List.of("2")),
                Map.of("2", "{\"t\":" + (SUNDAY + 120) + ",\"max\":5}"));
        assertEquals(cache.getRangeByScore(CacheKeys.METRICS_TASKS, 0, Double.MAX_VALUE, false, 0),
                List.of("task1"));
    }

    /** The writer says whether the minute landed, so the registry can count one the cache refused. */
    @Test
    public void aWriteTheCacheRefusesIsReported() {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        assertEquals(new MetricsWriter(() -> cache, "task1").write(SUNDAY, Map.of("jvm.heap", Map.of("used", 1.0))),
                true);
        final CacheClient refusing = mock(CacheClient.class);
        assertEquals(new MetricsWriter(() -> refusing, "task1").write(SUNDAY,
                Map.of("jvm.heap", Map.of("used", 1.0))), false, "a refused record");
    }

    /** A cache clear (DAO.clearAllCaches clears t1:) never takes the week of metrics with it. */
    @Test
    public void metricsSurviveACacheClear() {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        new MetricsWriter(() -> cache, "task1").write(SUNDAY, Map.of("jvm.heap", Map.of("used", 1.0)));
        cache.putValue(CacheKeys.FORMAT_VERSION + "x", "cached", null);
        cache.clearNamespace(CacheKeys.FORMAT_VERSION);
        assertEquals(cache.getHashFields(CacheKeys.metricKey("jvm.heap", "task1"), List.of("0")).size(), 1);
        assertEquals(cache.getRangeByScore(CacheKeys.METRICS_TASKS, 0, Double.MAX_VALUE, false, 0), List.of("task1"));
        assertEquals(cache.getValue(CacheKeys.FORMAT_VERSION + "x"), Optional.empty(), "the cache did clear");
    }

    @Test
    public void expiriesArePushedOutHourlyNotEveryMinute() {
        final CacheClient cache = mock(CacheClient.class);
        final MetricsWriter writer = new MetricsWriter(() -> cache, "task1");
        final String key = CacheKeys.metricKey("jvm.heap", "task1");
        for (int minute = 0; minute < 60; minute++) {
            writer.write(SUNDAY + minute * 60L, Map.of("jvm.heap", Map.of("used", 1.0)));
        }
        verify(cache, times(60)).putHashField(eq(key), any(), any());
        verify(cache, times(1)).expire(key, MetricsWriter.KEEP);
        verify(cache, times(1)).trimSortedSet(CacheKeys.METRICS_TASKS, MetricsWriter.MAX_TASKS);
        writer.write(SUNDAY + 3_600, Map.of("jvm.heap", Map.of("used", 1.0)));
        verify(cache, times(2)).expire(key, MetricsWriter.KEEP);
        verify(cache, never()).trimSortedSet(eq("other"), anyInt());
    }
}
