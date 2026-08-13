package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.paulsens.trip.action.BadgePhotoCommands;
import org.paulsens.trip.api.Beans;

/**
 * GET-only local-mode serving for custom badge images ({@code /badge-photos/badgeImages/...}) — in
 * production the CDN serves these keys and this path 404s, exactly like the profile-photo servlet.
 * Public-by-URL on purpose: the production CDN serves them to anyone holding the URL, and local mode must
 * behave like production. Uploads do NOT come through here — they ride the shared JSF dialog's multipart
 * form into {@code BadgePhotoCommands} — so there is no POST, no CSRF header, and no multipart-config.
 */
public class BadgePhotoServlet extends HttpServlet {

    private BadgePhotoCommands badgePhotos;

    void setBadgePhotos(final BadgePhotoCommands badgePhotos) {
        this.badgePhotos = badgePhotos;
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final String path = req.getPathInfo();
        if (path == null || path.contains("..")) {
            resp.sendError(404);
            return;
        }
        final String key = path.startsWith("/") ? path.substring(1) : path;
        if (!key.startsWith("badgeImages/")) {
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

    private BadgePhotoCommands photos() {
        BadgePhotoCommands photos = badgePhotos;
        if (photos == null) {
            // Resolved lazily so tests can inject; Beans.get selects with Any (the @FacesConfig trap).
            photos = Beans.get(BadgePhotoCommands.class);
            badgePhotos = photos;
        }
        return photos;
    }
}
