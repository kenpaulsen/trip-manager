package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.paulsens.trip.action.AlbumLibrary;
import org.paulsens.trip.api.Beans;

/**
 * GET-only local-mode serving for album photos copied into the media library
 * ({@code /album-library/[org/{orgId}/]album-photos/{number}.jpg}; {@link AlbumLibrary}). In production the media
 * CDN serves these keys and this path 404s, exactly like the trip stylesheet servlet. Public by URL on purpose: a
 * library picture is what public pages show.
 */
public class AlbumLibraryServlet extends HttpServlet {

    private transient AlbumLibrary library;

    void setLibrary(final AlbumLibrary library) {
        this.library = library;
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final String path = req.getPathInfo();
        if (path == null || path.contains("..")) {
            resp.sendError(404);
            return;
        }
        // The key's shape is the whole validation: only one of our own keys can name any bytes at all.
        final Optional<byte[]> bytes = store().localGet(path.startsWith("/") ? path.substring(1) : path);
        if (bytes.isEmpty()) {
            resp.sendError(404);
            return;
        }
        resp.setContentType("image/jpeg");
        resp.setContentLength(bytes.get().length);
        resp.setHeader("Cache-Control", "public, max-age=3600");
        resp.getOutputStream().write(bytes.get());
    }

    private AlbumLibrary store() {
        AlbumLibrary store = library;
        if (store == null) {
            // Resolved lazily so tests can inject; Beans.get selects with Any (the @FacesConfig trap).
            store = Beans.get(AlbumLibrary.class);
            library = store;
        }
        return store;
    }
}
