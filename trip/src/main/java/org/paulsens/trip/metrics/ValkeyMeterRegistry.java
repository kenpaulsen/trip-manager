package org.paulsens.trip.metrics;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import io.micrometer.core.instrument.step.StepRegistryConfig;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Micrometer's push registry for our metrics: once a minute it samples the catalog ({@link MetricsSampler}) and
 * writes the records to Valkey ({@link MetricsWriter}). Its counts and timers are Micrometer step meters, so a
 * count read at publish is exactly the minute that just ended.
 *
 * <p>The kill switch ({@code metrics.enabled}) is read here and nowhere else, once a minute, on the publisher's own
 * thread: never inside a DynamoDB call or a request.
 */
@Slf4j
final class ValkeyMeterRegistry extends StepMeterRegistry {

    /** A failure is logged the first time and then once per this many lost minutes: about once an hour. */
    static final long LOG_EVERY = 60;

    /** One minute: the resolution of every stored metric. */
    static final Duration STEP = Duration.ofMinutes(1);

    private final MetricsSampler sampler;
    private final Supplier<MetricsWriter> writer;
    private final BooleanSupplier enabled;
    private volatile boolean closing;
    private volatile boolean current;
    private final AtomicLong dropped = new AtomicLong();
    // Its own count, not dropped's: refused writes also add to dropped (the cache logs those), and sharing the
    // count let them push a first failure off the "% LOG_EVERY == 1" beat, so it went unlogged for an hour.
    private final AtomicLong failures = new AtomicLong();

    ValkeyMeterRegistry(final Clock clock, final MetricsSampler sampler, final Supplier<MetricsWriter> writer,
            final BooleanSupplier enabled) {
        super(new OneMinuteConfig(), clock);
        this.sampler = sampler;
        this.writer = writer;
        this.enabled = enabled;
    }

    @Override
    protected void publish() {
        // Sample first, always: it drains the minute's peaks, which must not carry into the next minute.
        final Map<String, Map<String, Number>> records = current ? sampler.peek(this) : sampler.sample(this);
        if (records.isEmpty() || !enabled.getAsBoolean()) {
            return;
        }
        try {
            if (!writer.get().write(minuteStart(), records)) {
                // The cache refused part of it (and logged that): the next minute tries again.
                dropped.incrementAndGet();
            }
        } catch (final RuntimeException ex) {
            lost(ex);
        }
    }

    /**
     * The scheduled publish: a failure anywhere (a meter that cannot be read) is counted, logged now and then, and
     * dropped, never thrown into the scheduler, whose next run would otherwise never come.
     */
    void publishSafely() {
        try {
            publish();
        } catch (final RuntimeException ex) {
            lost(ex);
        }
    }

    /**
     * Counts a minute that could not be written and says why in the log, the first time and then once an hour:
     * a meter that breaks after an upgrade must show somewhere, but never as a line a minute.
     */
    private void lost(final RuntimeException ex) {
        dropped.incrementAndGet();
        final long count = failures.incrementAndGet();
        if (count == 1) {
            // The stack trace once: a broken meter's frames say which binder it is.
            log.warn("Metrics minute not written (the first failure since start)", ex);
        } else if (count % LOG_EVERY == 1) {
            log.warn("Metrics minute not written ({} failures since start): {}", count, ex.toString());
        }
    }

    /**
     * How many minutes could not be written, or not wholly, since start: zero unless the cache refused a write or a
     * meter could not be read.
     */
    long dropped() {
        return dropped.get();
    }

    /**
     * One publish right after start, so a fresh server (and a page opened right after a deploy) has a minute to show
     * at once rather than after the first full minute. It holds the current, partial minute, which the first
     * regular publish then overwrites with the whole minute; so it peeks rather than drains, or the peaks it read
     * would be missing from that whole minute.
     */
    void publishFirst() {
        current = true;
        try {
            publishSafely();
        } finally {
            current = false;
        }
    }

    /**
     * The minute the records describe, in epoch seconds: the one that just ended. The first publish and the last one
     * (at shutdown) hold the current, partial minute instead, so neither overwrites the complete minute before it.
     */
    long minuteStart() {
        final long minute = clock.wallTime() / STEP.toMillis();
        return (closing || current ? minute : minute - 1) * STEP.toSeconds();
    }

    @Override
    public void close() {
        closing = true;
        super.close();
    }

    @Override
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.MILLISECONDS;
    }

    /** A one-minute step and no other configuration (Micrometer's {@code get} answers null for every key). */
    private static final class OneMinuteConfig implements StepRegistryConfig {
        @Override
        public String prefix() {
            return "trip.metrics";
        }

        @Override
        public String get(final String key) {
            return null;
        }

        @Override
        public Duration step() {
            return STEP;
        }
    }
}
