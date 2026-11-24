package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.TripPictureCommands;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The GET-only serving contract for local-mode trip pictures, and every 404 leg. */
public class TripPictureServletTest {

    private HttpServletRequest req;
    private HttpServletResponse resp;
    private ByteArrayOutputStream out;
    private AtomicInteger status;
    private TripPictureCommands photos;
    private TripPictureServlet servlet;

    @BeforeMethod
    public void setUp() throws IOException {
        req = Mockito.mock(HttpServletRequest.class);
        final CapturedResponse captured = CapturedResponse.capture();
        resp = captured.response();
        out = captured.body();
        status = captured.status();
        photos = Mockito.mock(TripPictureCommands.class);
        servlet = new TripPictureServlet();
        servlet.setTripPictures(photos);
    }

    @Test
    public void servesLocalModePictureBytes() throws IOException {
        Mockito.when(req.getPathInfo()).thenReturn("/tripPictures/trip-1/123-480.jpg");
        Mockito.when(photos.localGet("tripPictures/trip-1/123-480.jpg"))
                .thenReturn(Optional.of(new byte[] {1, 2, 3}));

        servlet.doGet(req, resp);
        Assert.assertEquals(out.toByteArray(), new byte[] {1, 2, 3});
        Mockito.verify(resp).setContentType("image/jpeg");
        Mockito.verify(resp).setHeader("Cache-Control", "public, max-age=3600");
    }

    @Test
    public void unknownPathsAre404() throws IOException {
        Mockito.when(req.getPathInfo()).thenReturn(null);
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404, "No path");

        Mockito.when(req.getPathInfo()).thenReturn("/downloads/x.jpg");
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404, "Not a trip-picture key");

        Mockito.when(req.getPathInfo()).thenReturn("/tripPictures/../secret");
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404, "Traversal is refused");
    }

    @Test
    public void missingLocalObjectIs404() throws IOException {
        Mockito.when(req.getPathInfo()).thenReturn("/tripPictures/trip-1/123-480.jpg");
        Mockito.when(photos.localGet(ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404);
    }
}
