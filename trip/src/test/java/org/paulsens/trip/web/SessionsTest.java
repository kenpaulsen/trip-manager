package org.paulsens.trip.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.audit.RequestContext;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.site.SiteContext;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link Sessions} is the one place login sessions are created, so what these tests pin down is the contract
 * every login mechanism relies on: the old session dies (fixation), the four auth attributes appear, and the
 * two carried attributes survive the rotation.
 */
public class SessionsTest {

    @Test
    public void establishRotatesTheSessionAndSetsAllAttributes() {
        final Map<String, Object> oldAttrs = new HashMap<>();
        oldAttrs.put(Sessions.AFTER_LOGIN_URL, "/trip/tripContacts.jsf?id=abc");
        oldAttrs.put(Sessions.DARK, Boolean.TRUE);
        final HttpSession oldSession = sessionOver(oldAttrs);
        final Map<String, Object> newAttrs = new HashMap<>();
        final HttpSession newSession = sessionOver(newAttrs);
        final HttpServletRequest request = requestWith(oldSession, newSession);
        final Person.Id userId = Person.Id.from("someone");
        final Creds creds = new Creds("who@example.com", userId, "user", "pw", null);

        final HttpSession result = Sessions.establish(request, "who@example.com", creds);

        Assert.assertSame(result, newSession);
        Mockito.verify(oldSession).invalidate();
        Assert.assertEquals(newAttrs.get(PersonCommands.ACTIVE_USER_ID), userId);
        Assert.assertEquals(newAttrs.get(PersonCommands.ACTIVE_USER_ROLE), "user");
        Assert.assertEquals(newAttrs.get(Sessions.LOGIN_EMAIL), "who@example.com");
        Assert.assertNull(newAttrs.get(Sessions.ADMIN_USER));
        Assert.assertEquals(newAttrs.get(Sessions.AFTER_LOGIN_URL), "/trip/tripContacts.jsf?id=abc");
        Assert.assertEquals(newAttrs.get(Sessions.DARK), Boolean.TRUE);
    }

    @Test
    public void establishSetsAdminUserForAdmins() {
        final Map<String, Object> newAttrs = new HashMap<>();
        final HttpSession newSession = sessionOver(newAttrs);
        final HttpServletRequest request = requestWith(null, newSession);
        final Person.Id adminId = Person.Id.from("the-admin");
        final Creds creds = new Creds("admin@example.com", adminId, "admin", "pw", null);

        Sessions.establish(request, "admin@example.com", creds);

        Assert.assertEquals(newAttrs.get(Sessions.ADMIN_USER), adminId);
        Assert.assertEquals(newAttrs.get(PersonCommands.ACTIVE_USER_ROLE), "admin");
    }

    @Test
    public void establishSurvivesAnUnreadableOldSession() {
        final HttpSession oldSession = Mockito.mock(HttpSession.class);
        Mockito.when(oldSession.getAttribute(Mockito.anyString()))
                .thenThrow(new RuntimeException("stale serialized state"));
        final Map<String, Object> newAttrs = new HashMap<>();
        final HttpSession newSession = sessionOver(newAttrs);
        final HttpServletRequest request = requestWith(oldSession, newSession);
        final Creds creds = new Creds("who@example.com", Person.Id.from("someone"), "user", "pw", null);

        Sessions.establish(request, "who@example.com", creds);

        Mockito.verify(oldSession).invalidate();
        Assert.assertEquals(newAttrs.get(Sessions.LOGIN_EMAIL), "who@example.com");
        Assert.assertNull(newAttrs.get(Sessions.AFTER_LOGIN_URL));
    }

    @Test
    public void establishSurvivesAnAlreadyInvalidOldSession() {
        final HttpSession oldSession = sessionOver(new HashMap<>());
        Mockito.doThrow(new IllegalStateException("already invalidated")).when(oldSession).invalidate();
        final HttpSession newSession = sessionOver(new HashMap<>());
        final HttpServletRequest request = requestWith(oldSession, newSession);
        final Creds creds = new Creds("who@example.com", Person.Id.from("someone"), "user", "pw", null);

        final HttpSession result = Sessions.establish(request, "who@example.com", creds);

        Assert.assertSame(result, newSession);
    }

    @Test
    public void logoutInvalidatesTheSession() {
        final HttpSession session = sessionOver(new HashMap<>());
        final HttpServletRequest request = requestWith(session, null);

        Sessions.logout(request);

        Mockito.verify(session).invalidate();
    }

