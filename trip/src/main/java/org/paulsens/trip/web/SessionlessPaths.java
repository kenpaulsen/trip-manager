package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The request paths that are served with no session at all: nothing on them may read one, create one, restore one
 * from a remember-me cookie or resolve a bearer token. Today that is Discover ({@code /api/discover/},
 * medjugorje#155), which answers anyone on the internet with public data.
 *
 * <p>Why a rule rather than "the resource just never asks for the session": the session is touched BEFORE a
 * resource runs. {@link RememberMeFilter} turns a remember-me cookie into a new session (a Valkey write and two
 * {@code Set-Cookie} headers) on any {@code /api/} request, and {@link SessionRecoveryFilter} loads the stored
 * session and resolves a bearer token for every request. On a public, cacheable endpoint each of those is wrong
 * three ways: a crawler could mint sessions without limit, a response carrying {@code Set-Cookie} must never be
 * stored by a shared cache, and an anonymous read has no business costing a session store round trip. Both
 * filters ask here first, so the list of such paths has one home.
 */
public final class SessionlessPaths {

    private static final String DISCOVER = "/api/discover";

    private SessionlessPaths() {
    }

    /**
     * Whether this request is for a sessionless path, judged on the path the request is ROUTED by, not on the raw
     * request line. The raw URI keeps what routing ignores: {@code /api/discover;x/trips} and
     * {@code /api/%64iscover/trips} both reach the Discover resource (the container and Jersey drop path
     * parameters and decode escapes before matching), and a rule that compared the raw text would let either one
     * past as an ordinary {@code /api/} path, session restore and all. {@code DiscoverResource} asks this same
     * method and answers 404 when it says no, so a spelling that reaches the resource and is NOT recognized here
     * can never produce a public, cacheable answer.
     */
    public static boolean matches(final HttpServletRequest request) {
        final String path = routedPath(request);
        // Ends at a path boundary: /api/discovery (some later, unrelated resource) is not this.
        return path != null && (path.equals(DISCOVER) || path.startsWith(DISCOVER + "/"));
    }

    /**
     * The path inside the application that the container routed on: its servlet path and path info, already
     * decoded and without path parameters. Where the container supplies neither (a unit test's bare request), the
     * request URI less the context path, with each segment's path parameters removed.
     */
    static String routedPath(final HttpServletRequest request) {
        final String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            return request.getPathInfo() == null ? servletPath : servletPath + request.getPathInfo();
        }
        final String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        final String context = request.getContextPath() == null ? "" : request.getContextPath();
        final String path = uri.startsWith(context) ? uri.substring(context.length()) : uri;
        return path.replaceAll(";[^/]*", "");
    }
}
