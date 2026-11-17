package org.paulsens.trip.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.mockito.Mockito;
import org.paulsens.trip.action.AlbumLibrary;
import org.testng.annotations.Test;

/** The local-mode GET for album photos copied into the library: a stored copy is served, anything else is a 404. */
public class AlbumLibraryServletTest {

    private static HttpServletRequest get(final String pathInfo) {
        final HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.getPathInfo()).thenReturn(pathInfo);
        return req;
    }

    @Test
    public void aStoredCopyIsServedAsAJpegAndEverythingElseIsNotFound() throws Exception {
        final AlbumLibrary library = Mockito.mock(AlbumLibrary.class);
        Mockito.when(library.localGet("org/a/album-photos/1.jpg")).thenReturn(Optional.of(new byte[] {7, 8, 9}));
        Mockito.when(library.localGet("album-photos/2.jpg")).thenReturn(Optional.empty());
        final AlbumLibraryServlet servlet = new AlbumLibraryServlet();
        servlet.setLibrary(library);

        final HttpServletResponse ok = Mockito.mock(HttpServletResponse.class);
        final ServletOutputStream out = Mockito.mock(ServletOutputStream.class);
        Mockito.when(ok.getOutputStream()).thenReturn(out);
        servlet.doGet(get("/org/a/album-photos/1.jpg"), ok);
        Mockito.verify(ok).setContentType("image/jpeg");
        Mockito.verify(ok).setContentLength(3);
        Mockito.verify(out).write(new byte[] {7, 8, 9});

        for (final String path : new String[] {null, "/album-photos/2.jpg", "/org/a/../b/album-photos/1.jpg"}) {
            final HttpServletResponse missing = Mockito.mock(HttpServletResponse.class);
            servlet.doGet(get(path), missing);
            Mockito.verify(missing).sendError(404);
        }
    }
}