    /** Off a bound request the site is SHARED: one ordinary (processor-routed) deletion, no raw sweep. */
    @Test
    public void logoutWithAResponseExpiresTheSessionCookieOnASharedHost() {
        final HttpSession session = sessionOver(new HashMap<>());
        final HttpServletRequest request = requestWith(session, null);
        Mockito.when(request.getContextPath()).thenReturn("");
        Mockito.when(request.isSecure()).thenReturn(true);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Sessions.logout(request, response);

        Mockito.verify(session).invalidate();
        final ArgumentCaptor<Cookie> dead = ArgumentCaptor.forClass(Cookie.class);
        Mockito.verify(response).addCookie(dead.capture());
        Assert.assertEquals(dead.getValue().getName(), Sessions.SESSION_COOKIE);
        Assert.assertEquals(dead.getValue().getMaxAge(), 0);
        Assert.assertEquals(dead.getValue().getPath(), "/");
        Assert.assertTrue(dead.getValue().getSecure(), "Secure tracks the request");
        Assert.assertTrue(dead.getValue().isHttpOnly());
        Assert.assertNull(dead.getValue().getDomain(), "the app never stamps a domain; the container does");
        Mockito.verify(response, Mockito.never()).addHeader(Mockito.anyString(), Mockito.anyString());
    }

    /** On an org-site host the raw host-only deletions go out too (the pre-SSO shadow cookies). */
    @Test
    public void logoutOnAnOrgSiteAlsoSweepsTheHostOnlyShadowCookies() throws Exception {
        final HttpSession session = sessionOver(new HashMap<>());
        final HttpServletRequest request = requestWith(session, null);
        Mockito.when(request.getContextPath()).thenReturn(null);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        final RequestContext onAcme = RequestContext.of(AuditActor.system(), null,
                SiteContext.org(Organization.Id.from("o"), "acme", "acme.unitetrip.com"));

        ScopedValue.where(RequestContext.SCOPE, onAcme).call(() -> logoutReturningNull(request, response));

        Mockito.verify(session).invalidate();
        Mockito.verify(response).addCookie(Mockito.any(Cookie.class));
        Mockito.verify(response).addHeader("Set-Cookie", "JSESSIONID=; Path=/; Max-Age=0; HttpOnly");
        Mockito.verify(response).addHeader("Set-Cookie", "trip_remember=; Path=/; Max-Age=0; HttpOnly");
    }

    private static Void logoutReturningNull(final HttpServletRequest request, final HttpServletResponse response) {
        Sessions.logout(request, response);
        return null;
    }

    @Test
    public void logoutWithoutAResponseOnlyInvalidates() {
        final HttpSession session = sessionOver(new HashMap<>());
        Sessions.logout(requestWith(session, null), null);
        Mockito.verify(session).invalidate();
    }

    @Test
    public void logoutIsIdempotent() {
        final HttpServletRequest none = Mockito.mock(HttpServletRequest.class);
        Mockito.when(none.getSession(false)).thenReturn(null);
        Sessions.logout(none); // no session at all: nothing thrown

        final HttpSession session = sessionOver(new HashMap<>());
        Mockito.doThrow(new IllegalStateException("already invalidated")).when(session).invalidate();
        Sessions.logout(requestWith(session, null)); // races another logout: nothing thrown
    }

