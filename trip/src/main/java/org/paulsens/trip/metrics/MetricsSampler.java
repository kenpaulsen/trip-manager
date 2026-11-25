package org.paulsens.trip.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.metrics.PoolMetricPublisher.PoolSample;

/**
 * Reads one minute's numbers into the catalog's records ({@link MetricCatalog}), metric name to field values.
 * Called once a minute by the registry's publish, which drains every {@link MinuteMax} whether or not the minute is
 * then written, so a peak never outlives its minute.
 *
 * <p>A metric with nothing behind it is left out rather than written as zeros: local mode never builds the DynamoDB
 * client, so it has no pool record, and a graph shows that as no data rather than as an idle pool.
 */
@Slf4j
final class MetricsSampler {

    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    private final PoolMetricPublisher pool;
    // Only the registry's publish calls sample, one minute at a time, so plain fields are enough.
    private long poolFailuresSeen;
    private long minutesSincePoolWarning = ValkeyMeterRegistry.LOG_EVERY;

    MetricsSampler(final PoolMetricPublisher pool) {
        this.pool = pool;
    }

    Map<String, Map<String, Number>> sample(final MeterRegistry registry) {
        final Map<String, Map<String, Number>> records = new LinkedHashMap<>();
        final PoolSample poolSample = pool.drain();
        warnOfUnreadPoolReports();
        if (poolSample.limit() > 0) {
            records.put(MetricCatalog.DDB_POOL.name(), Map.of("max", poolSample.leasedMax(),
                    "pending", poolSample.pendingMax(), "calls", poolSample.calls(), "limit", poolSample.limit()));
        }
        final double used = heap(registry, "jvm.memory.used");
        if (used > 0) {
            records.put(MetricCatalog.JVM_HEAP.name(), Map.of("used", used,
                    "committed", heap(registry, "jvm.memory.committed"), "max", heap(registry, "jvm.memory.max")));
        }
        final Collection<Timer> gc = registry.find("jvm.gc.pause").timers();
        records.put(MetricCatalog.JVM_GC.name(), Map.of(
                "pauses", gc.stream().mapToLong(Timer::count).sum(),
                "pauseMs", gc.stream().mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS)).sum()));
        records.put(MetricCatalog.HTTP_REQUESTS.name(), requests(registry));
        return records;
    }

    /**
     * Says in the log when DynamoDB pool reports could not be read. The publisher runs inside every DynamoDB call, so
     * it may only count a failure; this is where it shows: the first minute that has new ones, then at most once an
     * hour while they go on. Silent, an SDK change would leave the pool graphs at zero beside a believable call count.
     */
    private void warnOfUnreadPoolReports() {
        final long failures = pool.dropped();
        final boolean grew = failures > poolFailuresSeen;
        poolFailuresSeen = failures;
        minutesSincePoolWarning++;
        if (grew && minutesSincePoolWarning >= ValkeyMeterRegistry.LOG_EVERY) {
            minutesSincePoolWarning = 0;
            log.warn("DynamoDB pool reports not read ({} since start), so the pool graphs show zeros: {}", failures,
                    pool.lastFailure());
        }
    }

    private static Map<String, Number> requests(final MeterRegistry registry) {
        final Timer timer = registry.find(RequestMetricsFilter.TIMER).timer();
        final Counter errors = registry.find(RequestMetricsFilter.ERRORS).counter();
        return Map.of(
                "count", timer == null ? 0L : timer.count(),
                "errors", errors == null ? 0L : (long) errors.count(),
                "meanMs", timer == null ? 0.0 : timer.mean(TimeUnit.MILLISECONDS),
                "maxMs", RequestMetricsFilter.drainSlowestMillis(),
                "active", RequestMetricsFilter.drainActivePeak());
    }

    /**
     * The heap's total of one memory gauge, in MB: the binder reports one gauge per memory pool, and a pool with
     * no defined maximum answers -1, which must not shrink the sum.
     */
    private static double heap(final MeterRegistry registry, final String name) {
        return registry.find(name).tag("area", "heap").gauges().stream().mapToDouble(Gauge::value)
                .filter(value -> value > 0).sum() / BYTES_PER_MB;
    }
}
