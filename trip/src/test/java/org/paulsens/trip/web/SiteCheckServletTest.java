package org.paulsens.trip.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.site.DomainProbe;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * The probe's answer: a code this server has outstanding is echoed as plain text, anything else is a 404, and
 * neither touches a session (the probe is anonymous, on a host no organization holds yet).
 */
public class SiteCheckServletTest {

    @AfterMethod(alwaysRun = true)
    public void restoreCheck() {
        DomainProbe.useCheck(null);
    }

    @Test
    public void anOutstandingCodeIsEchoedAndAnythingElseIsA404() throws Exception {
        final List<String> bodies = new ArrayList<>();
        DomainProbe.useCheck((domain, code) -> askServlet(code, bodies));
        Assert.assertNull(DomainProbe.newClaimProblem("example.org", null), "the servlet echoed the code");
        Assert.assertEquals(bodies.size(), 1);

        final HttpServletRequest stranger = request("not-a-code");
        final HttpServletResponse refused = Mockito.mock(HttpServletResponse.class);
        new SiteCheckServlet().doGet(stranger, refused);
        Mockito.verify(refused).sendError(HttpServletResponse.SC_NOT_FOUND);
        Mockito.verify(refused, Mockito.never()).getOutputStream();
        Mockito.verify(stranger, Mockito.never()).getSession(ArgumentMatchers.anyBoolean());
        Mockito.verify(stranger, Mockito.never()).getSession();
    }

    /** What the probe would read back from this servlet, mid-probe; null when it echoed {@code code}. */
    private static String askServlet(final String code, final List<String> bodies) {
        try {
            final HttpServletRequest request = request(code);
            final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
            final ByteArrayOutputStream body = new ByteArrayOutputStream();
            Mockito.when(response.getOutputStream()).thenReturn(streamInto(body));
            new SiteCheckServlet().doGet(request, response);
            Mockito.verify(response).setStatus(HttpServletResponse.SC_OK);
            Mockito.verify(response).setContentType("text/plain");
            Mockito.verify(response).setHeader("Cache-Control", "no-store");
            Mockito.verify(request, Mockito.never()).getSession(ArgumentMatchers.anyBoolean());
            final String echoed = body.toString(StandardCharsets.UTF_8);
            bodies.add(echoed);
            return code.equals(echoed) ? null : "not echoed";
        } catch (final Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static HttpServletRequest request(final String code) {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getParameter("code")).thenReturn(code);
        return request;
    }

    private static ServletOutputStream streamInto(final ByteArrayOutputStream body) {
        return new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(final WriteListener listener) {
                // Blocking writes only.
            }

            @Override
            public void write(final int b) {
                body.write(b);
            }
        };
    }
}
