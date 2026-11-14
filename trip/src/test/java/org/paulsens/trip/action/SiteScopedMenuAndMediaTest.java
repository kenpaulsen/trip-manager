package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.V2PageBootstrap;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.site.ListingScope;
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
        // for a site admin: a hosted org's content is seen on its own site. The rule takes no viewer, so the
        // member and admin cases are the ones the menu lists below prove.
        Assert.assertTrue(trips.listsInMenu(cfpwPublic, null));
        Assert.assertTrue(trips.listsInMenu(orgless, null));
        Assert.assertFalse(trips.listsInMenu(acmePublic, null), "hosted org off the shared menu");
        Assert.assertFalse(trips.listsInMenu(acmeMine, null), "...even a members-only trip");
        // The site admin's org selector narrows the shared menus to one sharing org.
        Assert.assertTrue(trips.listsInMenu(cfpwPublic, FakeData.CFPW_ORG_ID));
        Assert.assertFalse(trips.listsInMenu(cfpwPublic, FakeData.BETA_ORG_ID));
        Assert.assertFalse(trips.listsInMenu(orgless, FakeData.CFPW_ORG_ID), "a selection means that org only");
        Assert.assertFalse(trips.listsInMenu(acmePublic, FakeData.ACME_ORG_ID),
                "selecting a hosted org still shows nothing of it here");
        Assert.assertTrue(trips.listsInMenu(cfpwPublic, " "), "blank = no selection");
        Assert.assertTrue(trips.getMenuTrips(1, null, true, FakeData.CFPW_ORG_ID).stream()
                .allMatch(t -> FakeData.CFPW_ORG_ID.equals(t.getOrgId())));
        Assert.assertTrue(trips.getMenuOldTrips(null, true, 1, 100, FakeData.CFPW_ORG_ID).stream()
                .allMatch(t -> FakeData.CFPW_ORG_ID.equals(t.getOrgId())));

        // Acme's site: Acme's trips only, whoever is looking.
        onSite(ACME_SITE, () -> assertAcmeSiteListsOnlyAcme(acmePublic, acmeMine, cfpwPublic, orgless));

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
        Assert.assertTrue(trips.getMenuOldTrips(me, false, 1, 100).stream()
                .noneMatch(t -> FakeData.ACME_ORG_ID.equals(t.getOrgId())), "member: still no hosted org");
        final List<Trip> acmeMenu = onSite(ACME_SITE, () -> trips.getMenuTrips(1, null, true));
        Assert.assertTrue(acmeMenu.stream().allMatch(t -> FakeData.ACME_ORG_ID.equals(t.getOrgId())));
        final List<Trip> acmeOld = onSite(ACME_SITE, () -> trips.getMenuOldTrips(null, true, 1, 100));
        Assert.assertTrue(acmeOld.stream().allMatch(t -> FakeData.ACME_ORG_ID.equals(t.getOrgId())));
    }

    /**
     * The owner decision of 2026-11-13: a shared site lists a hosted org its page CURATES on every site-wide
     * surface (the Trips menu, the public trips behind the sidebar and countdown, the home albums, the site
     * admin's selector), and drops it again once the curation is gone. Asserted at each call site, so a revert
     * of any one of them to {@code ListingScope.shows} fails here.
     */
    @Test
    public void aCuratedHostedOrgListsOnTheSharedSitesMenusAlbumsAndSelector() throws Exception {
        final String sectionId = "ssm-curation-" + UUID.randomUUID();
        final Trip acmeOpen = Trip.builder().id("ssm-acme-" + UUID.randomUUID()).title("Acme open house")
                .startDate(LocalDateTime.now().minusDays(1)).endDate(LocalDateTime.now().plusDays(5)).build();
        acmeOpen.setOrgId(FakeData.ACME_ORG_ID);
        acmeOpen.setOpenToPublic(true);
        Assert.assertTrue(DAO.getInstance().saveTrip(acmeOpen));
        try {
            Assert.assertFalse(onSite(SHARED, () -> listsAcme(acmeOpen)), "uncurated, a hosted org stays off");
            Assert.assertTrue(DAO.getInstance().saveContent(new ContentInstance(sectionId, V2PageBootstrap.PAGE_KEY,
                    "Trips", "pilgrimages", 1, new HashMap<>(Map.of(ListingScope.INCLUDE_ORGS_PROPERTY,
                    FakeData.ACME_ORG_ID)), null, 99, 0, null, null), 1));
            onSite(SHARED, () -> assertListsCuratedAcme(acmeOpen));
            DAO.getInstance().deleteContent(sectionId);
            Assert.assertFalse(onSite(SHARED, () -> listsAcme(acmeOpen)), "the curation left with the section");
        } finally {
            DAO.getInstance().deleteContent(sectionId);
            DAO.getInstance().deleteTrip(acmeOpen);
        }
    }

    /** On Acme's site: Acme's trips list, whoever is looking; another org's and org-less trips never do. */
    private Void assertAcmeSiteListsOnlyAcme(final Trip acmePublic, final Trip acmeMine, final Trip cfpwPublic,
            final Trip orgless) {
        Assert.assertTrue(trips.listsInMenu(acmePublic, null));
        Assert.assertTrue(trips.listsInMenu(acmeMine, null));
        Assert.assertFalse(trips.listsInMenu(cfpwPublic, null), "never a CFPW trip on Acme's site");
        Assert.assertFalse(trips.listsInMenu(orgless, null), "org-less legacy trips are nobody's");
        return null;
    }

    /** Every site-wide surface of the shared site lists the curated Acme trip, and the selector only Acme. */
    private Void assertListsCuratedAcme(final Trip acmeOpen) {
        Assert.assertTrue(trips.listsInMenu(acmeOpen, null), "curated: on the menu");
        Assert.assertTrue(ids(trips.getMenuTrips(1, null, false)).contains(acmeOpen.getId()));
        Assert.assertTrue(ids(trips.getPublicTrips()).contains(acmeOpen.getId()), "sidebar, countdown");
        Assert.assertTrue(media.getHomeAlbums(3650, 0).stream()
                .anyMatch(album -> acmeOpen.getId().equals(album.trip().getId())), "home albums");
        Assert.assertTrue(switchableIds().contains(FakeData.ACME_ORG_ID), "the admin's selector");
        Assert.assertFalse(switchableIds().contains(FakeData.BETA_ORG_ID), "only the curated org");
        return null;
    }

    /** On Acme's site: Acme's items are discoverable, the site-level ones are not. */
    private static Void assertAcmeSiteDiscoversOnlyAcme(final MediaItem siteLevel, final MediaItem acmes) {
        Assert.assertTrue(MediaCommands.discoverable(acmes));
        Assert.assertFalse(MediaCommands.discoverable(siteLevel), "org site: never the site's");
        Assert.assertEquals(MediaCommands.siteOrgId(), FakeData.ACME_ORG_ID);
        return null;
    }

    /** Whether ANY of the shared site's site-wide surfaces lists Acme or this Acme trip. */
    private boolean listsAcme(final Trip acmeTrip) {
        return trips.listsInMenu(acmeTrip, null)
                || ids(trips.getMenuTrips(1, null, true)).contains(acmeTrip.getId())
                || ids(trips.getPublicTrips()).contains(acmeTrip.getId())
                || media.getHomeAlbums(3650, 0).stream().anyMatch(album -> acmeTrip.getId().equals(album.trip()
                        .getId()))
                || switchableIds().contains(FakeData.ACME_ORG_ID);
    }

    private static List<String> ids(final List<Trip> list) {
        return list.stream().map(Trip::getId).toList();
    }

    private static List<String> switchableIds() {
        return new OrgCommands(TestCallers::siteAdmin).switchableOrgs().stream().map(o -> o.getId().getValue())
                .toList();
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
        onSite(ACME_SITE, () -> assertAcmeSiteDiscoversOnlyAcme(siteLevel, acmes));
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
        Assert.assertFalse(media.getVisibleInSlot("tripChat-" + FakeData.PUB_PAST_3D_TRIP_ID, 0).isEmpty());
        Assert.assertNotNull(DAO.getInstance().getMedia("fake-acme-doc", Cached.NO).orElse(null));
    }
}
