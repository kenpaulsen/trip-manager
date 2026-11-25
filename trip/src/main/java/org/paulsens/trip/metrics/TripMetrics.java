package org.paulsens.trip.metrics;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.util.TripThreads;

/**
 * The app's one metrics registry ({@code docs/metrics.md}): created with the class, so meters can be recorded from
 * the first request on, and publishing to Valkey from {@link #start()} (the {@code MetricsLifecycleListener}) until
 * {@link #stop()}. Unit tests never start it: their meters simply go unpublished.
 */
@Slf4j
public final class TripMetrics {

    /**
     * The only Micrometer meters kept: the ones {@link MetricsSampler} reads. The JVM binders register dozens; every
     * other one is denied at registration, so it costs nothing and can never be stored by accident.
     */
    static final List<String> KEPT = List.of("jvm.memory.used", "jvm.memory.committed", "jvm.memory.max",
            "jvm.gc.pause", RequestMetricsFilter.TIMER, RequestMetricsFilter.ERRORS);

    private static final ValkeyMeterRegistry REGISTRY = create();
    /** A private lock rather than the class's own: nothing outside can hold it and stall a start or a stop. */
    private static final Object LOCK = new Object();
    /**
     * How far past each minute's start the publish runs: late enough that the minute is over everywhere, early enough
     * that its peaks are still that minute's.
     */
    static final long PUBLISH_OFFSET_MILLIS = 1_000;
    private static JvmGcMetrics gcMetrics;
    private static ScheduledExecutorService scheduler;
    private static boolean started;

    private TripMetrics() {
    }

    private static ValkeyMeterRegistry create() {
        final ValkeyMeterRegistry registry = new ValkeyMeterRegistry(Clock.SYSTEM,
                new MetricsSampler(PoolMetricPublisher.INSTANCE), TripMetrics::writer, TripMetrics::enabled);
        registry.config().meterFilter(MeterFilter.denyUnless(id -> KEPT.contains(id.getName())));
        return registry;
    }

    /** The registry every meter of ours is recorded in: this package's alone (the filter, the sampler). */
    static MeterRegistry registry() {
        return REGISTRY;
    }

    /**
     * Binds the JVM meters and starts the once-a-minute publish, its thread from {@code TripThreads.factory()} so
     * every write rides the background cache lane. Idempotent.
     *
     * <p>Our own schedule, not Micrometer's {@code start()}: Micrometer publishes at a RANDOM point in the first 80%
     * of each step (its {@code calculateInitialDelay}, read from the 1.18.0 bytecode), which leaves its step counts
     * exact but would make each reset-on-read peak ({@link MinuteMax}) a 60-second window shifted by up to 48 seconds
     * from the minute it is filed under. Publishing {@value #PUBLISH_OFFSET_MILLIS} ms past each wall-clock minute
     * keeps every stored number that minute's.
     */
    public static void start() {
        synchronized (LOCK) {
            if (started) {
                return;
            }
            new JvmMemoryMetrics().bindTo(REGISTRY);
            gcMetrics = new JvmGcMetrics();
            gcMetrics.bindTo(REGISTRY);
            scheduler = Executors.newSingleThreadScheduledExecutor(TripThreads.factory());
            scheduler.scheduleAtFixedRate(REGISTRY::publishSafely, delayToNextMinute(System.currentTimeMillis()),
                    ValkeyMeterRegistry.STEP.toMillis(), TimeUnit.MILLISECONDS);
            scheduler.execute(REGISTRY::publishFirst);
            started = true;
        }
        log.info("Metrics publishing every minute");
    }

    /** Publishes the last, partial minute and stops. */
    public static void stop() {
        synchronized (LOCK) {
            if (!started) {
                return;
            }
            scheduler.shutdownNow();
            REGISTRY.close();
            gcMetrics.close();
            started = false;
        }
    }

    /** Milliseconds from {@code nowMillis} to {@value #PUBLISH_OFFSET_MILLIS} ms past the next minute's start. */
    static long delayToNextMinute(final long nowMillis) {
        final long step = ValkeyMeterRegistry.STEP.toMillis();
        return step - Math.floorMod(nowMillis, step) + PUBLISH_OFFSET_MILLIS;
    }

    private static MetricsWriter writer() {
        return Writer.INSTANCE.writer();
    }

    /** The kill switch, read once a minute on the publisher's thread; a settings failure leaves metrics on. */
    private static boolean enabled() {
        try {
            return new ConfigCommands().getBoolean(KnownSettings.METRICS_ENABLED);
        } catch (final RuntimeException ex) {
            return true;
        }
    }

    /** The task id is resolved lazily, on the publisher's first minute: a metadata call never delays startup. */
    private record Writer(MetricsWriter writer) {
        static final Writer INSTANCE = new Writer(new MetricsWriter(() -> DAO.getInstance().getCacheClient(),
                TaskIdentity.resolve()));
    }
}
