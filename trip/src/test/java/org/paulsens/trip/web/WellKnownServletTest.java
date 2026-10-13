package org.paulsens.trip.web;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The app-association files come out of WEB-INF as JSON with a content type, and nothing else does: a
 * request for any other name under {@code /.well-known/} is a 404, never a directory walk.
 */
public class WellKnownServletTest {

    private static final String AASA = "{\"applinks\":{\"details\":[]}}";

    private WellKnownServlet servlet;
    private ServletContext context;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter body;

    @BeforeMethod
    public void setUp() throws Exception {
        servlet = new WellKnownServlet();
        context = Mockito.mock(ServletContext.class);
        final ServletConfig config = Mockito.mock(ServletConfig.class);
        Mockito.when(config.getServletContext()).thenReturn(context);
        servlet.init(config);
        request = Mockito.mock(HttpServletRequest.class);
        response = Mockito.mock(HttpServletResponse.class);
        body = new StringWriter();
        Mockito.when(response.getWriter()).thenReturn(new PrintWriter(body, true));
    }

    @Test
    public void theAppleFileIsServedAsJsonFromWebInf() throws Exception {
        Mockito.when(request.getPathInfo()).thenReturn("/apple-app-site-association");
        Mockito.when(context.getResourceAsStream(WellKnownServlet.FOLDER + "apple-app-site-association"))
                .thenReturn(new ByteArrayInputStream(AASA.getBytes(StandardCharsets.UTF_8)));

        servlet.doGet(request, response);

        Assert.assertEquals(body.toString(), AASA);
        Mockito.verify(response).setStatus(200);
        Mockito.verify(response).setContentType("application/json");
        Mockito.verify(response).setHeader(ArgumentMatchers.eq("Cache-Control"), ArgumentMatchers.contains("max-age"));
        Mockito.verify(response, Mockito.never()).sendError(ArgumentMatchers.anyInt());
    }

    @Test
    public void theAndroidFileIsServedTheSameWay() throws Exception {
        Mockito.when(request.getPathInfo()).thenReturn("/assetlinks.json");
        Mockito.when(context.getResourceAsStream(WellKnownServlet.FOLDER + "assetlinks.json"))
                .thenReturn(new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8)));

        servlet.doGet(request, response);

        Assert.assertEquals(body.toString(), "[]");
        Mockito.verify(response).setContentType("application/json");
    }

    @Test
    public void anUnknownNameIs404WithoutTouchingTheContext() throws Exception {
        for (final String path : new String[] {"/other.json", "/../web.xml", "/", "", null}) {
            Mockito.reset(response, context);
            Mockito.when(request.getPathInfo()).thenReturn(path);

            servlet.doGet(request, response);

            Mockito.verify(response).sendError(404);
            Mockito.verify(context, Mockito.never()).getResourceAsStream(ArgumentMatchers.anyString());
        }
    }

    @Test
    public void aKnownNameTheOverlayDoesNotShipIs404() throws Exception {
        Mockito.when(request.getPathInfo()).thenReturn("/assetlinks.json");
        Mockito.when(context.getResourceAsStream(ArgumentMatchers.anyString())).thenReturn(null);

        servlet.doGet(request, response);

        Mockito.verify(response).sendError(404);
        Assert.assertEquals(body.toString(), "");
    }

    @Test
    public void fileNameAcceptsOnlyTheTwoFiles() {
        Assert.assertEquals(WellKnownServlet.fileName("/assetlinks.json"), "assetlinks.json");
        Assert.assertEquals(WellKnownServlet.fileName("apple-app-site-association"), "apple-app-site-association");
        Assert.assertNull(WellKnownServlet.fileName("/apple-app-site-association/"));
        Assert.assertNull(WellKnownServlet.fileName("/ASSETLINKS.JSON"));
        Assert.assertNull(WellKnownServlet.fileName(null));
    }
}
