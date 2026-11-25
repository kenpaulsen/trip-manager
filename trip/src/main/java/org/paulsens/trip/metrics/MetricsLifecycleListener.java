package org.paulsens.trip.metrics;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import lombok.extern.slf4j.Slf4j;

/**
 * Starts and stops the metrics publisher ({@link TripMetrics}). Declared in the live {@code web.xml} AFTER
 * {@code TripBootstrapListener}, which decides local mode before anything reaches the DAO. A failure here is
 * logged and swallowed: metrics must never keep the site from starting.
 */
@Slf4j
public class MetricsLifecycleListener implements ServletContextListener {

    private final Runnable start;
    private final Runnable stop;

    public MetricsLifecycleListener() {
        this(TripMetrics::start, TripMetrics::stop);
    }

    /** Test seam: what starting and stopping do. */
    MetricsLifecycleListener(final Runnable start, final Runnable stop) {
        this.start = start;
        this.stop = stop;
    }

    @Override
    public void contextInitialized(final ServletContextEvent event) {
        try {
            start.run();
        } catch (final RuntimeException ex) {
            log.error("Metrics did not start; the site runs without them", ex);
        }
    }

    @Override
    public void contextDestroyed(final ServletContextEvent event) {
        try {
            stop.run();
        } catch (final RuntimeException ex) {
            log.warn("Metrics did not stop cleanly", ex);
        }
    }
}
