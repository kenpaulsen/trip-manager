package org.paulsens.trip.action;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import org.mockito.Mockito;
import org.paulsens.trip.util.RandomData;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.SitePage;
import org.paulsens.trip.model.SitePage.Placement;
import org.paulsens.trip.site.SiteContext;
import org.testng.annotations.Test;

/**
 * An org site's own pages and menu links: the org admins' list (add, retitle, place, reorder, delete), the
 * page's starter content and its deletion, and what the menu and the page host read on each kind of site.
 */
public class SitePageCommandsTest {

    private final DAO dao = DAO.getInstance();

    @Test
    public void anOrgAdminAddsAPageWithStarterContentAndALink() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands pages = commandsFor(orgAdmin);

        assertTrue(pages.saveDialog(orgId, "page", "Mission", "  Our Mission ", null, "ABOUT"));
        assertTrue(pages.saveDialog(orgId, "link", null, "Donate", "https://example.org/give", "HELP"));
        final List<SitePage> stored = pages.pagesOf(orgId);
        assertEquals(stored.size(), 2);
        assertEquals(stored.get(0), new SitePage("mission", "Our Mission", null, "ABOUT"), "trimmed and lowercased");
        assertTrue(stored.get(1).isLink());
        assertTrue(stored.get(1).name().startsWith("link-"), "a link gets a generated, fixed identity");
        assertEquals(pages.pageOf(orgId, "mission").title(), "Our Mission");
        assertNull(pages.pageOf(orgId, null));
        assertEquals(pages.pagesIn(orgId, "HELP"), List.of(stored.get(1)));

