package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.model.Creds;

/**
 * The single place a signed-in session is created or destroyed.
 *
 * <p>Every way in -- the JSF login page, the REST login, and any future mechanism (email code, remember-me,
 * passkey) -- converges on {@link #establish}, so the fixation defense and the exact attribute set are decided
 * once. Before this class existed the browser login and {@code AuthResource} each did their own version, and
 * only the REST one rotated the session id.
 */
@Slf4j
public final class Sessions {

    /**
     * Set on the session at login and read by {@code AuditActor}. Leaving it unset would cost every audited
     * write its actor EMAIL while still recording an id -- a half-known actor, which looks like a populated
     * record until someone tries to read it.
     */
    public static final String LOGIN_EMAIL = "loginEmail";
    /** The admin's own id, retained across "act as user" so the pages can tell who is really signed in. */
    public static final String ADMIN_USER = "aUser";
    /** Stashed by auth-gated pages before redirecting to login; honored by the post-login redirect. */
    public static final String AFTER_LOGIN_URL = "afterLoginURL";
    /** The dark-mode preference -- cosmetic, but losing it on every login is a needless annoyance. */
    public static final String DARK = "dark";
    /**
     * Set (to {@code Boolean.TRUE}) only by a verified email-code login. It is the one-shot authorization for
     * setting a new password without knowing the old one, so nothing else may ever set it.
     */
    public static final String CODE_LOGIN = "codeLogin";
    /**
     * Set by {@link #establish} and cleared by the first page that reads it -- "this render is the first one
     * after a fresh sign-in". Drives one-time UI like the passkey setup offer. Plain marker, no authority.
     */
    public static final String JUST_LOGGED_IN = "justLoggedIn";

    private Sessions() {
    }

    /**
     * Populates a fresh session with what the rest of the application expects to find on it.
     *
     * <p>The old session is invalidated FIRST. Reusing whatever session id the caller arrived with lets an
     * attacker who can plant a cookie fix the victim's session id before they sign in, and then ride the
     * authenticated session afterwards. Invalidating means the id they planted is not the id that ends up
     * authenticated.
     *
     * <p>All four auth attributes are set because all four are read somewhere: {@code userId} by the auth
     * filter, {@code userRole} by {@code PersonCommands.hasRole}, {@code loginEmail} by {@code AuditActor},
     * and {@code aUser} by the admin pages. A session that sets fewer is subtly different from a real one in
     * ways that surface far from here.
     *
     * <p>{@code afterLoginURL} and {@code dark} are carried across the rotation: the first is the whole point
     * of the login redirect, and the second is a preference the user already expressed this visit.
     */
    public static HttpSession establish(final HttpServletRequest request, final String email, final Creds creds) {
        Object afterLogin = null;
        Object dark = null;
        final HttpSession existing = request.getSession(false);
        if (existing != null) {
            try {
                afterLogin = existing.getAttribute(AFTER_LOGIN_URL);
                dark = existing.getAttribute(DARK);
            } catch (final RuntimeException ex) {
                // A pre-login session can be unreadable (stale serialized state); losing the carried
                // attributes is acceptable, refusing the login is not.
                log.info("Could not read attributes off the pre-login session; continuing without them.", ex);
            }
            try {
                existing.invalidate();
            } catch (final IllegalStateException ex) {
                log.debug("Pre-login session was already invalid.", ex);
            }
        }
        final HttpSession session = request.getSession(true);
        final String role = creds.getPriv();
        session.setAttribute(PersonCommands.ACTIVE_USER_ID, creds.getUserId());
        session.setAttribute(PersonCommands.ACTIVE_USER_ROLE, role);
        session.setAttribute(LOGIN_EMAIL, email);
        session.setAttribute(ADMIN_USER, role != null && role.contains("admin") ? creds.getUserId() : null);
        session.setAttribute(JUST_LOGGED_IN, Boolean.TRUE);
        if (afterLogin != null) {
            session.setAttribute(AFTER_LOGIN_URL, afterLogin);
        }
        if (dark != null) {
            session.setAttribute(DARK, dark);
        }
        return session;
    }

    /**
     * Ends the session for real. Nulling the auth attributes -- what the logout link did for years -- leaves
     * the session id, the server-side state, and everything else on the session alive and reusable.
     * Idempotent: logging out when already logged out is a success.
     */
    public static void logout(final HttpServletRequest request) {
        final HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (final IllegalStateException ex) {
                log.debug("Session was already invalid at logout.", ex);
            }
        }
    }
}