    @Test
    public void viewAsPushClearsEverythingButKeepsIdentityAnchors() {
        final Person.Id adminId = Person.Id.from("the-admin");
        final Person.Id targetId = Person.Id.from("the-user");
        final Map<String, Object> attrs = adminSession(adminId);
        attrs.put("resumeRegTrip", "trip-1");
        attrs.put("regDraft:trip-1", "draft");
        attrs.put(PersonCommands.PAY_FOR, "someone-else");
        attrs.put(Sessions.CODE_LOGIN, Boolean.TRUE);
        attrs.put(Sessions.AFTER_LOGIN_URL, "/somewhere.jsf");
        attrs.put(PersonCommands.ACTING_FOR, Person.Id.from("kid"));
        final HttpSession session = sessionOver(attrs);

        Assert.assertTrue(Sessions.pushViewAs(session, targetId));

        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ID), targetId);
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ROLE), "user");
        Assert.assertEquals(attrs.get(Sessions.ADMIN_USER), adminId, "Back-to-Admin must keep rendering");
        Assert.assertEquals(attrs.get(Sessions.LOGIN_EMAIL), "admin@example.com",
                "audit attribution stays with the real human");
        Assert.assertEquals(attrs.get(Sessions.DARK), Boolean.TRUE);
        Assert.assertNull(attrs.get("resumeRegTrip"), "the resume banner must not follow the switch");
        Assert.assertNull(attrs.get("regDraft:trip-1"));
        Assert.assertNull(attrs.get(PersonCommands.PAY_FOR), "a payment target must never leak");
        Assert.assertNull(attrs.get(Sessions.CODE_LOGIN), "the one-shot password grant must never leak");
        Assert.assertNull(attrs.get(Sessions.AFTER_LOGIN_URL));
        Assert.assertNull(attrs.get(PersonCommands.ACTING_FOR));
        Assert.assertNotNull(attrs.get(Sessions.VIEW_AS_STACK));
    }

    @Test
    public void viewAsPushIsRefusedWithoutAnAdminSession() {
        final Map<String, Object> attrs = new HashMap<>();
        attrs.put(PersonCommands.ACTIVE_USER_ID, Person.Id.from("someone"));
        attrs.put("resumeRegTrip", "trip-1");
        final HttpSession session = sessionOver(attrs);

        Assert.assertFalse(Sessions.pushViewAs(session, Person.Id.from("target")));
        Assert.assertEquals(attrs.get("resumeRegTrip"), "trip-1", "a refused push must change nothing");
        Assert.assertFalse(Sessions.pushViewAs(session, null));
        Assert.assertFalse(Sessions.pushViewAs(null, Person.Id.from("target")));
    }

    @Test
    public void viewAsPopDropsViewedStateAndRestoresTheSnapshot() {
        final Person.Id adminId = Person.Id.from("the-admin");
        final Map<String, Object> attrs = adminSession(adminId);
        attrs.put("resumeRegTrip", "admins-own-trip");
        final HttpSession session = sessionOver(attrs);
        Assert.assertTrue(Sessions.pushViewAs(session, Person.Id.from("the-user")));
        // State picked up WHILE impersonating must not follow the admin back.
        attrs.put("resumeRegTrip", "users-trip");
        attrs.put("regDraft:users-trip", "users-draft");

        Assert.assertTrue(Sessions.popViewAs(session));

        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ID), adminId);
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ROLE), "admin");
        Assert.assertEquals(attrs.get("resumeRegTrip"), "admins-own-trip", "the snapshot is restored");
        Assert.assertNull(attrs.get("regDraft:users-trip"), "viewed-as state is wiped on the way back");
        Assert.assertNull(attrs.get(Sessions.VIEW_AS_STACK), "an emptied stack is removed");
    }

    @Test
    public void viewAsPopWithoutAPushDoesNothing() {
        final Map<String, Object> attrs = adminSession(Person.Id.from("the-admin"));
        Assert.assertFalse(Sessions.popViewAs(sessionOver(attrs)));
        Assert.assertFalse(Sessions.popViewAs(null));
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ROLE), "admin");
    }

    @Test
    public void nestedViewAsUnwindsOneLevelPerPop() {
        final Person.Id adminId = Person.Id.from("the-admin");
        final Map<String, Object> attrs = adminSession(adminId);
        final HttpSession session = sessionOver(attrs);
        Assert.assertTrue(Sessions.pushViewAs(session, Person.Id.from("first")));
        Assert.assertTrue(Sessions.pushViewAs(session, Person.Id.from("second")));

        Assert.assertTrue(Sessions.popViewAs(session));
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ID), Person.Id.from("first"));
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ROLE), "user");

        Assert.assertTrue(Sessions.popViewAs(session));
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ID), adminId);
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ROLE), "admin");
        Assert.assertFalse(Sessions.popViewAs(session));
    }

    /** The attribute set a real admin login leaves behind, plus dark mode. */
    private static Map<String, Object> adminSession(final Person.Id adminId) {
        final Map<String, Object> attrs = new HashMap<>();
        attrs.put(PersonCommands.ACTIVE_USER_ID, adminId);
        attrs.put(PersonCommands.ACTIVE_USER_ROLE, "admin");
        attrs.put(Sessions.LOGIN_EMAIL, "admin@example.com");
        attrs.put(Sessions.ADMIN_USER, adminId);
        attrs.put(Sessions.DARK, Boolean.TRUE);
        return attrs;
    }

    /** A session whose attributes live in the given map, so tests can assert on the map directly. */
    private static HttpSession sessionOver(final Map<String, Object> attrs) {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(Mockito.anyString()))
                .thenAnswer(call -> attrs.get(call.<String>getArgument(0)));
        Mockito.doAnswer(call -> attrs.put(call.getArgument(0), call.getArgument(1)))
                .when(session).setAttribute(Mockito.anyString(), Mockito.any());
        Mockito.doAnswer(call -> attrs.remove(call.<String>getArgument(0)))
                .when(session).removeAttribute(Mockito.anyString());
        Mockito.when(session.getAttributeNames())
                .thenAnswer(call -> Collections.enumeration(new ArrayList<>(attrs.keySet())));
        return session;
    }

    private static HttpServletRequest requestWith(final HttpSession existing, final HttpSession fresh) {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getSession(false)).thenReturn(existing);
        Mockito.when(request.getSession(true)).thenReturn(fresh);
        return request;
    }
}
