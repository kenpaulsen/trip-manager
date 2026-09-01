package org.paulsens.trip.security;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
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

/**
 * Bearer tokens for the REST API: issuance, refresh, revocation (see {@code docs/api-tokens.md}).
 *
 * <p>A grant is a REFRESH token (long-lived, rotate-on-use with {@link SelectorTokens}'s grace-and-theft
 * semantics -- the remember-me machinery, deliberately) plus an ACCESS token (short-lived, never rotates,
 * carries {@code parentSelector} back to the refresh token so revocation cascades). {@code role} and
 * {@code scope} are stamped on the rows at issuance and refresh, when credentials are read anyway; access
 * validation trusts the stamp for the access lifetime, and the refresh call is the freshness checkpoint at
 * which a demoted admin loses admin power -- the same staleness bound a live session has today.
 *
 * <p>Every refusal is null, indistinguishably, for the same enumeration reason the login endpoints answer
 * one generic message.
 *
 * <p>A plain singleton, not CDI: same rationale as {@link RememberMeService}, whose callers this shares.
 */
@Slf4j
public class TokenService {

    private static final TokenService INSTANCE = new TokenService(new ConfigCommands());

    private final ConfigCommands config;

    public static TokenService getInstance() {
        return INSTANCE;
    }

    // Public so tests outside this package (the resource tests) can supply their own config.
    public TokenService(final ConfigCommands config) {
        this.config = config;
    }

    /**
     * What a successful issue or refresh hands the client. A null {@code refreshToken} means "keep the one
     * you presented" -- the grace path, where the racing request that won already holds the rotated token.
     */
    public record Grant(String accessToken, long accessExpiresIn, String refreshToken, String scope) {
    }

    /** The kill switch: off means no issuance, no refresh, and (checked by the auth path) no acceptance. */
    public boolean enabled() {
        return config.getBoolean(KnownSettings.API_TOKEN_ENABLED);
    }

    /** Whether these credentials may hold this scope -- ADMIN is granted, never escalated. */
    public boolean mayGrant(final Creds creds, final AuthToken.Scope scope) {
        return scope != AuthToken.Scope.ADMIN || (creds != null && isAdmin(creds.getPriv()));
    }

    /**
     * Issues a fresh refresh+access pair for already-verified credentials. Null when the feature is off or
     * ADMIN scope is requested by credentials that do not hold the admin role -- scope is granted, never
     * escalated (the resource pre-checks via {@link #mayGrant} to answer 403; the check here is the
     * belt-and-braces layer).
     */
    public Grant issue(final Creds creds, final AuthToken.Scope requestedScope, final String label) {
        if (!enabled() || creds == null) {
            return null;
        }
        final AuthToken.Scope scope = requestedScope == null ? AuthToken.Scope.MEMBER : requestedScope;
        if (!mayGrant(creds, scope)) {
            return null;
        }
        final long now = Instant.now().getEpochSecond();
        final SelectorTokens.Minted refreshMinted = SelectorTokens.mint();
        final AuthToken refresh = AuthToken.builder()
                .selector(refreshMinted.selector())
                .validatorHash(refreshMinted.validatorHash())
                .userId(creds.getUserId())
                .email(creds.getEmail())
                .created(now)
                .lastUsed(now)
                .expires(now + refreshDays(scope) * 86_400L)
                .kind(AuthToken.Kind.REFRESH)
                .role(creds.getPriv())
                .scope(scope)
                .label(label)
                .build();
        if (!Boolean.TRUE.equals(DAO.getInstance().saveAuthToken(refresh))) {
            return null;
        }
        final String access = mintAccess(refresh, now);
        if (access == null) {
            // Half a grant is worse than none: without an access token the refresh token would sit unused
            // until its own expiry, so take it back out.
            DAO.getInstance().deleteAuthToken(refresh.getSelector());
            return null;
        }
        Audit.builder(AuditAction.TOKEN_ISSUE, AuditOutcome.SUCCESS)
                .actor(creds.getEmail(), creds.getUserId() == null ? null : creds.getUserId().getValue())
                .message("API token issued (" + wireScope(scope) + (label == null ? "" : ", " + label) + ")")
                .log();
        return new Grant(access, accessSeconds(), refreshMinted.presentable(), wireScope(scope));
    }

    /**
     * Presents a refresh token and answers a fresh grant, rotating the refresh validator. Null on ANY
     * failure. Role and scope are re-stamped from a fresh credentials read: a demoted admin's ADMIN scope
     * quietly becomes MEMBER, and a deleted account's token family dies here.
     */
    public Grant refresh(final String presented) {
        final AuthToken token = presentedRefreshRow(presented);
        if (token == null) {
            return null;
        }
        final long now = Instant.now().getEpochSecond();
        final SelectorTokens.Judgment judgment =
                SelectorTokens.judge(token, SelectorTokens.parse(presented).orElseThrow().validatorHash(), now);
        if (judgment == SelectorTokens.Judgment.EXPIRED) {
            revokeFamily(token);
            return null;
        }
        if (judgment == SelectorTokens.Judgment.THEFT) {
            burnStolen(token);
            return null;
        }
        final Creds creds = DAO.getInstance().getCredsForCodeLogin(token.getEmail(), Cached.NO);
        if (creds == null) {
            revokeFamily(token);
            return null;
        }
        final AuthToken.Scope scope = token.getScope() == AuthToken.Scope.ADMIN && isAdmin(creds.getPriv())
                ? AuthToken.Scope.ADMIN : AuthToken.Scope.MEMBER;
        token.setRole(creds.getPriv());
        token.setScope(scope);
        String rotated = null;
        if (judgment == SelectorTokens.Judgment.CURRENT) {
            rotated = rotate(token, now);
        }
        // Grace path: no rotation -- the racing request that won already rotated and holds the live token.
        final String access = mintAccess(token, now);
        if (access == null) {
            return null;
        }
        Audit.builder(AuditAction.TOKEN_REFRESH, AuditOutcome.SUCCESS)
                .actor(token.getEmail(), token.getUserId() == null ? null : token.getUserId().getValue())
                .message("API token refreshed (" + wireScope(scope) + ")")
                .log();
        return new Grant(access, accessSeconds(), rotated, wireScope(scope));
    }

