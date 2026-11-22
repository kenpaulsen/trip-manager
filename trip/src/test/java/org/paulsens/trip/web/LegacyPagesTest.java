package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import org.mockito.Mockito;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.SitePage;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * The hand-written page rule ({@link LegacyPages}): on an organization's host a hand-written page is that org's
 * Site Page of the same name or a 404, never the shared text in the org's look; on a shared host a link-out page
 * redirects to the link-out organization's Site Page; everything else passes.
 */
public class LegacyPagesTest {

    /** The shape of the deployment's WEB-INF/legacy-pages.json, cut down; local mode links out to Acme. */
    static final String DATA = """
            {
                "_comment": ["ignored"],
                "handWritten": ["/medjugorje", "/dm", "/board"],
                "sitePages": {
                    "/medjugorje/about/mission": "mission",
                    "/dm": "dm",
                    "/dm/day1": "dm-day1",
                    "/passport": "passport",
                    "/board": "board"
                },
                "linkOut": {
                    "slug": "cfpw",
                    "localSlug": "acme",
                    "paths": ["/medjugorje/about/mission", "/dm", "/dm/day1", "/passport"]
                }
            }
            """;

    private static final SiteContext ACME = SiteContext.org(Organization.Id.from(FakeData.ACME_ORG_ID), "acme",
            "acme.localhost");
    private static final SiteContext SHARED = SiteContext.shared("localhost");
    private static final Predicate<String> HAS_ALL = name -> true;
    private static final Predicate<String> HAS_NONE = name -> false;
    private static final Function<String, String> NOWHERE = name -> null;
    private static final Function<String, String> CFPW = name -> "https://cfpw.example.org/page.jsf?name=" + name;

    static LegacyPages data() throws IOException {
        return LegacyPages.parse(stream(DATA));
    }

