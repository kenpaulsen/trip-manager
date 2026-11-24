package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.paulsens.trip.action.Caller;

/**
 * What the GETs that serve a private document (a money PDF, a signed waiver, a receipt) to a signed-in SESSION
 * share: who the caller is, where a visitor who is not signed in is sent, and how the answer is written.
 *
 * <p>Every one of them opens with the same gate: no session, no document, and nothing about the document (not
 * even whether it exists) is decided first. The visitor goes to the login page with this URL as its {@code to}
 * and comes back afterwards. The gate itself stays at the top of each servlet's {@code doGet}, where the
 * document's own authorization follows it: "signed in" alone opens nothing. (It is also where the security
 * scan's reviewed list of redirects, {@code spotbugs-exclude.xml}, expects to find it.)
 */
abstract class SignedInServlet extends HttpServlet {

    static final String LOGIN = "/account/login.jsf?to=";

    /** The login page, returning to this request with its query. */
    static String loginUrl(final HttpServletRequest req) {
        return loginUrl(req, req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString()));
    }

    /**
     * The login page on THIS site (context path plus a constant path), returning to {@code returnTo}, which
     * travels only as a URL-encoded value that login accepts as a same-site path: no request data picks a host.
     */
    static String loginUrl(final HttpServletRequest req, final String returnTo) {
        return req.getContextPath() + LOGIN + URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
    }

    /** Seam for tests; production resolves the caller from the request's session. */
    protected Caller callerOf(final HttpServletRequest req) {
        return Caller.of(req.getSession(false));
    }

    /**
     * Writes a body no browser, and nothing between, may keep: these are money documents and signatures.
     *
     * @param disposition the {@code Content-Disposition}, or null to send none
     */
    static void writeNoStore(final HttpServletResponse resp, final String contentType, final String disposition,
            final byte[] body) throws IOException {
        resp.setContentType(contentType);
        resp.setContentLength(body.length);
        if (disposition != null) {
            resp.setHeader("Content-Disposition", disposition);
        }
        resp.setHeader("Cache-Control", "private, no-store");
        resp.getOutputStream().write(body);
    }
}
