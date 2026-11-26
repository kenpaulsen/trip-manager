package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.PageVersions;
import org.paulsens.trip.content.V2PageBootstrap;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Config;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Every content page's own stylesheet (docs/content-templates.md, "A page's own stylesheet"): who owns each kind of
 * page, where the choice is stored, who may make it, and what the page's head links.
 */
public class PageStylesheetsTest {

    private DAO dao;
    private final MediaCommands media = new MediaCommands();

    @BeforeClass
    public void seed() throws IOException {
        FakeData.initFakeData();
        FakeData.addFakeData();
        dao = DAO.getInstance();
    }

    private MediaItem sheet(final String orgId, final String type) {
        final String slug = "pg-" + UUID.randomUUID() + ".css";
        final String key = (orgId == null ? "" : "org/" + orgId + "/") + "stylesheets/" + slug;
        final MediaItem item = new MediaItem(UUID.randomUUID().toString(), key, "Sheet " + slug, "test", type, 10,
                null, 0, LocalDateTime.now(), "test", null, null, orgId);
        assertTrue(dao.saveMedia(item));
        return item;
    }

    private static String orgKey(final Organization org, final String name) {
        return OrgPageBootstrap.pageKey(org.getId(), name);
    }

    @Test
    public void everyKindOfPageHasItsOwnerAndAnythingElseNone() {
        final Organization org = TenancyFixtures.org("Owners");
        final Trip trip = TenancyFixtures.tripOwnedBy(org);
        final PageStylesheets.Owner onTrip = PageStylesheets.ownerOf(OrgPageBootstrap.tripPageKey(trip.getId()));
        assertEquals(onTrip.kind(), PageStylesheets.Kind.TRIP);
        assertEquals(onTrip.libraryOrg(), org.getId().getValue());
        assertEquals(onTrip.tripId(), trip.getId());
        assertTrue(PageStylesheets.ownerOf(OrgPageBootstrap.tripPageKey(trip.getId(), "agenda")).isNone(),
                "a trip's sub-page has no look of its own");

        final PageStylesheets.Owner about = PageStylesheets.ownerOf(orgKey(org, "about"));
        assertEquals(about.kind(), PageStylesheets.Kind.ORG);
        assertEquals(about.name(), "about");
        final String spanish = PageVersions.keyFor(orgKey(org, "about"), Language.Spanish);
        assertEquals(PageStylesheets.ownerOf(spanish).key(), orgKey(org, "about"), "a language version is its page");
        final PageStylesheets.Owner marketing = PageStylesheets.ownerOf(OrgPageBootstrap.MARKETING_PAGE_KEY);
        assertEquals(marketing.kind(), PageStylesheets.Kind.ORG, "the product's home is the platform org's");
        assertEquals(marketing.orgId(), FakeData.PLATFORM_ORG_ID);
        assertEquals(marketing.name(), OrgPageBootstrap.HOME_PAGE);

        assertEquals(PageStylesheets.ownerOf(V2PageBootstrap.PAGE_KEY).kind(), PageStylesheets.Kind.SHARED);
        final PageStylesheets.Owner board = PageStylesheets.ownerOf("page:shared:board");
        assertEquals(board.kind(), PageStylesheets.Kind.SHARED);
        assertNull(board.libraryOrg(), "the shared library");

        for (final String none : new String[] {null, "", "page:shared:Not A Name", "page:unitetrip:pricing",
                UUID.randomUUID().toString(), "page:trip:not-a-uuid:home"}) {
            assertTrue(PageStylesheets.ownerOf(none).isNone(), "no owner: " + none);
        }
        final Trip orphan = Trip.builder().id(UUID.randomUUID().toString()).title("No org").build();
        try {
            assertTrue(dao.saveTrip(orphan));
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        assertTrue(PageStylesheets.ownerOf(OrgPageBootstrap.tripPageKey(orphan.getId())).isNone(),
                "a trip with no organization never falls through to the shared library");
        assertEquals(new PageStylesheets(TestCallers::siteAdmin).kindOf(V2PageBootstrap.PAGE_KEY), "SHARED");
    }

    @Test
    public void anOrgAdminLinksAndUnlinksAnOrgPagesSheetAndItsHeadFollows() {
        final Person admin = TenancyFixtures.savedPerson("pgsadmin");
        final Organization org = TenancyFixtures.orgWithAdmin(admin);
        final PageStylesheets styles = new PageStylesheets(() -> TenancyFixtures.callerFor(admin));
        final MediaItem item = sheet(org.getId().getValue(), "text/css");
        final String about = orgKey(org, "about");

        assertNull(styles.linkFor(about), "nothing linked yet");
        assertTrue(styles.mayChoose(about));
        assertTrue(styles.link(about, item.getId()));
        final Organization stored = TenancyFixtures.reread(org);
        assertEquals(stored.getPageStylesheets(), Map.of("about", media.getUrl(item)));
        assertEquals(styles.linkFor(about), media.getUrl(item) + item.getCacheBuster());
        assertEquals(styles.linkedItem(about).getId(), item.getId());
        assertTrue(styles.usedBy(item).contains("about"), styles.usedBy(item).toString());
        assertEquals(styles.usedByItem(item.getId()), styles.usedBy(item));
        assertTrue(styles.usedByItem(" ").isEmpty());

        // The home page has no Editable Pages entry; its choice is stored by its name all the same.
        assertTrue(styles.link(orgKey(org, OrgPageBootstrap.HOME_PAGE), item.getId()));
        assertTrue(styles.usedBy(item).size() >= 2);

        // The settings dialog's menu: the same choice by URL; blank clears it.
        assertTrue(styles.choose(org.getId().getValue(), "about", media.getUrl(item)), "the same URL again");
        assertTrue(styles.choose(org.getId().getValue(), "about", " "));
        assertNull(styles.linkFor(about));
        assertFalse(styles.choose(org.getId().getValue(), "about", "https://elsewhere.example/x.css"));
        assertFalse(styles.choose(org.getId().getValue(), "Not A Name", media.getUrl(item)));
        assertFalse(styles.choose(" ", "about", media.getUrl(item)));

        assertTrue(styles.unlink(orgKey(org, OrgPageBootstrap.HOME_PAGE)));
        assertNull(TenancyFixtures.reread(org).getPageStylesheets(), "an empty choice leaves the row clean");
        assertTrue(styles.unlink(about), "nothing to drop is no failure");
    }

    @Test
    public void onlyTheOwnersOwnStylesheetsAndOnlyTheOwnersAdminsMayChoose() {
        final Person admin = TenancyFixtures.savedPerson("pgsgate");
        final Organization org = TenancyFixtures.orgWithAdmin(admin);
        final Organization other = TenancyFixtures.org("Elsewhere");
        final PageStylesheets styles = new PageStylesheets(() -> TenancyFixtures.callerFor(admin));
        final String about = orgKey(org, "about");

        assertFalse(styles.link(about, sheet(other.getId().getValue(), "text/css").getId()), "another org's");
        assertFalse(styles.link(about, sheet(null, "text/css").getId()), "the shared library's");
        assertFalse(styles.link(about, sheet(org.getId().getValue(), "image/png").getId()), "not a stylesheet");
        assertFalse(styles.link(about, "no-such-item"));
        assertFalse(styles.link(about, null));
        assertFalse(styles.link("not-a-page", sheet(org.getId().getValue(), "text/css").getId()));

        final Person member = TenancyFixtures.savedPerson("pgsmember");
        TenancyFixtures.addMember(org, member);
        final PageStylesheets plain = new PageStylesheets(() -> TestCallers.holdingNothing(member));
        assertFalse(plain.mayChoose(about), "a member does not choose the org's look");
        assertFalse(plain.link(about, sheet(org.getId().getValue(), "text/css").getId()));
        assertFalse(plain.unlink(about));
        assertFalse(plain.mayChoose(V2PageBootstrap.PAGE_KEY), "the shared pages are site admins'");
        assertNull(TenancyFixtures.reread(org).getPageStylesheets());
    }

    @Test
    public void aTripsPageKeepsItsChoiceOnTheTripAndItsManagersMayMakeIt() throws IOException {
        final Organization org = TenancyFixtures.org("Trip looks");
        final Trip trip = TenancyFixtures.tripOwnedBy(org);
        final String page = OrgPageBootstrap.tripPageKey(trip.getId());
        final MediaItem item = sheet(org.getId().getValue(), "text/css");
        final Person.Id manager = TestCallers.grant(PrivilegeCommands.TRIP_MGR, trip.getId());
        final PageStylesheets styles = new PageStylesheets(() -> TestCallers.person(manager));

        assertTrue(styles.mayChoose(page));
        assertTrue(styles.link(page, item.getId()));
        final Trip stored = dao.getTrip(trip.getId(), Cached.NO).orElseThrow();
        assertEquals(stored.pageOrNull().getStylesheetUrl(), media.getUrl(item));
        assertEquals(stored.getTitle(), trip.getTitle(), "nothing else of the row changed");
        assertNull(styles.linkFor(page), "a trip page's head links through its look, not here");
        assertTrue(styles.usedBy(item).contains(trip.getTitle()));

        assertTrue(styles.unlink(page));
        assertNull(dao.getTrip(trip.getId(), Cached.NO).orElseThrow().pageOrNull().getStylesheetUrl());
        final Trip plain = TenancyFixtures.tripOwnedBy(org);
        final Trip before = dao.getTrip(plain.getId(), Cached.NO).orElseThrow();
        assertTrue(new PageStylesheets(TestCallers::siteAdmin).unlink(OrgPageBootstrap.tripPageKey(plain.getId())));
        assertEquals(dao.getTrip(plain.getId(), Cached.NO).orElseThrow(), before,
                "unlinking a trip that links nothing writes nothing");
        assertFalse(new PageStylesheets(() -> TestCallers.person(manager))
                .mayChoose(OrgPageBootstrap.tripPageKey(plain.getId())), "another trip's manager");
    }

    /**
     * A trip editor tab opened before the page's own editor linked a sheet: its Save keeps the newer link unless the
     * editor changed the stylesheet itself, and a copy that changed it saves the change.
     */
    @Test
    public void anOpenTripEditorDoesNotUndoALinkMadeFromThePage() throws IOException {
        final Organization org = TenancyFixtures.org("Draft looks");
        final Trip trip = TenancyFixtures.tripOwnedBy(org);
        final String page = OrgPageBootstrap.tripPageKey(trip.getId());
        final MediaItem item = sheet(org.getId().getValue(), "text/css");
        final Trip draft = dao.getTrip(trip.getId(), Cached.NO).orElseThrow();
        assertTrue(new TripEditDrafts().start(draft, TestCallers.SITE_ADMIN_ID) != null);

        assertTrue(new PageStylesheets(TestCallers::siteAdmin).link(page, item.getId()));
        final TripCommands trips = new TripCommands(() -> new OrgCommands(TestCallers::siteAdmin));
        draft.setTitle(draft.getTitle() + " renamed");
        assertTrue(trips.saveTrip(draft));
        final Trip stored = dao.getTrip(trip.getId(), Cached.NO).orElseThrow();
        assertEquals(stored.pageOrNull().getStylesheetUrl(), media.getUrl(item), "the page's link survived");
        assertTrue(stored.getTitle().endsWith(" renamed"), "and the editor's own change was saved");

        draft.getPage().setStylesheetUrl(null);
        assertTrue(trips.saveTrip(draft));
        assertNull(dao.getTrip(trip.getId(), Cached.NO).orElseThrow().pageOrNull().getStylesheetUrl(),
                "a stylesheet the editor changed itself is saved");
        final Trip fresh = dao.getTrip(trip.getId(), Cached.NO).orElseThrow();
        fresh.getPage().setStylesheetUrl(media.getUrl(item));
        TripCommands.keepStoredStylesheet(fresh, null);
        assertEquals(fresh.pageOrNull().getStylesheetUrl(), media.getUrl(item), "no editor copy: as it was sent");
    }

    @Test
    public void theSharedPagesTakeSiteLevelSheetsFromSiteAdminsOnly() throws IOException {
        final PageStylesheets admin = new PageStylesheets(TestCallers::siteAdmin);
        final MediaItem shared = sheet(null, "text/css");
        final String board = "page:shared:board";
        try {
            assertTrue(admin.mayChoose(V2PageBootstrap.PAGE_KEY));
            assertTrue(admin.link(V2PageBootstrap.PAGE_KEY, shared.getId()));
            assertTrue(admin.link(board, shared.getId()));
            assertEquals(admin.linkFor(V2PageBootstrap.PAGE_KEY), media.getUrl(shared) + shared.getCacheBuster());
            assertEquals(admin.sharedLinks(Cached.NO).get(board), media.getUrl(shared));
            assertEquals(admin.usedBy(shared).size(), 2, admin.usedBy(shared).toString());
            assertFalse(admin.link(board, sheet(FakeData.CFPW_ORG_ID, "text/css").getId()),
                    "an organization's sheet is not the shared site's");

            assertTrue(admin.unlink(board));
            assertNull(admin.linkFor(board));
            assertTrue(admin.unlink(V2PageBootstrap.PAGE_KEY));
            assertTrue(admin.sharedLinks(Cached.NO).isEmpty());

            // The setting is hand-editable: a value naming anything but a shared stylesheet links nothing.
            final MediaItem orgSheet = sheet(FakeData.CFPW_ORG_ID, "text/css");
            setShared("{\"page:shared:board\":\"" + media.getUrl(orgSheet) + "\"}");
            assertNull(admin.linkFor(board), "an organization's file never styles a shared page");
            setShared("not json");
            assertTrue(admin.sharedLinks(Cached.NO).isEmpty());
            assertNull(admin.linkFor(board));
        } finally {
            setShared("");
        }
    }

    /** The Media page cannot leave a page linking a missing file either: a linked stylesheet is not deleted. */
    @Test
    public void theMediaPageRefusesToDeleteASheetAPageLinks() throws IOException {
        final PageStylesheets admin = new PageStylesheets(TestCallers::siteAdmin);
        final MediaCommands library = TestCallers.mediaAsSiteAdmin();
        final MediaItem shared = sheet(null, "text/css");
        try {
            assertTrue(admin.link("page:shared:board", shared.getId()));
            assertFalse(library.delete(shared.getId(), "test"), "the shared board still links it");
            assertFalse(library.update(shared.getId(), "stylesheets/moved-" + UUID.randomUUID() + ".css", "Moved",
                    null, null, null, "test"), "nor may it move: the board would link a missing file");
            assertEquals(dao.getMedia(shared.getId(), Cached.NO).orElseThrow().getS3Key(), shared.getS3Key());
            assertTrue(dao.getMedia(shared.getId(), Cached.NO).isPresent());
            assertTrue(admin.unlink("page:shared:board"));
            assertTrue(library.delete(shared.getId(), "test"));
            assertTrue(dao.getMedia(shared.getId(), Cached.NO).isEmpty());
            final MediaItem picture = sheet(null, "image/png");
            assertTrue(library.delete(picture.getId(), "test"), "anything else deletes as before");
        } finally {
            setShared("");
        }
    }

    /** A delete's refusal names the first pages that use the sheet, then how many more. */
    @Test
    public void theRefusalNamesAFewPagesAndCountsTheRest() {
        final Organization org = TenancyFixtures.org("Many looks");
        final PageStylesheets styles = new PageStylesheets(TestCallers::siteAdmin);
        final MediaItem item = sheet(org.getId().getValue(), "text/css");
        assertNull(styles.usersInWords(item), "nothing links it yet");
        assertNull(styles.usersInWords(sheet(org.getId().getValue(), "image/png")), "a picture is no stylesheet");
        for (final String name : new String[] {"one", "two", "three", "four", "five"}) {
            assertTrue(styles.link(orgKey(org, name), item.getId()));
        }
        assertTrue(styles.usersInWords(item).endsWith(" and 2 more"), styles.usersInWords(item));
    }

    /** A deleted Editable Page takes its stylesheet choice with it: a new page of the same name starts plain. */
    @Test
    public void deletingAnEditablePageDropsItsChoice() {
        final Person admin = TenancyFixtures.savedPerson("pgsdelete");
        final Organization org = TenancyFixtures.orgWithAdmin(admin);
        final String orgId = org.getId().getValue();
        final SitePageCommands pages = new SitePageCommands(() -> TenancyFixtures.callerFor(admin));
        final PageStylesheets styles = new PageStylesheets(() -> TenancyFixtures.callerFor(admin));
        final MediaItem item = sheet(orgId, "text/css");
        assertTrue(pages.addPage(orgId, "Team", "team", "ABOUT"));
        assertTrue(styles.link(orgKey(org, "team"), item.getId()));
        assertTrue(styles.link(orgKey(org, OrgPageBootstrap.HOME_PAGE), item.getId()));
        assertTrue(styles.usedBy(item).contains("Team"), styles.usedBy(item).toString());

        assertTrue(pages.deletePage(orgId, "team"));
        assertEquals(TenancyFixtures.reread(org).getPageStylesheets(), Map.of("home", media.getUrl(item)),
                "the other pages keep theirs");
        assertTrue(pages.addPage(orgId, "Team", "team", "ABOUT"));
        assertNull(styles.linkFor(orgKey(org, "team")));
    }

    /**
     * The Editable Pages settings dialog: its menu lists the org's sheets (and a current value the library no longer
     * lists, so the menu never posts back another), it shows what the page links, and its Save makes the choice for
     * a page and leaves a menu link alone.
     */
    @Test
    public void theSettingsDialogsMenuShowsAndMakesThePagesChoice() {
        final Person admin = TenancyFixtures.savedPerson("pgsmenu");
        final Organization org = TenancyFixtures.orgWithAdmin(admin);
        final String orgId = org.getId().getValue();
        final PageStylesheets styles = new PageStylesheets(() -> TenancyFixtures.callerFor(admin));
        final MediaItem item = sheet(orgId, "text/css");
        final String url = media.getUrl(item);

        assertTrue(styles.menu(orgId, "").stream().anyMatch(it -> url.equals(it.getValue())));
        final String gone = "/trip-stylesheets/org/" + orgId + "/stylesheets/long-gone.css";
        assertTrue(styles.menu(orgId, gone).stream().anyMatch(it -> gone.equals(it.getValue())),
                "a value the library no longer lists stays in the menu");
        assertTrue(styles.menu(" ", url).isEmpty());

        assertEquals(styles.storedFor(orgId, "about"), "");
        assertTrue(styles.chooseAfterSave(orgId, "about", false, url, ""));
        assertEquals(styles.storedFor(orgId, "about"), url);
        assertTrue(styles.chooseAfterSave(orgId, "about", false, url, url), "unchanged: nothing to write");
        assertTrue(styles.chooseAfterSave(orgId, "a-link", true, url, ""), "a menu link has no look");
        assertEquals(styles.storedFor(orgId, "a-link"), "");
        assertTrue(styles.chooseAfterSave(orgId, "about", false, null, url));
        assertEquals(styles.storedFor(orgId, "about"), "");
        // An address typed in capitals is the page's stored, lower-case one.
        assertTrue(styles.chooseAfterSave(orgId, " About ", false, url, ""));
        assertEquals(styles.storedFor(orgId, "about"), url);
        assertFalse(styles.choose(orgId, "Not A Name", url), "an address no page can have is refused, out loud");
        assertTrue(styles.chooseAfterSave(orgId, "about", false, "", url));
        // A dialog opened before the page's own editor linked a sheet: its title-only save leaves the link alone.
        assertTrue(styles.link(orgKey(org, "about"), item.getId()));
        assertTrue(styles.chooseAfterSave(orgId, "about", false, "", ""));
        assertEquals(styles.storedFor(orgId, "about"), url, "the newer link stays");
        assertEquals(styles.storedFor(null, "about"), "");
        assertEquals(styles.storedFor(orgId, "Not A Name"), "");
        assertNull(styles.linkedItem("not-a-page"));
        assertEquals(styles.libraryOrgOf(orgKey(org, "about")), orgId);

        // An imported sheet outside the stylesheets folder (the conference import's) is offered too.
        final MediaItem imported = new MediaItem(UUID.randomUUID().toString(),
                "org/" + orgId + "/conferences/imported-" + UUID.randomUUID() + ".css", "Imported", "test",
                "text/css", 10, null, 0, LocalDateTime.now(), "test", null, null, orgId);
        assertTrue(dao.saveMedia(imported));
        assertTrue(styles.menu(orgId, "").stream().anyMatch(it -> it.getLabel().startsWith("Imported")));
        // A page no Editable Pages entry names (a page deleted by hand) is named by its address.
        assertTrue(styles.link(orgKey(org, "ghost-page"), item.getId()));
        assertTrue(styles.usedBy(item).contains("ghost-page"), styles.usedBy(item).toString());
        assertTrue(styles.usedBy(sheet(orgId, "image/png")).isEmpty(), "a picture is no page's stylesheet");
        assertEquals(new PageStylesheets().kindOf(orgKey(org, "about")), "ORG");
    }

    @Test
    public void theHeadNeverThrowsAndNeverLinksAnUnknownName() {
        final PageStylesheets styles = new PageStylesheets(TestCallers::siteAdmin);
        assertNull(styles.linkForSitePage("Bad Name"));
        assertNull(styles.linkForSitePage(null));
        assertNull(styles.linkForSitePage("never-made"), "no request: the shared site, and no choice stored");
        assertNull(styles.libraryOrgOf(V2PageBootstrap.PAGE_KEY));
    }

    private static void setShared(final String value) {
        assertTrue(new ConfigCommands().save(new Config(KnownSettings.SITE_PAGE_STYLESHEETS.getName(), value,
                Config.Type.STRING, null, null, null), "test"));
    }
}