    /**
     * Revokes the presented refresh token and its access-token children. Idempotent and quiet: unknown or
     * malformed input is a success (there is nothing left to revoke, which is what the caller wanted), and a
     * THEFT-judged presentation burns the family with an alarm -- the attacker identified the token for us.
     */
    public void revoke(final String presented) {
        final AuthToken token = presentedRefreshRow(presented);
        if (token == null) {
            return;
        }
        final long now = Instant.now().getEpochSecond();
        final SelectorTokens.Judgment judgment =
                SelectorTokens.judge(token, SelectorTokens.parse(presented).orElseThrow().validatorHash(), now);
        if (judgment == SelectorTokens.Judgment.THEFT) {
            burnStolen(token);
            return;
        }
        revokeFamily(token);
        Audit.builder(AuditAction.TOKEN_REVOKE, AuditOutcome.SUCCESS)
                .actor(token.getEmail(), token.getUserId() == null ? null : token.getUserId().getValue())
                .message("API token revoked")
                .log();
    }

    /** The row a presented refresh token names, or null -- feature off, malformed, unknown, or wrong kind. */
    private AuthToken presentedRefreshRow(final String presented) {
        if (!enabled()) {
            return null;
        }
        final Optional<SelectorTokens.Parsed> parsed = SelectorTokens.parse(presented);
        if (parsed.isEmpty()) {
            return null;
        }
        final AuthToken token = DAO.getInstance().getAuthToken(parsed.get().selector(), Cached.NO).orElse(null);
        // An ACCESS (or cookie) selector presented as a refresh token is refused, not judged: kinds have
        // different lifetimes and rotation rules, and blurring them would let the short credential renew.
        return (token == null || token.getKind() != AuthToken.Kind.REFRESH) ? null : token;
    }

    /** Mints, saves, and answers a presentable ACCESS token tied to this refresh row; null if the save failed. */
    private String mintAccess(final AuthToken refresh, final long now) {
        final SelectorTokens.Minted minted = SelectorTokens.mint();
        final AuthToken access = AuthToken.builder()
                .selector(minted.selector())
                .validatorHash(minted.validatorHash())
                .userId(refresh.getUserId())
                .email(refresh.getEmail())
                .created(now)
                .lastUsed(now)
                .expires(now + accessSeconds())
                .kind(AuthToken.Kind.ACCESS)
                .role(refresh.getRole())
                .scope(refresh.getScope())
                .parentSelector(refresh.getSelector())
                .build();
        return Boolean.TRUE.equals(DAO.getInstance().saveAuthToken(access)) ? minted.presentable() : null;
    }

    /**
     * Rotates the refresh validator (same selector -- that is what makes theft detectable) and answers the
     * new presentable token. A failed save answers null and leaves the OLD validator valid: handing the
     * client a validator the store never accepted would strand the device, so "keep what you have" is the
     * honest answer.
     */
    private String rotate(final AuthToken token, final long now) {
        final String freshValidator = SelectorTokens.mintValidator();
        token.setPrevValidatorHash(token.getValidatorHash());
        token.setRotatedAt(now);
        token.setValidatorHash(Digests.sha256Base64(freshValidator));
        token.setLastUsed(now);
        return Boolean.TRUE.equals(DAO.getInstance().saveAuthToken(token))
                ? token.getSelector() + ":" + freshValidator : null;
    }

    /** Deletes a refresh row and every ACCESS child that points back at it. */
    private void revokeFamily(final AuthToken refresh) {
        final List<AuthToken> children = DAO.getInstance().listAuthTokensForUser(refresh.getUserId());
        for (final AuthToken child : children) {
            if (child.getKind() == AuthToken.Kind.ACCESS
                    && refresh.getSelector().equals(child.getParentSelector())) {
                DAO.getInstance().deleteAuthToken(child.getSelector());
            }
        }
        DAO.getInstance().deleteAuthToken(refresh.getSelector());
    }

    /** The theft signature, exactly remember-me's: kill the whole family loudly. */
    private void burnStolen(final AuthToken token) {
        revokeFamily(token);
        Audit.builder(AuditAction.ALARM, AuditOutcome.FAILURE)
                .actor(token.getEmail(), token.getUserId() == null ? null : token.getUserId().getValue())
                .message("token.theft: stale refresh validator presented for a live selector")
                .log();
    }

    private long accessSeconds() {
        return config.getInt(KnownSettings.API_TOKEN_ACCESS_MINUTES, 5, 240) * 60L;
    }

    private long refreshDays(final AuthToken.Scope scope) {
        return scope == AuthToken.Scope.ADMIN
                ? config.getInt(KnownSettings.API_TOKEN_REFRESH_ADMIN_DAYS, 1, 30)
                : config.getInt(KnownSettings.API_TOKEN_REFRESH_DAYS, 1, 365);
    }

    private static String wireScope(final AuthToken.Scope scope) {
        return scope.name().toLowerCase(Locale.ROOT);
    }

    private static boolean isAdmin(final String priv) {
        return priv != null && priv.contains("admin");
    }
}
