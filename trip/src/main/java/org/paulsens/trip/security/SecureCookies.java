package org.paulsens.trip.security;

import jakarta.servlet.http.HttpServletRequest;
import org.paulsens.trip.dynamo.LocalMode;

/**
 * The one rule for a cookie's {@code Secure} attribute, shared by every cookie this application writes itself.
 *
 * <p>Production is TLS-only, so a production cookie is ALWAYS Secure. It used to follow
 * {@code request.isSecure()}, which is true in production only because the load balancer forwards the scheme
 * and Tomcat's RemoteIpValve applies it: a valve dropped from the configuration would have silently turned
 * the flag off on the remember-me cookie with nothing failing. Local mode still follows the request, because
 * a plain-http {@code localhost} browser would never send a Secure cookie back.
 */
public final class SecureCookies {
    private SecureCookies() {
    }

    /** Whether a cookie written in answer to this request carries {@code Secure}. */
    public static boolean flagFor(final HttpServletRequest request) {
        return flagFor(request.isSecure(), LocalMode.isLocal());
    }

    /** The rule itself, apart from where its two facts come from (the unit tests all run in local mode). */
    static boolean flagFor(final boolean requestSecure, final boolean localMode) {
        return requestSecure || !localMode;
    }
}
