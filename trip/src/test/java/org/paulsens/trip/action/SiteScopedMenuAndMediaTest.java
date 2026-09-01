package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/** The Trips menu and the media library follow the site boundary the same way the public sections do. */
public class SiteScopedMenuAndMediaTest {

    private static final Organization.Id ACME = Organization.Id.from(FakeData.ACME_ORG_ID);
    private static final SiteContext ACME_SITE = SiteContext.org(ACME, "acme", "acme.localhost");
    private static final SiteContext SHARED = SiteContext.shared("localhost");

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

    private static Trip trip(final String orgId, final boolean open, final Person.Id member) {
        final Trip trip = Trip.builder().id("menu-" + orgId + "-" + open).title("t").build();
        trip.setOrgId(orgId);
        trip.setOpenToPublic(open);
        trip.setPeople(member == null ? new ArrayList<>() : new ArrayList<>(List.of(member)));
        return trip;
    }

    @Test
    public void theMenuRuleIsOwnOnlyOnAnOrgSiteAndTheDoubleGatePlusMembershipOnAShared() throws Exception {
        final Person.Id me = Person.Id.from("menu-me");
        final Trip acmePublic = trip(FakeData.ACME_ORG_ID, true, null);
        final Trip acmeMine = trip(FakeData.ACME_ORG_ID, false, me);
        final Trip cfpwPublic = trip(FakeData.CFPW_ORG_ID, true, null);
        final Trip orgless = trip(null, true, null);

        // Shared site: CFPW (no site of its own) lists; hosted Acme NEVER does -- not for its members, not
        // for a site admin: a hosted org's content is seen on its own site.
        Assert.assertTrue(trips.listsInMenu(cfpwPublic, null, false));
        Assert.assertTrue(trips.listsInMenu(orgless, null, false));
        Assert.assertFalse(trips.listsInMenu(acmePublic, null, false), "hosted org off the shared menu");
        Assert.assertFalse(trips.listsInMenu(acmeMine, me, false), "...even for a member of the trip");
        Assert.assertFalse(trips.listsInMenu(acmePublic, null, true), "...even for a site admin");
        // The site admin's org selector narrows the shared menus to one sharing org.
        Assert.assertTrue(trips.listsInMenu(cfpwPublic, null, true, FakeData.CFPW_ORG_ID));
        Assert.assertFalse(trips.listsInMenu(cfpwPublic, null, true, FakeData.BETA_ORG_ID));
        Assert.assertFalse(trips.listsInMenu(orgless, null, true, FakeData.CFPW_ORG_ID),
                "a selection means that org only");
        Assert.assertFalse(trips.listsInMenu(acmePublic, null, true, FakeData.ACME_ORG_ID),
                "selecting a hosted org still shows nothing of it here");
        Assert.assertTrue(trips.listsInMenu(cfpwPublic, null, false, " "), "blank = no selection");
        Assert.assertTrue(trips.getMenuTrips(1, null, true, FakeData.CFPW_ORG_ID).stream()
                .allMatch(t -> FakeData.CFPW_ORG_ID.equals(t.getOrgId())));
        Assert.assertTrue(trips.getMenuOldTrips(null, true, 1, 100, FakeData.CFPW_ORG_ID).stream()
                .allMatch(t -> FakeData.CFPW_ORG_ID.equals(t.getOrgId())));

        // Acme's site: Acme's trips only, whoever is looking.
        onSite(ACME_SITE, () -> {
            Assert.assertTrue(trips.listsInMenu(acmePublic, null, false));
            Assert.assertTrue(trips.listsInMenu(acmeMine, null, false));
            Assert.assertFalse(trips.listsInMenu(cfpwPublic, me, true), "not even a site admin on a CFPW trip");
            Assert.assertFalse(trips.listsInMenu(orgless, null, true), "org-less legacy trips are nobody's");
            return null;
        });

        final List<Trip> sharedMenu = trips.getMenuTrips(1, null, false);
        Assert.assertTrue(sharedMenu.stream().noneMatch(t -> FakeData.ACME_ORG_ID.equals(t.getOrgId())));
        Assert.assertTrue(trips.getMenuTrips(1, null, true).stream()
                .noneMatch(t -> FakeData.ACME_ORG_ID.equals(t.getOrgId())), "admin: still no hosted org");

        // The selector offers only the orgs that share the site: CFPW, never hosted Acme or Beta.
        final OrgCommands asAdmin = new OrgCommands(TestCallers::siteAdmin);
        final List<String> switchable = asAdmin.switchableOrgs().stream().map(o -> o.getId().getValue()).toList();
        Assert.assertTrue(switchable.contains(FakeData.CFPW_ORG_ID));
        Assert.assertFalse(switchable.contains(FakeData.ACME_ORG_ID));
        Assert.assertFalse(switchable.contains(FakeData.BETA_ORG_ID));
        Assert.assertTrue(new OrgCommands(() -> TestCallers.person(me)).switchableOrgs().isEmpty(),
                "the selector is a site admin's");
        final List<Trip> acmeMenu = onSite(ACME_SITE, () -> trips.getMenuTrips(1, null, true));
        Assert.assertTrue(acmeMenu.stream().allMatch(t -> FakeData.ACME_ORG_ID.equals(t.getOrgId())));
        final List<Trip> acmeOld = onSite(ACME_SITE, () -> trips.getMenuOldTrips(null, true, 1, 100));
        Assert.assertTrue(acmeOld.stream().allMatch(t -> FakeData.ACME_ORG_ID.equals(t.getOrgId())));
    }

