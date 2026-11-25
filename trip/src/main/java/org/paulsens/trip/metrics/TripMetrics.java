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
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
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
    private static final Lifecycle LIFECYCLE = new Lifecycle(REGISTRY, TripMetrics::newScheduler);
    /**
     * How far past each minute's start the publish runs: late enough that the minute is over everywhere, early enough
     * that its peaks are still that minute's.
     */
    static final long PUBLISH_OFFSET_MILLIS = 1_000;

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
        start(LIFECYCLE);
    }

    /** Test seam: {@link #start()} on a lifecycle of the test's own, so a test never starts the real publisher. */
    static void start(final Lifecycle lifecycle) {
        if (lifecycle.start()) {
            log.info("Metrics publishing every minute");
        }
    }

    /** Publishes the last, partial minute and stops; nothing when it was never started. */
    public static void stop() {
        LIFECYCLE.stop();
    }

    /**
     * The publisher's start and stop, as an instance so a test can drive them with its own registry and scheduler
     * rather than starting the real publisher (whose registry, once closed, stays closed for the JVM).
     */
    static final class Lifecycle {
        /** A private lock rather than the instance: nothing outside can hold it and stall a start or a stop. */
        private final Object lock = new Object();
        private final ValkeyMeterRegistry registry;
        private final Supplier<ScheduledExecutorService> schedulers;
        private JvmGcMetrics gcMetrics;
        private ScheduledExecutorService scheduler;
        private boolean started;

        Lifecycle(final ValkeyMeterRegistry registry, final Supplier<ScheduledExecutorService> schedulers) {
            this.registry = registry;
            this.schedulers = schedulers;
        }

        /** Starts publishing, or does nothing when already started; answers whether this call started it. */
        boolean start() {
            synchronized (lock) {
                if (started) {
                    return false;
                }
                new JvmMemoryMetrics().bindTo(registry);
                gcMetrics = new JvmGcMetrics();
                gcMetrics.bindTo(registry);
                scheduler = schedulers.get();
                scheduler.scheduleAtFixedRate(registry::publishSafely, delayToNextMinute(System.currentTimeMillis()),
                        ValkeyMeterRegistry.STEP.toMillis(), TimeUnit.MILLISECONDS);
                scheduler.execute(registry::publishFirst);
                started = true;
                return true;
            }
        }

        /** Publishes the last, partial minute and stops, or does nothing when not started. */
        void stop() {
            synchronized (lock) {
                if (!started) {
                    return;
                }
                scheduler.shutdownNow();
                registry.close();
                gcMetrics.close();
                started = false;
            }
        }
    }

    /** Milliseconds from {@code nowMillis} to {@value #PUBLISH_OFFSET_MILLIS} ms past the next minute's start. */
    static long delayToNextMinute(final long nowMillis) {
        final long step = ValkeyMeterRegistry.STEP.toMillis();
        return step - Math.floorMod(nowMillis, step) + PUBLISH_OFFSET_MILLIS;
    }

    static MetricsWriter writer() {
        return WriterHolder.INSTANCE;
    }

    /** The publisher's one thread, from {@code TripThreads.factory()} so every write rides the background lane. */
    static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(TripThreads.factory());
    }

    /** The kill switch, read once a minute on the publisher's thread; a settings failure leaves metrics on. */
    static boolean enabled() {
        return enabled(() -> new ConfigCommands().getBoolean(KnownSettings.METRICS_ENABLED));
    }

    /** Test seam: {@link #enabled()} over its settings read. */
    static boolean enabled(final BooleanSupplier setting) {
        try {
            return setting.getAsBoolean();
        } catch (final RuntimeException ex) {
            return true;
        }
    }

    /** The task id is resolved lazily, on the publisher's first minute: a metadata call never delays startup. */
    private static final class WriterHolder {
        static final MetricsWriter INSTANCE = new MetricsWriter(() -> DAO.getInstance().getCacheClient(),
                TaskIdentity.resolve());
    }
}
