package org.paulsens.trip.action;

import static org.paulsens.trip.action.TenancyFixtures.orgWithAdmin;
import static org.paulsens.trip.action.TenancyFixtures.reread;
import static org.paulsens.trip.action.TestCallers.grantsNothing;
import static org.paulsens.trip.util.TestPeople.savedPerson;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.content.MarketingPageBootstrap;
import org.paulsens.trip.content.OrgPageBootstrap;
import org.paulsens.trip.content.PageVersions;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.SitePage;
import org.paulsens.trip.model.SitePage.Placement;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

/**
 * An org site's own pages and menu links: the starter pages a new site gets once, the org admins' list (add,
 * retitle, place, reorder within a menu, delete), a page's starter content and its audited deletion, and what the
 * menu and the page host read on each kind of site.
 */
public class SitePageCommandsTest {

    private final DAO dao = DAO.getInstance();

    @Test
    public void aNewSiteGetsTheStarterPagesOnce() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedWithStarters(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands pages = commandsFor(orgAdmin);

        assertEquals(names(pages.pagesOf(orgId)), List.of("about", "board", "metrics"),
                "the slug's first save seeds them");
        assertEquals(pages.pageOf(orgId, "metrics").who(), SitePage.Access.SITE_ADMINS, "the metrics dashboard");
        assertEquals(pages.pageOf(orgId, "metrics").where(), Placement.HIDDEN);
        final List<ContentInstance> metrics = content("metrics", org);
        assertTrue(metrics.get(0).getValues().get("body").contains("orgSettings.jsf?orgId=" + orgId),
                "its way back to the hub names this organization");
        assertEquals(metrics.get(1).getTemplateId(), "graph-grid", "the graphs sit on the graph grid");
        assertEquals(dao.getContentForSection(metrics.get(1).getId(), Cached.NO).size(), 9,
                "eight tiles and the focus graph they show in");
        assertEquals(pages.pageOf(orgId, "board"), new SitePage("board", "Board of Directors", null, "ABOUT",
                Map.of("es", "Junta directiva", "hr", "Upravni odbor")), "with its menu titles in each language");
        assertEquals(pages.pageOf(orgId, "about").where(), Placement.ABOUT);
        assertEquals(pages.pageOf(orgId, "about").titleIn("hr"), "Tko smo mi");
        assertNotNull(org.getSitePagesSeededAt(), "the org row records the seeding");
        assertEquals(content("about", org).size(), 1, "each starter page starts with its starter content");
        final List<ContentInstance> board = content("board", org);
        assertEquals(board.size(), 2);
        assertEquals(board.get(1).getAllowedChildTemplateIds(), List.of("person"), "a list of person cards");
        assertTrue(board.get(0).getValues().get("body").contains("Board of Directors"));
        for (final Language language : List.of(Language.Spanish, Language.Croatian)) {
            assertEquals(version("about", org, language), List.of(), "the content is English only: a seeded "
                    + "version would outrank the org's own rewrite of the English page for that language");
            assertEquals(version("board", org, language), List.of());
        }

        assertTrue(pages.deletePage(orgId, "board"), "an org admin may delete a starter page");
        assertTrue(pages.ensureStarterPages(orgId), "already seeded");
        assertEquals(names(pages.pagesOf(orgId)), List.of("about", "metrics"),
                "a deleted starter page never comes back");
        assertEquals(content("board", org), List.of());
    }

    @Test
    public void theProductsOwnSiteGetsOnlyTheStartersMeantForEverySite() {
        final Organization platform = new Organization();
        platform.setSlug(Organization.PLATFORM_SLUG);
        assertEquals(SitePageCommands.startersFor(platform).stream().map(OrgPageBootstrap.StarterPage::name)
                .toList(), List.of("metrics"), "the dashboard, never About Us or the Board");
        final Organization other = new Organization();
        other.setSlug("pages" + RandomData.genAlpha(10).toLowerCase());
        assertEquals(SitePageCommands.startersFor(other).stream().map(OrgPageBootstrap.StarterPage::name)
                .toList(), List.of("about", "board", "metrics"));
    }

    @Test
    public void aSpanishFirstSiteGetsItsStarterTitlesInSpanishAndKeepsThemThroughADialogSave() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization unhosted = orgWithAdmin(orgAdmin);
        final String orgId = unhosted.getId().getValue();
        assertTrue(new OrgCommands(TestCallers::siteAdminHoldingNothing).saveOrgSettings(orgId,
                Map.of(KnownSettings.SITE_LANGUAGES.getName(), "es,en,hr")));
        host(unhosted);
        final SitePageCommands pages = commandsFor(orgAdmin);

        final SitePage about = pages.pageOf(orgId, "about");
        assertEquals(about.title(), "Quiénes somos", "the entry's own title is in the site's default language");
        assertEquals(about.titles(), Map.of("en", "About Us", "hr", "Tko smo mi"), "English is one of the others");
        assertEquals(pages.titlesEdit(orgId, about), Map.of("en", "About Us", "hr", "Tko smo mi"),
                "every title is one the dialog shows");
        assertEquals(pages.pageOf(orgId, "board").title(), "Junta directiva");

