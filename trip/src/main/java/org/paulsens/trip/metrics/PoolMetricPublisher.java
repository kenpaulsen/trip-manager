package org.paulsens.trip.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import software.amazon.awssdk.http.HttpMetric;
import software.amazon.awssdk.metrics.MetricCollection;
import software.amazon.awssdk.metrics.MetricPublisher;

/**
 * The DynamoDB client's connection-pool numbers, from the AWS SDK's own in-process metric hook: every call reports
 * how many connections were leased, how many calls were waiting for one, and the pool's size. Nothing is sent
 * anywhere (no CloudWatch): each minute {@link #drain} hands the peaks to the Valkey writer.
 *
 * <p><b>This runs inside every DynamoDB call</b>, on the calling thread. So it must never read a setting (the
 * settings DAO sits on the same path, and would recurse), never touch the cache, never block and never throw: a
 * failure is counted and dropped.
 */
public final class PoolMetricPublisher implements MetricPublisher {

    /** The one instance the DynamoDB client reports to and the metrics writer drains. */
    public static final PoolMetricPublisher INSTANCE = new PoolMetricPublisher();

    private final MinuteMax leased = new MinuteMax();
    private final MinuteMax pending = new MinuteMax();
    private final LongAdder calls = new LongAdder();
    private final AtomicLong limit = new AtomicLong();
    private final LongAdder dropped = new LongAdder();

    /** One minute's pool numbers: the peaks, the calls made, and the pool's size. */
    public record PoolSample(long leasedMax, long pendingMax, long calls, long limit) {
    }

    PoolMetricPublisher() {
    }

    @Override
    public void publish(final MetricCollection collection) {
        try {
            calls.increment();
            record(collection);
        } catch (final RuntimeException ex) {
            // Never let a metric fail the call it describes.
            dropped.increment();
        }
    }

    /** The HTTP numbers sit in a child collection (the call's attempt), so walk the whole tree. */
    private void record(final MetricCollection collection) {
        collection.metricValues(HttpMetric.LEASED_CONCURRENCY).forEach(leased::record);
        collection.metricValues(HttpMetric.PENDING_CONCURRENCY_ACQUIRES).forEach(pending::record);
        collection.metricValues(HttpMetric.MAX_CONCURRENCY).forEach(limit::set);
        collection.children().forEach(this::record);
    }

    /** This minute's numbers, starting the next minute over (the pool size is kept: it is a setting, not a count). */
    public PoolSample drain() {
        return new PoolSample(leased.drain(), pending.drain(), calls.sumThenReset(), limit.get());
    }

    /** How many reports could not be read: zero unless the SDK changed shape. */
    public long dropped() {
        return dropped.sum();
    }

    @Override
    public void close() {
        // Nothing to release: the numbers live in memory and are drained by the writer.
    }
}
