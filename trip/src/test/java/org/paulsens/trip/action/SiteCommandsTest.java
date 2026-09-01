package org.paulsens.trip.action;

import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The {@code #{site}} bean: a thin, read-only window onto the bound request's SiteContext. */
public class SiteCommandsTest {

    private final SiteCommands site = new SiteCommands();

    @Test
    public void unboundAnswersTheSharedDefaults() {
        Assert.assertTrue(site.isSharedSite());
        Assert.assertFalse(site.isOrgSite());
        Assert.assertFalse(site.isMarketingSite());
        Assert.assertNull(site.getSlug());
        Assert.assertNull(site.getOrgId());
        Assert.assertNull(site.getHost());
    }

    @Test
    public void aBoundOrgSiteAnswersItsOrgSlugAndHost() throws Exception {
        final Organization.Id orgId = Organization.Id.from("11111111-2222-3333-4444-555555555555");
        final RequestContext ctx = RequestContext.of(new AuditActor("a@x.org", "u-1"), null,
                SiteContext.org(orgId, "acme", "acme.unitetrip.com"));
        ScopedValue.where(RequestContext.SCOPE, ctx).call(() -> {
            Assert.assertTrue(site.isOrgSite());
            Assert.assertFalse(site.isSharedSite());
            Assert.assertEquals(site.getSlug(), "acme");
            Assert.assertEquals(site.getOrgId(), orgId.getValue());
            Assert.assertEquals(site.getHost(), "acme.unitetrip.com");
            return null;
        });
    }

    @Test
    public void aBoundMarketingSiteAnswersItsMode() throws Exception {
        final RequestContext ctx = RequestContext.of(null, null, SiteContext.marketing("unitetrip.com"));
        ScopedValue.where(RequestContext.SCOPE, ctx).call(() -> {
            Assert.assertTrue(site.isMarketingSite());
            Assert.assertNull(site.getOrgId());
            Assert.assertEquals(site.getHost(), "unitetrip.com");
            return null;
        });
    }

    @Test
    public void contextsWithoutAnExplicitSiteDegradeToShared() throws Exception {
        // Every factory that predates sites (system, spawns, tests) must keep working, as SHARED.
        Assert.assertTrue(RequestContext.system().site().isShared());
        Assert.assertTrue(RequestContext.of(new AuditActor("s@x.org", "u-2")).site().isShared());
        Assert.assertTrue(RequestContext.from(null).site().isShared());
        final RequestContext ctx = RequestContext.of(new AuditActor("s@x.org", "u-2"));
        ScopedValue.where(RequestContext.SCOPE, ctx).call(() -> {
            Assert.assertTrue(site.isSharedSite());
            return null;
        });
    }
}
