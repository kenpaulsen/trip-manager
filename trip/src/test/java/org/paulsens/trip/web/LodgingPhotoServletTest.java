package org.paulsens.trip.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import org.mockito.Mockito;
import org.paulsens.trip.action.LodgingUploadCommands;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Local-mode GET serving of lodging images; everything else is a 404. */
public class LodgingPhotoServletTest {

    @Test
    public void servesKnownKeysAndRefusesEverythingElse() throws Exception {
        final LodgingUploadCommands uploads = Mockito.mock(LodgingUploadCommands.class);
        Mockito.when(uploads.localGet("lodging/acc/a.jpg")).thenReturn(Optional.of(new byte[] {7, 8, 9}));
        Mockito.when(uploads.localGet("lodging/acc/missing.jpg")).thenReturn(Optional.empty());
        final LodgingPhotoServlet servlet = new LodgingPhotoServlet();
        servlet.setUploads(uploads);

        Assert.assertEquals(status(servlet, null), 404);
        Assert.assertEquals(status(servlet, "/../etc/passwd"), 404);
        Assert.assertEquals(status(servlet, "/badgeImages/x.jpg"), 404);
        Assert.assertEquals(status(servlet, "/lodging/acc/missing.jpg"), 404);
        Assert.assertEquals(status(servlet, "/lodging/acc/a.jpg"), 200);
        Assert.assertEquals(status(servlet, "lodging/acc/a.jpg"), 200, "a bare path works too");
    }

    private static int status(final LodgingPhotoServlet servlet, final String path) throws Exception {
        final HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.getPathInfo()).thenReturn(path);
        final HttpServletResponse resp = Mockito.mock(HttpServletResponse.class);
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final int[] status = {200};
        Mockito.doAnswer(call -> {
            status[0] = call.getArgument(0);
            return null;
        }).when(resp).sendError(Mockito.anyInt());
        Mockito.when(resp.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(final jakarta.servlet.WriteListener listener) {
            }

            @Override
            public void write(final int b) {
                body.write(b);
            }
        });
        servlet.doGet(req, resp);
        if (status[0] == 200) {
            Assert.assertEquals(body.size(), 3);
        }
        return status[0];
    }
}
