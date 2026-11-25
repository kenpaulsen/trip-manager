package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Serves {@link DeepHealth} as JSON: {@code {"status":"ok","checks":{"cache":"ok",...}}}, 200 when no check
 * failed and 503 when one did. The deploy's green check reads it; the load balancer does not (see
 * {@link DeepHealth} for why the two stay apart).
 *
 * <p>Sessionless like {@code health.jsf}: it never asks for a session, so a probe mints no cookie (issue #19).
 * {@code no-store}, because a cached answer would vouch for a server nobody asked.
 */
public class DeepHealthServlet extends HttpServlet {

    private transient DeepHealth health;

    void setHealth(final DeepHealth health) {
        this.health = health;
    }

    @Override
    public void init() {
        if (health == null) {
            health = DeepHealth.production();
        }
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final DeepHealth.Report report = health.report();
        resp.setStatus(report.healthy() ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        resp.setContentType("application/json");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setHeader("Cache-Control", "no-store");
        resp.getOutputStream().write(toJson(report).getBytes(StandardCharsets.UTF_8));
    }

    /** By hand: the names are fixed identifiers and the values an enum, so nothing here needs escaping. */
    static String toJson(final DeepHealth.Report report) {
        final String checks = report.checks().entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":\"" + e.getValue().wire() + "\"")
                .collect(Collectors.joining(","));
        return "{\"status\":\"" + (report.healthy() ? "ok" : "fail") + "\",\"checks\":{" + checks + "}}";
    }
}
