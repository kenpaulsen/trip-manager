package org.paulsens.trip.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
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
        final HttpSession fresh = sessionOver(newAttrs);
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
    public void anApiRestoreContinuesTheChain() throws Exception {
        final RememberMeService service = Mockito.mock(RememberMeService.class);
        Mockito.when(service.validateAndRotate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new Creds("who@example.com", Person.Id.from("who"), "user", "x", null));
        final RememberMeFilter filter = new RememberMeFilter(service);
        final HttpServletRequest req = request(null, COOKIE);
        final HttpSession fresh = sessionOver(new HashMap<>());
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

    private static HttpSession sessionOver(final Map<String, Object> attrs) {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(ArgumentMatchers.anyString()))
                .thenAnswer(call -> attrs.get(call.<String>getArgument(0)));
        Mockito.doAnswer(call -> attrs.put(call.getArgument(0), call.getArgument(1)))
                .when(session).setAttribute(ArgumentMatchers.anyString(), ArgumentMatchers.any());
        return session;
    }

    private static FilterChain chain(final AtomicBoolean flag) {
        return (rq, rs) -> flag.set(true);
    }
}
