package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.ContentTemplate;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * What an ORGANIZATION's site lists is that org's data and nothing else -- a property of the site, applied
 * by the beans the public page reads through, not an option an editor could forget. The shared site keeps
 * listing everything (its own curation is a later phase).
 */
public class SiteScopedListingsTest {

    private static final Organization.Id ACME = Organization.Id.from(FakeData.ACME_ORG_ID);
    private static final Organization.Id CFPW = Organization.Id.from(FakeData.CFPW_ORG_ID);

    private final TripCommands trips = new TripCommands();
    private final MediaCommands media = new MediaCommands();

    @BeforeClass
    public void init() {
        DAO.getInstance();
        FakeData.addFakeData();
    }

    private static <T> T onSite(final SiteContext site, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).call(body);
    }

    private static ContentInstance listing() {
        return new ContentInstance("ssl-listing", "page:x", "Trips", "pilgrimages", 1,
                new java.util.HashMap<>(java.util.Map.of("language", "English")), null, 0, 1, null, null);
    }

    @Test
    public void anOrgSiteListsOnlyItsOwnPublicTrips() throws Exception {
        final List<Trip> everywhere = trips.getPublicTripsFor(listing());
        Assert.assertTrue(everywhere.stream().anyMatch(trip -> !ACME.getValue().equals(trip.getOrgId())),
                "the shared site lists other tenants' trips (fixture precondition)");

        final List<Trip> onAcme = onSite(SiteContext.org(ACME, "acme", "acme.localhost"),
                () -> trips.getPublicTripsFor(listing()));
        Assert.assertTrue(onAcme.stream().allMatch(trip -> ACME.getValue().equals(trip.getOrgId())),
                "an org site never lists another tenant's trip: " + onAcme);
        final List<Trip> onCfpw = onSite(SiteContext.org(CFPW, "cfpw", "cfpw.localhost"),
                () -> trips.getPublicTripsFor(listing()));
        Assert.assertTrue(onCfpw.stream().allMatch(trip -> CFPW.getValue().equals(trip.getOrgId())));
        Assert.assertFalse(onCfpw.isEmpty(), "CFPW's own public trips still list on its site");
        Assert.assertEquals(onSite(SiteContext.marketing("unitetrip.com"), () -> trips.getPublicTripsFor(listing()))
                .size(), everywhere.size(), "non-org sites are unfiltered (shared-site curation is phase 2)");
    }

    @Test
    public void anOrgSiteShowsOnlyItsOwnAlbums() throws Exception {
        final List<MediaCommands.TripAlbum> everywhere = media.getHomeAlbums(3650, 1);
        Assert.assertFalse(everywhere.isEmpty(), "fixture precondition: the shared site has albums");
        final List<MediaCommands.TripAlbum> onAcme = onSite(SiteContext.org(ACME, "acme", "acme.localhost"),
                () -> media.getHomeAlbums(3650, 1));
        Assert.assertTrue(onAcme.stream().allMatch(album -> ACME.getValue().equals(album.trip().getOrgId())),
                "an org site shows no other tenant's pictures");
        final List<MediaCommands.TripAlbum> onCfpw = onSite(SiteContext.org(CFPW, "cfpw", "cfpw.localhost"),
                () -> media.getHomeAlbums(3650, 1));
        Assert.assertTrue(onCfpw.stream().allMatch(album -> CFPW.getValue().equals(album.trip().getOrgId())));
    }

    @Test
    public void templateChoicesFollowTheSite() throws Exception {
        final String shared = "ssl-shared-" + UUID.randomUUID();
        final String acmeOwned = "ssl-acme-" + UUID.randomUUID();
        Assert.assertTrue(DAO.getInstance().saveTemplate(template(shared, null), 5));
        Assert.assertTrue(DAO.getInstance().saveTemplate(template(acmeOwned, ACME.getValue()), 5));
        final ContentCommands content = new ContentCommands();
        content.setCallerSource(() -> new Caller(Person.Id.from("ssl-admin"), true,
                new AuditActor("ssl@example.com", "ssl-admin"), new PrivilegeCommands()));
        final String page = "page:ssl-" + UUID.randomUUID();

        final List<String> onShared = ids(content.getTemplateChoicesFor(page));
        Assert.assertTrue(onShared.contains(shared));
        Assert.assertFalse(onShared.contains(acmeOwned), "an org's template never reaches the shared page");

        final List<String> onAcme = onSite(SiteContext.org(ACME, "acme", "acme.localhost"),
                () -> ids(content.getTemplateChoicesFor(page)));
        Assert.assertTrue(onAcme.contains(shared), "shared templates are offered everywhere");
        Assert.assertTrue(onAcme.contains(acmeOwned), "an org site sees its own templates");

        final List<String> onCfpw = onSite(SiteContext.org(CFPW, "cfpw", "cfpw.localhost"),
                () -> ids(content.getTemplateChoicesFor(page)));
        Assert.assertFalse(onCfpw.contains(acmeOwned), "...and never another tenant's");
        Assert.assertTrue(onCfpw.contains(shared));
    }

    private static List<String> ids(final List<ContentTemplate> templates) {
        return templates.stream().map(ContentTemplate::getId).toList();
    }

    private static ContentTemplate template(final String id, final String orgId) {
        final ContentTemplate template = new ContentTemplate(id, 0, id, null, "<p>{{msg}}</p>", List.of(),
                LocalDateTime.now(), "test");
        template.setOrgId(orgId);
        return template;
    }

    @Test
    public void theSiteAdmitsOwnersOnlyWhenItIsAnOrgSite() {
        Assert.assertTrue(SiteContext.shared("localhost").admits(null));
        Assert.assertTrue(SiteContext.shared("localhost").admits("anyone"));
        Assert.assertTrue(SiteContext.marketing("unitetrip.com").admits("anyone"));
        final SiteContext acme = SiteContext.org(ACME, "acme", "acme.localhost");
        Assert.assertTrue(acme.admits(ACME.getValue()));
        Assert.assertFalse(acme.admits(CFPW.getValue()));
        Assert.assertFalse(acme.admits(null), "an org-less trip is nobody's; an org site does not show it");
        // The stricter rule for one-tenant-only things (templates): never on a non-org site.
        Assert.assertTrue(acme.isSiteOf(ACME.getValue()));
        Assert.assertFalse(acme.isSiteOf(CFPW.getValue()));
        Assert.assertFalse(SiteContext.shared("localhost").isSiteOf(ACME.getValue()));
        Assert.assertFalse(SiteContext.marketing("unitetrip.com").isSiteOf(ACME.getValue()));
        Assert.assertTrue(SiteContext.current().isShared(), "no request bound here");
        Assert.assertNotNull(DAO.getInstance().getTemplate("text-only", Cached.NO).orElse(null));
    }
}