        final List<ContentInstance> content = dao.getContentForSection(
                OrgPageBootstrap.pageKey(org.getId(), "mission"), Cached.NO);
        assertEquals(content.size(), 1, "a new page is never blank");
        assertEquals(content.get(0).getValues().get("body"), SitePageCommands.STARTER_BODY);
    }

    @Test
    public void anExistingPageKeyIsAdoptedNotOverwritten() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String key = OrgPageBootstrap.pageKey(org.getId(), "board");
        final ContentInstance authored = new ContentInstance("authored-" + RandomData.genAlpha(8).toLowerCase(), key,
                "Board", "text-only", 1, new java.util.HashMap<>(java.util.Map.of("body", "<p>Ours</p>")), null, 0, 0,
                null, "someone");
        assertTrue(dao.saveContent(authored, 5));
        assertTrue(commandsFor(orgAdmin).addPage(org.getId().getValue(), "Board", "board", "ABOUT"));
        final List<ContentInstance> content = dao.getContentForSection(key, Cached.NO);
        assertEquals(content.size(), 1, "the migration's rows stay as they are");
        assertEquals(content.get(0).getValues().get("body"), "<p>Ours</p>");
    }

    @Test
    public void badInputIsRefusedAndNothingIsSaved() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands pages = commandsFor(orgAdmin);

        assertFalse(pages.addPage(orgId, "Home", "home", "ABOUT"), "home is the home page's own");
        assertFalse(pages.addPage(orgId, "Bad", "9lives", "ABOUT"));
        assertFalse(pages.addPage(orgId, "Bad", null, "ABOUT"));
        assertFalse(pages.addPage(orgId, " ", "blank", "ABOUT"), "a title is required");
        assertFalse(pages.addPage(orgId, "x".repeat(61), "long", "ABOUT"));
        assertFalse(pages.addLink(orgId, "Bad", "javascript:alert(1)", "HELP"));
        assertFalse(pages.addLink(orgId, "Bad", "#top", "HELP"));
        assertTrue(pages.addPage(orgId, "One", "one", "ABOUT"));
        assertFalse(pages.addPage(orgId, "Again", "one", "HELP"), "an address is unique within the site");
        assertEquals(pages.pagesOf(orgId).size(), 1);

        assertFalse(commandsFor(savedPerson()).addPage(orgId, "Nope", "nope", "ABOUT"), "an outsider");
        assertFalse(pages.updatePage(orgId, "missing", "Title", null, "ABOUT"), "an unknown page");
        assertFalse(pages.updatePage(orgId, "one", "", null, "ABOUT"));
        assertEquals(pages.pagesOf("no-such-org"), List.of());
        assertEquals(pages.pagesOf(null), List.of());
    }

    @Test
    public void updateMoveAndPlaceKeepTheAddress() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands pages = commandsFor(orgAdmin);
        assertTrue(pages.addPage(orgId, "A", "a", "ABOUT"));
        assertTrue(pages.addPage(orgId, "B", "b", "ABOUT"));
        assertTrue(pages.addLink(orgId, "C", "/pay", "HELP"));
        final String link = pages.pagesOf(orgId).get(2).name();

        assertTrue(pages.saveDialog(orgId, "edit", "a", "Alpha", "ignored for a page", "HIDDEN"));
        assertEquals(pages.pageOf(orgId, "a"), new SitePage("a", "Alpha", null, "HIDDEN"));
        assertTrue(pages.updatePage(orgId, link, "Pay", "https://example.org/pay", "ABOUT"));
        assertEquals(pages.pageOf(orgId, link).url(), "https://example.org/pay");
        assertFalse(pages.updatePage(orgId, link, "Pay", "ftp://x", "ABOUT"), "a link's URL is still checked");
        assertTrue(pages.setPlacement(orgId, "b", "HELP"));
        assertEquals(pages.pageOf(orgId, "b").where(), Placement.HELP);

        assertTrue(pages.movePage(orgId, "b", -1));
        assertEquals(names(pages.pagesOf(orgId)), List.of("b", "a", link));
        assertTrue(pages.movePage(orgId, "b", -1), "the top is a no-op, still a success");
        assertTrue(pages.movePage(orgId, link, 1), "so is the bottom");
        assertTrue(pages.movePage(orgId, "a", 1));
        assertEquals(names(pages.pagesOf(orgId)), List.of("b", link, "a"));
    }

    @Test
    public void deletingAPageDeletesItsContentAndContainersChildren() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands pages = commandsFor(orgAdmin);
        assertTrue(pages.addPage(orgId, "Doomed", "doomed", "ABOUT"));
        assertTrue(pages.addLink(orgId, "Gone", "https://example.org/", "HELP"));
        final String key = OrgPageBootstrap.pageKey(org.getId(), "doomed");
        final String containerId = "box-" + RandomData.genAlpha(8).toLowerCase();
        assertTrue(dao.saveContent(new ContentInstance(containerId, key, "Box", "container", 1,
                new java.util.HashMap<>(), null, 1, 0, null, "t"), 5));
        assertTrue(dao.saveContent(new ContentInstance("kid-" + RandomData.genAlpha(8).toLowerCase(), containerId,
                "Kid", "text-only", 1, new java.util.HashMap<>(), null, 0, 0, null, "t"), 5));

        assertTrue(pages.deletePage(orgId, "doomed"));
        assertEquals(dao.getContentForSection(key, Cached.NO), List.of());
        assertEquals(dao.getContentForSection(containerId, Cached.NO), List.of());
        final String link = pages.pagesOf(orgId).get(0).name();
        assertTrue(pages.deletePage(orgId, link));
        assertEquals(pages.pagesOf(orgId), List.of());
        assertNull(dao.getOrganization(org.getId(), Cached.NO).orElseThrow().getSitePages(),
                "an emptied list is stored as none");
        assertFalse(pages.deletePage(orgId, link), "already gone");
    }

    @Test
    public void theMenuAndThePageHostReadOnlyTheCurrentOrgSite() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands pages = commandsFor(orgAdmin);
        assertTrue(pages.addPage(orgId, "Mission", "mission", "ABOUT"));
        assertTrue(pages.addPage(orgId, "Passport", "passport", "HELP"));
        assertTrue(pages.addPage(orgId, "Day 1", "day-1", "HIDDEN"));
        assertTrue(pages.addLink(orgId, "Give", "https://example.org/", "HELP"));
        final SiteContext site = SiteContext.org(org.getId(), org.getSlug(), org.getSlug() + ".unitetrip.com");

        assertEquals(names(onSite(site, pages::getAboutEntries)), List.of("mission"));
        assertEquals(onSite(site, pages::getHelpEntries).size(), 2);
        assertEquals(onSite(site, () -> pages.currentPage(" day-1 ")).title(), "Day 1",
                "a page out of the menu is still served");
        assertNull(onSite(site, () -> pages.currentPage(pages.pagesOf(orgId).get(3).name())), "a link has no page");
        assertNull(onSite(site, () -> pages.currentPage("nope")));
        assertNull(onSite(site, () -> pages.currentPage(null)));
        assertEquals(onSite(site, () -> pages.viewUrl(orgId, pages.pageOf(orgId, "mission"))),
                "/page.jsf?name=mission", "on its own site a page is a relative link");

        final SiteContext shared = SiteContext.shared("visitqueenofpeace.com");
        assertEquals(onSite(shared, pages::getAboutEntries), List.of(), "a shared site has no org pages");
        assertNull(onSite(shared, () -> pages.currentPage("mission")));
        assertEquals(onSite(shared, () -> pages.viewUrl(orgId, pages.pageOf(orgId, "mission"))),
                "https://" + org.getSlug() + ".unitetrip.com/page.jsf?name=mission");

        final SitePage give = pages.pagesOf(orgId).get(3);
        assertEquals(pages.hrefOf(give), "https://example.org/");
        assertEquals(pages.viewUrl(orgId, give), "https://example.org/");
        assertEquals(pages.hrefOf(pages.pageOf(orgId, "mission")), "/page.jsf?name=mission");
        assertEquals(pages.placementLabel(give), "Help menu");
        assertEquals(pages.placementLabel(pages.pageOf(orgId, "mission")), "About menu");
        assertEquals(pages.placementLabel(pages.pageOf(orgId, "day-1")), "Not in the menu");
    }

    @Test
    public void anOrgWithoutASiteHasNoViewUrl() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = orgWithAdmin(orgAdmin);
        final SitePageCommands pages = commandsFor(orgAdmin);
        assertTrue(pages.addPage(org.getId().getValue(), "Prepared", "prepared", "ABOUT"));
        assertNull(pages.viewUrl(org.getId().getValue(), pages.pageOf(org.getId().getValue(), "prepared")));
    }

    @Test
    public void canEditContentFollowsTheContentPrivilege() throws Exception {
        final Person orgAdmin = savedPerson();
        final String orgId = orgWithAdmin(orgAdmin).getId().getValue();
        assertFalse(commandsFor(orgAdmin).canEditContent(orgId), "an org admin does not write pages by default");
        assertTrue(siteAdmin().canEditContent(orgId));
    }

    @Test
    public void aSiteHasAPageLimitAndAnUnknownOrgIsRefused() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final List<SitePage> full = new java.util.ArrayList<>();
        for (int i = 0; i < SitePageCommands.MAX_PAGES; i++) {
            full.add(new SitePage("p" + i, "Page " + i, null, "HIDDEN"));
        }
        final Organization stored = dao.getOrganization(org.getId(), Cached.NO).orElseThrow();
        stored.setSitePages(full);
        assertTrue(dao.saveOrganization(stored));
        assertFalse(commandsFor(orgAdmin).addPage(orgId, "One more", "one-more", "ABOUT"), "the menu is full");
        assertFalse(commandsFor(orgAdmin).addLink(orgId, "One more", "/pay", "HELP"));
        assertEquals(commandsFor(orgAdmin).pagesOf(orgId).size(), SitePageCommands.MAX_PAGES);

        final String missing = Organization.Id.newInstance().getValue();
        assertFalse(siteAdmin().addPage(missing, "Ghost", "ghost", "ABOUT"), "an unknown org is refused");
        assertFalse(siteAdmin().movePage(missing, "ghost", 1));
        assertFalse(siteAdmin().deletePage(missing, "ghost"));
    }

    @Test
    public void suggestedNamesAreValidAddresses() {
        final SitePageCommands pages = siteAdmin();
        assertEquals(pages.suggestName("Pray the Divine Mercy"), "pray-the-divine-mercy");
        assertEquals(pages.suggestName("Peregrinación   a Medjugorje!"), "peregrinacion-a-medjugorje");
        assertEquals(pages.suggestName("2026 Guide"), "page-2026-guide");
        assertEquals(pages.suggestName(""), "page");
        assertEquals(pages.suggestName(null), "page");
        assertTrue(OrgPageBootstrap.isPageName(pages.suggestName("x".repeat(80))));
        assertTrue(OrgPageBootstrap.isPageName(pages.suggestName("9" + "y".repeat(60))));
    }

    @Test
    public void placementReadsUnknownAsHidden() {
        assertEquals(Placement.from(null), Placement.HIDDEN);
        assertEquals(Placement.from("SIDEBAR"), Placement.HIDDEN);
        assertEquals(Placement.from(" HELP "), Placement.HELP);
        assertEquals(new SitePage("a", "A", " ", "ABOUT").url(), null, "a blank URL makes it a page");
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> names(final List<SitePage> pages) {
        return pages.stream().map(SitePage::name).toList();
    }

    private static <T> T onSite(final SiteContext site, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).call(body);
    }

    private Organization orgWithAdmin(final Person orgAdmin) {
        final OrgCommands admin = new OrgCommands(SitePageCommandsTest::siteAdminCaller);
        final Organization org = admin.createOrganization("Pages " + RandomData.genAlpha(10), null, null);
        assertTrue(admin.setOrgAdmin(org.getId().getValue(), orgAdmin.getId(), true));
        return dao.getOrganization(org.getId(), Cached.NO).orElseThrow();
    }

    private Organization hostedOrgWithAdmin(final Person orgAdmin) {
        final Organization org = orgWithAdmin(orgAdmin);
        final String slug = "pages" + RandomData.genAlpha(10).toLowerCase();
        assertTrue(new OrgCommands(SitePageCommandsTest::siteAdminCaller).saveOrgEdits(org.getId().getValue(),
                org.getName(), null, null, null, null, slug));
        return dao.getOrganization(org.getId(), Cached.NO).orElseThrow();
    }

    private Person savedPerson() throws IOException {
        final Person person = Person.builder().first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email("pages." + RandomData.genAlpha(12).toLowerCase() + "@example.com").build();
        assertTrue(dao.savePerson(person));
        return person;
    }

    private static SitePageCommands commandsFor(final Person person) {
        return new SitePageCommands(() -> new Caller(person.getId(), false,
                new AuditActor(person.getEmail(), person.getId().getValue()), grantsNothing()));
    }

    private static SitePageCommands siteAdmin() {
        return new SitePageCommands(SitePageCommandsTest::siteAdminCaller);
    }

    private static Caller siteAdminCaller() {
        return new Caller(Person.Id.from("admin-pages"), true, new AuditActor("admin@test", "admin"),
                grantsNothing());
    }

    private static PrivilegeCommands grantsNothing() {
        final PrivilegeCommands none = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(none.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        return none;
    }
}
