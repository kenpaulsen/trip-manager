package org.paulsens.trip.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.ProfilePhotos;
import org.paulsens.trip.media.PendingUploads;
import org.paulsens.trip.media.PhotoProcessor;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** The GET-only serving contract: local photo bytes, the session-guarded preview, and every 4xx leg. */
public class ProfilePhotoServletTest {

    private HttpServletRequest req;
    private HttpServletResponse resp;
    private ByteArrayOutputStream out;
    private AtomicInteger status;
    private ProfilePhotos photos;
    private PendingUploads pending;
    private ProfilePhotoServlet servlet;

    @BeforeMethod
    public void setUp() throws IOException {
        req = Mockito.mock(HttpServletRequest.class);
        resp = Mockito.mock(HttpServletResponse.class);
        out = new ByteArrayOutputStream();
        status = new AtomicInteger(200);
        photos = Mockito.mock(ProfilePhotos.class);
        pending = new PendingUploads();
        servlet = new ProfilePhotoServlet();
        servlet.setProfilePhotos(photos);
        servlet.setPendingUploads(pending);

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

    private void signIn(final String personId) {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ID)).thenReturn(personId);
        Mockito.when(req.getSession(false)).thenReturn(session);
    }

    @Test
    public void servesLocalModePhotoBytes() throws IOException {
        Mockito.when(req.getPathInfo()).thenReturn("/profilePics/p1/1-123.jpg");
        Mockito.when(photos.localGet("profilePics/p1/1-123.jpg"))
                .thenReturn(Optional.of(new byte[] {1, 2, 3}));

        servlet.doGet(req, resp);
        Assert.assertEquals(out.toByteArray(), new byte[] {1, 2, 3});
        Mockito.verify(resp).setContentType("image/jpeg");
    }

    @Test
    public void unknownPathsAre404() throws IOException {
        Mockito.when(req.getPathInfo()).thenReturn(null);
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404);

        Mockito.when(req.getPathInfo()).thenReturn("/downloads/x.jpg");
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404);

        Mockito.when(req.getPathInfo()).thenReturn("/profilePics/../secret");
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404);
    }

    @Test
    public void missingLocalObjectIs404() throws IOException {
        Mockito.when(req.getPathInfo()).thenReturn("/profilePics/p1/1-123.jpg");
        Mockito.when(photos.localGet(ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404);
    }

    @Test
    public void previewRequiresTheStagingSession() throws IOException {
        final PendingUploads.Pending staged = pending.put(new byte[] {9},
                new PhotoProcessor.PreviewImage(new byte[] {4, 5}, 10, 10, 20, 20), "me", "profile", "p1");
        Mockito.when(req.getPathInfo()).thenReturn("/preview/" + staged.token());

        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 401, "Anonymous must not pull previews");

        signIn("someone-else");
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404, "Another session's token must not resolve");

        signIn("me");
        servlet.doGet(req, resp);
        Assert.assertEquals(out.toByteArray(), new byte[] {4, 5});
        Mockito.verify(resp).setHeader("Cache-Control", "private, no-store");
    }

    /** Background-removal cutouts are PNGs; the preview endpoint must say so or the canvas draws garbage. */
    @Test
    public void bgCutoutPreviewsServeAsPng() throws IOException {
        final PendingUploads.Pending cutout = pending.put(new byte[] {7},
                new PhotoProcessor.PreviewImage(new byte[] {8}, 512, 512, 512, 512), "me",
                org.paulsens.trip.action.ProfilePhotoCommands.BG_PURPOSE, "p1#2");
        signIn("me");
        Mockito.when(req.getPathInfo()).thenReturn("/preview/" + cutout.token());
        servlet.doGet(req, resp);
        Assert.assertEquals(out.toByteArray(), new byte[] {8});
        Mockito.verify(resp).setContentType("image/png");
    }

    @Test
    public void unknownPreviewTokenIs404() throws IOException {
        signIn("me");
        Mockito.when(req.getPathInfo()).thenReturn("/preview/nope");
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404);
    }
}
