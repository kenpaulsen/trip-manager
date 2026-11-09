package org.paulsens.trip.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The report sink: every POST is a 204 whatever it carries, the body is read to its cap and no further, a GET is
 * refused, and the per-minute admission window opens and closes on the clock alone.
 */
public class CspReportServletTest {

    private HttpServletRequest req;
    private HttpServletResponse resp;
    private CspReportServlet servlet;
    private AtomicLong now;

    @BeforeMethod
    public void setUp() {
        req = Mockito.mock(HttpServletRequest.class);
        resp = Mockito.mock(HttpServletResponse.class);
        servlet = new CspReportServlet();
        now = new AtomicLong(1_000_000L);
        servlet.setClock(now::get);
    }

    private void body(final byte[] bytes) throws IOException {
        final ByteArrayInputStream source = new ByteArrayInputStream(bytes);
        Mockito.when(req.getInputStream()).thenReturn(new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(final ReadListener listener) {
            }
        });
    }

    @Test
    public void aReportIsAcceptedWithNoContent() throws Exception {
        body("{\"csp-report\":{\"violated-directive\":\"img-src\"}}".getBytes(StandardCharsets.UTF_8));

        servlet.doPost(req, resp);

        Mockito.verify(resp).setStatus(HttpServletResponse.SC_NO_CONTENT);
        Mockito.verify(req, Mockito.never()).getSession();
        Mockito.verify(req, Mockito.never()).getSession(Mockito.anyBoolean());
    }

    @Test
    public void anOversizedBodyIsStillAcceptedButReadOnlyToTheCap() throws Exception {
        final byte[] huge = new byte[CspReportServlet.MAX_BODY_BYTES * 3];
        Arrays.fill(huge, (byte) 'x');
        body(huge);

        servlet.doPost(req, resp);

        Mockito.verify(resp).setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Test
    public void aGetIsNotAllowed() throws Exception {
        servlet.doGet(req, resp);

        Mockito.verify(resp).sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    @Test
    public void theRateLimitAdmitsTwentyAMinuteThenReopensWithTheClock() {
        for (int i = 0; i < CspReportServlet.MAX_PER_MINUTE; i++) {
            Assert.assertTrue(servlet.admit(), "report " + (i + 1) + " of the minute");
        }
        Assert.assertFalse(servlet.admit(), "the twenty-first is dropped");
        now.addAndGet(30_000L);
        Assert.assertFalse(servlet.admit(), "half a minute later it is still the same window");
        now.addAndGet(30_000L);
        Assert.assertTrue(servlet.admit(), "a new minute, a new allowance");
    }

    @Test
    public void droppedReportsStillAnswerNoContent() throws Exception {
        for (int i = 0; i <= CspReportServlet.MAX_PER_MINUTE; i++) {
            servlet.admit();
        }
        body("{}".getBytes(StandardCharsets.UTF_8));

        servlet.doPost(req, resp);

        Mockito.verify(resp).setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
