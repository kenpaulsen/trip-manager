package org.paulsens.trip.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.RememberToken;
import org.paulsens.trip.util.RandomData;
import org.paulsens.trip.cache.Cached;

/**
 * The {@code trip_remember} cookie: quietly signs a browser back in after its session is gone.
 *
 * <p>Selector/validator design. The cookie is {@code selector:validator}; the row (see
 * {@code RememberMeDAO}) stores the selector and SHA-256(validator). Lookup is by selector, the validator is
 * compared constant-time, and on every successful use the validator ROTATES under the same selector -- so a
 * copied cookie dies the next time the real browser checks in, and a stale validator against a live selector
 * is treated as theft: row deleted, ALARM audit, cookie expired.
 *
 * <p>One exception to "stale means theft": for {@value #ROTATION_GRACE_SECONDS} seconds after a rotation, the
 * validator that rotation replaced is still honored (restore only -- no second rotation, no Set-Cookie).
 * A browser whose session just died fires several requests at once with the SAME cookie; whichever lands
 * first rotates, and without the grace every sibling request would trip the theft detector and burn a
 * legitimate token. The window is short enough that a thief gains at most one restore they could already
 * have had by winning the race.
 *
 * <p>Admin accounts are excluded at BOTH ends (no cookie issued, no cookie honored), so a role change
 * mid-token also kills the token. Expiry is absolute from creation. Role is read fresh from the pass table at
 * every restore, never from the token.
 *
 * <p>A plain singleton, not CDI: the servlet filter that restores sessions runs outside CDI's comfort zone,
 * and the service has no injected collaborators that tests cannot hand it directly.
 */
// Not final: the filter test stubs this class, and a final class's methods cannot be stubbed.
@Slf4j
public class RememberMeService {

    public static final String COOKIE_NAME = "trip_remember";
    /** How long the pre-rotation validator stays honored; see the class javadoc for why it exists at all. */
    static final long ROTATION_GRACE_SECONDS = 30;
    private static final char SEPARATOR = ':';
    private static final RememberMeService INSTANCE = new RememberMeService(new ConfigCommands());

    private final ConfigCommands config;

    public static RememberMeService getInstance() {
        return INSTANCE;
    }

    // Package-private: tests supply their own config.
    RememberMeService(final ConfigCommands config) {
        this.config = config;
    }

    /** Issues a fresh token + cookie for this login. No-op for admins and when the feature is off. */
    public void issue(final HttpServletRequest request, final HttpServletResponse response, final Creds creds) {
        if (!enabled() || creds == null || isAdmin(creds.getPriv())) {
            return;
        }
        final String selector = RandomData.genSecureToken(9);
        final String validator = RandomData.genSecureToken(32);
        final long now = Instant.now().getEpochSecond();
        final long expires = now + days() * 86_400L;
        final RememberToken token = new RememberToken(selector, Digests.sha256Base64(validator), null, null,
                creds.getUserId(), creds.getEmail(), now, now, expires);
        if (!Boolean.TRUE.equals(DAO.getInstance().saveRememberToken(token))) {
            // No row means the cookie could never work; better no cookie than a dead one.
            return;
        }
        response.addCookie(cookie(request, selector + SEPARATOR + validator, (int) (days() * 86_400L)));
    }

    /**
     * Validates the cookie on a session-less request and answers fresh {@link Creds} to establish a session
     * with, rotating the validator. Null means "not signed in" -- for every reason, silently; the cookie is
     * expired client-side whenever it can never work again.
     */
    public Creds validateAndRotate(final HttpServletRequest request, final HttpServletResponse response) {
        final Cookie cookie = find(request);
        if (cookie == null || !enabled()) {
            return null;
        }
        final String value = cookie.getValue() == null ? "" : cookie.getValue();
        final int split = value.indexOf(SEPARATOR);
        if (split <= 0 || split == value.length() - 1) {
            expire(request, response);
            return null;
        }
        final String selector = value.substring(0, split);
        final String validator = value.substring(split + 1);
        final RememberToken token = DAO.getInstance().getRememberToken(selector, Cached.NO).orElse(null);
        if (token == null) {
            expire(request, response);
            return null;
        }
        if (token.getExpires() == null || token.getExpires() <= Instant.now().getEpochSecond()) {
            DAO.getInstance().deleteRememberToken(selector);
            expire(request, response);
            return null;
        }
        final String presentedHash = Digests.sha256Base64(validator);
        final boolean current = Digests.matches(token.getValidatorHash(), presentedHash);
        if (!current && !withinRotationGrace(token, presentedHash)) {
            // A known selector with the wrong validator is the theft signature: either the thief presented a
            // stale copy after the owner rotated, or the owner is presenting a stale copy after the thief
            // used it. Both mean the token is compromised; kill it loudly.
            DAO.getInstance().deleteRememberToken(selector);
            Audit.builder(AuditAction.ALARM, AuditOutcome.FAILURE)
                    .actor(token.getEmail(), token.getUserId() == null ? null : token.getUserId().getValue())
                    .message("remember.theft: stale remember-me validator presented for a live selector")
                    .log();
            expire(request, response);
            return null;
        }
        // Role comes from the pass table NOW, not from the token: privilege changes must bite immediately,
        // and an account that became admin since issue stops being restorable at all.
        final Creds creds = DAO.getInstance().getCredsForCodeLogin(token.getEmail(), Cached.NO);
        if (creds == null || isAdmin(creds.getPriv())) {
            DAO.getInstance().deleteRememberToken(selector);
            expire(request, response);
            return null;
        }
        if (current) {
            rotate(request, response, token, selector);
        }
        // Grace path: no rotation and no Set-Cookie -- the racing request that won already rotated and set
        // the browser's cookie to the live validator; rotating again here would just restage the race.
        return creds;
    }

