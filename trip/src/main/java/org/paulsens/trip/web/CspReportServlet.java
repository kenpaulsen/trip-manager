package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Where browsers POST Content-Security-Policy violation reports ({@code report-uri} and {@code report-to} in
 * {@link ContentSecurityPolicy}): one log line per report, which CloudWatch keeps with the rest of the
 * application log ({@code docs/security-headers.md}, "Reading the reports").
 *
 * <p>Deliberately dumb. The body is browser-supplied JSON about a page the browser loaded, so nothing here parses
 * it beyond trimming it to one line: a parser would be attack surface on an anonymous endpoint. It is capped at
 * {@link #MAX_BODY_BYTES} and the servlet logs at most {@link #MAX_PER_MINUTE} reports a minute, because a
 * misconfigured policy, a browser extension or a scanner could otherwise write to CloudWatch as fast as it can
 * POST, and log ingestion is billed by the byte. Nothing here touches a session (issue medjugorje#19): browsers
 * send reports without credentials, and a report must never mint one.
 */
@Slf4j
public class CspReportServlet extends HttpServlet {

    static final int MAX_BODY_BYTES = 8 * 1024;
    static final int MAX_PER_MINUTE = 20;

    private static final long MINUTE_MILLIS = 60_000L;

    private final AtomicLong windowStart = new AtomicLong();
    private final AtomicInteger inWindow = new AtomicInteger();
    private LongSupplier clock = System::currentTimeMillis;

    /** Test seam: the clock the rate limiter reads. */
    void setClock(final LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final String body = readBody(req);
        if (admit()) {
            log.warn("CSP violation report from {}: {}", req.getHeader("User-Agent"), body);
        }
        // 204 whatever happened: the browser does nothing with the answer, and a non-2xx would only make it
        // retry or log a second error about the first.
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    /** At most {@link #MAX_PER_MINUTE} admitted per clock minute; the rest are dropped on the floor. */
    boolean admit() {
        final long now = clock.getAsLong();
        final long start = windowStart.get();
        if (now - start >= MINUTE_MILLIS && windowStart.compareAndSet(start, now)) {
            inWindow.set(0);
        }
        return inWindow.incrementAndGet() <= MAX_PER_MINUTE;
    }

    /** The first {@link #MAX_BODY_BYTES} of the body, whitespace collapsed so a report is one log line. */
    private static String readBody(final HttpServletRequest req) throws IOException {
        try (InputStream in = req.getInputStream()) {
            final byte[] bytes = in.readNBytes(MAX_BODY_BYTES);
            final boolean truncated = in.read() >= 0;
            final String text = new String(bytes, StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
            return truncated ? text + " ...[truncated]" : text;
        }
    }
}