    @Test
    public void theCountdownFollowsTheSite() throws Exception {
        Assert.assertTrue(trips.getCountdownTrips(3650).stream().allMatch(Trip::isCfpw),
                "the shared site's countdown is a CFPW notion");
        final List<Trip> acme = onSite(ACME_SITE, () -> trips.getCountdownTrips(3650));
        Assert.assertTrue(acme.stream().allMatch(t -> FakeData.ACME_ORG_ID.equals(t.getOrgId())),
                "an org site counts down to its own trips");
    }

    @Test
    public void mediaIsDiscoverableOnlyOnTheSiteThatOwnsIt() throws Exception {
        final MediaItem siteLevel = new MediaItem("sm-site", "downloads/site.pdf", "Site", null, "application/pdf",
                1L, "home-docs", 0, LocalDateTime.now(), "t", null, null, null);
        final MediaItem acmes = siteLevel.withOrg(FakeData.ACME_ORG_ID);
        Assert.assertTrue(MediaCommands.discoverable(siteLevel), "shared site: site-level items");
        Assert.assertFalse(MediaCommands.discoverable(acmes), "shared site: never a tenant's");
        Assert.assertNull(MediaCommands.siteOrgId());
        onSite(ACME_SITE, () -> {
            Assert.assertTrue(MediaCommands.discoverable(acmes));
            Assert.assertFalse(MediaCommands.discoverable(siteLevel), "org site: never the site's");
            Assert.assertEquals(MediaCommands.siteOrgId(), FakeData.ACME_ORG_ID);
            return null;
        });
        Assert.assertEquals(acmes.withHidden(true).getOrgId(), FakeData.ACME_ORG_ID, "copies keep the owner");
        Assert.assertNull(new MediaItem("x", "k", "t", null, "c", 1L, null, 0, null, "u", null).getOrgId());

        // The seeded Acme document: on the shared site's Documents slot, library and picker it is absent.
        Assert.assertTrue(media.getVisibleInSlot("home-docs", 0).stream().noneMatch(MediaItem::isOrgOwned));
        Assert.assertTrue(media.getCurated().stream().noneMatch(MediaItem::isOrgOwned));
        Assert.assertTrue(media.getSelectableForSlot("x").stream().noneMatch(MediaItem::isOrgOwned));
        final List<MediaItem> acmeDocs = onSite(ACME_SITE, () -> media.getVisibleInSlot("home-docs", 0));
        Assert.assertTrue(acmeDocs.stream().allMatch(item -> FakeData.ACME_ORG_ID.equals(item.getOrgId())));
        Assert.assertFalse(acmeDocs.isEmpty(), "Acme's own brochure lists on Acme's site");
        // A chat slot is scoped by its trip, not per item: old null-org photos still show in their album.
        Assert.assertFalse(media.getVisibleInSlot("tripChat-pub-past-3d", 0).isEmpty());
        Assert.assertNotNull(DAO.getInstance().getMedia("fake-acme-doc", Cached.NO).orElse(null));
    }
}
