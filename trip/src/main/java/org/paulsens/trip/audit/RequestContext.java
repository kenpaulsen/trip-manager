package org.paulsens.trip.audit;

import jakarta.servlet.http.HttpSession;

/**
 * The request-scoped facts that off-thread code may legitimately need: WHO is acting and their role. Bound
 * once per request as a {@link ScopedValue} by {@code SessionRecoveryFilter} (the outermost filter), and
 * inherited automatically by every {@code StructuredTaskScope} fork -- which is what finally fixes the
 * "audit actor lost across an async boundary" class of bug: a forked subtask sees the same actor its
 * request saw, with no hand-capturing.
 *
 * <p><b>Hard rule: nothing mutable or thread-affine goes in here.</b> Never {@code FacesContext} (its
 * component tree and view state are single-thread by design), never {@code HttpSession} or
 * {@code HttpServletRequest}, never a {@code Caller} (its memo map is documented single-request,
 * single-thread). A fat context is how non-thread-safe state gets smuggled across forks; this record stays
 * two immutable fields on purpose.</p>
 *
 * <p>Plain spawned threads ({@code TripThreads.start}) do NOT inherit ScopedValues -- fire-and-forget work
 * must rebind explicitly via {@code TripThreads.startAs}, and scheduled work binds
 * {@link AuditActor#system()}.</p>
 */
public record RequestContext(AuditActor actor, String userRole) {

    /** The one binding point is {@code SessionRecoveryFilter}; everyone else only reads. */
    public static final ScopedValue<RequestContext> SCOPE = ScopedValue.newInstance();

    private static final RequestContext ANONYMOUS = new RequestContext(AuditActor.from(null), null);

    /** Derives the context from the request's session (null session = anonymous visitor). */
    public static RequestContext from(final HttpSession session) {
        if (session == null) {
            return ANONYMOUS;
        }
        return new RequestContext(AuditActor.from(session), roleOf(session));
    }

    /** A context for background work the application starts on its own behalf. */
    public static RequestContext system() {
        return new RequestContext(AuditActor.system(), null);
    }

    /** A context carrying an explicitly captured actor (fire-and-forget spawns re-binding their request's). */
    public static RequestContext of(final AuditActor actor) {
        return of(actor, null);
    }

    /**
     * A context carrying an explicit actor AND role -- the bearer-token path, where both come from the
     * validated token rather than a session ({@code docs/api-tokens.md}). Still two immutable fields; the
     * hard rule above holds.
     */
    public static RequestContext of(final AuditActor actor, final String userRole) {
        return new RequestContext(actor == null ? AuditActor.from(null) : actor, userRole);
    }

    private static String roleOf(final HttpSession session) {
        final Object role = session.getAttribute("userRole");
        return role == null ? null : role.toString();
    }
}
