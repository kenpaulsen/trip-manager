package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.paulsens.trip.action.TripStylesheets;
import org.paulsens.trip.api.Beans;

/**
 * GET-only local-mode serving for a trip page's own stylesheets ({@code /trip-stylesheets/org/{orgId}/stylesheets/
 * {slug}.css}; docs/conference.md "Look and feel"). In production the media CDN serves these keys and this path
 * 404s, exactly like the branding-photo servlet. Public by URL on purpose: a page's stylesheet is fetched by every
 * anonymous browser. Never cached: the editor overwrites the same key, and the link's {@code ?r=} is for browsers
 * talking to the CDN.
 */
public class StylesheetServlet extends HttpServlet {

    private TripStylesheets stylesheets;

    void setStylesheets(final TripStylesheets stylesheets) {
        this.stylesheets = stylesheets;
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final String path = req.getPathInfo();
        if (path == null || path.contains("..")) {
            resp.sendError(404);
            return;
        }
        final String key = path.startsWith("/") ? path.substring(1) : path;
        // The key's shape is the whole validation: only one of our stylesheet keys can name any bytes at all.
        final Optional<byte[]> bytes = TripStylesheets.isStylesheetKey(key) ? store().localGet(key)
                : Optional.empty();
        if (bytes.isEmpty()) {
            resp.sendError(404);
            return;
        }
        resp.setContentType("text/css; charset=utf-8");
        resp.setContentLength(bytes.get().length);
        resp.setHeader("Cache-Control", "no-cache");
        resp.getOutputStream().write(bytes.get());
    }

    private TripStylesheets store() {
        TripStylesheets store = stylesheets;
        if (store == null) {
            // Resolved lazily so tests can inject; Beans.get selects with Any (the @FacesConfig trap).
            store = Beans.get(TripStylesheets.class);
            stylesheets = store;
        }
        return store;
    }
}