        assertTrue(pages.saveDialog(orgId, "edit", "about", about.title(), pages.titlesEdit(orgId, about), null,
                about.placement()), "the dialog saved as it opened");
        assertEquals(pages.pageOf(orgId, "about"), about, "nothing lost or moved by the save");
    }

    @Test
    public void startersAdoptWhatTheSiteAlreadyHas() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = orgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands pages = commandsFor(orgAdmin);
        final ContentInstance authored = new ContentInstance("authored-" + RandomData.genAlpha(8).toLowerCase(),
                OrgPageBootstrap.pageKey(org.getId(), "board"), "Board", "text-only", 1,
                new HashMap<>(Map.of("body", "<p>Ours</p>")), null, 0, 0, null, "someone");
        assertTrue(dao.saveContent(authored, 5));
        assertTrue(pages.addPage(orgId, "Who We Are", "about", "HELP"), "prepared before the site exists");
        assertFalse(pages.ensureStarterPages(orgId), "no subdomain, no site to seed");
        assertNull(reread(org).getSitePagesSeededAt());

        host(org);
        assertEquals(pages.pagesOf(orgId), List.of(new SitePage("about", "Who We Are", null, "HELP"),
                new SitePage("board", "Board of Directors", null, "ABOUT",
                        Map.of("es", "Junta directiva", "hr", "Upravni odbor")),
                new SitePage("metrics", "Metrics", null, "HIDDEN", Map.of("es", "Métricas", "hr", "Metrike"),
                        "SITE_ADMINS", "HIDE")), "the org's own entry is kept as is");
        assertEquals(content("about", org).size(), 1, "its own page, its own content");
        final List<ContentInstance> board = content("board", org);
        assertEquals(board.size(), 1, "the rows already under the key are adopted, never added to");
        assertEquals(board.get(0).getValues().get("body"), "<p>Ours</p>");
    }

    @Test
    public void onlyTheOrgsAdminsSeedTheStarters() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = orgWithAdmin(orgAdmin);
        final Organization stored = reread(org);
        stored.setSlug("pages" + RandomData.genAlpha(10).toLowerCase());
        assertTrue(dao.saveOrganization(stored), "a slug written behind the commands' back, still unseeded");
        assertFalse(commandsFor(savedPerson()).ensureStarterPages(org.getId().getValue()), "an outsider");
        assertNull(reread(org).getSitePagesSeededAt());
        assertFalse(siteAdmin().ensureStarterPages(null));
        assertTrue(commandsFor(orgAdmin).ensureStarterPages(org.getId().getValue()));
        assertEquals(names(commandsFor(orgAdmin).pagesOf(org.getId().getValue())), List.of("about", "board",
                "metrics"));
    }

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

        final List<ContentInstance> rows = content("mission", org);
        assertEquals(rows.size(), 1, "a new page is never blank");
        assertEquals(rows.get(0).getValues().get("body"), SitePageCommands.STARTER_BODY);
    }

    @Test
    public void anExistingPageKeyIsAdoptedNotOverwritten() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String key = OrgPageBootstrap.pageKey(org.getId(), "team");
        final ContentInstance authored = new ContentInstance("authored-" + RandomData.genAlpha(8).toLowerCase(), key,
                "Team", "text-only", 1, new HashMap<>(Map.of("body", "<p>Ours</p>")), null, 0, 0, null, "someone");
        assertTrue(dao.saveContent(authored, 5));
        assertTrue(commandsFor(orgAdmin).addPage(org.getId().getValue(), "Team", "team", "ABOUT"));
        final List<ContentInstance> rows = dao.getContentForSection(key, Cached.NO);
        assertEquals(rows.size(), 1, "the rows already there stay as they are");
        assertEquals(rows.get(0).getValues().get("body"), "<p>Ours</p>");
    }

    @Test
    public void badInputIsRefusedAndNothingIsSaved() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands pages = commandsFor(orgAdmin);

        assertFalse(pages.addPage(orgId, "Home", "home", "ABOUT"), "home is the home page's own");
        assertFalse(pages.addPage(orgId, "Pay", "pay", "HELP"), "pay is the How to pay page's own");
        assertFalse(pages.addPage(orgId, "Pay", " PAY ", "HELP"), "however it is typed");
        assertFalse(pages.saveDialog(orgId, "page", "pay", "Pay", null, "HELP"), "through the dialog too");
        assertTrue(dao.getContentForSection(OrgPageBootstrap.payPageKey(org.getId()), Cached.NO).isEmpty(),
                "a refused page seeds nothing under the pay page's key");
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
    public void updateKeepsTheAddressAndMovesStayInTheirOwnMenu() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands pages = commandsFor(orgAdmin);
        assertTrue(pages.addPage(orgId, "A", "a", "ABOUT"));
        assertTrue(pages.addPage(orgId, "Hidden", "h", "HIDDEN"));
        assertTrue(pages.addPage(orgId, "B", "b", "ABOUT"));
        assertTrue(pages.addLink(orgId, "Link", "/pay", "HELP"));
        assertTrue(pages.addPage(orgId, "C", "c", "ABOUT"));
        final String link = pages.pagesOf(orgId).get(3).name();

        assertTrue(pages.saveDialog(orgId, "edit", "h", "Day 1", "ignored for a page", "HIDDEN"));
        assertEquals(pages.pageOf(orgId, "h"), new SitePage("h", "Day 1", null, "HIDDEN"));
        assertTrue(pages.updatePage(orgId, link, "Pay", "https://example.org/pay", "HELP"));
        assertEquals(pages.pageOf(orgId, link).url(), "https://example.org/pay");
        assertFalse(pages.updatePage(orgId, link, "Pay", "ftp://x", "HELP"), "a link's URL is still checked");

        assertTrue(pages.movePage(orgId, "b", -1));
        assertEquals(names(pages.pagesOf(orgId)), List.of("b", "h", "a", link, "c"),
                "Up passes the entry out of the menu and swaps with the About entry above");
        assertTrue(pages.movePage(orgId, "b", -1), "the top of its menu is a no-op, still a success");
        assertTrue(pages.movePage(orgId, "a", 1));
        assertEquals(names(pages.pagesOf(orgId)), List.of("b", "h", "c", link, "a"));
        assertTrue(pages.movePage(orgId, link, 1), "alone in its menu: nowhere to go");
        assertEquals(names(pages.pagesOf(orgId)), List.of("b", "h", "c", link, "a"));

        assertFalse(pages.canMove(orgId, pages.pageOf(orgId, "b"), -1));
        assertTrue(pages.canMove(orgId, pages.pageOf(orgId, "b"), 1));
        assertFalse(pages.canMove(orgId, pages.pageOf(orgId, "a"), 1));
        assertFalse(pages.canMove(orgId, pages.pageOf(orgId, link), -1));
        assertFalse(pages.canMove(orgId, null, 1));
        assertEquals(names(pages.tableOf(orgId)), List.of("b", "c", "a", link, "h"), "the table groups by menu");
    }

    @Test
    public void anOrgAdminDeletesAPageAndEveryRowIsAudited() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands pages = commandsFor(orgAdmin);
        assertFalse(pages.canEditContent(orgId), "he cannot write the page's words...");
        assertTrue(pages.addPage(orgId, "Doomed", "doomed", "ABOUT"));
        assertTrue(pages.addLink(orgId, "Gone", "https://example.org/", "HELP"));
        final String key = OrgPageBootstrap.pageKey(org.getId(), "doomed");
        final String tag = RandomData.genAlpha(10).toLowerCase();
        final String containerId = "box-" + tag;
        assertTrue(dao.saveContent(new ContentInstance(containerId, key, "Box " + tag, "container", 1,
                new HashMap<>(), null, 1, 0, null, "t"), 5));
        assertTrue(dao.saveContent(new ContentInstance("kid-" + tag, containerId, "Kid " + tag, "text-only", 1,
                new HashMap<>(), null, 0, 0, null, "t"), 5));

        final String spanish = PageVersions.keyFor(key, Language.Spanish);
        assertTrue(dao.saveContent(new ContentInstance("es-" + tag, spanish, "Es " + tag, "text-only", 1,
                new HashMap<>(), null, 0, 0, null, "t"), 5));

        // The audit trail as the console sink prints it: synchronous, and present whichever sinks this JVM built.
        final PrintStream original = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        final boolean deleted;
        try {
            deleted = pages.deletePage(orgId, "doomed");
        } finally {
            System.setOut(original);
        }
        assertTrue(deleted, "...but deleting the page deletes them");
        assertEquals(dao.getContentForSection(key, Cached.NO), List.of());
        assertEquals(dao.getContentForSection(spanish, Cached.NO), List.of(),
                "its Spanish version went too: a new page of the same name must not serve it");
        assertEquals(dao.getContentForSection(containerId, Cached.NO), List.of());
        final List<String> lines = captured.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.contains(orgAdmin.getEmail() + " | CONTENT | Deleted '"))
                .filter(line -> Character.isDigit(line.charAt(0))) // not a sink's prefixed fallback copy
                .toList();
        assertEquals(lines.size(), 4, "one CONTENT line per deleted row, the Spanish version's too: " + lines);
        assertTrue(lines.stream().anyMatch(line -> line.contains("'Introduction' from " + key)), "the starter text");
        assertTrue(lines.stream().anyMatch(line -> line.contains("'Box " + tag + "' from " + key)), "the container");
        assertTrue(lines.stream().anyMatch(line -> line.contains("'Kid " + tag + "' with its container")),
                "and the container's child");

        final String link = pages.pagesOf(orgId).get(0).name();
        assertTrue(pages.deletePage(orgId, link));
        assertEquals(pages.pagesOf(orgId), List.of());
        assertNull(reread(org).getSitePages(), "an emptied list is stored as none");
        assertFalse(pages.deletePage(orgId, link), "already gone");
    }

    @Test
    public void aTitleIsWrittenPerLanguageTheSiteOffersAndReadInTheReadersLanguage() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands pages = commandsFor(orgAdmin);
        assertEquals(pages.otherLanguages(orgId), List.of(), "a one-language site has no other titles");
        assertEquals(pages.titlesEdit(orgId, null), Map.of());
        assertTrue(new OrgCommands(TestCallers::siteAdminHoldingNothing).saveOrgSettings(orgId,
                Map.of(KnownSettings.SITE_LANGUAGES.getName(), "en,es,hr")));
        assertEquals(pages.otherLanguages(orgId), List.of(Language.Spanish, Language.Croatian));
        assertEquals(pages.titlesEdit(orgId, null), Map.of("es", "", "hr", ""), "a new entry's empty fields");

        final Map<String, String> typed = new HashMap<>(Map.of("es", " Nuestra misión ", "hr", " ", "fr", "Mission"));
        assertTrue(pages.saveDialog(orgId, "page", "mission", "Our Mission", typed, null, "ABOUT"));
        final SitePage mission = pages.pageOf(orgId, "mission");
        assertEquals(mission.titles(), Map.of("es", "Nuestra misión"),
                "trimmed, a blank one dropped, and only a language the site offers kept");
        assertEquals(pages.titlesEdit(orgId, mission), Map.of("es", "Nuestra misión", "hr", ""));

        final SiteContext site = SiteContext.org(org.getId(), org.getSlug(), org.getSlug() + ".unitetrip.com");
        assertEquals(in(site, Language.Spanish, () -> pages.titleOf(pages.currentPage("mission"))), "Nuestra misión");
        assertEquals(in(site, Language.Croatian, () -> pages.titleOf(pages.currentPage("mission"))), "Our Mission",
                "nothing written in Croatian: the page's own title");
        assertEquals(in(site, Language.Spanish, () -> pages.titleOf(pages.getAboutEntries().get(0))),
                "Nuestra misión", "the menu reads the same");
        assertNull(pages.titleOf(null));

        assertTrue(pages.saveDialog(orgId, "edit", "mission", "Mission", null, null, "HELP"));
        assertEquals(pages.pageOf(orgId, "mission").titles(), Map.of("es", "Nuestra misión"),
                "a save without titles keeps them");
        assertTrue(pages.saveDialog(orgId, "edit", "mission", "Mission", Map.of("hr", "Naša misija"), null, "HELP"));
        assertEquals(pages.pageOf(orgId, "mission").titles(), Map.of("hr", "Naša misija"), "a save with them replaces");
        assertFalse(pages.saveDialog(orgId, "edit", "mission", "Mission", Map.of("es", "x".repeat(61)), null, "HELP"),
                "a title in another language keeps the length rule");
        assertEquals(pages.pageOf(orgId, "mission").titles(), Map.of("hr", "Naša misija"), "nothing saved");
        assertTrue(pages.saveDialog(orgId, "link", null, "Give", Map.of("es", "Donar"), "https://example.org/",
                "HELP"));
        assertEquals(pages.pagesIn(orgId, "HELP").get(1).titleIn("es"), "Donar");

        // A title for a language the site does not offer yet (a starter's) survives a dialog save, so the entry
        // still reads translated once the site adds the language (review, 2026-11-24).
        assertTrue(new OrgCommands(TestCallers::siteAdminHoldingNothing).saveOrgSettings(orgId,
                Map.of(KnownSettings.SITE_LANGUAGES.getName(), "en,es")));
        assertTrue(pages.saveDialog(orgId, "edit", "mission", "Mission", Map.of("es", "Misión"), null, "HELP"));
        assertEquals(pages.pageOf(orgId, "mission").titles(), Map.of("es", "Misión", "hr", "Naša misija"),
                "the Croatian title the dialog did not show is kept");
        assertTrue(pages.saveDialog(orgId, "edit", "mission", "Mission", Map.of("es", " "), null, "HELP"));
        assertEquals(pages.pageOf(orgId, "mission").titles(), Map.of("hr", "Naša misija"),
                "an offered language's title is still cleared by the dialog");
    }

    @Test
    public void anOlderRowWithoutTitlesReadsAsBefore() throws Exception {
        final com.fasterxml.jackson.databind.ObjectMapper mapper = DAO.getInstance().getMapper();
        final SitePage old = mapper.readValue("{\"name\":\"about\",\"title\":\"About\",\"placement\":\"ABOUT\"}",
                SitePage.class);
        assertEquals(old, new SitePage("about", "About", null, "ABOUT"));
        assertEquals(old.titles(), Map.of());
        assertEquals(old.titleIn("es"), "About");
        assertEquals(old.titleIn(null), "About");
        assertFalse(mapper.writeValueAsString(old).contains("titles"), "no titles, no field: rows stay as they were");
        final SitePage written = old.withTitles(Map.of("es", "Acerca de"));
        assertEquals(mapper.readValue(mapper.writeValueAsString(written), SitePage.class), written);
        assertEquals(written.withDetails("About us", null, Placement.HELP).titles(), Map.of("es", "Acerca de"),
                "retitling keeps the other languages' titles");
    }

    private static <T> T in(final SiteContext site, final Language language,
            final ScopedValue.CallableOp<T, Exception> body) throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)
                .withLocale(language.getLocale())).call(body);
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
        assertEquals(onSite(site, () -> pages.currentPage(" day-1 ")).name(), "day-1",
                "a page out of the menu is still served, and the host gets the STORED name back");
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
        assertEquals(pages.iconOf(give), "pi pi-external-link");
        assertEquals(pages.iconOf(pages.pageOf(orgId, "mission")), "pi pi-file");
        assertEquals(pages.placementLabel(give), "Help menu");
        assertEquals(pages.placementLabel(pages.pageOf(orgId, "mission")), "About menu");
        assertEquals(pages.placementLabel(pages.pageOf(orgId, "day-1")), "Not in the menu");
    }

    /**
     * The product's own site (the platform org, slug www) serves its Editable Pages like any org site: the menu lists
     * Pricing and Why UniteTrip (seeded by FakeData exactly as the production script installs them) and the page
     * host renders them. The placeholder marketing host, which no org holds, still has none.
     */
    @Test
    public void thePlatformSiteServesItsOwnPages() throws Exception {
        final SitePageCommands pages = siteAdmin();
        final SiteContext platform = SiteContext.org(Organization.Id.from(FakeData.PLATFORM_ORG_ID),
                Organization.PLATFORM_SLUG, "unitetrip.com");
        assertTrue(platform.isMarketing(), "the platform org's site is the marketing site");
        assertEquals(names(onSite(platform, pages::getAboutEntries)),
                List.of(MarketingPageBootstrap.PRICING_PAGE, MarketingPageBootstrap.WHY_PAGE));
        assertEquals(onSite(platform, () -> pages.currentPage(MarketingPageBootstrap.PRICING_PAGE)).title(),
                "Pricing");
        // Where its ROWS live is OrgPageBootstrapTest's (thePlatformOrgsNamedPagesAreItsSitePages) and the browser
        // suite's (MarketingSitePagesPwIT) to prove: other classes here clear the shared content store mid-suite.

        final SiteContext placeholder = SiteContext.marketing("unitetrip.com");
        assertEquals(onSite(placeholder, pages::getAboutEntries), List.of());
        assertNull(onSite(placeholder, () -> pages.currentPage(MarketingPageBootstrap.PRICING_PAGE)));
    }

    /**
     * The product's own site shows Help only once something is in it (a Help entry or its donate link): one rule for
     * the menu and the Editable Pages preview. Every other org site always shows it; the placeholder host never does.
     */
    @Test
    public void thePlatformSiteShowsHelpOnlyWithSomethingInIt() throws Exception {
        final SitePageCommands pages = siteAdmin();
        final String platformId = FakeData.PLATFORM_ORG_ID;
        final SiteContext platform = SiteContext.org(Organization.Id.from(platformId), Organization.PLATFORM_SLUG,
                "unitetrip.com");
        assertFalse(pages.helpMenuShown(platformId), "nothing in Help: hidden");
        assertFalse(onSite(platform, pages::isHelpMenuShown), "and the menu agrees");
        assertFalse(onSite(SiteContext.marketing("unitetrip.com"), pages::isHelpMenuShown), "placeholder: none");
        assertTrue(onSite(SiteContext.shared("visitqueenofpeace.com"), pages::isHelpMenuShown), "shared: always");

        final Person orgAdmin = savedPerson();
        final Organization acme = hostedOrgWithAdmin(orgAdmin);
        assertTrue(pages.helpMenuShown(acme.getId().getValue()), "an org site always has Help (Make Payment)");
        // Make Payment: every org's Help but the platform org's, and the menu asks the same rule per host.
        assertTrue(pages.paymentInHelp(acme.getId().getValue()));
        assertFalse(pages.paymentInHelp(platformId), "the product's own site takes no payments");
        assertFalse(onSite(platform, pages::isPaymentInHelp));
        assertTrue(onSite(SiteContext.org(acme.getId(), acme.getSlug(), "acme.example.org"), pages::isPaymentInHelp));
        assertTrue(onSite(SiteContext.shared("visitqueenofpeace.com"), pages::isPaymentInHelp));
        assertFalse(onSite(SiteContext.marketing("unitetrip.com"), pages::isPaymentInHelp), "placeholder: none");

        assertTrue(pages.addLink(platformId, "Getting started", "https://example.org/start", "HELP"));
        final String link = pages.pagesIn(platformId, "HELP").get(0).name();
        try {
            assertTrue(pages.helpMenuShown(platformId), "a Help entry shows Help");
            assertTrue(onSite(platform, pages::isHelpMenuShown));
        } finally {
            assertTrue(pages.deletePage(platformId, link));
        }
        assertFalse(pages.helpMenuShown(platformId), "the entry gone: hidden again");

        setPlatformDonate("https://donate.example.org/");
        try {
            assertTrue(pages.helpMenuShown(platformId), "a donate link puts Donate in Help");
        } finally {
            setPlatformDonate(null);
        }
        assertFalse(pages.helpMenuShown(platformId));
    }

    private static void setPlatformDonate(final String url) throws IOException {
        TenancyFixtures.orgSetting(FakeData.PLATFORM_ORG_ID, KnownSettings.SITE_DONATE_URL, url);
    }

    @Test
    public void aLocalhostAdminPageLinksToTheLocalOrgSite() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands pages = commandsFor(orgAdmin);
        assertTrue(pages.addPage(orgId, "Mission", "mission", "ABOUT"));
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getServerName()).thenReturn("localhost");
        Mockito.when(request.getServerPort()).thenReturn(8080);
        installFaces(request);
        try {
            assertEquals(onSite(SiteContext.shared("localhost"),
                    () -> pages.viewUrl(orgId, pages.pageOf(orgId, "mission"))),
                    "http://" + org.getSlug() + ".localhost:8080/page.jsf?name=mission");
        } finally {
            TestFaces.clear();
        }
    }

    @Test
    public void anUnknownPageAnswers404() throws Exception {
        siteAdmin().answerNotFound(); // off a JSF request: nothing to answer
        final FacesContext ctx = installFaces(Mockito.mock(HttpServletRequest.class));
        try {
            siteAdmin().answerNotFound();
            Mockito.verify(ctx.getExternalContext()).responseSendError(404, null);
            Mockito.verify(ctx).responseComplete();
            Mockito.when(ctx.getResponseComplete()).thenReturn(true);
            siteAdmin().answerNotFound();
            Mockito.verify(ctx.getExternalContext(), Mockito.times(1)).responseSendError(404, null);
        } finally {
            TestFaces.clear();
        }
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
        assertFalse(siteAdmin().canEditContent(" "));
        assertNotNull(new SitePageCommands(), "the CDI constructor reads the caller only when asked");
        assertFalse(new SitePageCommands(() -> new Caller(null, false, AuditActor.system(), grantsNothing()))
                .canEditContent(orgId), "nobody signed in");
    }

    @Test
    public void aSiteHasAPageLimitAndAnUnknownOrgIsRefused() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final List<SitePage> full = new ArrayList<>();
        for (int i = 0; i < SitePageCommands.MAX_PAGES; i++) {
            full.add(new SitePage("p" + i, "Page " + i, null, "HIDDEN"));
        }
        final Organization stored = reread(org);
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
    public void placementReadsUnknownAsHidden() {
        assertEquals(Placement.from(null), Placement.HIDDEN);
        assertEquals(Placement.from("SIDEBAR"), Placement.HIDDEN);
        assertEquals(Placement.from(" HELP "), Placement.HELP);
        assertEquals(new SitePage("a", "A", " ", "ABOUT").url(), null, "a blank URL makes it a page");
    }

    /**
     * The view gate: each access level admits exactly its readers, the page host hands everyone else the refusal
     * (or the 404 for a name it does not serve), and the menu lists a page only for a reader who may open it.
     */
    @Test
    public void aPagesAccessDecidesWhoSeesItAndWhatTheMenuLists() throws Exception {
        final Person orgAdmin = savedPerson();
        final Person member = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands admin = commandsFor(orgAdmin);
        assertTrue(admin.addPage(orgId, "Open", Map.of(), "open", "ABOUT", null));
        assertTrue(admin.addPage(orgId, "Members", Map.of(), "members", "ABOUT", "SIGNED_IN"));
        assertTrue(admin.addPage(orgId, "Admins", Map.of(), "admins", "ABOUT", "ORG_ADMINS"));
        assertTrue(siteAdmin().addPage(orgId, "Metrics", Map.of(), "metrics", "ABOUT", "SITE_ADMINS"));
        final SiteContext site = SiteContext.org(org.getId(), org.getSlug(), org.getSlug() + ".unitetrip.com");

        final SitePageCommands visitor = new SitePageCommands(() -> Caller.of(null));
        final SitePageCommands reader = commandsFor(member);
        assertEquals(names(onSite(site, visitor::getAboutEntries)), List.of("open"));
        assertEquals(names(onSite(site, reader::getAboutEntries)), List.of("open", "members"));
        assertEquals(names(onSite(site, admin::getAboutEntries)), List.of("open", "members", "admins"));
        assertEquals(names(onSite(site, siteAdmin()::getAboutEntries)), List.of("open", "members", "admins",
                "metrics"));

        assertEquals(onSite(site, () -> visitor.hostFor("open")), "/WEB-INF/contentPage.xhtml");
        assertEquals(onSite(site, () -> visitor.hostFor("members")), "/WEB-INF/pageRefused.xhtml");
        assertEquals(onSite(site, () -> reader.hostFor("members")), "/WEB-INF/contentPage.xhtml");
        assertEquals(onSite(site, () -> reader.hostFor("admins")), "/WEB-INF/pageRefused.xhtml");
        assertEquals(onSite(site, () -> admin.hostFor("admins")), "/WEB-INF/contentPage.xhtml");
        assertEquals(onSite(site, () -> admin.hostFor("metrics")), "/WEB-INF/pageRefused.xhtml",
                "an org admin is not a site admin");
        assertEquals(onSite(site, () -> siteAdmin().hostFor("metrics")), "/WEB-INF/contentPage.xhtml");
        assertEquals(onSite(site, () -> visitor.hostFor("nope")), "/WEB-INF/noSuchPage.xhtml");
        assertFalse(visitor.canView(orgId, null), "no entry is nobody's to see");
        assertFalse(commandsFor(savedPerson()).canView(orgId, admin.pageOf(orgId, "admins")),
                "another org's admin is no admin here");
    }

    /**
     * A page for site admins is theirs alone: an org admin cannot make one, does not see it in the list or the
     * preview, and cannot change, move or delete it, so the gate cannot be lowered from under the site admins.
     */
    @Test
    public void onlyASiteAdminMakesOrManagesASiteAdminsPage() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands admin = commandsFor(orgAdmin);

        assertFalse(admin.addPage(orgId, "Metrics", Map.of(), "metrics", "HIDDEN", "SITE_ADMINS"));
        assertEquals(admin.pagesOf(orgId), List.of(), "the refused add saved nothing");
        assertFalse(admin.accessChoices().contains("SITE_ADMINS"));
        assertTrue(siteAdmin().accessChoices().contains("SITE_ADMINS"));
        assertTrue(siteAdmin().addPage(orgId, "Metrics", Map.of(), "metrics", "HIDDEN", "SITE_ADMINS"));
        assertTrue(admin.addPage(orgId, "Day 1", "day-1", "HIDDEN"));

        assertEquals(names(admin.tableOf(orgId)), List.of("day-1"));
        assertEquals(names(admin.pagesIn(orgId, "HIDDEN")), List.of("day-1"));
        assertEquals(names(siteAdmin().tableOf(orgId)), List.of("metrics", "day-1"));
        assertFalse(admin.updatePage(orgId, "metrics", "Open now", Map.of(), null, "HIDDEN", "EVERYONE"));
        assertFalse(admin.movePage(orgId, "metrics", 1));
        assertFalse(admin.deletePage(orgId, "metrics"));
        assertFalse(admin.updatePage(orgId, "day-1", "Day 1", Map.of(), null, "HIDDEN", "SITE_ADMINS"),
                "nor can an org admin raise a page to site admins");
        assertEquals(siteAdmin().pageOf(orgId, "metrics").who(), SitePage.Access.SITE_ADMINS);
        assertEquals(siteAdmin().pageOf(orgId, "day-1").who(), SitePage.Access.EVERYONE);

        assertTrue(siteAdmin().saveDialog(orgId, "edit", "metrics", "Metrics", Map.of(), null, "HIDDEN",
                "ORG_ADMINS"));
        assertEquals(admin.pageOf(orgId, "metrics").who(), SitePage.Access.ORG_ADMINS);
        assertTrue(admin.updatePage(orgId, "metrics", "Numbers", null, null, "HIDDEN"),
                "a save without an access keeps the page's own");
        assertEquals(admin.pageOf(orgId, "metrics").who(), SitePage.Access.ORG_ADMINS);
        assertEquals(admin.accessLabel("ORG_ADMINS"), "This organization's admins");
        assertEquals(admin.accessLabel(null), "Everyone, signed in or not");
    }

    /** Up and Down on an org admin's list pass over the site admins' page they cannot see, rather than stall on it. */
    @Test
    public void anOrgAdminsMovesSkipThePageTheyCannotSee() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands admin = commandsFor(orgAdmin);
        assertTrue(admin.addPage(orgId, "Day 1", "day-1", "HIDDEN"));
        assertTrue(siteAdmin().addPage(orgId, "Metrics", Map.of(), "metrics", "HIDDEN", "SITE_ADMINS"));
        assertTrue(admin.addPage(orgId, "Day 2", "day-2", "HIDDEN"));
        assertTrue(admin.canMove(orgId, admin.pageOf(orgId, "day-1"), 1), "day 2 is its visible neighbor");
        assertTrue(admin.movePage(orgId, "day-1", 1));
        assertEquals(names(siteAdmin().pagesOf(orgId)), List.of("day-2", "metrics", "day-1"),
                "the two pages he sees swapped; the one he does not stayed put");
        assertFalse(admin.canMove(orgId, admin.pageOf(orgId, "day-1"), 1), "nothing he can see below it");
        assertTrue(siteAdmin().canMove(orgId, siteAdmin().pageOf(orgId, "day-1"), -1));
    }

    /**
     * The sidebar rule: a page's own Show or Hide wins over the organization's on or off, a page with no choice
     * follows the organization, and an organization that forces it off hides it everywhere (there is no forced on).
     */
    @Test
    public void aPageChoosesItsSidebarUnlessTheOrganizationForcesItOff() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization org = hostedOrgWithAdmin(orgAdmin);
        final String orgId = org.getId().getValue();
        final SitePageCommands admin = commandsFor(orgAdmin);
        assertTrue(admin.addPage(orgId, "Plain", "plain", "HIDDEN"));
        assertTrue(admin.saveDialog(orgId, "page", "wide", "Wide", Map.of(), null, "HIDDEN", null, "HIDE"));
        assertTrue(admin.saveDialog(orgId, "page", "side", "Side", Map.of(), null, "HIDDEN", null, "SHOW"));
        final SiteContext site = SiteContext.org(org.getId(), org.getSlug(), org.getSlug() + ".unitetrip.com");

        assertTrue(onSite(site, () -> admin.sidebarFor("plain")), "the default is on");
        assertFalse(onSite(site, () -> admin.sidebarFor("wide")));
        assertTrue(onSite(site, () -> admin.sidebarFor("side")));
        assertTrue(onSite(site, () -> admin.sidebarFor("nope")), "not an Editable Page: the template's own default");
        assertEquals(admin.sidebarLabel(orgId, "DEFAULT"), "The organization's default, shown now");

        setSidebar(orgId, KnownSettings.SIDEBAR_OFF);
        assertFalse(onSite(site, () -> admin.sidebarFor("plain")), "off is the new default");
        assertTrue(onSite(site, () -> admin.sidebarFor("side")), "a page's own Show still wins");
        assertFalse(admin.sidebarForcedOff(orgId));
        assertEquals(admin.sidebarLabel(orgId, null), "The organization's default, hidden now");

        setSidebar(orgId, KnownSettings.SIDEBAR_FORCED_OFF);
        assertFalse(onSite(site, () -> admin.sidebarFor("side")), "forced off overrides a page's Show");
        assertTrue(admin.sidebarForcedOff(orgId));

        assertTrue(admin.updatePage(orgId, "side", "Side", null, null, "HIDDEN"), "a save without a choice");
        assertEquals(admin.pageOf(orgId, "side").side(), SitePage.Sidebar.SHOW, "keeps the page's own");
        assertTrue(admin.saveDialog(orgId, "edit", "side", "Side", Map.of(), null, "HIDDEN", null, "DEFAULT"));
        assertNull(admin.pageOf(orgId, "side").sidebar(), "the default is stored as nothing, as before the choice");
        assertEquals(admin.sidebarChoices(), List.of("DEFAULT", "SHOW", "HIDE"));
        assertEquals(admin.sidebarLabel(orgId, "HIDE"), "Hide the sidebar");
        assertEquals(admin.sidebarLabel(orgId, "SHOW"), "Show the sidebar");
        assertEquals(SitePage.Sidebar.from("SIDEWAYS"), SitePage.Sidebar.DEFAULT, "unknown follows the default");
        assertEquals(admin.pageOf(orgId, "wide").withTitles(Map.of()).side(), SitePage.Sidebar.HIDE,
                "every copy keeps the choice");
    }

    private static void setSidebar(final String orgId, final String rule) {
        assertTrue(new OrgCommands(TestCallers::siteAdminHoldingNothing).saveOrgSettings(orgId,
                Map.of(KnownSettings.SITE_PAGES_SIDEBAR.getName(), rule)));
    }

    @Test
    public void theRefusalSendsAVisitorToSignInAndAReaderHome() {
        assertTrue(new SitePageCommands(() -> Caller.of(null)).refusedUrl().startsWith("/account/login.jsf?to="));
        assertTrue(commandsFor(savedPerson()).refusedUrl().startsWith("/?error="));
    }

    @Test
    public void accessReadsBlankAsEveryoneAndUnknownAsSiteAdmins() throws Exception {
        assertEquals(SitePage.Access.from(null), SitePage.Access.EVERYONE);
        assertEquals(SitePage.Access.from(" "), SitePage.Access.EVERYONE);
        assertEquals(SitePage.Access.from(" ORG_ADMINS "), SitePage.Access.ORG_ADMINS);
        assertEquals(SitePage.Access.from("STAFF"), SitePage.Access.SITE_ADMINS, "an unknown gate fails closed");
        final SitePage open = new SitePage("a", "A", null, "ABOUT", Map.of(), "EVERYONE");
        assertNull(open.access(), "an open page stores no access, exactly as before the gate");
        final ObjectMapper json = new ObjectMapper();
        assertFalse(json.writeValueAsString(open).contains("access"));
        final SitePage gated = open.withAccess(SitePage.Access.SIGNED_IN);
        assertEquals(json.readValue(json.writeValueAsString(gated), SitePage.class), gated);
        assertEquals(json.readValue("{\"name\":\"a\",\"title\":\"A\",\"placement\":\"ABOUT\"}",
                SitePage.class).who(), SitePage.Access.EVERYONE, "an older row reads as open");
        assertEquals(gated.withDetails("B", null, Placement.HELP).who(), SitePage.Access.SIGNED_IN);
        assertEquals(gated.withTitles(Map.of("es", "B")).who(), SitePage.Access.SIGNED_IN);
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> names(final List<SitePage> pages) {
        return pages.stream().map(SitePage::name).toList();
    }

    private List<ContentInstance> content(final String page, final Organization org) {
        return dao.getContentForSection(OrgPageBootstrap.pageKey(org.getId(), page), Cached.NO);
    }

    private List<ContentInstance> version(final String page, final Organization org, final Language language) {
        return dao.getContentForSection(PageVersions.keyFor(OrgPageBootstrap.pageKey(org.getId(), page), language),
                Cached.NO);
    }

    private static <T> T onSite(final SiteContext site, final ScopedValue.CallableOp<T, Exception> body)
            throws Exception {
        return ScopedValue.where(RequestContext.SCOPE, RequestContext.of(null, null, site)).call(body);
    }

    /** Gives {@code org} a subdomain the way a site admin does, which seeds its starter pages. */
    private Organization host(final Organization org) {
        final String slug = "pages" + RandomData.genAlpha(10).toLowerCase();
        assertTrue(new OrgCommands(TestCallers::siteAdminHoldingNothing).saveOrgEdits(org.getId().getValue(),
                org.getName(), null, null, null, null, slug));
        return reread(org);
    }

    private Organization hostedWithStarters(final Person orgAdmin) {
        return host(orgWithAdmin(orgAdmin));
    }

    /** A hosted org whose starter pages are already gone, so a test starts from an empty list. */
    private Organization hostedOrgWithAdmin(final Person orgAdmin) throws IOException {
        final Organization stored = hostedWithStarters(orgAdmin);
        stored.setSitePages(null);
        assertTrue(dao.saveOrganization(stored));
        return reread(stored);
    }

    private static SitePageCommands commandsFor(final Person person) {
        return new SitePageCommands(() -> TestCallers.holdingNothing(person));
    }

    private static SitePageCommands siteAdmin() {
        return new SitePageCommands(TestCallers::siteAdminHoldingNothing);
    }

    private static FacesContext installFaces(final HttpServletRequest request) {
        final ExternalContext external = Mockito.mock(ExternalContext.class);
        Mockito.when(external.getRequest()).thenReturn(request);
        final FacesContext ctx = Mockito.mock(FacesContext.class);
        Mockito.when(ctx.getExternalContext()).thenReturn(external);
        return TestFaces.install(ctx);
    }
}
