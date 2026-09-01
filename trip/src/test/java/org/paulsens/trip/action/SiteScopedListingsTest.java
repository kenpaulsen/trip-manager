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
import org.paulsens.trip.site.ListingScope;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * What an ORGANIZATION's site lists is that org's data and nothing else -- a property of the site, applied
 * by the beans the public page reads through, not an option an editor could forget. A SHARED site lists a
 * hosted org's content only when both sides agree (the section's curation list and the org's own choice).
 */
public class SiteScopedListingsTest {

    private static final Organization.Id ACME = Organization.Id.from(FakeData.ACME_ORG_ID);
    private static final Organization.Id BETA = Organization.Id.from(FakeData.BETA_ORG_ID);
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

    private static ContentInstance listingCurating(final String... orgIds) {
        final ContentInstance instance = listing();
        instance.getValues().put(ListingScope.INCLUDE_ORGS_PROPERTY, String.join(",", orgIds));
        return instance;
    }

    @Test
    public void anOrgSiteListsOnlyItsOwnPublicTrips() throws Exception {
        final List<Trip> shared = trips.getPublicTripsFor(listing());
        Assert.assertTrue(shared.stream().anyMatch(trip -> CFPW.getValue().equals(trip.getOrgId())),
                "the shared site lists the shared-tier org (CFPW has no site of its own)");
        Assert.assertTrue(shared.stream().noneMatch(trip -> ACME.getValue().equals(trip.getOrgId())),
                "a hosted org is off the shared site until a site admin curates it in");

        final List<Trip> onAcme = onSite(SiteContext.org(ACME, "acme", "acme.localhost"),
                () -> trips.getPublicTripsFor(listing()));
        Assert.assertTrue(onAcme.stream().allMatch(trip -> ACME.getValue().equals(trip.getOrgId())),
                "an org site never lists another tenant's trip: " + onAcme);
        final List<Trip> onBeta = onSite(SiteContext.org(BETA, "beta", "beta.localhost"),
                () -> trips.getPublicTripsFor(listingCurating(FakeData.CFPW_ORG_ID)));
        Assert.assertTrue(onBeta.stream().allMatch(trip -> BETA.getValue().equals(trip.getOrgId())),
                "a curation list never widens an org site");
        Assert.assertEquals(onSite(SiteContext.marketing("unitetrip.com"), () -> trips.getPublicTripsFor(listing()))
                .size(), shared.size(), "non-org sites share the default rule");
    }

    @Test
    public void theSharedSiteListsAHostedOrgOnlyWhenBothSidesAgree() throws Exception {
        final List<Trip> curated = trips.getPublicTripsFor(listingCurating(FakeData.ACME_ORG_ID));
        final List<Trip> acmesOwn = onSite(SiteContext.org(ACME, "acme", "acme.localhost"),
                () -> trips.getPublicTripsFor(listing()));
        // Org-less legacy trips (no owner to gate on) keep listing everywhere on a shared site; every OWNED
        // trip must be Acme's, and all of Acme's own listing must be there.
        Assert.assertTrue(curated.stream().noneMatch(trip -> CFPW.getValue().equals(trip.getOrgId())),
                "a list is exhaustive: CFPW drops off when only Acme is picked");
        Assert.assertTrue(curated.stream().filter(trip -> trip.getOrgId() != null)
                .allMatch(trip -> ACME.getValue().equals(trip.getOrgId())), "only the picked org's trips");
        Assert.assertTrue(curated.stream().map(Trip::getId).toList()
                .containsAll(acmesOwn.stream().map(Trip::getId).toList()),
                "with Acme picked, the shared site lists what Acme's own site lists");
        // The org side of the gate: an org that opted out never appears, whatever the site picked.
        final Organization acme = DAO.getInstance().getOrganization(ACME, Cached.NO).orElseThrow();
        acme.setAllowSharedSites(Boolean.FALSE);
        Assert.assertTrue(DAO.getInstance().saveOrganization(acme));
        try {
            Assert.assertTrue(trips.getPublicTripsFor(listingCurating(FakeData.ACME_ORG_ID)).stream()
                    .noneMatch(trip -> ACME.getValue().equals(trip.getOrgId())),
                    "opted out: nothing of Acme's on the shared site even when picked");
        } finally {
            final Organization restore = DAO.getInstance().getOrganization(ACME, Cached.NO).orElseThrow();
            restore.setAllowSharedSites(null);
            Assert.assertTrue(DAO.getInstance().saveOrganization(restore));
        }
    }

    @Test
    public void anOrgSiteShowsOnlyItsOwnAlbums() throws Exception {
        final List<MediaCommands.TripAlbum> shared = media.getHomeAlbums(3650, 1);
        Assert.assertFalse(shared.isEmpty(), "fixture precondition: the shared site has albums");
        Assert.assertTrue(shared.stream().noneMatch(album -> ACME.getValue().equals(album.trip().getOrgId())),
                "a hosted org's albums are off the shared site by default");
        final List<MediaCommands.TripAlbum> onAcme = onSite(SiteContext.org(ACME, "acme", "acme.localhost"),
                () -> media.getHomeAlbums(3650, 1));
        Assert.assertTrue(onAcme.stream().allMatch(album -> ACME.getValue().equals(album.trip().getOrgId())),
                "an org site shows no other tenant's pictures");
        final List<MediaCommands.TripAlbum> onBeta = onSite(SiteContext.org(BETA, "beta", "beta.localhost"),
                () -> media.getHomeAlbums(3650, 1));
        Assert.assertTrue(onBeta.stream().allMatch(album -> BETA.getValue().equals(album.trip().getOrgId())));
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

        final List<String> onBeta = onSite(SiteContext.org(BETA, "beta", "beta.localhost"),
                () -> ids(content.getTemplateChoicesFor(page)));
        Assert.assertFalse(onBeta.contains(acmeOwned), "...and never another tenant's");
        Assert.assertTrue(onBeta.contains(shared));
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
