package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.web.DeepHealth.Check;
import org.paulsens.trip.web.DeepHealth.Status;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link DeepHealthServlet}: the status code is the verdict, the body names checks and states only, and a probe
 * never touches a session (issue #19: an anonymous request mints nothing).
 */
public class DeepHealthServletTest {

    private static DeepHealthServlet servletAnswering(final Status cache, final Status media) {
        final Map<String, Check> checks = new LinkedHashMap<>();
        checks.put("cache", () -> cache);
        checks.put("media", () -> media);
        final DeepHealthServlet servlet = new DeepHealthServlet();
        servlet.setHealth(new DeepHealth(checks, Duration.ofSeconds(2), System::currentTimeMillis));
        servlet.init();
        return servlet;
    }

    private static String get(final DeepHealthServlet servlet, final HttpServletRequest request,
            final HttpServletResponse response) throws Exception {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        Mockito.when(response.getOutputStream()).thenReturn(CapturedResponse.streamInto(body));
        servlet.doGet(request, response);
        return body.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void healthyAnswers200WithEveryCheckAndNoSession() throws Exception {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        final String body = get(servletAnswering(Status.OK, Status.NOT_CONFIGURED), request, response);

        Assert.assertEquals(body, "{\"status\":\"ok\",\"checks\":{\"cache\":\"ok\",\"media\":\"not_configured\"}}");
        Mockito.verify(response).setStatus(HttpServletResponse.SC_OK);
        Mockito.verify(response).setContentType("application/json");
        Mockito.verify(response).setHeader("Cache-Control", "no-store");
        Mockito.verify(request, Mockito.never()).getSession();
        Mockito.verify(request, Mockito.never()).getSession(ArgumentMatchers.anyBoolean());
    }

    @Test
    public void aFailedCheckAnswers503() throws Exception {
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        final String body = get(servletAnswering(Status.FAIL, Status.OK), Mockito.mock(HttpServletRequest.class),
                response);
        Assert.assertEquals(body, "{\"status\":\"fail\",\"checks\":{\"cache\":\"fail\",\"media\":\"ok\"}}");
        Mockito.verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    public void initBuildsTheProductionChecksWhenNoneWereGiven() throws Exception {
        final DeepHealthServlet servlet = new DeepHealthServlet();
        servlet.init();
        final String body = get(servlet, Mockito.mock(HttpServletRequest.class),
                Mockito.mock(HttpServletResponse.class));
        Assert.assertTrue(body.contains("\"datastore\":\"ok\""), body);
    }
}
