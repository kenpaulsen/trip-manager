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
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.action.ChatPhotos;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The local-mode serving contract (all that remains here: the upload POST moved into the shared dialog's
 * listener, {@code ChatPhotoUploadTest} covers it).
 */
public class ChatUploadServletTest {

    private HttpServletRequest req;
    private HttpServletResponse resp;
    private ByteArrayOutputStream out;
    private AtomicInteger status;
    private ChatUploadServlet servlet;

    @BeforeMethod
    public void setUp() throws IOException {
        req = Mockito.mock(HttpServletRequest.class);
        resp = Mockito.mock(HttpServletResponse.class);
        out = new ByteArrayOutputStream();
        status = new AtomicInteger(200);
        servlet = new ChatUploadServlet();
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
    public void localGetServesOnlyChatKeysAndOnlyKnownOnes() throws Exception {
        try (MockedStatic<ChatPhotos> photoStatics = Mockito.mockStatic(ChatPhotos.class)) {
            final ChatPhotos photos = Mockito.mock(ChatPhotos.class);
            photoStatics.when(ChatPhotos::getChatPhotos).thenReturn(photos);
            Mockito.when(photos.localGet("chat/trip-1/x.jpg")).thenReturn(
                    Optional.of(new ChatPhotos.ServedPhoto(new byte[] {9, 9}, "image/jpeg")));

            Mockito.when(req.getPathInfo()).thenReturn(null);
            servlet.doGet(req, resp);
            Assert.assertEquals(status.get(), 404, "no path");

            Mockito.when(req.getPathInfo()).thenReturn("/etc/passwd");
            servlet.doGet(req, resp);
            Assert.assertEquals(status.get(), 404, "outside the chat prefix");

            Mockito.when(req.getPathInfo()).thenReturn("/chat/../secret");
            servlet.doGet(req, resp);
            Assert.assertEquals(status.get(), 404, "traversal");

            Mockito.when(photos.localGet("chat/trip-1/missing.jpg")).thenReturn(Optional.empty());
            Mockito.when(req.getPathInfo()).thenReturn("/chat/trip-1/missing.jpg");
            servlet.doGet(req, resp);
            Assert.assertEquals(status.get(), 404, "unknown key");

            status.set(200);
            Mockito.when(req.getPathInfo()).thenReturn("/chat/trip-1/x.jpg");
            servlet.doGet(req, resp);
            Assert.assertEquals(status.get(), 200);
            Assert.assertEquals(out.toByteArray(), new byte[] {9, 9});
            Mockito.verify(resp).setContentType("image/jpeg");
        }
    }
}
