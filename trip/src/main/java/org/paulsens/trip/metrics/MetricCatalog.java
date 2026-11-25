package org.paulsens.trip.metrics;

import java.util.List;
import java.util.Optional;

/**
 * Every metric the app stores, declared once (the {@code KnownSettings} pattern): the writer stores exactly these,
 * the reader and the dashboard's graph template offer exactly these, and nothing else reaches Valkey. A metric is
 * one record per minute per server (ECS task) with a few numeric FIELDS; a graph shows one field.
 *
 * <p>Text is by key in the {@code server} bundle ({@code server.metrics.<metric>.label},
 * {@code server.metrics.<metric>.<field>}, {@code server.metrics.unit.<unit>}; {@code MetricGraphType.METRIC_TEXT},
 * {@code docs/metrics.md}), so the graph template's metric list reads in the viewer's language.
 */
public final class MetricCatalog {

    /** How a field's per-minute values fold into one bucket of a graph (a graph's own choice may override it). */
    public enum Rollup {
        /** The bucket's highest value: peaks, so a spike survives a coarse graph. */
        MAX,
        /** The bucket's mean. */
        AVG,
        /** The bucket's lowest value. */
        MIN,
        /** The bucket's total: counts, always (an average of counts per minute would be a different number). */
        SUM
    }

    /** How the servers' values for one minute combine into the one line a graph shows by default. */
    public enum Combine {
        /** The highest server's value: peaks and levels. */
        MAX,
        /** All servers together: counts. */
        SUM
    }

    /**
     * One number in a metric's record.
     *
     * @param name   its key in the stored JSON
     * @param unit   the unit a graph's axis names ({@code connections}, {@code ms}, {@code MB}, {@code requests})
     * @param rollup how its minutes fold into a bucket by default; {@link Rollup#SUM} means it is a count
     * @param combine how servers combine
     * @param limit  the name of the field in the same record that is this one's ceiling (drawn as a line), or null
     */
    public record Field(String name, String unit, Rollup rollup, Combine combine, String limit) {

        /** Whether this is a count: its buckets always sum, whatever a graph asks. */
        public boolean isCount() {
            return rollup == Rollup.SUM;
        }
    }

    /**
     * One stored metric.
     *
     * @param name   its address: the key segment {@code metrics:v1:<name>:<task>} and the text keys' prefix
     * @param fields its numbers, in the order a menu lists them
     */
    public record Metric(String name, List<Field> fields) {

        /** The field called {@code fieldName}, if this metric has it. */
        public Optional<Field> field(final String fieldName) {
            return fields.stream().filter(field -> field.name().equals(fieldName)).findFirst();
        }
    }

    /** DynamoDB's connection pool: the per-minute peak in use, peak waiting, calls, and the pool's size. */
    public static final Metric DDB_POOL = new Metric("ddb.pool", List.of(
            new Field("max", "connections", Rollup.MAX, Combine.MAX, "limit"),
            new Field("pending", "calls", Rollup.MAX, Combine.MAX, null),
            new Field("calls", "calls", Rollup.SUM, Combine.SUM, null),
            new Field("limit", "connections", Rollup.MAX, Combine.MAX, null)));

    /** The JVM heap at the minute's end: used, committed by the JVM, and its ceiling. */
    public static final Metric JVM_HEAP = new Metric("jvm.heap", List.of(
            new Field("used", "MB", Rollup.MAX, Combine.MAX, "max"),
            new Field("committed", "MB", Rollup.MAX, Combine.MAX, "max"),
            new Field("max", "MB", Rollup.MAX, Combine.MAX, null)));

    /** Garbage collection in the minute: how many pauses and their total time. */
    public static final Metric JVM_GC = new Metric("jvm.gc", List.of(
            new Field("pauses", "pauses", Rollup.SUM, Combine.SUM, null),
            new Field("pauseMs", "ms", Rollup.SUM, Combine.MAX, null)));

    /** Requests in the minute: how many, how many failed, their mean and slowest time, the most in flight. */
    public static final Metric HTTP_REQUESTS = new Metric("http.requests", List.of(
            new Field("count", "requests", Rollup.SUM, Combine.SUM, null),
            new Field("errors", "requests", Rollup.SUM, Combine.SUM, null),
            new Field("meanMs", "ms", Rollup.AVG, Combine.MAX, null),
            new Field("maxMs", "ms", Rollup.MAX, Combine.MAX, null),
            new Field("active", "requests", Rollup.MAX, Combine.MAX, null)));

    /** Every stored metric, in the order the graph template lists them. */
    public static final List<Metric> ALL = List.of(DDB_POOL, JVM_HEAP, JVM_GC, HTTP_REQUESTS);

    /** The metric called {@code name}, if the catalog has it. */
    public static Optional<Metric> metric(final String name) {
        return ALL.stream().filter(metric -> metric.name().equals(name)).findFirst();
    }

    private MetricCatalog() {
    }
}
