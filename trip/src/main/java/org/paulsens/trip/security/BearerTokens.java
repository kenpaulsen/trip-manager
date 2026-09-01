package org.paulsens.trip.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The servlet-edge glue for bearer auth ({@code docs/api-tokens.md}): pulls {@code Authorization: Bearer}
 * off an {@code /api/} request, validates it through {@link TokenService}'s cached path, and stashes the
 * resulting {@link TokenPrincipal} as a request attribute for everything downstream ({@code TripAuthFilter},
 * {@code BaseResource}).
 *
 * <p>Called from {@code SessionRecoveryFilter}, and deliberately BEFORE that filter binds the
 * {@code RequestContext} ScopedValue -- an actor bound before token resolution would audit every token
 * request as UNKNOWN, which is the half-attributed-actor failure this design exists to prevent.
 *
 * <p>An invalid or expired token resolves to null and the request proceeds anonymous: the auth filter then
 * answers the same 401 an unauthenticated session request gets, which is the client's cue to refresh.
 */
public final class BearerTokens {

    /** Where the resolved principal rides the request. Set here, read everywhere else. */
    public static final String PRINCIPAL_ATTR = "trip.tokenPrincipal";

    private static final String SCHEME = "Bearer ";

    private BearerTokens() {
    }

    /**
     * Resolves the request's bearer token, if it carries one on an API path, stashing the principal as a
     * request attribute. Null (and no attribute) for non-API paths, absent or non-Bearer Authorization
     * headers, and every invalid token -- indistinguishably.
     */
    public static TokenPrincipal resolve(final HttpServletRequest request) {
        if (request == null || request.getRequestURI() == null || !request.getRequestURI().startsWith("/api/")) {
            return null;
        }
        final String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return null;
        }
        final TokenPrincipal principal =
                TokenService.getInstance().validateAccess(header.substring(SCHEME.length()).trim());
        if (principal != null) {
            request.setAttribute(PRINCIPAL_ATTR, principal);
        }
        return principal;
    }

    /** The principal {@link #resolve} stashed on this request, or null when the caller is not a bearer. */
    public static TokenPrincipal principalOf(final HttpServletRequest request) {
        return request == null ? null : (TokenPrincipal) request.getAttribute(PRINCIPAL_ATTR);
    }
}
