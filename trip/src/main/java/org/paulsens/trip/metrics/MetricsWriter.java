package org.paulsens.trip.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;

/**
 * Writes one minute's records to Valkey: per metric, one hash field keyed by the minute of the week, so the same
 * field is overwritten a week later and a metric's storage is bounded by construction (10,080 fields, about 1 MB a
 * metric per server; measured with {@code MEMORY USAGE}). Every command is best-effort: a write the cache refuses is
 * dropped and never retried; {@link #write} says whether every record landed, and the cache client logs the refusal.
 *
 * <p>Runs on the metrics publisher's thread, which {@link TripMetrics} starts from {@code TripThreads.factory()}, so
 * these commands ride the BACKGROUND cache lane and can never crowd out a request's.
 */
public final class MetricsWriter {

    /** How long a server's hash outlives its last write: a week of history plus a day of slack. */
    static final Duration KEEP = Duration.ofDays(8);
    /** How many servers the registry remembers; each deploy adds one, and 64 is weeks of deploys. */
    static final int MAX_TASKS = 64;
    /** How often a key's expiry is pushed out: hourly is plenty against eight days. */
    static final long REFRESH_SECONDS = 3600;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MINUTES_PER_DAY = 1440;

    private final Supplier<CacheClient> cache;
    private final String taskId;
    /** Key to the epoch second its expiry was last pushed out. */
    private final Map<String, Long> refreshedAt = new ConcurrentHashMap<>();

    MetricsWriter(final Supplier<CacheClient> cache, final String taskId) {
        this.cache = cache;
        this.taskId = taskId;
    }

    /**
     * Stores {@code records} (metric name to its numbers) for the minute starting at {@code minuteStart} (epoch
     * seconds), and notes this server in the registry. False when the cache refused a record or the registry
     * entry: the minute is then (partly) missing from the graphs.
     */
    boolean write(final long minuteStart, final Map<String, Map<String, Number>> records) {
        final CacheClient client = cache.get();
        boolean landed = true;
        for (final Map.Entry<String, Map<String, Number>> record : records.entrySet()) {
            landed &= writeOne(client, minuteStart, record.getKey(), record.getValue());
        }
        landed &= client.addScoredEntries(CacheKeys.METRICS_TASKS, Map.of(taskId, (double) minuteStart));
        if (due(CacheKeys.METRICS_TASKS, minuteStart)) {
            client.trimSortedSet(CacheKeys.METRICS_TASKS, MAX_TASKS);
            client.expire(CacheKeys.METRICS_TASKS, KEEP);
        }
        return landed;
    }

    private boolean writeOne(final CacheClient client, final long minuteStart, final String metric,
            final Map<String, Number> fields) {
        final String key = CacheKeys.metricKey(metric, taskId);
        final boolean landed = client.putHashField(key, String.valueOf(minuteOfWeek(minuteStart)),
                record(minuteStart, fields));
        if (due(key, minuteStart)) {
            client.expire(key, KEEP);
        }
        return landed;
    }

    /** Whether {@code key}'s expiry is due to be pushed out, noting that it now is. */
    private boolean due(final String key, final long now) {
        final Long last = refreshedAt.get(key);
        if (last != null && now - last < REFRESH_SECONDS) {
            return false;
        }
        refreshedAt.put(key, now);
        return true;
    }

    /** The stored JSON: {@code t} (the minute's epoch second, which tells this week's field from last week's) first. */
    public static String record(final long minuteStart, final Map<String, Number> fields) {
        final ObjectNode node = JSON.createObjectNode();
        node.put("t", minuteStart);
        fields.forEach((name, value) -> putNumber(node, name, value));
        return node.toString();
    }

    private static void putNumber(final ObjectNode node, final String name, final Number value) {
        if (value instanceof Double || value instanceof Float) {
            // Three decimals is a microsecond of a millisecond: plenty, and it keeps each record small.
            node.put(name, Math.round(value.doubleValue() * 1000.0) / 1000.0);
        } else {
            node.put(name, value.longValue());
        }
    }

    /** The minute of the week (Sunday 00:00 UTC is 0, Saturday 23:59 is 10079) of {@code epochSecond}. */
    public static int minuteOfWeek(final long epochSecond) {
        final ZonedDateTime at = Instant.ofEpochSecond(epochSecond).atZone(ZoneOffset.UTC);
        final int day = at.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : at.getDayOfWeek().getValue();
        return day * MINUTES_PER_DAY + at.getHour() * 60 + at.getMinute();
    }
}
