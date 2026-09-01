package org.paulsens.trip.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.PassCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.AuthToken;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.security.BearerTokens;
import org.paulsens.trip.security.TokenService;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The end-to-end identity binding for a bearer request ({@code docs/api-tokens.md}): the SINGLETON
 * {@code TokenService} (real config, flipped on through the settings store for the test) validates the
 * token inside {@code SessionRecoveryFilter}, and the whole chain then sees a fully-known
 * {@code AuditActor} -- with no session anywhere. This is the regression test for the half-attributed-actor
 * failure the issue calls out.
 */
public class BearerRequestBindingTest {

    @Test
    public void aBearerApiRequestBindsTheTokenActorForTheWholeChain() throws Exception {
        final ConfigCommands config = new ConfigCommands();
        Assert.assertTrue(config.saveKnown(Map.of("api.token.enabled", "true"), "test"));
        try {
            final Creds creds = new PassCommands().login("user2", "user");
            final TokenService.Grant grant =
                    TokenService.getInstance().issue(creds, AuthToken.Scope.MEMBER, null);
            Assert.assertNotNull(grant, "the singleton service must see the enabled setting");

            final Map<String, Object> attributes = new HashMap<>();
            final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
            Mockito.when(request.getRequestURI()).thenReturn("/api/people/me");
            Mockito.when(request.getHeader("Authorization")).thenReturn("Bearer " + grant.accessToken());
            Mockito.when(request.getSession(false)).thenReturn(null);
            Mockito.doAnswer(call -> attributes.put(call.getArgument(0), call.getArgument(1)))
                    .when(request).setAttribute(Mockito.anyString(), Mockito.any());

            final AuditActor[] seen = new AuditActor[1];
            final FilterChain chain = (req, res) -> seen[0] = AuditActor.current();
            new SessionRecoveryFilter().doFilter(request,
                    Mockito.mock(HttpServletResponse.class), chain);

            Assert.assertNotNull(seen[0]);
            Assert.assertEquals(seen[0].email(), creds.getEmail(),
                    "a half-known actor looks like a populated record until someone reads it");
            Assert.assertEquals(seen[0].id(), creds.getUserId().getValue());
            Assert.assertNotNull(attributes.get(BearerTokens.PRINCIPAL_ATTR),
                    "the principal must ride the request for TripAuthFilter and BaseResource");
            Mockito.verify(request, Mockito.never()).getSession(true);

            TokenService.getInstance().revoke(grant.refreshToken());
        } finally {
            // Blank deletes the row, restoring the declared default (off).
            Assert.assertTrue(config.saveKnown(Map.of("api.token.enabled", ""), "test"));
        }
    }

    /** With the feature off (the default), the same request binds anonymous -- no acceptance at all. */
    @Test
    public void withTheFeatureOffABearerHeaderBindsNothing() throws Exception {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getRequestURI()).thenReturn("/api/people/me");
        Mockito.when(request.getHeader("Authorization")).thenReturn("Bearer sel:validator");
        Mockito.when(request.getSession(false)).thenReturn(null);

        final AuditActor[] seen = new AuditActor[1];
        final FilterChain chain = (req, res) -> seen[0] = AuditActor.current();
        new SessionRecoveryFilter().doFilter(request, Mockito.mock(HttpServletResponse.class), chain);

        Assert.assertFalse(seen[0].isKnown());
        Mockito.verify(request, Mockito.never()).setAttribute(Mockito.eq(BearerTokens.PRINCIPAL_ATTR),
                Mockito.any());
    }
}
