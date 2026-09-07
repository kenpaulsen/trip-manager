package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.paulsens.trip.action.LodgingUploadCommands;
import org.paulsens.trip.api.Beans;

/**
 * GET-only local-mode serving for lodging images ({@code /lodging-photos/lodging/...}): a hotel's photos,
 * its room-type photos and its floor plans. In production the media CDN serves these keys and this path
 * 404s, exactly like the badge-photo servlet. Public-by-URL on purpose (the CDN is), and uploads never come
 * through here -- they ride the shared JSF dialog into {@link LodgingUploadCommands}.
 */
public class LodgingPhotoServlet extends HttpServlet {

    private LodgingUploadCommands uploads;

    void setUploads(final LodgingUploadCommands uploads) {
        this.uploads = uploads;
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final String path = req.getPathInfo();
        if (path == null || path.contains("..")) {
            resp.sendError(404);
            return;
        }
        final String key = path.startsWith("/") ? path.substring(1) : path;
        if (!key.startsWith("lodging/")) {
            resp.sendError(404);
            return;
        }
        final Optional<byte[]> bytes = photos().localGet(key);
        if (bytes.isEmpty()) {
            resp.sendError(404);
            return;
        }
        resp.setContentType("image/jpeg");
        resp.setContentLength(bytes.get().length);
        resp.setHeader("Cache-Control", "private, max-age=3600");
        resp.getOutputStream().write(bytes.get());
    }

    private LodgingUploadCommands photos() {
        LodgingUploadCommands photos = uploads;
        if (photos == null) {
            // Resolved lazily so tests can inject; Beans.get selects with Any (the @FacesConfig trap).
            photos = Beans.get(LodgingUploadCommands.class);
            uploads = photos;
        }
        return photos;
    }
}
