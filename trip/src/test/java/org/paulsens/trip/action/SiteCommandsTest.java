package org.paulsens.trip.action;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.V2PageBootstrap;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
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
    public void thePageKeyAndTitleFollowTheSite() throws Exception {
        DAO.getInstance();      // local mode: FakeData seeds the Acme org this org site names
        Assert.assertEquals(site.getPageKey(), V2PageBootstrap.PAGE_KEY, "unbound = the shared page");
        Assert.assertEquals(site.pageTitle("Shared title"), "Shared title");
        Assert.assertNull(site.getOrgName());

        final Organization.Id acme = Organization.Id.from(FakeData.ACME_ORG_ID);
        ScopedValue.where(RequestContext.SCOPE,
                RequestContext.of(null, null, SiteContext.org(acme, "acme", "acme.unitetrip.com"))).call(() -> {
            Assert.assertEquals(site.getPageKey(), OrgPageBootstrap.pageKey(acme),
                    "an org site renders ITS page, keyed by org id (a slug rename must not lose it)");
            Assert.assertEquals(site.getOrgName(), "Acme Inc");
            Assert.assertEquals(site.pageTitle("Shared title"), "Acme Inc", "the org name is the title");
            return null;
        });
        final Organization.Id nobody = Organization.Id.from("00000000-0000-4000-8000-000000000000");
        ScopedValue.where(RequestContext.SCOPE,
                RequestContext.of(null, null, SiteContext.org(nobody, "ghost", "ghost.unitetrip.com"))).call(() -> {
            Assert.assertEquals(site.getOrgName(), "ghost", "an unreadable org falls back to the slug");
            return null;
        });
        ScopedValue.where(RequestContext.SCOPE,
                RequestContext.of(null, null, SiteContext.marketing("unitetrip.com"))).call(() -> {
            Assert.assertEquals(site.getPageKey(), OrgPageBootstrap.MARKETING_PAGE_KEY);
            Assert.assertEquals(site.pageTitle("Shared title"), SiteCommands.MARKETING_TITLE);
            Assert.assertNull(site.getOrgName());
            return null;
        });
    }

    @Test
    public void orgSiteLinksFollowWhereTheAdminIsBrowsingFrom() {
        Assert.assertEquals(SiteCommands.orgSiteUrl("acme", "www.visitqueenofpeace.com", 443, "unitetrip.com"),
                "https://acme.unitetrip.com/");
        Assert.assertEquals(SiteCommands.orgSiteUrl("acme", null, -1, "unitetrip.com"),
                "https://acme.unitetrip.com/", "off-request: the production shape");
        Assert.assertEquals(SiteCommands.orgSiteUrl("acme", "localhost", 8080, "unitetrip.com"),
                "http://acme.localhost:8080/", "a laptop cannot resolve the base domain; *.localhost it can");
        Assert.assertEquals(SiteCommands.orgSiteUrl("acme", "cfpw.localhost", 80, "unitetrip.com"),
                "http://acme.localhost/");
        Assert.assertEquals(site.orgSiteUrl("acme"), "https://acme.unitetrip.com/", "no FacesContext here");

        final HttpServletRequest local = Mockito.mock(HttpServletRequest.class);
        Mockito.when(local.getServerName()).thenReturn("localhost");
        Mockito.when(local.getServerPort()).thenReturn(8080);
        Assert.assertEquals(SiteCommands.orgSiteUrl("acme", local), "http://acme.localhost:8080/");
        Assert.assertEquals(SiteCommands.orgSiteUrl("acme", (HttpServletRequest) null),
                "https://acme.unitetrip.com/");
    }

    @Test
    public void anUnreadableOrgFallsBackToItsSlugInsteadOfFailingThePage() {
        final SiteContext acme = SiteContext.org(Organization.Id.from(FakeData.ACME_ORG_ID), "acme",
                "acme.unitetrip.com");
        Assert.assertEquals(SiteCommands.orgNameOf(acme, id -> {
            throw new IllegalStateException("cache down");
        }), "acme", "a public page must render even when the org row cannot be read");
        Assert.assertEquals(SiteCommands.orgNameOf(acme, id -> Optional.empty()), "acme");
        Assert.assertNull(SiteCommands.orgNameOf(SiteContext.shared("localhost"), id -> Optional.empty()));
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
