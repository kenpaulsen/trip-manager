package org.paulsens.trip.metrics;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A peak that starts over each time it is read: the exact per-minute maximum a once-a-minute publish needs.
 *
 * <p>Micrometer's own max is a decaying window, not one step: measured on 1.18.0 with a mock clock, a timer that
 * recorded 500 ms in one minute still reported 500 ms at the end of the NEXT minute, in which nothing was recorded
 * (docs/metrics.md, "Why our own peaks"). A burst that comes and goes between two samples is exactly what these
 * metrics exist to catch, so every peak we store comes from one of these.
 */
public final class MinuteMax {

    private final AtomicLong peak = new AtomicLong();

    /** Counts {@code value} toward this minute's peak. Lock-free: callers sit on hot paths. */
    public void record(final long value) {
        peak.accumulateAndGet(value, Math::max);
    }

    /** This minute's peak, starting the next minute at zero. */
    public long drain() {
        return peak.getAndSet(0);
    }

    /** This minute's peak so far, left in place for the read that ends the minute. */
    public long peek() {
        return peak.get();
    }
}
