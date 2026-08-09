package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import org.mockito.Mockito;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
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

    @Test
    public void logoutIsIdempotent() {
        final HttpServletRequest none = Mockito.mock(HttpServletRequest.class);
        Mockito.when(none.getSession(false)).thenReturn(null);
        Sessions.logout(none); // no session at all: nothing thrown

        final HttpSession session = sessionOver(new HashMap<>());
        Mockito.doThrow(new IllegalStateException("already invalidated")).when(session).invalidate();
        Sessions.logout(requestWith(session, null)); // races another logout: nothing thrown
    }

    /** A session whose attributes live in the given map, so tests can assert on the map directly. */
    private static HttpSession sessionOver(final Map<String, Object> attrs) {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(Mockito.anyString()))
                .thenAnswer(call -> attrs.get(call.<String>getArgument(0)));
        Mockito.doAnswer(call -> attrs.put(call.getArgument(0), call.getArgument(1)))
                .when(session).setAttribute(Mockito.anyString(), Mockito.any());
        return session;
    }

    private static HttpServletRequest requestWith(final HttpSession existing, final HttpSession fresh) {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getSession(false)).thenReturn(existing);
        Mockito.when(request.getSession(true)).thenReturn(fresh);
        return request;
    }
}