    private static InputStream stream(final String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @AfterMethod
    public void uninstall() {
        LegacyPages.install(null);
    }

    @Test
    public void everySpellingOfAnAddressComesDownToOne() {
        Assert.assertEquals(LegacyPages.canonical("/dm/index.jsf", null), "/dm");
        Assert.assertEquals(LegacyPages.canonical("/dm/", null), "/dm");
        Assert.assertEquals(LegacyPages.canonical("/dm", null), "/dm");
        Assert.assertEquals(LegacyPages.canonical("/faces", "/dm/day1.xhtml"), "/dm/day1");
        Assert.assertEquals(LegacyPages.canonical("/biy/index.faces", null), "/biy");
        Assert.assertEquals(LegacyPages.canonical("/medjugorje/links.jsf", null), "/medjugorje/links");
        Assert.assertEquals(LegacyPages.canonical("/api", "/chat/x"), "/api/chat/x");
        Assert.assertEquals(LegacyPages.canonical("/faces", null), "/");
        Assert.assertEquals(LegacyPages.canonical(null, null), "/");
        Assert.assertEquals(LegacyPages.canonical("/index.jsf", null), "/", "the site root is no hand-written page");
    }

    @Test
    public void onAnOrgHostAHandWrittenPageIsTheOrgsSitePageOfThatName() throws IOException {
        final LegacyPages pages = data();
        Assert.assertEquals(pages.decide("/board", ACME, HAS_ALL, CFPW),
                new LegacyPages.Decision(LegacyPages.Kind.REDIRECT, "/page.jsf?name=board"));
        Assert.assertEquals(pages.decide("/dm/day1", ACME, "dm-day1"::equals, CFPW).location(),
                "/page.jsf?name=dm-day1", "never the link-out site: this host's own page");
    }

    @Test
    public void onAnOrgHostAHandWrittenPageTheOrgLacksIsNotFound() throws IOException {
        final LegacyPages pages = data();
        Assert.assertEquals(pages.decide("/dm", ACME, HAS_NONE, CFPW).kind(), LegacyPages.Kind.NOT_FOUND);
        Assert.assertEquals(pages.decide("/medjugorje/trips/may2022", ACME, HAS_ALL, CFPW).kind(),
                LegacyPages.Kind.NOT_FOUND, "a hand-written page with no Site Page name is never the shared text");
        Assert.assertEquals(pages.decide("/medjugorje", ACME, HAS_ALL, CFPW).kind(), LegacyPages.Kind.NOT_FOUND,
                "the prefix itself");
    }

    @Test
    public void onAnOrgHostEverythingElsePasses() throws IOException {
        final LegacyPages pages = data();
        for (final String path : List.of("/", "/trip/tripDetails", "/dmx", "/boards", "/page", "/passport")) {
            Assert.assertEquals(pages.decide(path, ACME, HAS_NONE, CFPW).kind(), LegacyPages.Kind.PASS, path);
        }
    }

    @Test
    public void theMarketingAndUnknownHostsAnswerLikeAnOrgWithNoPages() throws IOException {
        final LegacyPages pages = data();
        Assert.assertEquals(pages.decide("/dm", SiteContext.marketing("localhost"), HAS_NONE, CFPW).kind(),
                LegacyPages.Kind.NOT_FOUND);
        Assert.assertEquals(pages.decide("/dm", SiteContext.unknown("x.localhost"), HAS_NONE, CFPW).kind(),
                LegacyPages.Kind.NOT_FOUND);
    }

    @Test
    public void onASharedHostALinkOutPageRedirectsToTheLinkOutSite() throws IOException {
        final LegacyPages pages = data();
        Assert.assertEquals(pages.decide("/dm", SHARED, HAS_NONE, CFPW),
                new LegacyPages.Decision(LegacyPages.Kind.REDIRECT, "https://cfpw.example.org/page.jsf?name=dm"));
        Assert.assertEquals(pages.decide("/passport", SHARED, HAS_NONE, CFPW).location(),
                "https://cfpw.example.org/page.jsf?name=passport", "link-out need not be under a prefix");
    }

    @Test
    public void onASharedHostEverythingElseRendersAsItAlwaysHas() throws IOException {
        final LegacyPages pages = data();
        Assert.assertEquals(pages.decide("/dm", SHARED, HAS_NONE, NOWHERE).kind(), LegacyPages.Kind.PASS,
                "the link-out org has no such page: the hand-written one keeps rendering");
        Assert.assertEquals(pages.decide("/board", SHARED, HAS_NONE, CFPW).kind(), LegacyPages.Kind.PASS);
        Assert.assertEquals(pages.decide("/medjugorje/trips/may2022", SHARED, HAS_NONE, CFPW).kind(),
                LegacyPages.Kind.PASS, "a stale page stays as it is");
        Assert.assertEquals(pages.decide("/dm", SiteContext.shared(null), HAS_NONE, NOWHERE).kind(),
                LegacyPages.Kind.PASS, "an unbound request is the shared default");
    }

    @Test
    public void withoutDataEveryRequestPasses() {
        Assert.assertEquals(LegacyPages.NONE.decide("/dm", ACME, HAS_NONE, CFPW).kind(), LegacyPages.Kind.PASS);
        Assert.assertEquals(LegacyPages.NONE.decide("/dm", SHARED, HAS_NONE, CFPW).kind(), LegacyPages.Kind.PASS);
        Assert.assertSame(LegacyPages.installed(), LegacyPages.NONE);
    }

    @Test
    public void theLinkOutSlugIsTheLocalOneInLocalMode() throws IOException {
        final LegacyPages pages = data();
        Assert.assertEquals(pages.linkOutSlug(false), "cfpw");
        Assert.assertEquals(pages.linkOutSlug(true), "acme");
        Assert.assertNull(LegacyPages.parse(stream("{}")).linkOutSlug(false), "no link out at all");
    }

    @Test
    public void theLinkOutResolverAddressesTheOrgsOwnSiteAndOnlyItsPages() throws IOException {
        // The unit suite runs local, so the local slug (acme) is the link-out org; acme holds a "mission" page.
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getServerName()).thenReturn("localhost");
        Mockito.when(request.getServerPort()).thenReturn(8080);
        final Function<String, String> resolver = data().linkOutUrl(request);
        Assert.assertEquals(resolver.apply(FakeData.ACME_SITE_PAGE),
                "http://acme.localhost:8080/page.jsf?name=" + FakeData.ACME_SITE_PAGE);
        Assert.assertNull(resolver.apply("dm"), "a page acme does not have: no link out");
        Assert.assertNull(LegacyPages.NONE.linkOutUrl(request).apply("mission"), "no link-out org");
    }

