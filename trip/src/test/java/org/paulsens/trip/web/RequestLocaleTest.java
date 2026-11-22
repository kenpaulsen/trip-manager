package org.paulsens.trip.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * {@link RequestLocale}: who decides a request's language ({@code docs/i18n.md}). The settings are passed in
 * ({@code among}), so no test changes what the rest of the suite's site offers.
 */
public class RequestLocaleTest {

    private static final List<Language> ALL = List.of(Language.English, Language.Spanish, Language.Croatian);
    private static final SiteContext PAGE = SiteContext.shared("localhost");
    private static final SiteContext API = SiteContext.shared("localhost").forApiClient();

    @BeforeClass
    public void seed() {
        // Another class may have cleared the shared caches; the preference tests need a seeded person.
        // The DAO first: it is what seeds the fixtures the first time, and addFakeData reads them.
        DAO.getInstance();
        FakeData.addFakeData();
    }

    @Test
    public void aSiteOfferingOneLanguageAnswersInItWithoutLookingAtTheRequest() {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        final Locale locale = ScopedValue.where(RequestContext.SCOPE, visitor(PAGE))
                .call(() -> RequestLocale.resolve(request, response, visitor(PAGE)));
        Assert.assertEquals(locale, Locale.ENGLISH, "site.languages ships as English alone");
        Mockito.verifyNoInteractions(request, response);
    }

    @Test
    public void aVisitorGetsTheBrowsersLanguageWhenTheSiteOffersIt() {
        final HttpServletRequest request = page(null, "hr-HR", "en");
        Assert.assertEquals(choose(request, visitor(PAGE)), Language.Croatian);
        Assert.assertEquals(choose(page(null, "de"), visitor(PAGE)), Language.English, "not offered: the default");
        Assert.assertEquals(choose(page(null), visitor(PAGE)), Language.English, "no header at all");
    }

    @Test
    public void theCookieChoiceOutranksTheBrowser() {
        final HttpServletRequest request = page(null, "hr");
        Mockito.when(request.getCookies())
                .thenReturn(new Cookie[] {new Cookie("other", "x"), new Cookie("lang", "es")});
        Assert.assertEquals(choose(request, visitor(PAGE)), Language.Spanish);
    }

