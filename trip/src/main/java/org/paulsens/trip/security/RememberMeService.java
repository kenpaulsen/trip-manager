package org.paulsens.trip.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.AuthToken;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;

/**
 * The {@code trip_remember} cookie: quietly signs a browser back in after its session is gone.
 *
 * <p>Selector/validator design, shared with the API bearer tokens through {@link SelectorTokens} (see
 * {@code docs/api-tokens.md}). The cookie is {@code selector:validator}; the row (an {@link AuthToken} of
 * kind {@code REMEMBER}) stores the selector and SHA-256(validator). Lookup is by selector, the validator is
 * compared constant-time, and on every successful use the validator ROTATES under the same selector -- so a
 * copied cookie dies the next time the real browser checks in, and a stale validator against a live selector
 * is treated as theft: row deleted, ALARM audit, cookie expired.
 *
 * <p>One exception to "stale means theft": for {@link SelectorTokens#ROTATION_GRACE_SECONDS} seconds after a
 * rotation, the validator that rotation replaced is still honored (restore only -- no second rotation, no
 * Set-Cookie). A browser whose session just died fires several requests at once with the SAME cookie;
 * whichever lands first rotates, and without the grace every sibling request would trip the theft detector
 * and burn a legitimate token. The window is short enough that a thief gains at most one restore they could
 * already have had by winning the race.
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
    /** The shared grace constant, re-exposed where the cookie tests historically found it. */
    static final long ROTATION_GRACE_SECONDS = SelectorTokens.ROTATION_GRACE_SECONDS;
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
        final SelectorTokens.Minted minted = SelectorTokens.mint();
        final long now = Instant.now().getEpochSecond();
        final AuthToken token = AuthToken.builder()
                .selector(minted.selector())
                .validatorHash(minted.validatorHash())
                .userId(creds.getUserId())
                .email(creds.getEmail())
                .created(now)
                .lastUsed(now)
                .expires(now + days() * 86_400L)
                .kind(AuthToken.Kind.REMEMBER)
                .build();
        if (!Boolean.TRUE.equals(DAO.getInstance().saveAuthToken(token))) {
            // No row means the cookie could never work; better no cookie than a dead one.
            return;
        }
        response.addCookie(cookie(request, minted.presentable(), (int) (days() * 86_400L)));
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
        final Optional<SelectorTokens.Parsed> parsed = SelectorTokens.parse(cookie.getValue());
        if (parsed.isEmpty()) {
            expire(request, response);
            return null;
        }
        final String selector = parsed.get().selector();
        final AuthToken token = DAO.getInstance().getAuthToken(selector, Cached.NO).orElse(null);
        if (token == null) {
            expire(request, response);
            return null;
        }
        final long now = Instant.now().getEpochSecond();
        final SelectorTokens.Judgment judgment = SelectorTokens.judge(token, parsed.get().validatorHash(), now);
        if (judgment == SelectorTokens.Judgment.EXPIRED) {
            DAO.getInstance().deleteAuthToken(selector);
            expire(request, response);
            return null;
        }
        if (judgment == SelectorTokens.Judgment.THEFT) {
            // A known selector with the wrong validator is the theft signature: either the thief presented a
            // stale copy after the owner rotated, or the owner is presenting a stale copy after the thief
            // used it. Both mean the token is compromised; kill it loudly.
            DAO.getInstance().deleteAuthToken(selector);
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
            DAO.getInstance().deleteAuthToken(selector);
            expire(request, response);
            return null;
        }
        if (judgment == SelectorTokens.Judgment.CURRENT) {
            rotate(request, response, token, selector);
        }
        // Grace path: no rotation and no Set-Cookie -- the racing request that won already rotated and set
        // the browser's cookie to the live validator; rotating again here would just restage the race.
        return creds;
    }

    /** Deletes THIS browser's token and cookie (logout). */
    public void revoke(final HttpServletRequest request, final HttpServletResponse response) {
        final Cookie cookie = find(request);
        if (cookie != null) {
            SelectorTokens.parse(cookie.getValue())
                    .ifPresent(parsed -> DAO.getInstance().deleteAuthToken(parsed.selector()));
            expire(request, response);
        }
    }

    /** Deletes EVERY credential of every kind for this person (password change, credentials removal). */
    public void revokeAllFor(final Person.Id userId) {
        final int revoked = DAO.getInstance().deleteAuthTokensForUser(userId).size();
        if (revoked > 0) {
            log.info("Revoked {} auth token(s) for {}", revoked, userId.getValue());
        }
    }

    private void rotate(final HttpServletRequest request, final HttpServletResponse response,
            final AuthToken token, final String selector) {
        final String freshValidator = SelectorTokens.mintValidator();
        final long now = Instant.now().getEpochSecond();
        // The outgoing validator stays honored for the grace window; only ONE generation back, so a
        // validator two rotations old is stale evidence again.
        token.setPrevValidatorHash(token.getValidatorHash());
        token.setRotatedAt(now);
        token.setValidatorHash(Digests.sha256Base64(freshValidator));
        token.setLastUsed(now);
        if (Boolean.TRUE.equals(DAO.getInstance().saveAuthToken(token))) {
            final int remaining = (int) Math.max(60L, token.getExpires() - Instant.now().getEpochSecond());
            response.addCookie(cookie(request, selector + ":" + freshValidator, remaining));
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
