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
import org.paulsens.trip.action.BrandingPhotos;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The GET-only serving contract for an organization's branding images in local mode, and every 404 leg. The
 * key's own shape is the whole validation: only a well-formed branding key of a known role, with an
 * extension this app actually writes, can name any bytes at all.
 */
public class BrandingPhotoServletTest {

    private static final String LOGO_KEY = "org/org-1/branding/logo-1725000000000.png";

    private HttpServletRequest req;
    private HttpServletResponse resp;
    private ByteArrayOutputStream out;
    private AtomicInteger status;
    private BrandingPhotos photos;
    private BrandingPhotoServlet servlet;

    @BeforeMethod
    public void setUp() throws IOException {
        req = Mockito.mock(HttpServletRequest.class);
        resp = Mockito.mock(HttpServletResponse.class);
        out = new ByteArrayOutputStream();
        status = new AtomicInteger(200);
        photos = Mockito.mock(BrandingPhotos.class);
        servlet = new BrandingPhotoServlet();
        servlet.setBrandingPhotos(photos);

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
        Mockito.doAnswer(call -> status.getAndSet(call.getArgument(0)))
                .when(resp).sendError(ArgumentMatchers.anyInt());
    }

    @Test
    public void servesLocalModeBrandingBytesWithTheTypeTheKeyDeclares() throws IOException {
        Mockito.when(req.getPathInfo()).thenReturn("/" + LOGO_KEY);
        Mockito.when(photos.localGet(LOGO_KEY)).thenReturn(Optional.of(new byte[] {1, 2, 3}));

        servlet.doGet(req, resp);

        Assert.assertEquals(out.toByteArray(), new byte[] {1, 2, 3});
        Mockito.verify(resp).setContentType("image/png");
        Mockito.verify(resp).setHeader("Cache-Control", "private, max-age=3600");
        Assert.assertEquals(status.get(), 200, "anonymous by design: browsers fetch a logo without a session");
    }

    @Test
    public void aJpegRoleIsServedAsAJpeg() throws IOException {
        final String ogKey = "org/org-1/branding/ogImage-42.jpg";
        Mockito.when(req.getPathInfo()).thenReturn("/" + ogKey);
        Mockito.when(photos.localGet(ogKey)).thenReturn(Optional.of(new byte[] {9}));

        servlet.doGet(req, resp);

        Mockito.verify(resp).setContentType("image/jpeg");
    }

    @Test
    public void anythingThatIsNotABrandingKeyIs404WithoutEvenAskingTheStore() throws IOException {
        final String[] refused = {null, "/../secret", "/profilePics/p1/1-2.jpg",
                "/org/org-1/media/brochure.pdf", "/org/org-1/branding/mascot-1.png",
                "/org/org-1/branding/logo-1.webp"};
        for (final String path : refused) {
            status.set(200);
            Mockito.when(req.getPathInfo()).thenReturn(path);
            servlet.doGet(req, resp);
            Assert.assertEquals(status.get(), 404, "must be refused: " + path);
        }
        Mockito.verify(photos, Mockito.never()).localGet(ArgumentMatchers.anyString());
    }

    @Test
    public void aWellFormedKeyWithNoBytesBehindItIs404() throws IOException {
        Mockito.when(req.getPathInfo()).thenReturn("/" + LOGO_KEY);
        Mockito.when(photos.localGet(ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        servlet.doGet(req, resp);
        Assert.assertEquals(status.get(), 404, "in production the CDN serves these and this path 404s");
    }
}