    @Test
    public void aLangParameterOnAGetWinsAndIsRememberedInACookieWithNoSession() {
        final HttpServletRequest request = page("trip=abc&lang=es&x=1", "hr");
        Mockito.when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("lang", "hr")});
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Assert.assertEquals(RequestLocale.among(ALL, "", request, response, visitor(PAGE)), Language.Spanish);
        final ArgumentCaptor<Cookie> cookie = ArgumentCaptor.forClass(Cookie.class);
        Mockito.verify(response).addCookie(cookie.capture());
        Assert.assertEquals(cookie.getValue().getName(), "lang");
        Assert.assertEquals(cookie.getValue().getValue(), "es");
        Assert.assertEquals(cookie.getValue().getPath(), "/");
        Assert.assertTrue(cookie.getValue().isHttpOnly());
        Assert.assertEquals(cookie.getValue().getAttribute("SameSite"), "Lax");
        Assert.assertTrue(cookie.getValue().getMaxAge() > 150 * 24 * 3600);
        Mockito.verify(request, Mockito.never()).getSession();
        Mockito.verify(request, Mockito.never()).getSession(true);
        Mockito.verify(request, Mockito.never()).getParameter(Mockito.anyString());
    }

    @Test
    public void aLangParameterIsIgnoredWhenNotOfferedMalformedOrNotAGet() {
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        final List<Language> noCroatian = List.of(Language.English, Language.Spanish);
        Assert.assertEquals(RequestLocale.among(noCroatian, "", page("lang=hr"), response, visitor(PAGE)),
                Language.English);
        Assert.assertEquals(RequestLocale.among(ALL, "", page("lang=<script>"), response, visitor(PAGE)),
                Language.English);
        Assert.assertEquals(RequestLocale.among(ALL, "", page("mylang=es"), response, visitor(PAGE)),
                Language.English, "another parameter that merely ends in lang");
        final HttpServletRequest post = page("lang=es");
        Mockito.when(post.getMethod()).thenReturn("POST");
        Assert.assertEquals(RequestLocale.among(ALL, "", post, response, visitor(PAGE)), Language.English);
        Mockito.verify(response, Mockito.never()).addCookie(Mockito.any());
    }

    @Test
    public void aSignedInPersonsSavedPreferenceOutranksTheBrowserButNotTheCookie() throws Exception {
        final Person.Id id = Person.Id.from(FakeData.ADMIN_PERSON_ID);
        setLanguage(id, "hr");
        try {
            Assert.assertEquals(choose(page(null, "es"), person(id, PAGE)), Language.Croatian);
            final HttpServletRequest withCookie = page(null, "es");
            Mockito.when(withCookie.getCookies()).thenReturn(new Cookie[] {new Cookie("lang", "es")});
            Assert.assertEquals(choose(withCookie, person(id, PAGE)), Language.Spanish);
        } finally {
            setLanguage(id, null);
        }
    }

    @Test
    public void anExplicitChoiceSetsOnlyTheCookieNeverThePersonsSavedLanguage() throws Exception {
        final Person.Id id = Person.Id.from(FakeData.ADMIN_PERSON_ID);
        setLanguage(id, null);
        try {
            final HttpServletRequest own = page("lang=es");
            Mockito.when(own.getSession(false)).thenReturn(Mockito.mock(HttpSession.class));
            final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
            Assert.assertEquals(RequestLocale.among(ALL, "", own, response, person(id, PAGE)), Language.Spanish);
            Mockito.verify(response).addCookie(Mockito.argThat(cookie -> "es".equals(cookie.getValue())));
            Assert.assertNull(languageOf(id), "any page can carry a ?lang= link, so a GET never saves the preference");
        } finally {
            setLanguage(id, null);
        }
    }

    @Test
    public void aBrowsersApiCallFollowsItsPage() {
        final HttpServletRequest request = api("Mozilla/5.0", "hr");
        Mockito.when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("lang", "es")});
        Assert.assertEquals(RequestLocale.among(ALL, "", request, null, visitor(API)), Language.Spanish);
    }

    @Test
    public void aNativeAppIsAnsweredInItsLanguageOnlyFromTheVersionThatShipsTranslations() {
        final HttpServletRequest old = api("UniteTrip/1.2.0 (45; iOS)", "es");
        Assert.assertEquals(RequestLocale.among(ALL, "1.3.0", old, null, visitor(API)), Language.English);
        Assert.assertEquals(RequestLocale.among(ALL, "", old, null, visitor(API)), Language.English,
                "no floor set yet: every app build is answered in the default language");
        final HttpServletRequest current = api("UniteTrip/1.3.0 (Android 16; Pixel 9)", "es");
        Assert.assertEquals(RequestLocale.among(ALL, "1.3.0", current, null, visitor(API)), Language.Spanish);
        Assert.assertEquals(RequestLocale.among(ALL, "1.3", api("UniteTrip/2.0 (1; iOS)", "hr"), null, visitor(API)),
                Language.Croatian);
    }

    @Test
    public void versionsCompareNumericallyPartByPart() {
        Assert.assertTrue(RequestLocale.atLeast("1.10.0", "1.9.9"));
        Assert.assertTrue(RequestLocale.atLeast("1.3", "1.3.0"));
        Assert.assertTrue(RequestLocale.atLeast("2", "1.99"));
        Assert.assertFalse(RequestLocale.atLeast("1.2.9", "1.3"));
        Assert.assertFalse(RequestLocale.atLeast("1.3.0", null));
        Assert.assertFalse(RequestLocale.atLeast("1.3.0", " "));
        Assert.assertFalse(RequestLocale.atLeast("1.3.0", "soon"));
    }

    @Test
    public void aSessionlessPathVariesOnTheHeaderAlone() throws Exception {
        final Person.Id id = Person.Id.from(FakeData.ADMIN_PERSON_ID);
        setLanguage(id, "hr");
        try {
            final HttpServletRequest request = api("Mozilla/5.0", "es");
            Mockito.when(request.getRequestURI()).thenReturn("/api/discover/trips");
            Mockito.when(request.getCookies()).thenReturn(new Cookie[] {new Cookie("lang", "hr")});
            Assert.assertEquals(RequestLocale.among(ALL, "", request, null, person(id, API)), Language.Spanish,
                    "a public, cacheable answer: neither the cookie nor the person may change it");
        } finally {
            setLanguage(id, null);
        }
    }

    private static Language choose(final HttpServletRequest request, final RequestContext context) {
        return RequestLocale.among(ALL, "", request, Mockito.mock(HttpServletResponse.class), context);
    }

    private static HttpServletRequest page(final String query, final String... acceptLanguages) {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getMethod()).thenReturn("GET");
        Mockito.when(request.getQueryString()).thenReturn(query);
        Mockito.when(request.getRequestURI()).thenReturn("/index.jsf");
        if (acceptLanguages.length > 0) {
            Mockito.when(request.getHeader("Accept-Language")).thenReturn(String.join(",", acceptLanguages));
            Mockito.when(request.getLocales()).thenAnswer(call -> Collections.enumeration(
                    List.of(acceptLanguages).stream().map(Locale::forLanguageTag).toList()));
        }
        return request;
    }

    private static HttpServletRequest api(final String agent, final String... acceptLanguages) {
        final HttpServletRequest request = page(null, acceptLanguages);
        Mockito.when(request.getRequestURI()).thenReturn("/api/trips");
        Mockito.when(request.getHeader("User-Agent")).thenReturn(agent);
        return request;
    }

    private static RequestContext visitor(final SiteContext site) {
        return RequestContext.of(null, null, site);
    }

    private static RequestContext person(final Person.Id id, final SiteContext site) {
        return RequestContext.of(new AuditActor("admin@example.com", id.getValue()), "admin", site);
    }

    private static void setLanguage(final Person.Id id, final String tag) throws Exception {
        final Person person = DAO.getInstance().getPerson(id, Cached.YES).orElseThrow();
        person.setLanguage(tag);
        DAO.getInstance().savePerson(person);
    }

    private static String languageOf(final Person.Id id) {
        return DAO.getInstance().getPerson(id, Cached.YES).orElseThrow().getLanguage();
    }
}
