package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Function;
import org.paulsens.trip.action.CheckinCommands;
import org.paulsens.trip.api.Beans;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.security.TicketCodes;
import org.paulsens.trip.util.TicketQr;

/**
 * A ticket's QR code as a PNG, {@code /ticket-qr?trip=&t=} ({@code docs/checkin.md}): the image the confirmation and
 * approval emails embed, because mail clients render no inline SVG and the ticket page's QR is inline SVG. The
 * image encodes exactly what the page's QR encodes, {@code SiteUrls.ticketUrl} for the ticket presented, so a
 * scan of the email lands where a scan of the page does. Public by URL like the ticket page itself: the code IS
 * the secret (an exact, constant-time match through {@link CheckinCommands#byTicket}; a prefix or a guess is a
 * 404), and nothing here touches a session, since the recipient's mail client fetches it signed out. Any failure
 * is a plain 404 with no body to tell a guesser apart from a typo.
 */
public class TicketQrServlet extends HttpServlet {

    private transient CheckinCommands checkin;
    private transient Function<String, Optional<Trip>> trips;

    /** Test seam: the bean that resolves tickets and the trip lookup. */
    void setCheckin(final CheckinCommands checkin, final Function<String, Optional<Trip>> trips) {
        this.checkin = checkin;
        this.trips = trips;
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final String tripId = req.getParameter("trip");
        final String code = TicketCodes.normalize(req.getParameter("t"));
        final byte[] png = (tripId == null || tripId.isBlank() || code == null) ? null : pngFor(tripId, code);
        if (png == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        resp.setContentType("image/png");
        resp.setContentLength(png.length);
        // Private: the URL names a bearer secret, so no shared cache may keep it; a day, because the code is fixed.
        resp.setHeader("Cache-Control", "private, max-age=86400");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.getOutputStream().write(png);
    }

    /** The PNG for a ticket this trip holds, or null when there is no such trip, ticket or URL. */
    private byte[] pngFor(final String tripId, final String code) {
        final Trip trip = trips().apply(tripId).orElse(null);
        final Registration reg = trip == null ? null : checkin().byTicket(tripId, code);
        if (reg == null) {
            return null;
        }
        final String url = checkin().ticketUrl(trip, reg.getTicketCode());
        return url.isEmpty() ? null : TicketQr.png(url);
    }

    private CheckinCommands checkin() {
        CheckinCommands bean = checkin;
        if (bean == null) {
            // Resolved lazily so tests can inject; Beans.get selects with Any (the @FacesConfig trap).
            bean = Beans.get(CheckinCommands.class);
            checkin = bean;
        }
        return bean;
    }

    private Function<String, Optional<Trip>> trips() {
        Function<String, Optional<Trip>> lookup = trips;
        if (lookup == null) {
            lookup = id -> DAO.getInstance().getTrip(id, Cached.YES);
            trips = lookup;
        }
        return lookup;
    }
}