    @Test
    public void theMenuLinksStraightToTheLinkOutPageOnASharedHostOnly() throws IOException {
        final LegacyPages pages = data();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getServerName()).thenReturn("localhost");
        Mockito.when(request.getServerPort()).thenReturn(80);
        Assert.assertEquals(pages.href("/medjugorje/about/mission.jsf", SHARED, request),
                "http://acme.localhost/page.jsf?name=mission");
        Assert.assertEquals(pages.href("/dm", SHARED, request), "/dm", "acme has no dm page: the old address");
        Assert.assertEquals(pages.href("/medjugorje/links.jsf", SHARED, request), "/medjugorje/links.jsf");
        Assert.assertEquals(pages.href("/medjugorje/about/mission.jsf", ACME, request),
                "/medjugorje/about/mission.jsf", "an org host never links out");
        Assert.assertNull(pages.href(null, SHARED, request));
        Assert.assertEquals(pages.href("/medjugorje/about/mission.jsf", SHARED, null),
                "https://acme.unitetrip.com/page.jsf?name=mission", "off a request: the production address");
    }

    @Test
    public void aPageCountsOnlyWhenTheOrgHoldsItAsAPage() {
        final Organization org = new Organization();
        Assert.assertFalse(LegacyPages.holdsPage(null, "dm"));
        org.setSitePages(null);
        Assert.assertFalse(LegacyPages.holdsPage(org, "dm"));
        org.setSitePages(List.of(new SitePage("dm", "Divine Mercy", "https://example.org/dm", "HELP"),
                new SitePage("biy", "Bible", null, "HIDDEN")));
        Assert.assertFalse(LegacyPages.holdsPage(org, "dm"), "a LINK entry has no page to show");
        Assert.assertTrue(LegacyPages.holdsPage(org, "biy"), "a hidden page is still a page");
    }

    @Test
    public void theOrganizationsAreReadFromTheCache() {
        Assert.assertNull(LegacyPages.orgWithSlug(null));
        Assert.assertNull(LegacyPages.orgWithSlug("no-such-slug"));
        Assert.assertEquals(LegacyPages.orgWithSlug("acme").getId().getValue(), FakeData.ACME_ORG_ID);
        Assert.assertNull(LegacyPages.orgOf(SHARED));
        Assert.assertEquals(LegacyPages.orgOf(ACME).getSlug(), "acme");
        Assert.assertNull(LegacyPages.orgOf(SiteContext.org(Organization.Id.from("no-such-org"), "x", "x.localhost")));
    }

    @Test
    public void theInstalledDataIsWhatTheMenuReads() throws IOException {
        final LegacyPages pages = data();
        LegacyPages.install(pages);
        Assert.assertSame(LegacyPages.installed(), pages);
        LegacyPages.install(null);
        Assert.assertSame(LegacyPages.installed(), LegacyPages.NONE);
    }

    @Test
    public void malformedDataIsRefusedLoudly() {
        final Map<String, String> bad = Map.of(
                "not an object", "[]",
                "a relative address", "{\"handWritten\": [\"dm\"]}",
                "a suffix", "{\"sitePages\": {\"/dm/index.jsf\": \"dm\"}}",
                "a trailing /index", "{\"sitePages\": {\"/dm/index\": \"dm\"}}",
                "the root", "{\"handWritten\": [\"/\"]}",
                "a bad page name", "{\"sitePages\": {\"/dm\": \"Divine Mercy\"}}",
                "an unmapped link out", "{\"sitePages\": {}, \"linkOut\": {\"paths\": [\"/dm\"]}}");
        for (final Map.Entry<String, String> entry : bad.entrySet()) {
            Assert.assertTrue(refused(entry.getValue()), entry.getKey() + " was accepted");
        }
        Assert.assertThrows(IOException.class, () -> LegacyPages.parse(stream("{not json")));
    }

    private static boolean refused(final String json) {
        try {
            LegacyPages.parse(stream(json));
            return false;
        } catch (final IllegalArgumentException | IOException expected) {
            return expected instanceof IllegalArgumentException;
        }
    }
}
