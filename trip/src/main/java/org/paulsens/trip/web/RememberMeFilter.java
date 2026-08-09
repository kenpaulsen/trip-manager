package org.paulsens.trip.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.security.RememberMeService;

/**
 * Restores a signed-in session from the {@code trip_remember} cookie when a request arrives without one.
 *
 * <p>Mapped {@code /*}, AFTER {@link SessionRecoveryFilter} (which must stay first: it binds the request
 * context and turns unreadable-session failures into a clean retry -- a retry that then flows through here
 * and is restored, which is exactly the "deploy broke my session" experience this feature exists to fix).
 *
 * <p>Page requests are REDIRECTED back to the same URL after a restore rather than continuing the chain: the
 * request context was already bound off the anonymous session above this filter, so continuing would run the
 * page actor-less. API requests continue instead -- JSON clients don't follow redirect semantics -- because
 * their actor binding happens later, per-resource.
 */
@Slf4j
public class RememberMeFilter implements Filter {

    private final RememberMeService rememberMe;

    public RememberMeFilter() {
        this(RememberMeService.getInstance());
    }

    RememberMeFilter(final RememberMeService rememberMe) {
        this.rememberMe = rememberMe;
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse res)) {
            chain.doFilter(request, response);
            return;
        }
        if (signedIn(req) || !hasCookie(req)) {
            chain.doFilter(request, response);
            return;
        }
        final Creds creds = rememberMe.validateAndRotate(req, res);
        if (creds == null) {
            chain.doFilter(request, response);
            return;
        }
        Sessions.establish(req, creds.getEmail(), creds);
        Audit.builder(AuditAction.LOGIN, AuditOutcome.SUCCESS)
                .actor(creds.getEmail(), creds.getUserId() == null ? null : creds.getUserId().getValue())
                .message("Session restored via remember-me cookie")
                .log();
        if (req.getRequestURI().startsWith("/api/")) {
            chain.doFilter(request, response);
        } else {
            res.sendRedirect(redirectTarget(req));
        }
    }

    /** One cheap attribute read; the overwhelmingly common case must cost nothing. */
    private static boolean signedIn(final HttpServletRequest req) {
        final HttpSession session = req.getSession(false);
        return session != null && session.getAttribute(PersonCommands.ACTIVE_USER_ID) != null;
    }

    private static boolean hasCookie(final HttpServletRequest req) {
        final var cookies = req.getCookies();
        if (cookies == null) {
            return false;
        }
        for (final var cookie : cookies) {
            if (RememberMeService.COOKIE_NAME.equals(cookie.getName())) {
                return true;
            }
        }
        return false;
    }

    /** Same convention as {@link SessionRecoveryFilter}: replay a GET exactly, anything else without its body. */
    private static String redirectTarget(final HttpServletRequest req) {
        final String query = req.getQueryString();
        final boolean replayable = "GET".equalsIgnoreCase(req.getMethod());
        return req.getRequestURI() + (replayable && query != null && !query.isBlank() ? "?" + query : "");
    }
}
