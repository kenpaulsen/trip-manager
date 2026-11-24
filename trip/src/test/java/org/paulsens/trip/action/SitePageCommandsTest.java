package org.paulsens.trip.action;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

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

        assertEquals(names(pages.pagesOf(orgId)), List.of("about", "board"), "the slug's first save seeds them");
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
        assertEquals(names(pages.pagesOf(orgId)), List.of("about"), "a deleted starter page never comes back");
        assertEquals(content("board", org), List.of());
    }

    @Test
    public void aSpanishFirstSiteGetsItsStarterTitlesInSpanishAndKeepsThemThroughADialogSave() throws Exception {
        final Person orgAdmin = savedPerson();
        final Organization unhosted = orgWithAdmin(orgAdmin);
        final String orgId = unhosted.getId().getValue();
        assertTrue(new OrgCommands(SitePageCommandsTest::siteAdminCaller).saveOrgSettings(orgId,
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
                        Map.of("es", "Junta directiva", "hr", "Upravni odbor"))), "the org's own entry is kept as is");
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
        assertEquals(names(commandsFor(orgAdmin).pagesOf(org.getId().getValue())), List.of("about", "board"));
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
        assertTrue(new OrgCommands(SitePageCommandsTest::siteAdminCaller).saveOrgSettings(orgId,
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
        assertTrue(new OrgCommands(SitePageCommandsTest::siteAdminCaller).saveOrgSettings(orgId,
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
     * The product's own site (the platform org, slug www) serves its Site Pages like any org site: the menu lists
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
     * the menu and the Site Pages preview. Every other org site always shows it; the placeholder host never does.
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

    private void setPlatformDonate(final String url) throws IOException {
        final Organization platform = dao.getOrganization(Organization.Id.from(FakeData.PLATFORM_ORG_ID), Cached.NO)
                .orElseThrow();
        if (url == null) {
            platform.getSettingsOverrides().remove(KnownSettings.SITE_DONATE_URL.getName());
        } else {
            platform.getSettingsOverrides().put(KnownSettings.SITE_DONATE_URL.getName(), url);
        }
        assertTrue(dao.saveOrganization(platform));
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
        RequestFacesContext.install(request);
        try {
            assertEquals(onSite(SiteContext.shared("localhost"),
                    () -> pages.viewUrl(orgId, pages.pageOf(orgId, "mission"))),
                    "http://" + org.getSlug() + ".localhost:8080/page.jsf?name=mission");
        } finally {
            RequestFacesContext.clear();
        }
    }

    @Test
    public void anUnknownPageAnswers404() throws Exception {
        siteAdmin().answerNotFound(); // off a JSF request: nothing to answer
        final FacesContext ctx = RequestFacesContext.install(Mockito.mock(HttpServletRequest.class));
        try {
            siteAdmin().answerNotFound();
            Mockito.verify(ctx.getExternalContext()).responseSendError(404, null);
            Mockito.verify(ctx).responseComplete();
            Mockito.when(ctx.getResponseComplete()).thenReturn(true);
            siteAdmin().answerNotFound();
            Mockito.verify(ctx.getExternalContext(), Mockito.times(1)).responseSendError(404, null);
        } finally {
            RequestFacesContext.clear();
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

    private Organization reread(final Organization org) {
        return dao.getOrganization(org.getId(), Cached.NO).orElseThrow();
    }

    private Organization orgWithAdmin(final Person orgAdmin) {
        final OrgCommands admin = new OrgCommands(SitePageCommandsTest::siteAdminCaller);
        final Organization org = admin.createOrganization("Pages " + RandomData.genAlpha(10), null, null);
        assertTrue(admin.setOrgAdmin(org.getId().getValue(), orgAdmin.getId(), true));
        return reread(org);
    }

    /** Gives {@code org} a subdomain the way a site admin does, which seeds its starter pages. */
    private Organization host(final Organization org) {
        final String slug = "pages" + RandomData.genAlpha(10).toLowerCase();
        assertTrue(new OrgCommands(SitePageCommandsTest::siteAdminCaller).saveOrgEdits(org.getId().getValue(),
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

    /** {@code FacesContext.setCurrentInstance} is protected, so reach it from a subclass. */
    private abstract static class RequestFacesContext extends FacesContext {
        static FacesContext install(final HttpServletRequest request) {
            final ExternalContext external = Mockito.mock(ExternalContext.class);
            Mockito.when(external.getRequest()).thenReturn(request);
            final FacesContext ctx = Mockito.mock(FacesContext.class);
            Mockito.when(ctx.getExternalContext()).thenReturn(external);
            FacesContext.setCurrentInstance(ctx);
            return ctx;
        }

        static void clear() {
            FacesContext.setCurrentInstance(null);
        }
    }
}