    /**
     * Whether this stale validator is the browser racing its own rotation rather than a theft: it must be
     * the exact validator the LAST rotation replaced, presented within {@value #ROTATION_GRACE_SECONDS}
     * seconds of that rotation. Rows from before the grace columns existed have neither field and never
     * match, which keeps the old strict behavior for them.
     */
    private static boolean withinRotationGrace(final RememberToken token, final String presentedHash) {
        return token.getPrevValidatorHash() != null && token.getRotatedAt() != null
                && Digests.matches(token.getPrevValidatorHash(), presentedHash)
                && Instant.now().getEpochSecond() - token.getRotatedAt() <= ROTATION_GRACE_SECONDS;
    }

    /** Deletes THIS browser's token and cookie (logout). */
    public void revoke(final HttpServletRequest request, final HttpServletResponse response) {
        final Cookie cookie = find(request);
        if (cookie != null) {
            final String value = cookie.getValue() == null ? "" : cookie.getValue();
            final int split = value.indexOf(SEPARATOR);
            if (split > 0) {
                DAO.getInstance().deleteRememberToken(value.substring(0, split));
            }
            expire(request, response);
        }
    }

    /** Deletes EVERY browser's token for this person (password change, credentials removal). */
    public void revokeAllFor(final Person.Id userId) {
        final int revoked = DAO.getInstance().deleteRememberTokensForUser(userId);
        if (revoked > 0) {
            log.info("Revoked {} remember-me token(s) for {}", revoked, userId.getValue());
        }
    }

    private void rotate(final HttpServletRequest request, final HttpServletResponse response,
            final RememberToken token, final String selector) {
        final String freshValidator = RandomData.genSecureToken(32);
        final long now = Instant.now().getEpochSecond();
        // The outgoing validator stays honored for the grace window; only ONE generation back, so a
        // validator two rotations old is stale evidence again.
        token.setPrevValidatorHash(token.getValidatorHash());
        token.setRotatedAt(now);
        token.setValidatorHash(Digests.sha256Base64(freshValidator));
        token.setLastUsed(now);
        if (Boolean.TRUE.equals(DAO.getInstance().saveRememberToken(token))) {
            final int remaining = (int) Math.max(60L, token.getExpires() - Instant.now().getEpochSecond());
            response.addCookie(cookie(request, selector + SEPARATOR + freshValidator, remaining));
        }
        // A failed rotation save leaves the old validator valid; the login still proceeds -- rotation is a
        // hardening measure, not a precondition.
    }

    private void expire(final HttpServletRequest request, final HttpServletResponse response) {
        response.addCookie(cookie(request, "", 0));
    }

    private Cookie cookie(final HttpServletRequest request, final String value, final int maxAge) {
        final Cookie cookie = new Cookie(COOKIE_NAME, value);
        cookie.setHttpOnly(true);
        // Secure tracks the request so plain-http local mode still works; production is TLS-only.
        cookie.setSecure(request.isSecure());
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }

    private static Cookie find(final HttpServletRequest request) {
        final Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (final Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie;
            }
        }
        return null;
    }

    private boolean enabled() {
        return config.getBoolean(KnownSettings.LOGIN_REMEMBER_ENABLED);
    }

    private long days() {
        return config.getInt(KnownSettings.LOGIN_REMEMBER_DAYS, 1, 365);
    }

    private static boolean isAdmin(final String priv) {
        return priv != null && priv.contains("admin");
    }
}
