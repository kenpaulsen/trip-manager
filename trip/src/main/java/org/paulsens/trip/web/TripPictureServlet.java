package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.paulsens.trip.action.TripPictureCommands;
import org.paulsens.trip.api.Beans;

/**
 * GET-only local-mode serving for trip pictures ({@code /trip-pictures/tripPictures/...}); in production the CDN
 * serves these keys and this path 404s, exactly like the badge-photo servlet. Public by URL on purpose: the
 * production CDN serves them to anyone holding the URL, a trip's cover is shown to anonymous visitors, and local mode
 * must behave like production. Uploads do NOT come through here: they ride the shared JSF dialog's multipart form
 * into {@code TripPictureCommands}, or the REST edge's raw body, so there is no POST and no multipart-config.
 */
public class TripPictureServlet extends HttpServlet {

    private transient TripPictureCommands tripPictures;

    void setTripPictures(final TripPictureCommands tripPictures) {
        this.tripPictures = tripPictures;
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final String path = req.getPathInfo();
        if (path == null || path.contains("..")) {
            resp.sendError(404);
            return;
        }
        final String key = path.startsWith("/") ? path.substring(1) : path;
        if (!key.startsWith(TripPictureCommands.PREFIX)) {
            resp.sendError(404);
            return;
        }
        final Optional<byte[]> bytes = pictures().localGet(key);
        if (bytes.isEmpty()) {
            resp.sendError(404);
            return;
        }
        resp.setContentType("image/jpeg");
        resp.setContentLength(bytes.get().length);
        // public, unlike the badge and lodging servlets' private: a trip picture is a cover anonymous visitors
        // see (Discover, the listing), and the production CDN caches it shared, which local mode mirrors.
        resp.setHeader("Cache-Control", "public, max-age=3600");
        resp.getOutputStream().write(bytes.get());
    }

    private TripPictureCommands pictures() {
        TripPictureCommands pictures = tripPictures;
        if (pictures == null) {
            // Resolved lazily so tests can inject; Beans.get selects with Any (the @FacesConfig trap).
            pictures = Beans.get(TripPictureCommands.class);
            tripPictures = pictures;
        }
        return pictures;
    }
}
