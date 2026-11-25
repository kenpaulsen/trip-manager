package org.paulsens.trip.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.metrics.MetricCatalog.Combine;
import org.paulsens.trip.metrics.MetricCatalog.Field;
import org.paulsens.trip.metrics.MetricCatalog.Metric;
import org.paulsens.trip.metrics.MetricCatalog.Rollup;

/**
 * Reads a metric back for a graph: one field over a range, folded into buckets, one line per server (ECS task), which
 * {@link #combinedLine} folds into the one combined line. Reads only; the writer is {@link MetricsWriter}.
 *
 * <p>A server's minutes are read in day-sized chunks ({@value #CHUNK} fields per HMGET): a whole week in one
 * HGETALL costs under a millisecond of server time (measured), but a chunk keeps every command far from the
 * slowlog threshold however large a record grows. A record whose {@code t} falls outside the range is last week's
 * value in that minute's field and is skipped; a missing field is a minute with no sample. Buckets with no sample
 * come back as null points, so a graph draws a gap rather than a line across it.
 */
public final class MetricsStore {

    /** A graph gets at most about this many points per line; "auto" picks the finest bucket that stays under it. */
    public static final int MAX_POINTS = 1100;
    /** The buckets a graph may ask for, finest first. */
    public static final List<Duration> BUCKETS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(10), Duration.ofHours(1));
    /** The longest range kept: a week. */
    public static final Duration MAX_RANGE = Duration.ofDays(7);
    static final int CHUNK = 1440;
    /** The line name of the combined line. */
    public static final String COMBINED = "all";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One point: a bucket's start (epoch seconds) and its value, null for a bucket with no sample. */
    public record Point(long at, Double value) {
    }

    /** One line of a graph: a server's id, or {@link #COMBINED}. */
    public record Line(String task, List<Point> points) {
    }

    /**
     * What a graph draws: the lines, the bucket they were folded into, and the first and last bucket's start (epoch
     * seconds), which every line spans.
     */
    public record Series(Metric metric, Field field, Duration bucket, long first, long last, List<Line> lines) {

        /** Whether any line holds a sample: false draws the graph's empty state. */
        public boolean isEmpty() {
            return lines.stream().flatMap(line -> line.points().stream()).allMatch(point -> point.value() == null);
        }
    }

    /**
     * Keeps a value for the rest of the caller's request (the dashboard's per-request memo), so the graphs of one
     * metric, and a graph's limit line, share one read of each server's hash: the starter dashboard's nine graphs
     * read four hashes, not twelve.
     */
    @FunctionalInterface
    public interface Memo {
        /** The value under {@code key}, computed on the first ask. */
        <T> T get(String key, Supplier<T> compute);
    }

    /**
     * One server's records of a metric, column by column: each record's {@code t} and every field's value, NaN
     * where a record lacks it. Columns rather than parsed JSON: a request may hold a week of several metrics.
     */
    record Records(long[] at, Map<String, double[]> fields) {
    }

    private final Supplier<CacheClient> cache;
    private final Clock clock;
    private final Memo memo;

    /** The production cache and clock, with {@code memo} keeping each server's records for the request. */
    public MetricsStore(final Memo memo) {
        this(() -> DAO.getInstance().getCacheClient(), Clock.systemUTC(), memo);
    }

    /** Test seam: the cache and the clock, with nothing kept between reads. */
    public MetricsStore(final Supplier<CacheClient> cache, final Clock clock) {
        this(cache, clock, MetricsStore::compute);
    }

    /** Test seam: the cache, the clock and the memo. */
    public MetricsStore(final Supplier<CacheClient> cache, final Clock clock, final Memo memo) {
        this.cache = cache;
        this.clock = clock;
        this.memo = memo;
    }

    private static <T> T compute(final String key, final Supplier<T> compute) {
        return compute.get();
    }

    /**
     * {@code field} of {@code metric} over the last {@code range} (at most a week), in {@code bucket}s (null for
     * the finest that stays under {@link #MAX_POINTS}), each bucket folded by {@code rollup} (null for the field's
     * own; a count always sums), one line per server: {@link #combinedLine} folds them into the one line a graph
     * shows by default, so one read serves both views.
     */
    public Series read(final Metric metric, final Field field, final Duration range, final Duration bucket,
            final Rollup rollup) {
        final Duration span = range.compareTo(MAX_RANGE) > 0 ? MAX_RANGE : range;
        final Duration step = bucket == null ? autoBucket(span) : bucket;
        final Rollup fold = field.isCount() || rollup == null ? field.rollup() : rollup;
        final long now = clock.instant().getEpochSecond();
        final long from = now - span.toSeconds();
        final List<String> tasks = cache.get().getRangeByScore(CacheKeys.METRICS_TASKS, from, Double.MAX_VALUE,
                false, 0);
        final List<String> minutes = minuteFields(from, now);
        final Map<String, Map<Long, Double>> byTask = new LinkedHashMap<>();
        for (final String task : tasks) {
            final Map<Long, Double> buckets = buckets(samples(records(metric, task, span, minutes), field, from,
                    now), step.toSeconds(), fold);
            if (!buckets.isEmpty()) {
                byTask.put(task, buckets);
            }
        }
        final long first = Math.floorDiv(from, step.toSeconds()) * step.toSeconds();
        final long last = Math.floorDiv(now, step.toSeconds()) * step.toSeconds();
        return new Series(metric, field, step, first, last, perTaskLines(byTask, first, last, step.toSeconds()));
    }

    /**
     * {@code perTask}'s servers as one line, by its field's rule ({@link Combine}): what a graph shows by default,
     * so one read serves both views.
     */
    public Line combinedLine(final Series perTask) {
        final List<Map<Long, Double>> tasks = perTask.lines().stream().map(MetricsStore::valuesOf).toList();
        return new Line(COMBINED, dense(combined(tasks, perTask.field().combine()), perTask.first(), perTask.last(),
                perTask.bucket().toSeconds()));
    }

    private static Map<Long, Double> valuesOf(final Line line) {
        final Map<Long, Double> out = new TreeMap<>();
        line.points().stream().filter(point -> point.value() != null).forEach(point -> out.put(point.at(),
                point.value()));
        return out;
    }

    /** The finest bucket that keeps {@code range} under {@link #MAX_POINTS} points. */
    public static Duration autoBucket(final Duration range) {
        return BUCKETS.stream().filter(step -> range.toSeconds() / step.toSeconds() <= MAX_POINTS).findFirst()
                .orElse(BUCKETS.get(BUCKETS.size() - 1));
    }

    /** The hash fields (minutes of the week) that cover {@code from} to {@code now}, each once, in time order. */
    static List<String> minuteFields(final long from, final long now) {
        final Set<String> fields = new LinkedHashSet<>();
        for (long minute = Math.floorDiv(from, 60); minute <= Math.floorDiv(now, 60); minute++) {
            fields.add(String.valueOf(MetricsWriter.minuteOfWeek(minute * 60)));
        }
        return new ArrayList<>(fields);
    }

    /**
     * One server's records of {@code metric} over {@code span}, read once per request whatever field is asked
     * for. The memo key names the span, not its exact start: the graphs of one render ask a second or so apart, and
     * {@link #samples} filters each by its own range.
     */
    private Records records(final Metric metric, final String task, final Duration span,
            final List<String> minutes) {
        return memo.get("MetricsStore.records:" + metric.name() + ":" + task + ":" + span.toSeconds(),
                () -> readRecords(metric, CacheKeys.metricKey(metric.name(), task), minutes));
    }

    private Records readRecords(final Metric metric, final String key, final List<String> minutes) {
        final List<JsonNode> parsed = new ArrayList<>();
        for (int i = 0; i < minutes.size(); i += CHUNK) {
            final List<String> chunk = minutes.subList(i, Math.min(minutes.size(), i + CHUNK));
            cache.get().getHashFields(key, chunk).values().forEach(json -> parsed.add(parse(json)));
        }
        final long[] at = new long[parsed.size()];
        final Map<String, double[]> fields = new LinkedHashMap<>();
        metric.fields().forEach(field -> fields.put(field.name(), new double[parsed.size()]));
        for (int i = 0; i < parsed.size(); i++) {
            at[i] = parsed.get(i).path("t").asLong(-1);
            for (final Map.Entry<String, double[]> column : fields.entrySet()) {
                final JsonNode value = parsed.get(i).path(column.getKey());
                column.getValue()[i] = value.isNumber() ? value.asDouble() : Double.NaN;
            }
        }
        return new Records(at, fields);
    }

    /** One server's samples of {@code field} in the range: minute start to value. */
    static Map<Long, Double> samples(final Records records, final Field field, final long from, final long now) {
        final Map<Long, Double> out = new TreeMap<>();
        final double[] values = records.fields().get(field.name());
        for (int i = 0; values != null && i < values.length; i++) {
            final long at = records.at()[i];
            if (at >= from - 60 && at <= now && !Double.isNaN(values[i])) {
                out.put(at, values[i]);
            }
        }
        return out;
    }

    private static JsonNode parse(final String json) {
        try {
            return JSON.readTree(json);
        } catch (final IOException ex) {
            return JSON.createObjectNode();
        }
    }

    /** Folds minute samples into buckets: bucket start to value. */
    static Map<Long, Double> buckets(final Map<Long, Double> samples, final long step, final Rollup fold) {
        final Map<Long, List<Double>> grouped = new TreeMap<>();
        samples.forEach((at, value) -> grouped.computeIfAbsent(Math.floorDiv(at, step) * step,
                ignored -> new ArrayList<>()).add(value));
        final Map<Long, Double> out = new TreeMap<>();
        grouped.forEach((at, values) -> out.put(at, fold(values, fold)));
        return out;
    }

    static double fold(final List<Double> values, final Rollup fold) {
        return switch (fold) {
            case MAX -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case MIN -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            case SUM -> values.stream().mapToDouble(Double::doubleValue).sum();
            case AVG -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        };
    }

    /** The servers' buckets as one: the highest server's value, or all servers' together. */
    static Map<Long, Double> combined(final Collection<Map<Long, Double>> tasks, final Combine combine) {
        final Map<Long, Double> out = new TreeMap<>();
        for (final Map<Long, Double> task : tasks) {
            task.forEach((at, value) -> out.merge(at, value, combine == Combine.SUM ? Double::sum : Math::max));
        }
        return out;
    }

    private static List<Line> perTaskLines(final Map<String, Map<Long, Double>> byTask, final long first,
            final long last, final long step) {
        final List<Line> lines = new ArrayList<>();
        byTask.forEach((task, buckets) -> lines.add(new Line(task, dense(buckets, first, last, step))));
        return lines;
    }

    /** Every bucket from {@code first} to {@code last}, null where there was no sample. */
    static List<Point> dense(final Map<Long, Double> buckets, final long first, final long last, final long step) {
        final List<Point> points = new ArrayList<>();
        for (long at = first; at <= last; at += step) {
            points.add(new Point(at, buckets.get(at)));
        }
        return points;
    }
}
