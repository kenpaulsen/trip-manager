package org.paulsens.trip.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;

/**
 * Redirects any request for a {@code .pdf} to the managed-media CDN.
 *
 * <p>The PDFs used to be served from the web application itself, from four different directories and linked
 * three different ways. When they moved to S3 the pages were rewritten to point at the CDN, but pages are not
 * the only thing that links to them: these are travel guides, waivers and novenas that get emailed to
 * pilgrims, printed with a URL on them, and bookmarked. Ten of the thirty were referenced by no page at all,
 * which is a strong hint that their real audience is holding a link somewhere this repository cannot see.
 *
 * <p>Deleting the files without this would 404 every one of those. Because no two PDFs shared a file name, the
 * mapping collapses to one rule -- whatever path was requested, serve the CDN copy of that file name -- so a
 * single filter covers every old URL shape, including ones nobody wrote down.
 *
 * <p>A 301 is deliberate: the move is permanent, and it lets clients and crawlers update. If no media base URL
 * is configured (local development), the filter stands aside and the request falls through to whatever the
 * container would have done.
 */
public class PdfRedirectFilter implements Filter {

    static final String BASE_URL_VAR = "TRIP_MEDIA_BASE_URL";
    /** Every migrated PDF lives under this single prefix; see scripts/migrate-media.sh for why. */
    static final String PREFIX = "/downloads/";

    private String baseUrl;

    @Override
    public void init(final FilterConfig config) {
        final String configured = System.getenv(BASE_URL_VAR);
        this.baseUrl = (configured == null || configured.isBlank())
                ? null : configured.trim().replaceAll("/+$", "");
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        if (baseUrl == null || !(request instanceof HttpServletRequest req)
                || !(response instanceof HttpServletResponse resp)) {
            chain.doFilter(request, response);
            return;
        }
        final String path = req.getRequestURI();
        if (path == null || !path.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            chain.doFilter(request, response);
            return;
        }
        final String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        resp.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
        resp.setHeader("Location", baseUrl + PREFIX + name);
        resp.setHeader("Cache-Control", "public, max-age=3600");
    }
}
