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
