package org.paulsens.trip.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.AuditPage;
import org.paulsens.trip.model.AuditQuery;

/**
 * Recovers a browser whose stored session can no longer be read, instead of showing it a stack trace.
 *
 * <p>Sessions are held in Valkey and serialised by Redisson with Kryo, which writes fields <b>by position</b>
 * and stores no field names. A session blob is therefore only readable by code whose classes have exactly the
 * shape they had when it was written. Add one field to a class that lives in the session — {@code Trip} does, as
 * {@code lastTrip} — and every field after it shifts on read. That is not a clean failure: values land in the
 * wrong slots and surface as nonsense far from the cause, such as
 * {@code DateTimeException: Invalid value for MonthOfYear ... 19} on the public home page.
 *
 * <p>The blob is unrecoverable, but the <b>user</b> is: their session holds a login and some page state, none of
 * it irreplaceable. So this drops the unreadable session and lets them start a fresh one, which costs them a
 * re-login and costs everyone else nothing — as opposed to namespacing sessions per build, which would log
 * <em>everybody</em> out on every deploy, benign or not.
 *
 * <p>Deliberately narrow. Only failures that name Kryo or the Redisson codec in their cause chain are treated
 * this way; anything else propagates untouched, because clearing sessions in response to unrelated errors would
 * turn one bug into an intermittent logout nobody can explain.
 */
@Slf4j
public class SessionRecoveryFilter implements Filter {

    /** At most one audit record per browser per window, however many requests fail in it. */
    static final Duration AUDIT_DEDUPE_WINDOW = Duration.ofMinutes(5);
    /** How far back to look for an earlier record. The window is minutes, so the newest page is plenty. */
    static final int AUDIT_SCAN_LIMIT = 50;
    /** The marker that makes these findable, and that the dedupe scan matches on. */
    static final String AUDIT_PREFIX = "session.cleared:";

    /** Frames that identify a session-deserialisation failure rather than an application error. */
    private static final Set<String> SERIALIZATION_MARKERS = Set.of(
            "com.esotericsoftware.kryo",
            "org.redisson.codec",
            "org.redisson.client.handler.commanddecoder");

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        try {
            chain.doFilter(request, response);
        } catch (final IOException | ServletException | RuntimeException | Error ex) {
            if (!(request instanceof HttpServletRequest req)
                    || !(response instanceof HttpServletResponse res)
                    || !isSessionDeserializationFailure(ex)) {
                throw ex;
            }
            recover(req, res, ex);
        }
    }

    /**
     * Drops the unreadable session and sends the browser back to the same page with a clean one.
     *
     * <p>The redirect matters: without it the user is left looking at whatever error page the container chose,
     * still holding the cookie that caused it, and a reload would fail the same way. A redirect makes the next
     * request start from nothing, which is the state that works.
     */
    private void recover(
            final HttpServletRequest req, final HttpServletResponse res, final Throwable cause)
            throws IOException {
        final String sessionId = req.getRequestedSessionId();
        log.warn("Clearing an unreadable session ({}) for {}; it cannot be deserialised by this build",
                sessionId, req.getRequestURI(), cause);
        invalidateQuietly(req);
        expireCookie(req, res);
        auditOnce(sessionId, req);
        if (res.isCommitted()) {
            // Something was already written, so a redirect is impossible. The session is still cleared, so the
            // user's next request works; this one is lost.
            return;
        }
        res.sendRedirect(redirectTarget(req));
    }

    /**
     * Invalidating touches the very object that failed to load, so it may fail again. That is fine and expected:
     * expiring the cookie below is what actually detaches the browser from the bad blob.
     */
    private void invalidateQuietly(final HttpServletRequest req) {
        try {
            final HttpSession session = req.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        } catch (final RuntimeException ex) {
            log.debug("Could not invalidate the unreadable session; expiring the cookie instead", ex);
        }
    }

    private void expireCookie(final HttpServletRequest req, final HttpServletResponse res) {
        final Cookie dead = new Cookie("JSESSIONID", "");
        dead.setPath(req.getContextPath().isEmpty() ? "/" : req.getContextPath());
        dead.setMaxAge(0);
        dead.setHttpOnly(true);
        dead.setSecure(req.isSecure());
        res.addCookie(dead);
    }

    /** Back to the same page for a GET; for anything else the path without its body, since that cannot be replayed. */
    private String redirectTarget(final HttpServletRequest req) {
        final String query = req.getQueryString();
        final boolean replayable = "GET".equalsIgnoreCase(req.getMethod());
        return req.getRequestURI() + (replayable && query != null && !query.isBlank() ? "?" + query : "");
    }

    /**
     * Records the clearance, at most once per browser per {@value #AUDIT_DEDUPE_WINDOW} minutes.
     *
     * <p>Deduped by reading the audit trail rather than by holding a counter, because the trail is the thing that
     * has to be right: a counter in the cache would let the same incident write a record per request after a
     * cache blip, and a page full of identical rows is how a genuinely interesting event gets skimmed past.
     *
     * <p>Keyed on the <b>session id</b>, not the person: the session is exactly what could not be read, so there
     * is no signed-in user to name. That means a browser which clears, logs back in and fails again on a second
     * stale cookie can produce a second record inside the window — correct, since those are two sessions.
     */
    private void auditOnce(final String sessionId, final HttpServletRequest req) {
        final String key = sessionId == null ? "unknown" : sessionId;
        try {
            if (recentlyAudited(key)) {
                return;
            }
            Audit.builder(AuditAction.ALARM, AuditOutcome.FAILURE)
                    .message(AUDIT_PREFIX + " session=" + key + " path=" + req.getRequestURI()
                            + "; the stored session could not be deserialised by this build and was cleared")
                    .log();
        } catch (final RuntimeException ex) {
            // Never let bookkeeping break the recovery: the user getting a working page matters more than the
            // record of why they needed one.
            log.warn("Unable to record that session {} was cleared", key, ex);
        }
    }

    /** Whether this session already has a clearance record inside the window. */
    private boolean recentlyAudited(final String key) {
        final Instant since = Instant.now().minus(AUDIT_DEDUPE_WINDOW);
        final AuditPage page = DAO.getInstance().getAuditEvents(AuditQuery.builder()
                        .action(AuditAction.ALARM)
                        .since(since)
                        .text(AUDIT_PREFIX)
                        .limit(AUDIT_SCAN_LIMIT)
                        .build())
                ;
        return page.getEvents().stream()
                .anyMatch(e -> e.getMessage() != null && e.getMessage().contains("session=" + key));
    }

    /**
     * Whether this failure is a session that cannot be deserialised.
     *
     * <p>Checks the whole cause chain and each frame, since the decode happens on a Netty thread and reaches the
     * request wrapped in whatever the session manager threw. Guards against a cyclic chain, which
     * {@code CompletionException} plumbing can produce.
     */
    static boolean isSessionDeserializationFailure(final Throwable error) {
        final Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable t = error; t != null && seen.put(t, Boolean.TRUE) == null; t = t.getCause()) {
            if (namesSerialization(t.getClass().getName()) || framesNameSerialization(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean framesNameSerialization(final Throwable t) {
        for (final StackTraceElement frame : t.getStackTrace()) {
            if (namesSerialization(frame.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesSerialization(final String className) {
        if (className == null) {
            return false;
        }
        final String lower = className.toLowerCase(Locale.ROOT);
        return SERIALIZATION_MARKERS.stream().anyMatch(lower::startsWith);
    }
}
