package org.paulsens.trip.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.security.RememberMeService;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link RememberMeFilter}'s routing: who gets passed through untouched, who gets restored, and how a restore
 * ends (redirect for pages, continue for the API).
 */
public class RememberMeFilterTest {

    private static final Cookie COOKIE = new Cookie(RememberMeService.COOKIE_NAME, "sel:val");

    @Test
    public void aSignedInRequestPassesStraightThrough() throws Exception {
        final RememberMeService service = Mockito.mock(RememberMeService.class);
        final RememberMeFilter filter = new RememberMeFilter(service);
        final HttpServletRequest req = request(sessionWithUser(), COOKIE);
        final AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(req, Mockito.mock(HttpServletResponse.class), chain(chained));

        Assert.assertTrue(chained.get());
        Mockito.verifyNoInteractions(service);
    }

    @Test
    public void noCookieMeansNoValidationAtAll() throws Exception {
        final RememberMeService service = Mockito.mock(RememberMeService.class);
        final RememberMeFilter filter = new RememberMeFilter(service);
        final AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(request(null, (Cookie) null), Mockito.mock(HttpServletResponse.class), chain(chained));

        Assert.assertTrue(chained.get());
        Mockito.verifyNoInteractions(service);
    }

    @Test
    public void aFailedValidationContinuesAnonymously() throws Exception {
        final RememberMeService service = Mockito.mock(RememberMeService.class);
        Mockito.when(service.validateAndRotate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(null);
        final RememberMeFilter filter = new RememberMeFilter(service);
        final AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(request(null, COOKIE), Mockito.mock(HttpServletResponse.class), chain(chained));

        Assert.assertTrue(chained.get(), "an invalid cookie must fall through to the anonymous flow");
    }

    @Test
    public void aPageRestoreRedirectsBackToTheSameUrl() throws Exception {
        final RememberMeService service = Mockito.mock(RememberMeService.class);
        Mockito.when(service.validateAndRotate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new Creds("who@example.com", Person.Id.from("who"), "user", "x", null));
        final RememberMeFilter filter = new RememberMeFilter(service);
        final Map<String, Object> newAttrs = new HashMap<>();
        final HttpServletRequest req = request(null, COOKIE);
        // Built OUTSIDE the when(): stubbing a new mock inside another stubbing is the classic
        // UnfinishedStubbingException.
        final HttpSession fresh = FakeSessions.over(newAttrs);
        Mockito.when(req.getSession(true)).thenReturn(fresh);
        Mockito.when(req.getRequestURI()).thenReturn("/trip/itinerary.jsf");
        Mockito.when(req.getQueryString()).thenReturn("id=who");
        Mockito.when(req.getMethod()).thenReturn("GET");
        final HttpServletResponse res = Mockito.mock(HttpServletResponse.class);
        final AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(req, res, chain(chained));

        Assert.assertFalse(chained.get(), "a page restore must redirect, not continue actor-less");
        Mockito.verify(res).sendRedirect("/trip/itinerary.jsf?id=who");
        Assert.assertEquals(newAttrs.get(PersonCommands.ACTIVE_USER_ID), Person.Id.from("who"));
    }

    @Test
    public void aRestoreNeverRedirectsOffSite() throws Exception {
        final RememberMeService service = Mockito.mock(RememberMeService.class);
        Mockito.when(service.validateAndRotate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new Creds("who@example.com", Person.Id.from("who"), "user", "x", null));
        final RememberMeFilter filter = new RememberMeFilter(service);
        final HttpServletRequest req = request(null, COOKIE);
        final HttpSession fresh = FakeSessions.over(new HashMap<>());
        Mockito.when(req.getSession(true)).thenReturn(fresh);
        // A protocol-relative path would otherwise bounce the just-restored session to evil.example.
        Mockito.when(req.getRequestURI()).thenReturn("//evil.example/x");
        Mockito.when(req.getMethod()).thenReturn("GET");
        final HttpServletResponse res = Mockito.mock(HttpServletResponse.class);

        filter.doFilter(req, res, chain(new AtomicBoolean()));

        Mockito.verify(res).sendRedirect("/");
    }

    @Test
    public void anApiRestoreContinuesTheChain() throws Exception {
        final RememberMeService service = Mockito.mock(RememberMeService.class);
        Mockito.when(service.validateAndRotate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new Creds("who@example.com", Person.Id.from("who"), "user", "x", null));
        final RememberMeFilter filter = new RememberMeFilter(service);
        final HttpServletRequest req = request(null, COOKIE);
        final HttpSession fresh = FakeSessions.over(new HashMap<>());
        Mockito.when(req.getSession(true)).thenReturn(fresh);
        Mockito.when(req.getRequestURI()).thenReturn("/api/auth/me");
        final HttpServletResponse res = Mockito.mock(HttpServletResponse.class);
        final AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(req, res, chain(chained));

        Assert.assertTrue(chained.get(), "JSON clients have no redirect semantics; the request continues");
        Mockito.verify(res, Mockito.never()).sendRedirect(ArgumentMatchers.anyString());
    }

    @Test
    public void aNonHttpRequestPassesThrough() throws Exception {
        final AtomicBoolean chained = new AtomicBoolean();
        new RememberMeFilter().doFilter(Mockito.mock(jakarta.servlet.ServletRequest.class),
                Mockito.mock(jakarta.servlet.ServletResponse.class), chain(chained));
        Assert.assertTrue(chained.get());
    }

    @Test
    public void unrelatedCookiesDoNotTriggerValidation() throws Exception {
        final RememberMeService service = Mockito.mock(RememberMeService.class);
        final RememberMeFilter filter = new RememberMeFilter(service);
        final AtomicBoolean chained = new AtomicBoolean();

        filter.doFilter(request(null, new Cookie("JSESSIONID", "abc")),
                Mockito.mock(HttpServletResponse.class), chain(chained));

        Assert.assertTrue(chained.get());
        Mockito.verifyNoInteractions(service);
    }

    /**
     * Discover answers anyone and is cached as public: a remember-me cookie riding along must not be turned into
     * a new session there (a store write and two Set-Cookie headers on a cacheable response). The filter stands
     * aside before it even looks for a session; every other /api/ path still restores, as the test above shows.
     */
    @Test
    public void aRememberMeCookieOnASessionlessPathCreatesNoSession() throws Exception {
        for (final String uri : List.of("/api/discover/trips", "/api/discover/trips/some-id", "/api/discover")) {
            final RememberMeService service = Mockito.mock(RememberMeService.class);
            final RememberMeFilter filter = new RememberMeFilter(service);
            final HttpServletRequest req = request(null, COOKIE);
            Mockito.when(req.getRequestURI()).thenReturn(uri);
            Mockito.when(req.getContextPath()).thenReturn("");
            final HttpServletResponse res = Mockito.mock(HttpServletResponse.class);
            final AtomicBoolean chained = new AtomicBoolean();

            filter.doFilter(req, res, chain(chained));

            Assert.assertTrue(chained.get(), uri + " must still be served");
            Mockito.verifyNoInteractions(service);
            Mockito.verify(req, Mockito.never()).getSession();
            Mockito.verify(req, Mockito.never()).getSession(ArgumentMatchers.anyBoolean());
            Mockito.verify(req, Mockito.never()).getCookies();
            Mockito.verifyNoInteractions(res);
        }
    }

    /** The match ends at a path boundary and honors a context path: a look-alike path is an ordinary API path. */
    @Test
    public void onlyTheDiscoverPathsAreSessionless() {
        Assert.assertTrue(SessionlessPaths.matches(at("/api/discover/trips", "")));
        Assert.assertTrue(SessionlessPaths.matches(at("/api/discover", "")));
        Assert.assertTrue(SessionlessPaths.matches(at("/app/api/discover/trips", "/app")), "under a context path");
        Assert.assertTrue(SessionlessPaths.matches(at("/api/discover/trips", null)));
        Assert.assertFalse(SessionlessPaths.matches(at("/api/discovery", "")), "a longer name is another resource");
        Assert.assertFalse(SessionlessPaths.matches(at("/api/trips", "")));
        Assert.assertFalse(SessionlessPaths.matches(at("/api/trips/api/discover/x", "")), "only at the start");
        Assert.assertFalse(SessionlessPaths.matches(at("/trip/index.jsf", "")));
        Assert.assertFalse(SessionlessPaths.matches(at(null, "")));
    }

    /**
     * The rule is judged on the path the request is ROUTED by. The router ignores path parameters and decodes
     * escapes, so a spelling with either still reaches Discover; were the rule to read the raw request line, a
     * remember-me cookie on such a request would be turned into a session on a publicly cacheable answer.
     */
    @Test
    public void aPathParameterOrAnEscapeDoesNotStepAroundTheRule() throws Exception {
        Assert.assertTrue(SessionlessPaths.matches(at("/api/discover;x/trips", "")), "a path parameter");
        Assert.assertTrue(SessionlessPaths.matches(at("/api/discover/trips;jsessionid=abc", "")));
        Assert.assertTrue(SessionlessPaths.matches(at("/api;v=1/discover;x=y/trips/some-id;z", "")));
        Assert.assertFalse(SessionlessPaths.matches(at("/api/discovery;x/trips", "")), "still another resource");

        // In the container the routed path is its own, already decoded and stripped: that is what is read.
        Assert.assertTrue(SessionlessPaths.matches(routed("/api/%64iscover;x/trips", "/api", "/discover/trips")));
        Assert.assertTrue(SessionlessPaths.matches(routed("/api/discover", "/api", "/discover")));
        Assert.assertFalse(SessionlessPaths.matches(routed("/api/discover/trips", "/api", "/trips")),
                "the container's routing wins over the raw line");
        Assert.assertFalse(SessionlessPaths.matches(routed("/trip/index.jsf", "/trip/index.jsf", null)));
        Assert.assertEquals(SessionlessPaths.routedPath(routed("/x", "/trip/index.jsf", null)), "/trip/index.jsf");

        final RememberMeService service = Mockito.mock(RememberMeService.class);
        final HttpServletRequest req = request(null, COOKIE);
        Mockito.when(req.getRequestURI()).thenReturn("/api/discover;x/trips");
        Mockito.when(req.getContextPath()).thenReturn("");
        final AtomicBoolean chained = new AtomicBoolean();
        new RememberMeFilter(service).doFilter(req, Mockito.mock(HttpServletResponse.class), chain(chained));
        Assert.assertTrue(chained.get());
        Mockito.verifyNoInteractions(service);
    }

    private static HttpServletRequest routed(final String uri, final String servletPath, final String pathInfo) {
        final HttpServletRequest req = at(uri, "");
        Mockito.when(req.getServletPath()).thenReturn(servletPath);
        Mockito.when(req.getPathInfo()).thenReturn(pathInfo);
        return req;
    }

    private static HttpServletRequest at(final String uri, final String contextPath) {
        final HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.getRequestURI()).thenReturn(uri);
        Mockito.when(req.getContextPath()).thenReturn(contextPath);
        return req;
    }

    private static HttpServletRequest request(final HttpSession session, final Cookie cookie) {
        final HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.when(req.getSession(false)).thenReturn(session);
        Mockito.when(req.getCookies()).thenReturn(cookie == null ? null : new Cookie[] {cookie});
        return req;
    }

    private static HttpSession sessionWithUser() {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ID)).thenReturn(Person.Id.from("x"));
        return session;
    }

    private static FilterChain chain(final AtomicBoolean flag) {
        return (rq, rs) -> flag.set(true);
    }
}
