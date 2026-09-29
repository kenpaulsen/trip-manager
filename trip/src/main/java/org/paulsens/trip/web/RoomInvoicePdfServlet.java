package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.action.ReportCommands;
import org.paulsens.trip.action.RoomInvoicePdf;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.api.Beans;
import org.paulsens.trip.model.Trip;

/**
 * {@code GET /reports/room-invoice?trip=<id>}: the Room Invoice as a PDF download, one page per accommodation
 * ({@link RoomInvoicePdf}). Linked from {@code admin/reports/roomInvoice.xhtml}.
 *
 * <p>The URL deliberately does NOT end in {@code .pdf}: {@code PdfRedirectFilter} answers every such request in
 * production with a 301 to the media CDN (and stands aside locally, so a {@code .pdf} URL would pass every
 * local test and break only once deployed). The file name travels in {@code Content-Disposition} instead.
 *
 * <p>Gated like the page: money, so a site administrator, the global {@code viewFinances}, or
 * {@code tripFinView} on this trip. A visitor who is not signed in goes to login and comes back here; a
 * signed-in refusal is a plain 403, since there is no page to put a growl on.
 */
public class RoomInvoicePdfServlet extends HttpServlet {

    static final String LOGIN = "/account/login.jsf?to=";
    /** The GLOBAL finance-view privilege; the pages name it as a literal too (there is no constant for it). */
    static final String VIEW_FINANCES = "viewFinances";

    private transient Function<String, Trip> trips;
    private transient Function<String, ReportCommands.RoomInvoice> invoices;

    /** The test seam: a servlet container builds this with the no-arg constructor, and Beans.get needs one. */
    void setSources(final Function<String, Trip> trips, final Function<String, ReportCommands.RoomInvoice> invoices) {
        this.trips = trips;
        this.invoices = invoices;
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final Caller caller = Caller.of(req.getSession(false));
        final String tripId = req.getParameter("trip");
        if (!caller.isAuthenticated()) {
            final String back = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
            resp.sendRedirect(req.getContextPath() + LOGIN + URLEncoder.encode(back, StandardCharsets.UTF_8));
            return;
        }
        if (tripId == null || tripId.isBlank()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        // getTrip answers a blank trip for an unknown id or one this site does not serve.
        final Trip trip = trips().apply(tripId);
        if (!tripId.equals(trip.getId())) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!caller.has(VIEW_FINANCES) && !caller.has(PrivilegeCommands.TRIP_FIN_VIEW, tripId)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        final byte[] pdf = RoomInvoicePdf.render(trip.getTitle(), invoices().apply(tripId));
        resp.setContentType("application/pdf");
        resp.setContentLength(pdf.length);
        resp.setHeader("Content-Disposition", disposition(RoomInvoicePdf.fileName(trip.getTitle())));
        // Money: never cached by a browser or anything between.
        resp.setHeader("Cache-Control", "private, no-store");
        resp.getOutputStream().write(pdf);
    }

    /** RFC 6266: an ASCII fallback name, plus the real one percent-encoded for every current browser. */
    static String disposition(final String fileName) {
        final String ascii = fileName.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'");
        final String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    private Function<String, Trip> trips() {
        if (trips == null) {
            trips = Beans.get(TripCommands.class)::getTrip;
        }
        return trips;
    }

    private Function<String, ReportCommands.RoomInvoice> invoices() {
        if (invoices == null) {
            invoices = Beans.get(ReportCommands.class)::roomInvoice;
        }
        return invoices;
    }
}
