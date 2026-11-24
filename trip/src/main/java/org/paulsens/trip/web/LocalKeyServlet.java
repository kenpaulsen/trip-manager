package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;

/**
 * GET-only local-mode serving of stored bytes by their storage key: {@code /{mount}/{key}}. In production the
 * media CDN serves these keys and the store answers nothing, so every such path 404s; local mode has no CDN, and
 * must behave like production to anyone holding the URL.
 *
 * <p>Seven servlets (badges, lodging photos, trip pictures, branding, the album library, trip stylesheets, and
 * the profile-photo bytes) each carried this method whole, differing only in which keys they accept, where the
 * bytes come from, and two header values. The shape they share is the security-relevant part, so it lives here
 * once: a path that climbs ({@code ..}) or names a key that is not this servlet's is a 404 BEFORE the store is
 * asked, and a miss is the same 404, so the answer never says which keys exist.
 */
abstract class LocalKeyServlet extends HttpServlet {

    /** What a browser may do with an image whose key never changes content: keep it an hour, not share it. */
    static final String PRIVATE_HOUR = "private, max-age=3600";
    /** As {@link #PRIVATE_HOUR}, for bytes anonymous visitors see and the production CDN caches shared. */
    static final String PUBLIC_HOUR = "public, max-age=3600";

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final String path = req.getPathInfo();
        if (path == null || path.contains("..")) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        final String key = path.startsWith("/") ? path.substring(1) : path;
        final String contentType = contentTypeOf(key);
        final Optional<byte[]> bytes = (contentType == null) ? Optional.empty() : localGet(key);
        if (bytes.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        write(resp, bytes.get(), contentType, cacheControl());
    }

    /** The Content-Type of a key this servlet serves, or null for any other key: a 404 that never reaches the store. */
    protected abstract String contentTypeOf(String key);

    /** The bytes stored under an accepted key in local mode; empty for a miss, and always in production. */
    protected abstract Optional<byte[]> localGet(String key);

    /** The {@code Cache-Control} of a served key. */
    protected String cacheControl() {
        return PRIVATE_HOUR;
    }

    static void write(final HttpServletResponse resp, final byte[] bytes, final String contentType,
            final String cacheControl) throws IOException {
        resp.setContentType(contentType);
        resp.setContentLength(bytes.length);
        resp.setHeader("Cache-Control", cacheControl);
        resp.getOutputStream().write(bytes);
    }
}
