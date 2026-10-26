package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.LodgingAccess;
import org.paulsens.trip.action.LodgingAccessCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.action.ReportCommands;
import org.paulsens.trip.action.RoomInvoiceHeading;
import org.paulsens.trip.action.RoomInvoicePdf;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.api.Beans;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Trip;

/**
 * {@code GET /reports/room-invoice?trip=<id>}: the Room Invoice as a PDF download, one page per accommodation
 * ({@link RoomInvoicePdf}). Linked from {@code admin/reports/roomInvoice.xhtml}.
 *
 * <p>The URL deliberately does NOT end in {@code .pdf}: {@code PdfRedirectFilter} answers every such request in
 * production with a 301 to the media CDN (and stands aside locally, so a {@code .pdf} URL would pass every
 * local test and break only once deployed). The file name travels in {@code Content-Disposition} instead.
 *
 * <p>Gated like the page: a FINANCE viewer (site administrator, the global {@code viewFinances}, or
 * {@code tripFinView} on this trip) gets the whole invoice, and a Hotel Operator of a hotel in play on the trip
 * ({@link LodgingAccess#isOperatorHere()}) gets their hotel's part, built by
 * {@link ReportCommands#roomInvoiceFor}. An invoice REFUSED for want of a hotel cost
 * ({@code RoomInvoice.blocked}) is answered {@code 409 Conflict} as {@code text/plain} with
 * {@link ReportCommands#blockedMessage} in the reader's words, never as a PDF with a hole in it. A visitor who
 * is not signed in goes to login and comes back here; a signed-in refusal is a plain 403, since there is no
 * page to put a growl on.
 *
 * <p>The access object is computed by a {@link LodgingAccessCommands} built on THIS request's
 * {@link Caller}: the CDI bean's default caller source is {@code Caller.current()}, which reads the
 * FacesContext, and a plain servlet has none (the memo it keeps in the Faces request map is irrelevant here
 * for the same reason). The bean has no injected collaborators, so constructing one is what the pages get.
 */
public class RoomInvoicePdfServlet extends HttpServlet {

    static final String LOGIN = "/account/login.jsf?to=";
    /** The GLOBAL finance-view privilege; the pages name it as a literal too (there is no constant for it). */
    static final String VIEW_FINANCES = "viewFinances";

    /** The invoice as one caller may see it: {@link ReportCommands#roomInvoiceFor}'s shape. */
    @FunctionalInterface
    interface InvoiceSource {
        ReportCommands.RoomInvoice invoice(String tripId, LodgingAccess access, boolean finance);
    }

    private transient Function<String, Trip> trips;
    private transient InvoiceSource invoices;

    /** The test seam: a servlet container builds this with the no-arg constructor, and Beans.get needs one. */
    void setSources(final Function<String, Trip> trips, final InvoiceSource invoices) {
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
        final LodgingAccess access = new LodgingAccessCommands(() -> caller).forTrip(tripId);
        // Internal = a finance viewer or the trip's own lodging admin (the bill is the hotel's cost side, which
        // is exactly what they reconcile), as on the page: the flag picks the wording of a 409 refusal.
        final boolean internal = caller.has(VIEW_FINANCES) || caller.has(PrivilegeCommands.TRIP_FIN_VIEW, tripId)
                || access.isInternal();
        if (!internal && !access.isOperatorHere()) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        final ReportCommands.RoomInvoice invoice = invoices().invoice(tripId, access, internal);
        if (invoice.isBlocked()) {
            final byte[] why = ReportCommands.blockedMessage(invoice.getMissingCost(), !internal)
                    .getBytes(StandardCharsets.UTF_8);
            resp.setStatus(HttpServletResponse.SC_CONFLICT);
            resp.setContentType("text/plain;charset=UTF-8");
            resp.setContentLength(why.length);
            resp.setHeader("Cache-Control", "private, no-store");
            resp.getOutputStream().write(why);
            return;
        }
        final byte[] pdf = RoomInvoicePdf.render(new RoomInvoiceHeading(trip.getTitle(), orgNameOf(trip)), invoice,
                internal);
        resp.setContentType("application/pdf");
        resp.setContentLength(pdf.length);
        resp.setHeader("Content-Disposition", disposition(RoomInvoicePdf.fileName(trip.getTitle())));
        // Money: never cached by a browser or anything between.
        resp.setHeader("Cache-Control", "private, no-store");
        resp.getOutputStream().write(pdf);
    }

    /** The organization the hotel invoices, as the page prints it ({@code theTrip.organization.name}); "" if gone. */
    static String orgNameOf(final Trip trip) {
        final Organization org = trip.getOrganization();
        return (org == null || org.getName() == null) ? "" : org.getName();
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

    private InvoiceSource invoices() {
        if (invoices == null) {
            invoices = Beans.get(ReportCommands.class)::roomInvoiceFor;
        }
        return invoices;
    }
}
