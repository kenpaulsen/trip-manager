package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.chat.InviteLinks;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** A short link becomes a redirect to the landing page with the full trip id; anything else is a 404. */
public class InviteLinkServletTest {

    private static final String TRIP = "30692184-e927-4398-b8f9-a6e1eda1ea7b";

    private InviteLinkServlet servlet;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeMethod
    public void setUp() {
        servlet = new InviteLinkServlet();
        request = Mockito.mock(HttpServletRequest.class);
        response = Mockito.mock(HttpServletResponse.class);
        Mockito.when(request.getContextPath()).thenReturn("");
    }

    @Test
    public void aShortLinkRedirectsToTheLandingPage() throws Exception {
        Mockito.when(request.getPathInfo()).thenReturn("/" + InviteLinks.compactTripId(TRIP) + "/sel.val");

        servlet.doGet(request, response);

        Mockito.verify(response).sendRedirect("/trip/chatInvite.jsf?trip=" + TRIP + "&token=sel.val");
        Mockito.verify(response).setHeader("Cache-Control", "no-store");
        Mockito.verify(request, Mockito.never()).getSession();
        Mockito.verify(request, Mockito.never()).getSession(ArgumentMatchers.anyBoolean());
    }

    @Test
    public void theContextPathIsKept() throws Exception {
        Mockito.when(request.getContextPath()).thenReturn("/app");
        Mockito.when(request.getPathInfo()).thenReturn("/faketrip/sel.val");

        servlet.doGet(request, response);

        Mockito.verify(response).sendRedirect("/app/trip/chatInvite.jsf?trip=faketrip&token=sel.val");
    }

    @Test
    public void anythingElseIs404() throws Exception {
        for (final String path : new String[] {null, "/", "/onlytrip", "/trip/notoken", "/trip/s.v/extra"}) {
            Mockito.reset(response);
            Mockito.when(request.getPathInfo()).thenReturn(path);

            servlet.doGet(request, response);

            Mockito.verify(response).sendError(404);
            Mockito.verify(response, Mockito.never()).sendRedirect(ArgumentMatchers.anyString());
        }
        Assert.assertTrue(true);
    }
}
