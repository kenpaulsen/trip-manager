package org.paulsens.trip.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.PersonCommands;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The 404 split: signed-in users keep the landing-page forward, anonymous requests get the cheap static
 * page. The load-bearing assertions are the negatives — no session is ever created, and no JSF forward
 * happens for a guest — because those are what made scanner floods expensive (2026-08-19).
 */
public class NotFoundServletTest {

    private NotFoundServlet servlet;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private RequestDispatcher dispatcher;
    private StringWriter body;

    @BeforeMethod
    public void setUp() throws Exception {
        servlet = new NotFoundServlet();
        request = Mockito.mock(HttpServletRequest.class);
        response = Mockito.mock(HttpServletResponse.class);
        dispatcher = Mockito.mock(RequestDispatcher.class);
        body = new StringWriter();
        Mockito.when(request.getRequestDispatcher("/index.jsf")).thenReturn(dispatcher);
        Mockito.when(response.getWriter()).thenReturn(new PrintWriter(body, true));
    }

    @Test
    public void aSignedInUserIsForwardedToTheLandingPageAsBefore() throws Exception {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(request.getSession(false)).thenReturn(session);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ID)).thenReturn("user-1");

        servlet.service(request, response);

        Mockito.verify(dispatcher).forward(request, response);
        Assert.assertEquals(body.toString(), "", "a signed-in user must get the forward, not the static page");
    }

    @Test
    public void aGuestGetsTheStaticPageWithAHomeLinkAndNoSession() throws Exception {
        Mockito.when(request.getSession(false)).thenReturn(null);
        Mockito.when(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(404);

        servlet.service(request, response);

        Mockito.verify(response).setStatus(404);
        Mockito.verify(response).setContentType("text/html");
        final String html = body.toString();
        Assert.assertTrue(html.contains("href=\"/\""), "the page must link home");
        Assert.assertTrue(html.contains("Page not found"), "well-formatted 404 copy expected; got: " + html);
        Assert.assertTrue(html.contains("<!DOCTYPE html>"), "full standalone page, no template");
        Assert.assertFalse(html.contains("src="), "no external references -- they would 404 and re-enter");
        // The negatives that keep scanner floods cheap:
        Mockito.verify(request, Mockito.never()).getSession();
        Mockito.verify(request, Mockito.never()).getSession(true);
        Mockito.verify(request, Mockito.never()).getRequestDispatcher(ArgumentMatchers.anyString());
    }

    /** A session that exists but carries no login (e.g. a scanner that picked up a cookie) is a guest. */
    @Test
    public void anAnonymousSessionIsStillAGuest() throws Exception {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(request.getSession(false)).thenReturn(session);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ID)).thenReturn(null);

        servlet.service(request, response);

        Mockito.verify(request, Mockito.never()).getRequestDispatcher(ArgumentMatchers.anyString());
        Assert.assertTrue(body.toString().contains("href=\"/\""));
    }

    /** Error dispatch preserves the original method; a scanner's DELETE must get this page, not a 405 body. */
    @Test
    public void nonGetMethodsAreServedTheSamePage() throws Exception {
        Mockito.when(request.getSession(false)).thenReturn(null);
        Mockito.when(request.getMethod()).thenReturn("DELETE");
        Mockito.when(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(405);

        servlet.service(request, response);

        Mockito.verify(response).setStatus(405);
        Assert.assertTrue(body.toString().contains("Method not allowed"));
    }

    /** A direct hit on /notFound (no error dispatch, no status attribute) reads as a plain 404. */
    @Test
    public void aDirectHitDefaultsTo404() throws Exception {
        Mockito.when(request.getSession(false)).thenReturn(null);
        Mockito.when(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE)).thenReturn(null);

        servlet.service(request, response);

        Mockito.verify(response).setStatus(404);
    }
}
