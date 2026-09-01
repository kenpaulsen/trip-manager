package org.paulsens.trip.action;

import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link Caller#bound()}: the caller behind ANY request, read from the request-bound {@code RequestContext}
 * (actor + role) rather than FacesContext -- so a bean re-checking authorization inside a write answers the
 * same for a page, the REST API and a servlet.
 */
public class CallerBoundTest {

    private static <T> T bound(final RequestContext ctx, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, ctx).call(body);
    }

    @Test
    public void boundReadsTheActorAndTheRoleFromTheRequestContext() throws Exception {
        DAO.getInstance();
        final Person.Id me = Person.Id.from("bound-me");
        final AuditActor actor = new AuditActor("me@example.com", me.getValue());

        final Caller admin = bound(RequestContext.of(actor, "admin"), Caller::bound);
        Assert.assertEquals(admin.personId(), me);
        Assert.assertTrue(admin.isSiteAdmin(), "the session role 'admin' is the site-admin role");
        Assert.assertTrue(admin.has("anything"), "...and a site admin holds everything");
        Assert.assertEquals(admin.auditActor(), actor, "audit records name the same actor");

        final Caller member = bound(RequestContext.of(actor, "member"), Caller::bound);
        Assert.assertEquals(member.personId(), me);
        Assert.assertFalse(member.isSiteAdmin());
        Assert.assertFalse(member.has("mediaAdmin"), "no privilege rows: nothing held");

        final Caller roleless = bound(RequestContext.of(actor), Caller::bound);
        Assert.assertFalse(roleless.isSiteAdmin(), "no role = not an administrator");
        Assert.assertTrue(roleless.isAuthenticated());

        final Caller nobody = bound(RequestContext.of(null, null,
                SiteContext.org(Organization.Id.from("11111111-2222-3333-4444-555555555555"), "x", "x.localhost")),
                Caller::bound);
        Assert.assertNull(nobody.personId(), "an anonymous request has no caller id");
        Assert.assertFalse(nobody.isAuthenticated());
        Assert.assertFalse(nobody.hasHere("mediaAdmin"), "hasHere on an org site: still nobody");

        Assert.assertNull(Caller.bound().personId(), "unbound (scheduler, test): nobody");
        Assert.assertFalse(Caller.bound().isSiteAdmin());
    }
}
