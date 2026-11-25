package org.paulsens.trip.metrics;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.testng.annotations.Test;

/** Every request timed and counted, a 5xx or a throw counted as an error, and a failure to count never fatal. */
public class RequestMetricsFilterTest {

    @Test
    public void requestsAreTimedAndFailuresCounted() throws Exception {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final RequestMetricsFilter filter = new RequestMetricsFilter(() -> registry);
        RequestMetricsFilter.drainSlowestMillis();
        RequestMetricsFilter.drainActivePeak();

        filter.doFilter(mock(ServletRequest.class), response(200), (request, response) -> pause());
        filter.doFilter(mock(ServletRequest.class), response(503), mock(FilterChain.class));
        final FilterChain throwing = mock(FilterChain.class);
        final HttpServletResponse ok = response(200);
        doThrow(new ServletException("boom")).when(throwing).doFilter(null, ok);
        assertThrows(ServletException.class, () -> filter.doFilter(null, ok, throwing));

        assertEquals(filter.errors().count(), 2.0, "the 503 and the throw");
        assertEquals(filter.timer().count(), 3L, "every request, failed or not");
        assertEquals(new RequestMetricsFilter().timer().getId().getName(), RequestMetricsFilter.TIMER);
        assertTrue(RequestMetricsFilter.drainSlowestMillis() >= 5.0, "the slow one is this minute's slowest");
        assertEquals(RequestMetricsFilter.drainActivePeak(), 1L, "one at a time");
        assertEquals(RequestMetricsFilter.drainActivePeak(), 0L, "none left in flight");
    }

    private static HttpServletResponse response(final int status) {
        final HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getStatus()).thenReturn(status);
        return response;
    }

    private static void pause() throws IOException {
        try {
            Thread.sleep(6);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        }
    }
}
