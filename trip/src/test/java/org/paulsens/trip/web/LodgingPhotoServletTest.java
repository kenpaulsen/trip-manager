package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServletRequest;
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
        final CapturedResponse captured = CapturedResponse.capture();
        servlet.doGet(req, captured.response());
        if (captured.status().get() == 200) {
            Assert.assertEquals(captured.body().size(), 3);
        }
        return captured.status().get();
    }
}
