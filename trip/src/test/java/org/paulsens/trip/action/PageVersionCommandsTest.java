package org.paulsens.trip.action;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * {@link PageVersionCommands}: which version of a page a request reads. Beta Corp's seeded site offers English,
 * Spanish and Croatian ({@code FakeData.seedBetaBranding}); the shared site offers English alone.
 */
public class PageVersionCommandsTest {

    private static final SiteContext BETA =
            SiteContext.org(Organization.Id.from(FakeData.BETA_ORG_ID), "beta", "beta.unitetrip.com");
    private static final String PAGE = "page:org:" + FakeData.BETA_ORG_ID + ":pv-test";

    private final PageVersionCommands versions = new PageVersionCommands();

    @BeforeClass
    public void seed() {
        // The DAO first: it is what seeds the fixtures the first time, and addFakeData reads them.
        DAO.getInstance();
        FakeData.addFakeData();
    }

    @Test
    public void aSiteWithOneLanguageHasNoVersions() {
        Assert.assertFalse(versions.isOffered());
        Assert.assertEquals(PageVersionCommands.forThisRequest("page:shared:board", null), "page:shared:board");
        Assert.assertNull(PageVersionCommands.forThisRequest(null, null));
    }

    @Test
    public void aReaderFallsBackToThePagesOwnVersionUntilTheirsIsWritten() {
        Assert.assertEquals(reading(Language.Spanish), PAGE, "nothing written in Spanish yet");
        Assert.assertEquals(reading(Language.English), PAGE);
        final List<PageVersionCommands.Tab> tabs = on(Language.Spanish, () -> versions.tabs(PAGE + "@es"));
        Assert.assertEquals(tabs.stream().map(PageVersionCommands.Tab::tag).toList(), List.of("en", "es", "hr"),
                "the page's own language first");
        Assert.assertTrue(tabs.get(0).own());
        Assert.assertTrue(tabs.get(0).written(), "the page's own version always counts as written");
        Assert.assertEquals(tabs.get(0).key(), PAGE);
        Assert.assertEquals(tabs.get(1).key(), PAGE + "@es");
        Assert.assertFalse(tabs.get(1).written());
        Assert.assertTrue(tabs.get(1).current(), "the version the key names");
        Assert.assertEquals(on(Language.Spanish, () -> versions.current(PAGE + "@es")).name(), "Español");
        Assert.assertEquals(on(Language.Spanish, () -> versions.current(PAGE)).tag(), "en");
        Assert.assertTrue(on(Language.Spanish, versions::isOffered));
    }

    /**
     * A version marked with what is NOW the page's own language (the site's default changed after it was
     * written): readers of that language get it, and the bar still lists it as a version. The unmarked page,
     * written in what used to be the default, keeps a tab of its own, the original page, which is current while
     * it is the page shown; the other languages' tabs still open their own (empty) versions.
     */
    @Test
    public void aVersionInWhatBecameTheOwnLanguageIsStillServedAndListed() {
        final String page = "page:org:" + FakeData.BETA_ORG_ID + ":pv-own";
        final ContentInstance english = new ContentInstance("pv-own-" + UUID.randomUUID(), page + "@en", "Hi",
                "text-only", 1, new HashMap<>(Map.of("body", "<p>Hi</p>")), null, 0, 0, null, "t");
        Assert.assertTrue(DAO.getInstance().saveContent(english, 5));
        try {
            Assert.assertEquals(on(Language.English, () -> PageVersionCommands.forThisRequest(page, null)),
                    page + "@en", "English is Beta's default, and an @en version was written: it is served");
            final List<PageVersionCommands.Tab> onlyVersion = on(Language.English, () -> versions.tabs(page + "@en"));
            final PageVersionCommands.Tab tab = onlyVersion.get(0);
            Assert.assertEquals(tab.key(), page + "@en", "the bar opens the version, so it can be edited");
            Assert.assertTrue(tab.written());
            Assert.assertFalse(tab.own(), "a version, which can be deleted");
            Assert.assertTrue(onlyVersion.get(0).current());
            Assert.assertEquals(onlyVersion.size(), 3, "no original-page tab while the unmarked page is empty");
            final PageVersionCommands.Tab emptyOriginal = on(Language.English, () -> versions.tabs(page)).get(3);
            Assert.assertTrue(emptyOriginal.current(), "but it keeps one while it is the page shown");
            Assert.assertFalse(emptyOriginal.written());
            Assert.assertEquals(versions.languageTagOf(page + "@en"), "en");
        } finally {
            DAO.getInstance().deleteContent(english.getId());
        }
    }

