package org.paulsens.trip.web;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Serves the two app-association files the phones' operating systems fetch from {@code /.well-known/}:
 * Apple's {@code apple-app-site-association} (universal links) and Android's {@code assetlinks.json} (app
 * links). Both say "links on this host to these paths belong to this app", which is what lets a chat invite
 * link open the UniteTrip app instead of the browser when the app is installed.
 *
 * <p>A servlet rather than two static files at the webapp root, for two reasons. Apple's file has no
 * extension, so the default servlet would send it without a content type, and Apple's fetcher wants
 * {@code application/json}. And the contents are account-specific (the Apple team id, the Play signing
 * certificate's fingerprint), so they live in the private overlay under {@code WEB-INF/well-known/}, where
 * nothing else can serve them, and this class in the public repo knows only the names. The mapping is
 * {@code /.well-known/*} in the live web.xml; anything but the known names is a plain 404.</p>
 *
 * <p>Since 2026-11 the folder also holds Stripe's {@code apple-developer-merchantid-domain-association}:
 * Apple fetches it when an organization registers a host for Apple Pay in the Payment Element
 * ({@code OrgCommands.registerPaymentDomain}). It is Stripe's file, the same for every Stripe account, and
 * goes out as text/plain.</p>
 *
 * <p>Every host this app answers on serves the same files -- the apex, {@code www}, and each organization's
 * subdomain -- which is what the invite links need, since a link is minted on the trip's own site.</p>
 */
public class WellKnownServlet extends HttpServlet {

    static final String FOLDER = "/WEB-INF/well-known/";
    /** Stripe's Apple Pay domain-verification file: identical for every Stripe account, plain text, no extension. */
    static final String APPLE_PAY = "apple-developer-merchantid-domain-association";
    static final Set<String> KNOWN = Set.of("apple-app-site-association", "assetlinks.json", APPLE_PAY);

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response)
            throws ServletException, IOException {
        final String name = fileName(request.getPathInfo());
        if (name == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        try (InputStream in = getServletContext().getResourceAsStream(FOLDER + name)) {
            if (in == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            final String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(APPLE_PAY.equals(name) ? "text/plain" : "application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            // Apple's CDN re-fetches on its own schedule and Android verifies at install; a short public
            // cache keeps a change from taking a day to show up without hammering the task.
            response.setHeader("Cache-Control", "public, max-age=3600");
            response.getWriter().write(json);
        }
    }

    /** The one path segment after {@code /.well-known/}, only when it names a file this servlet owns. */
    static String fileName(final String pathInfo) {
        if (pathInfo == null) {
            return null;
        }
        final String name = pathInfo.startsWith("/") ? pathInfo.substring(1) : pathInfo;
        return KNOWN.contains(name) ? name : null;
    }
}
