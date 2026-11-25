package org.paulsens.trip.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Times every request for the {@code http.requests} metric: how many, how many failed (5xx), their mean and slowest
 * time, and the most in flight at once. Declared in the live {@code web.xml}, mapped to every path.
 *
 * <p>Why a filter and not Micrometer's Tomcat binder: the request threads are virtual, so the connector's thread
 * pool has no "busy threads" to report, and requests in flight is the number that still means something. The
 * count and total time ride Micrometer's step timer (exact per minute); the slowest request and the in-flight peak
 * are {@link MinuteMax}es, because Micrometer's max decays over more than one minute.
 */
public class RequestMetricsFilter implements Filter {

    /** The Micrometer names, read back by {@link MetricsSampler}. */
    static final String TIMER = "trip.http.requests";
    static final String ERRORS = "trip.http.errors";

    private static final MinuteMax SLOWEST_NANOS = new MinuteMax();
    private static final MinuteMax ACTIVE = new MinuteMax();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private final Supplier<MeterRegistry> registry;

    public RequestMetricsFilter() {
        this(TripMetrics::registry);
    }

    /** Test seam: the registry the count and errors go to (the app's own publishes only once a minute). */
    RequestMetricsFilter(final Supplier<MeterRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        final long start = System.nanoTime();
        ACTIVE.record(IN_FLIGHT.incrementAndGet());
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            IN_FLIGHT.decrementAndGet();
            record(System.nanoTime() - start, failed || serverError(response));
        }
    }

    /** One finished request; never throws, so a metric cannot fail the response it describes. */
    void record(final long nanos, final boolean failed) {
        try {
            SLOWEST_NANOS.record(nanos);
            timer().record(nanos, TimeUnit.NANOSECONDS);
            if (failed) {
                errors().increment();
            }
        } catch (final RuntimeException ex) {
            // A metric must never fail a request: dropped.
            return;
        }
    }

    private static boolean serverError(final ServletResponse response) {
        return response instanceof HttpServletResponse http && http.getStatus() >= 500;
    }

    Timer timer() {
        return Timer.builder(TIMER).register(registry.get());
    }

    Counter errors() {
        return Counter.builder(ERRORS).register(registry.get());
    }

    /** This minute's slowest request in milliseconds, starting the next minute over. */
    static double drainSlowestMillis() {
        return SLOWEST_NANOS.drain() / 1_000_000.0;
    }

    /**
     * This minute's most requests in flight at once. The next minute starts from the requests still in flight, so
     * a slow request that spans the minute boundary counts in both.
     */
    static long drainActivePeak() {
        final long peak = ACTIVE.drain();
        ACTIVE.record(IN_FLIGHT.get());
        return peak;
    }

    /** This minute's slowest request so far, in milliseconds, left in place. */
    static double peekSlowestMillis() {
        return SLOWEST_NANOS.peek() / 1_000_000.0;
    }

    /** This minute's most requests in flight so far, left in place. */
    static long peekActivePeak() {
        return ACTIVE.peek();
    }
}
