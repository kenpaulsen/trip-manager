package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.paulsens.trip.chat.InviteLinks;

/**
 * The short chat invite link, {@code /i/{trip}/{selector}.{validator}} (see {@link InviteLinks}): expands
 * the packed trip id and redirects to the landing page the long link always pointed at, on the same host.
 * The landing page, the login round-trip and the redemption itself stay in one place; this is only the
 * short spelling. Anything that is not a well-formed short link is a plain 404, and nothing here touches a
 * session: the visitor is usually signed out, and the landing page handles that.
 *
 * <p>A redirect rather than a forward so the browser lands on the real page URL: the login round-trip
 * stashes the landing URL as {@code ?to=}, and that must be a URL the page can be reloaded on.</p>
 */
public class InviteLinkServlet extends HttpServlet {

    @Override
    protected void doGet(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        final var link = InviteLinks.parse(request.getPathInfo());
        if (link.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        final InviteLinks.Parsed parsed = link.get();
        response.sendRedirect(request.getContextPath() + InviteLinks.landingPath(parsed.tripId(), parsed.token()));
    }
}
