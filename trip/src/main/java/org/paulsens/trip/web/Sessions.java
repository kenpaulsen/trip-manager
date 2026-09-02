package org.paulsens.trip.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.security.RememberMeService;
import org.paulsens.trip.site.SiteContext;

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

    /**
     * The organization whose Appearance page this admin has unsaved preview values for, as a plain id
     * string, and those values as a {@code HashMap<String, String>} of setting name to value. Scalars only,
     * exactly like everything else on the session: a domain object here is a domain object in the session.
     *
     * <p>Written and read ONLY by {@code BrandCommands}, which applies them in place of the organization's
     * stored branding while that one admin renders that one page (see {@code docs/org-admin.md},
     * "Previewing unsaved appearance"). They are one person's unsaved edit, so they must never be consulted
     * anywhere else: the org's live site, every other page, and every other visitor read the stored values.
     */
    public static final String APPEARANCE_PREVIEW_ORG = "appearancePreviewOrg";
    public static final String APPEARANCE_PREVIEW = "appearancePreview";

    /**
     * The stack of admin session snapshots behind "View As" ({@link #pushViewAs}/{@link #popViewAs}).
     * A list (not one map) because the View As affordance renders on user-visible pages too, so a second
     * push before the first pop is reachable; each pop unwinds exactly one level.
     */
    public static final String VIEW_AS_STACK = "viewAsStack";

    private Sessions() {
    }

    /**
     * Admin "View As": snapshots EVERY session attribute onto the stack, clears the session, and seeds the
     * target identity -- so nothing of the admin's browsing state (registration drafts, resume banners,
     * payment targets, one-shot grants, staged uploads, acting-for) can leak into what the viewed user
     * "has". Mirrors {@link #establish}'s wipe without rotating the id (no privilege is gained here).
     *
     * <p>Three attributes deliberately survive: {@code aUser} (what renders the Back-to-Admin affordance),
     * {@code loginEmail} (audit attribution stays with the real human -- an audit row during View As reads
     * admin-email acting as target-id, which is exactly what happened), and {@code dark} (cosmetic).
     *
     * @return false (and does nothing) unless the session really belongs to an admin ({@code aUser} set).
     */
    public static boolean pushViewAs(final HttpSession session, final Person.Id targetId) {
        if (session == null || targetId == null || session.getAttribute(ADMIN_USER) == null) {
            return false;
        }
        final List<HashMap<String, Object>> stack = stackOf(session);
        final HashMap<String, Object> saved = new HashMap<>();
        for (final String name : attributeNames(session)) {
            if (!VIEW_AS_STACK.equals(name)) {
                saved.put(name, session.getAttribute(name));
                session.removeAttribute(name);
            }
        }
        stack.add(saved);
        session.setAttribute(VIEW_AS_STACK, stack);
        session.setAttribute(PersonCommands.ACTIVE_USER_ID, targetId);
        session.setAttribute(PersonCommands.ACTIVE_USER_ROLE, "user");
        session.setAttribute(ADMIN_USER, saved.get(ADMIN_USER));
        session.setAttribute(LOGIN_EMAIL, saved.get(LOGIN_EMAIL));
        if (saved.get(DARK) != null) {
            session.setAttribute(DARK, saved.get(DARK));
        }
        return true;
    }

    /**
     * Back to admin: clears everything the viewed-as user accumulated and restores the most recent
     * snapshot. Restoring is best-effort comfort; the CLEARING is the point -- state picked up while
     * impersonating must not follow the admin back either.
     *
     * @return false (and does nothing) when there is no snapshot to pop.
     */
    public static boolean popViewAs(final HttpSession session) {
        if (session == null) {
            return false;
        }
        final List<HashMap<String, Object>> stack = stackOf(session);
        if (stack.isEmpty()) {
            return false;
        }
        final HashMap<String, Object> saved = stack.remove(stack.size() - 1);
        for (final String name : attributeNames(session)) {
            session.removeAttribute(name);
        }
        for (final HashMap.Entry<String, Object> entry : saved.entrySet()) {
            if (entry.getValue() != null) {
                session.setAttribute(entry.getKey(), entry.getValue());
            }
        }
        if (!stack.isEmpty()) {
            session.setAttribute(VIEW_AS_STACK, stack);
        }
        return true;
    }

    /** The current snapshot stack, always mutable and never null. */
    @SuppressWarnings("unchecked")
    private static List<HashMap<String, Object>> stackOf(final HttpSession session) {
        final Object raw = session.getAttribute(VIEW_AS_STACK);
        return (raw instanceof List<?>) ? (List<HashMap<String, Object>>) raw : new ArrayList<>();
    }

    /** Attribute names materialized first: removing while enumerating is undefined. */
    private static List<String> attributeNames(final HttpSession session) {
        final List<String> names = new ArrayList<>();
        final Enumeration<String> raw = session.getAttributeNames();
        if (raw != null) {
            names.addAll(Collections.list(raw));
        }
        return names;
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

    /**
     * {@link #logout(HttpServletRequest)} plus the browser side: the session cookie is expired too, so
     * logging out on one {@code *.unitetrip.com} site signs the browser out of EVERY site the shared
     * (domain-wide) session cookie covered.
     *
     * <p>Two deletions of {@code JSESSIONID} go out on an org-site host, and both are needed. The first is
     * an ordinary {@code addCookie}, which the container's cookie processor widens to the shared domain on
     * those hosts exactly as it widened the login cookie -- that deletes the SSO cookie. The second is a raw
     * host-only {@code Set-Cookie} header, which bypasses the processor on purpose: a browser that signed in
     * at {@code acme.unitetrip.com} BEFORE the shared-cookie deploy still holds a host-only cookie of the same
     * name, and Tomcat picks whichever presented id is still valid -- so unless that one dies here too, a
     * signed-out browser could be quietly served by the other session. Shared hosts get only the first
     * (their cookie was never widened, so the first already is the host-only delete).
     */
    public static void logout(final HttpServletRequest request, final HttpServletResponse response) {
        logout(request);
        if (response == null) {
            return;
        }
        final String path = request.getContextPath() == null || request.getContextPath().isEmpty()
                ? "/" : request.getContextPath();
        final Cookie dead = new Cookie(SESSION_COOKIE, "");
        dead.setPath(path);
        dead.setMaxAge(0);
        dead.setHttpOnly(true);
        dead.setSecure(request.isSecure());
        response.addCookie(dead);
        if (!SiteContext.current().isShared()) {
            response.addHeader("Set-Cookie", hostOnlyDeletion(SESSION_COOKIE, path));
            response.addHeader("Set-Cookie", hostOnlyDeletion(RememberMeService.COOKIE_NAME, "/"));
        }
    }

    /** The container's session cookie; its name is Tomcat's default and the live descriptor never renames it. */
    static final String SESSION_COOKIE = "JSESSIONID";

    /** A host-only deletion the cookie processor never sees (a raw header is not a {@code Cookie}). */
    static String hostOnlyDeletion(final String name, final String path) {
        return name + "=; Path=" + path + "; Max-Age=0; HttpOnly";
    }
}
