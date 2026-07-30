package org.paulsens.trip.audit;

import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpSession;
import java.util.Map;

/**
 * Who is doing the thing being audited.
 *
 * <p>Almost every call site had the same bug in the same way: it recorded whoever the action was ABOUT rather
 * than whoever performed it. Mail recorded the recipient, todo saved recorded the person whose list changed.
 * That reads fine until an admin acts on someone else's behalf, at which point the trail attributes the action
 * to the wrong person -- which is precisely when an audit trail is being consulted.
 *
 * <p>The actor is the signed-in session, full stop. Resolving it in one place means a call site cannot get it
 * wrong by passing the convenient variable that happens to be in scope.
 */
public record AuditActor(String email, String id) {

    /** Session keys, as set at login and read throughout the XHTML pages. */
    private static final String LOGIN_EMAIL = "loginEmail";
    private static final String USER_ID = "userId";

    private static final AuditActor UNKNOWN = new AuditActor(null, null);

    /**
     * The signed-in user, or an empty actor outside a request (background threads, tests, the reset-password
     * flow where nobody is signed in yet).
     */
    public static AuditActor current() {
        final FacesContext context = FacesContext.getCurrentInstance();
        if (context == null || context.getExternalContext() == null) {
            return UNKNOWN;
        }
        final Map<String, Object> session = context.getExternalContext().getSessionMap();
        if (session == null) {
            return UNKNOWN;
        }
        return new AuditActor(str(session.get(LOGIN_EMAIL)), str(session.get(USER_ID)));
    }

    /**
     * The signed-in user resolved from a raw {@link HttpSession}, for edges with no FacesContext (JAX-RS
     * resources, future sockets). Reads the same session keys as {@link #current()} so the two paths cannot
     * drift.
     */
    public static AuditActor from(final HttpSession session) {
        if (session == null) {
            return UNKNOWN;
        }
        return new AuditActor(str(session.getAttribute(LOGIN_EMAIL)), str(session.getAttribute(USER_ID)));
    }

    /**
     * The signed-in user, falling back to the given email when nobody is signed in.
     *
     * <p>The fallback is for self-service flows -- creating an account, resetting a forgotten password -- where
     * there is no session yet and the person acting genuinely is the account holder.
     */
    public static AuditActor currentOr(final String fallbackEmail) {
        final AuditActor actor = current();
        return (actor.email() == null) ? new AuditActor(fallbackEmail, null) : actor;
    }

    public boolean isKnown() {
        return email != null || id != null;
    }

    private static String str(final Object value) {
        // userId may be a Person.Id or a plain String depending on the page that set it; toString covers both
        // because Person.Id serializes to its value.
        return (value == null) ? null : value.toString();
    }
}
