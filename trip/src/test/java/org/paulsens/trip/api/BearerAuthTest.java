package org.paulsens.trip.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import java.lang.reflect.Field;
import org.mockito.Mockito;
import org.paulsens.trip.model.AuthToken;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.security.BearerTokens;
import org.paulsens.trip.security.TokenPrincipal;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The dual-acceptance plumbing ({@code docs/api-tokens.md}): one filter, two credentials. A bearer principal
 * must authenticate with NO session anywhere, prefer-win every identity question on {@code BaseResource},
 * be exempt from the CSRF sentinel (a bearer token is never ambient), and honor the scope cap -- a
 * member-scoped token held by an administrator behaves as a member.
 */
public class BearerAuthTest extends ResourceTestSupport {

    private static final Person.Id WHO = Person.Id.from("bearer-1");

    private static TokenPrincipal principal(final String role, final AuthToken.Scope scope) {
        return new TokenPrincipal(WHO, "bearer@example.com", role, scope, "sel-1");
    }

    /** A minimal concrete resource, to ask BaseResource's protected questions directly. */
    private static final class Probe extends BaseResource {
        @Override
        protected String versionedType() {
            return MediaType.APPLICATION_JSON;
        }
    }

    @Test
    public void theAuthFilterAcceptsAPrincipalWithNoSessionAtAll() throws Exception {
        final HttpServletRequest bare = Mockito.mock(HttpServletRequest.class);
        Mockito.when(bare.getSession(false)).thenReturn(null);
        Mockito.when(bare.getAttribute(BearerTokens.PRINCIPAL_ATTR))
                .thenReturn(principal("user", AuthToken.Scope.MEMBER));
        final TripAuthFilter filter = new TripAuthFilter();
        final Field field = TripAuthFilter.class.getDeclaredField("request");
        field.setAccessible(true);
        field.set(filter, bare);
        final ContainerRequestContext ctx = Mockito.mock(ContainerRequestContext.class);

        filter.filter(ctx);

        Mockito.verify(ctx, Mockito.never()).abortWith(Mockito.any());
        Mockito.verify(ctx).setProperty(TripAuthFilter.PERSON_ID_PROP, WHO);
        Mockito.verify(bare).setAttribute(TripAuthFilter.PERSON_ID_PROP, WHO);
        Mockito.verify(bare, Mockito.never()).getSession(true);
    }

    @Test
    public void aBearerCallerIsExemptFromTheCsrfSentinelAndACookieCallerIsNot() {
        final Probe probe = resource(new Probe());

        signedInAs(WHO);
        Assert.assertTrue(probe.csrfMissing(null), "cookie callers keep the sentinel exactly as before");
        Assert.assertFalse(probe.csrfMissing(CSRF_OK));

        bearer(principal("user", AuthToken.Scope.MEMBER));
        Assert.assertFalse(probe.csrfMissing(null),
                "a bearer token is only sent on purpose; CSRF does not apply to it");
    }

    @Test
    public void identityQuestionsPreferThePrincipalAndTheActorIsFullyKnown() {
        final Probe probe = resource(new Probe());
        bearer(principal("user", AuthToken.Scope.MEMBER));

        Assert.assertEquals(probe.personId(), WHO);
        Assert.assertEquals(probe.actor().email(), "bearer@example.com",
                "a half-known actor is the audit failure this design forbids");
        Assert.assertEquals(probe.actor().id(), WHO.getValue());
        Assert.assertEquals(probe.caller().personId(), WHO);
        Assert.assertEquals(probe.caller().auditActor().email(), "bearer@example.com");
    }

    /** The scope cap, at both of its consumers: {@code isSiteAdmin()} and the caller's short-circuit. */
    @Test
    public void aMemberScopedAdminIsNotSiteAdminAndAnAdminScopedOneIs() {
        final Probe capped = resource(new Probe());
        bearer(principal("admin", AuthToken.Scope.MEMBER));
        Assert.assertFalse(capped.isSiteAdmin(),
                "a member-scoped token held by an administrator behaves as a member");
        Assert.assertFalse(capped.caller().isSiteAdmin());
        Assert.assertFalse(capped.caller().has("configAdmin"),
                "no admin short-circuit means the privilege row (absent here) decides");

        final Probe full = resource(new Probe());
        bearer(principal("admin", AuthToken.Scope.ADMIN));
        Assert.assertTrue(full.isSiteAdmin());
        Assert.assertTrue(full.caller().isSiteAdmin());
        Assert.assertTrue(full.caller().has("configAdmin"), "site admin short-circuits every privilege");
    }
}
