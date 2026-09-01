package org.paulsens.trip.security;

import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.AuthToken;
import org.paulsens.trip.model.Person;

/**
 * The identity a validated bearer access token establishes for one request ({@code docs/api-tokens.md}):
 * everything downstream code may ask about the caller, resolved once at the servlet edge and stashed as a
 * request attribute by {@code BearerTokens}. Immutable on purpose -- it rides a ScopedValue.
 *
 * <p>{@code role} and {@code scope} are the values stamped on the token row at issuance/refresh; the refresh
 * call is their freshness checkpoint, the same staleness bound a live session's login-stamped role has.
 */
public record TokenPrincipal(Person.Id personId, String email, String role, AuthToken.Scope scope,
        String selector) {

    /** Both halves, always -- a token row stores email AND id precisely so this is never half-known. */
    public AuditActor actor() {
        return new AuditActor(email, personId == null ? null : personId.getValue());
    }

    /**
     * The scope cap, in one place: site-admin requires the admin ROLE and admin SCOPE, so a member-scoped
     * token held by an administrator behaves as that person without the admin role. Explicit privilege rows
     * still apply under member scope -- member means "no more than this user's non-admin powers", not
     * "read-only".
     */
    public boolean siteAdmin() {
        return scope == AuthToken.Scope.ADMIN && role != null && role.contains("admin");
    }
}
