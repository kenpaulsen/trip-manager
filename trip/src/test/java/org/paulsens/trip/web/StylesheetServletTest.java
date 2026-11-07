package org.paulsens.trip.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.TripStylesheets;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;

/** The GET-only local-mode serving of a trip page stylesheet, and every 404 leg. */
public class StylesheetServletTest {

    private static final String KEY = "org/org-1/stylesheets/pw-styles.css";

    private HttpServletRequest req;
    private HttpServletResponse resp;
    private ByteArrayOutputStream out;
    private AtomicInteger status;
    private TripStylesheets sheets;
    private StylesheetServlet servlet;

    @BeforeMethod
    public void setUp() throws IOException {
        req = Mockito.mock(HttpServletRequest.class);
        resp = Mockito.mock(HttpServletResponse.class);
        out = new ByteArrayOutputStream();
        status = new AtomicInteger(200);
        sheets = Mockito.mock(TripStylesheets.class);
        servlet = new StylesheetServlet();
        servlet.setStylesheets(sheets);
        final ServletOutputStream sink = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(final WriteListener listener) {
            }

            @Override
            public void write(final int b) {
                out.write(b);
            }
        };
        Mockito.when(resp.getOutputStream()).thenReturn(sink);
        Mockito.doAnswer(call -> status.getAndSet(call.getArgument(0))).when(resp).sendError(ArgumentMatchers.anyInt());
    }

    @Test
    public void aStoredStylesheetIsServedAsCss() throws IOException {
        Mockito.when(req.getPathInfo()).thenReturn("/" + KEY);
        Mockito.when(sheets.localGet(KEY)).thenReturn(Optional.of(".a{}".getBytes(StandardCharsets.UTF_8)));
        servlet.doGet(req, resp);
        assertEquals(status.get(), 200);
        assertEquals(out.toString(StandardCharsets.UTF_8), ".a{}");
        Mockito.verify(resp).setContentType("text/css; charset=utf-8");
        Mockito.verify(resp).setHeader("Cache-Control", "no-cache");
    }

    @Test
    public void anythingElseIs404() throws IOException {
        for (final String path : new String[] {null, "/org/org-1/stylesheets/../x.css", "/org/org-1/branding/l.png",
                "/" + KEY + "/more", "/not-a-key"}) {
            status.set(200);
            Mockito.when(req.getPathInfo()).thenReturn(path);
            servlet.doGet(req, resp);
            assertEquals(status.get(), 404, String.valueOf(path));
        }
        Mockito.verify(sheets, Mockito.never()).localGet(ArgumentMatchers.anyString());
        Mockito.when(req.getPathInfo()).thenReturn("/" + KEY);
        Mockito.when(sheets.localGet(KEY)).thenReturn(Optional.empty());
        status.set(200);
        servlet.doGet(req, resp);
        assertEquals(status.get(), 404, "a well-formed key nobody stored");
    }
}
