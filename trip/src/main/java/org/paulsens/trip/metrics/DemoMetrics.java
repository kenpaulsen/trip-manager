package org.paulsens.trip.metrics;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.paulsens.trip.cache.CacheClient;

/**
 * A made-up week of metrics for LOCAL MODE only ({@code FakeData}), so the dashboard has something real-looking to
 * show on a laptop and in the webtests: two demo servers, the first retired two days ago by a blue/green deploy that
 * started the second (five minutes of overlap), each with a daily rhythm, a startup spike in the DynamoDB pool, and
 * the odd burst. The local server's own live minutes come on top. Deterministic (a fixed seed), written once per JVM.
 */
public final class DemoMetrics {

    public static final String OLD_TASK = "demo-task-a";
    public static final String NEW_TASK = "demo-task-b";

    private static final AtomicBoolean SEEDED = new AtomicBoolean();
    private static final Duration WEEK = Duration.ofDays(7);
    private static final Duration DEPLOY_AGO = Duration.ofDays(2);
    private static final Duration OVERLAP = Duration.ofMinutes(5);

    private DemoMetrics() {
    }

    /** Writes the demo week into {@code cache}, ending at {@code now}; a second call in the same JVM does nothing. */
    public static void seedOnce(final CacheClient cache, final Instant now) {
        if (SEEDED.compareAndSet(false, true)) {
            seed(cache, now);
        }
    }

    /** The demo week ending at {@code now}, whether or not this JVM wrote it before. */
    static void seed(final CacheClient cache, final Instant now) {
        final long end = Math.floorDiv(now.getEpochSecond(), 60) * 60;
        final long deploy = end - DEPLOY_AGO.toSeconds();
        final SplittableRandom random = new SplittableRandom(20_261_124L);
        write(new MetricsWriter(() -> cache, OLD_TASK), end - WEEK.toSeconds(), deploy + OVERLAP.toSeconds(), random);
        write(new MetricsWriter(() -> cache, NEW_TASK), deploy, end, random);
    }

    private static void write(final MetricsWriter writer, final long from, final long to,
            final SplittableRandom random) {
        for (long minute = from; minute < to; minute += 60) {
            writer.write(minute, minuteOf(minute, (minute - from) / 60, random));
        }
    }

    /** One minute's records: {@code age} minutes since the server started, for its startup spike. */
    static Map<String, Map<String, Number>> minuteOf(final long minute, final long age,
            final SplittableRandom random) {
        final int hour = Instant.ofEpochSecond(minute).atZone(ZoneOffset.UTC).getHour();
        // Busiest in the afternoon Pacific (about 23:00 UTC), quietest twelve hours later.
        final double day = 0.5 + 0.5 * Math.sin(2 * Math.PI * (hour - 17) / 24.0);
        final long burst = random.nextInt(600) == 0 ? 60 + random.nextInt(110) : 0;
        final long startup = age < 3 ? 140 - age * 40 : 0;
        final long pool = Math.max(startup, Math.max(burst, Math.round(3 + 25 * day + random.nextDouble() * 5)));
        final Map<String, Map<String, Number>> records = new LinkedHashMap<>();
        records.put(MetricCatalog.DDB_POOL.name(), Map.of("max", pool, "pending", Math.max(0, (pool - 150) / 3),
                "calls", Math.round(200 + 2500 * day + random.nextDouble() * 300), "limit", 200L));
        records.put(MetricCatalog.JVM_HEAP.name(), Map.of("used", 800 + 1200 * day + (minute / 60 % 17) * 40
                + random.nextDouble() * 100, "committed", 2600.0, "max", 3072.0));
        final long pauses = 1 + random.nextInt(3);
        records.put(MetricCatalog.JVM_GC.name(), Map.of("pauses", pauses,
                "pauseMs", pauses * (5 + random.nextDouble() * 15)));
        final double mean = 40 + 60 * day + random.nextDouble() * 20;
        records.put(MetricCatalog.HTTP_REQUESTS.name(), Map.of("count", Math.round(20 + 400 * day
                + random.nextDouble() * 40), "errors", random.nextInt(100) == 0 ? 1L : 0L, "meanMs", mean,
                "maxMs", mean * (3 + random.nextDouble() * 10), "active", 1 + Math.round(8 * day
                        + random.nextDouble() * 3)));
        return records;
    }
}
