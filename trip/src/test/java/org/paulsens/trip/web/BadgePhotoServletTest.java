package org.paulsens.trip.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.BadgePhotoCommands;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The GET-only serving contract for local-mode badge images, and every 404 leg. */
public class BadgePhotoServletTest {

    private HttpServletRequest req;
    private HttpServletResponse resp;
    private ByteArrayOutputStream out;
    private AtomicInteger status;
    private BadgePhotoCommands photos;
    private BadgePhotoServlet servlet;

    @BeforeMethod
    public void setUp() throws IOException {
        req = Mockito.mock(HttpServletRequest.class);
        resp = Mockito.mock(HttpServletResponse.class);
        out = new ByteArrayOutputStream();
        status = new AtomicInteger(200);
        photos = Mockito.mock(BadgePhotoCommands.class);
        servlet = new BadgePhotoServlet();
        servlet.setBadgePhotos(photos);

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
        Mockito.doAnswer(call -> {
            status.set(call.getArgument(0));
            return null;
        }).when(resp).sendError(ArgumentMatchers.anyInt());
    }

    @Test
    public void servesLocalModeBadgeBytes() throws IOException {
        Mockito.when(req.getPathInfo()).thenReturn("/badgeImages/trip-1/123.jpg");
        Mockito.when(photos.localGet("badgeImages/trip-1/123.jpg"))
                .thenReturn(Optional.of(new byte[] {1, 2, 3}));

        servlet.doGet(req, resp);
        Assert.assertEquals(out.toByteArray(), new byte[] {1, 2, 3});
        Mockito.verify(resp).setContentType("image/jpeg");
        Mockito.verify(resp).setHeader("Cache-Control", "private, max-age=3600");
    }

    @Test
    public void unknownPathsAre404() throws IOException {
        Mockito.when(req.getPathInfo()).thenReturn(null);
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404, "No path");

        Mockito.when(req.getPathInfo()).thenReturn("/downloads/x.jpg");
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404, "Not a badge key");

        Mockito.when(req.getPathInfo()).thenReturn("/badgeImages/../secret");
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404, "Traversal is refused");
    }

    @Test
    public void missingLocalObjectIs404() throws IOException {
        Mockito.when(req.getPathInfo()).thenReturn("/badgeImages/trip-1/123.jpg");
        Mockito.when(photos.localGet(ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404);
    }
}
