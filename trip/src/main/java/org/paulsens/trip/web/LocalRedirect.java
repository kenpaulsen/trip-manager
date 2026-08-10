package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Builds the "send the browser back to the page it asked for" redirect target that both
 * {@link SessionRecoveryFilter} and {@link RememberMeFilter} use after they fix up a request.
 *
 * <p>The target is derived from {@code getRequestURI()}, which is a same-origin path -- with one exception the
 * guard here closes: a protocol-relative path ({@code //host} or {@code /\host}) would make
 * {@code sendRedirect} bounce OFF this site. Nothing secret leaks that way (cookies are not sent cross-origin),
 * but an off-site redirect from our own URL is exactly the open-redirect shape a scanner flags, so any path
 * that is not a plain single-slash local path falls back to the root.
 */
public final class LocalRedirect {

    private LocalRedirect() {
    }

    /**
     * A CLIENT-SUPPLIED redirect target, admitted only when it is a plain same-origin path: must start with a
     * single {@code /}, no protocol-relative {@code //} or {@code /\}, no CR/LF (header injection), and a sane
     * length. Returns {@code null} for anything else — the caller refuses rather than falling back, because a
     * client that sent garbage should hear about it, unlike the server-derived paths above.
     */
    public static String sanitizeLocalTarget(final String raw) {
        if (raw == null || raw.length() > 2048 || !raw.startsWith("/")
                || raw.startsWith("//") || raw.startsWith("/\\")
                || raw.indexOf('\r') >= 0 || raw.indexOf('\n') >= 0) {
            return null;
        }
        return raw;
    }

    static String samePathOrRoot(final HttpServletRequest req) {
        final String uri = req.getRequestURI();
        if (uri == null || !uri.startsWith("/") || uri.startsWith("//") || uri.startsWith("/\\")) {
            return "/";
        }
        // A GET is replayable, so its query survives; a POST cannot be replayed, so its body-bearing query is
        // dropped rather than half-repeated.
        final String query = req.getQueryString();
        final boolean replayable = "GET".equalsIgnoreCase(req.getMethod());
        return uri + (replayable && query != null && !query.isBlank() ? "?" + query : "");
    }
}