    /**
     * The unmarked page written in what used to be the default (Spanish, say) beside a version in what is now
     * the default (English): an editor reading the unmarked page sees the original-page tab marked current, not
     * some language's, and every other language's tab still opens its own version.
     */
    @Test
    public void theUnmarkedPageKeepsItsOwnTabOnceTheOwnLanguageHasAVersion() {
        final String page = "page:org:" + FakeData.BETA_ORG_ID + ":pv-original";
        final ContentInstance original = new ContentInstance("pv-orig-" + UUID.randomUUID(), page, "Hola",
                "text-only", 1, new HashMap<>(Map.of("body", "<p>Hola</p>")), null, 0, 0, null, "t");
        final ContentInstance english = new ContentInstance("pv-orig-" + UUID.randomUUID(), page + "@en", "Hi",
                "text-only", 1, new HashMap<>(Map.of("body", "<p>Hi</p>")), null, 0, 0, null, "t");
        Assert.assertTrue(DAO.getInstance().saveContent(original, 5));
        Assert.assertTrue(DAO.getInstance().saveContent(english, 5));
        try {
            Assert.assertEquals(on(Language.Croatian, () -> PageVersionCommands.forThisRequest(page, null)), page,
                    "Croatian has no version: it falls back to the unmarked page");
            final List<PageVersionCommands.Tab> tabs = on(Language.Croatian, () -> versions.tabs(page));
            Assert.assertEquals(tabs.stream().map(PageVersionCommands.Tab::key).toList(),
                    List.of(page + "@en", page + "@es", page + "@hr", page),
                    "every language opens its own version; the unmarked page has the last tab");
            Assert.assertEquals(tabs.stream().filter(PageVersionCommands.Tab::current).toList(), List.of(tabs.get(3)),
                    "only the original-page tab is current while the unmarked page is shown");
            final PageVersionCommands.Tab originalTab = tabs.get(3);
            Assert.assertEquals(originalTab.tag(), "", "its language is not known");
            Assert.assertEquals(originalTab.name(), "Izvorna stranica");
            Assert.assertTrue(originalTab.own(), "never deleted as a version");
            Assert.assertTrue(originalTab.written());
            Assert.assertFalse(tabs.get(1).written(), "Spanish has no version of its own");
            Assert.assertFalse(tabs.get(1).current());
            Assert.assertEquals(on(Language.English, () -> versions.current(page + "@en")).tag(), "en");
            Assert.assertEquals(on(Language.English, () -> versions.languageTagOf(page)), "",
                    "the unmarked text is in whatever the default used to be");

            final String link = versions.editUrl("/page.jsf", "name=pv-original&version=en", originalTab.tag());
            Assert.assertEquals(link, "/page.jsf?name=pv-original&version=original&edit=true");
            Assert.assertEquals(on(Language.English, () -> PageVersionCommands.forVersion(page, null,
                    PageVersionCommands.ORIGINAL)), page, "the original-page link opens the unmarked page");
            Assert.assertEquals(on(Language.English, () -> PageVersionCommands.forVersion(page, null, "es")),
                    page + "@es", "a language named outright still opens its version, empty or not");
        } finally {
            DAO.getInstance().deleteContent(original.getId());
            DAO.getInstance().deleteContent(english.getId());
        }
    }

    @Test
    public void theBarsLinksNameAVersionAndKeepThePagesOwnParameters() {
        Assert.assertEquals(versions.editUrl("/page.jsf", "name=about&edit=true&version=hr", "es"),
                "/page.jsf?name=about&version=es&edit=true");
        Assert.assertEquals(versions.editUrl("/index.jsf", null, "hr"), "/index.jsf?version=hr&edit=true");
        Assert.assertEquals(versions.editUrl("//evil.example/page.jsf", null, "hr"), "/?version=hr&edit=true",
                "a protocol-relative request URI never becomes a link to another host");
        Assert.assertEquals(versions.ownKey(PAGE + "@hr"), PAGE);
    }

    @Test
    public void aPageWithAWrittenVersionNamesEveryWrittenLanguageForSearchEngines() throws Exception {
        final String page = "page:org:" + FakeData.BETA_ORG_ID + ":pv-alternates";
        final String url = "http://beta.localhost:8080/page.jsf";
        Assert.assertEquals(on(Language.English, () -> PageVersionCommands.alternates(page, url, "name=x")),
                List.of(), "nothing beyond the page itself: nothing to say");
        final ContentInstance spanish = new ContentInstance("pv-alt-" + UUID.randomUUID(), page + "@es", "Hola",
                "text-only", 1, new HashMap<>(Map.of("body", "<p>Hola</p>")), null, 0, 0, null, "t");
        Assert.assertTrue(DAO.getInstance().saveContent(spanish, 5));
        try {
            final List<PageVersionCommands.Alternate> alternates = on(Language.Croatian,
                    () -> PageVersionCommands.alternates(page + "@es", url, "name=x&lang=hr&edit=true&version=es"));
            Assert.assertEquals(alternates, List.of(
                    new PageVersionCommands.Alternate("en", url + "?name=x&lang=en"),
                    new PageVersionCommands.Alternate("es", url + "?name=x&lang=es"),
                    new PageVersionCommands.Alternate("x-default", url + "?name=x")),
                    "each written language, never Croatian (nothing written), and the address with no language");
            Assert.assertEquals(on(Language.English, () -> PageVersionCommands.alternates(page, url, null)).get(0),
                    new PageVersionCommands.Alternate("en", url + "?lang=en"));
            Assert.assertEquals(PageVersionCommands.alternates(page, url, null), List.of(),
                    "the shared site offers one language");
            Assert.assertEquals(versions.alternates(page), List.of(), "off a page request");
            Assert.assertEquals(versions.alternates(null), List.of());
        } finally {
            DAO.getInstance().deleteContent(spanish.getId());
        }
    }

    private static String reading(final Language language) {
        return on(language, () -> PageVersionCommands.forThisRequest(PAGE, null));
    }

    private static <T> T on(final Language language, final ScopedValue.CallableOp<T, RuntimeException> body) {
        final RequestContext context = RequestContext.of(null, null, BETA).withLocale(language.getLocale());
        return ScopedValue.where(RequestContext.SCOPE, context).call(body);
    }
}
