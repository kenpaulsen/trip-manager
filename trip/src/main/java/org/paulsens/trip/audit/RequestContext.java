package org.paulsens.trip.audit;

import jakarta.servlet.http.HttpSession;
import org.paulsens.trip.site.SiteContext;

/**
 * The request-scoped facts that off-thread code may legitimately need: WHO is acting, their role, and WHICH
 * SITE the request is for. Bound once per request as a {@link ScopedValue} by {@code SessionRecoveryFilter}
 * (the outermost filter), and inherited automatically by every {@code StructuredTaskScope} fork -- which is
 * what finally fixes the "audit actor lost across an async boundary" class of bug: a forked subtask sees the
 * same actor its request saw, with no hand-capturing.
 *
 * <p><b>Hard rule: nothing mutable or thread-affine goes in here.</b> Never {@code FacesContext} (its
 * component tree and view state are single-thread by design), never {@code HttpSession} or
 * {@code HttpServletRequest}, never a {@code Caller} (its memo map is documented single-request,
 * single-thread). A fat context is how non-thread-safe state gets smuggled across forks; this record stays
 * three immutable fields on purpose.</p>
 *
 * <p>The {@link SiteContext} is here because it is a fact about the request's hostname, not about the user:
 * once the session cookie spans {@code *.unitetrip.com}, one session serves several sites, so the site must
 * never be derived from (or cached on) the session. {@link #system()} carries the SHARED default -- scheduled
 * and background work has no host, and code it runs must take the organization explicitly.</p>
 *
 * <p>Plain spawned threads ({@code TripThreads.start}) do NOT inherit ScopedValues -- fire-and-forget work
 * must rebind explicitly via {@code TripThreads.startAs}, and scheduled work binds
 * {@link AuditActor#system()}.</p>
 */
public record RequestContext(AuditActor actor, String userRole, SiteContext site) {

    /** The one binding point is {@code SessionRecoveryFilter}; everyone else only reads. */
    public static final ScopedValue<RequestContext> SCOPE = ScopedValue.newInstance();

    public RequestContext {
        if (site == null) {
            site = SiteContext.shared(null);
        }
    }

    /** Derives the context from the request's session (null session = anonymous visitor). */
    public static RequestContext from(final HttpSession session) {
        return from(session, null);
    }

    /** {@link #from(HttpSession)} carrying the request's resolved site. */
    public static RequestContext from(final HttpSession session, final SiteContext site) {
        if (session == null) {
            return new RequestContext(AuditActor.from(null), null, site);
        }
        return new RequestContext(AuditActor.from(session), roleOf(session), site);
    }

    /** A context for background work the application starts on its own behalf. */
    public static RequestContext system() {
        return new RequestContext(AuditActor.system(), null, null);
    }

    /** A context carrying an explicitly captured actor (fire-and-forget spawns re-binding their request's). */
    public static RequestContext of(final AuditActor actor) {
        return of(actor, null);
    }

    /**
     * A context carrying an explicit actor AND role -- the bearer-token path, where both come from the
     * validated token rather than a session ({@code docs/api-tokens.md}). Still three immutable fields; the
     * hard rule above holds.
     */
    public static RequestContext of(final AuditActor actor, final String userRole) {
        return of(actor, userRole, null);
    }

    /** {@link #of(AuditActor, String)} carrying the request's resolved site. */
    public static RequestContext of(final AuditActor actor, final String userRole, final SiteContext site) {
        return new RequestContext(actor == null ? AuditActor.from(null) : actor, userRole, site);
    }

    private static String roleOf(final HttpSession session) {
        final Object role = session.getAttribute("userRole");
        return role == null ? null : role.toString();
    }
}
