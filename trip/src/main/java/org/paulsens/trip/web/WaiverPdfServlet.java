package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.WaiverCommands;
import org.paulsens.trip.action.WaiverPdf;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.WaiverAcceptance;

/**
 * A signed document as a PDF download: {@code GET /waivers/signed?trip=&person=&waiver=&version=}, linked from
 * the registration page and the admin registrations page ({@code docs/waivers.md}).
 *
 * <p>The {@link TripMoneyPdfServlet} rules: the URL does NOT end in {@code .pdf} ({@code PdfRedirectFilter} sends
 * those to the media CDN in production), the file name travels in {@code Content-Disposition}, a visitor not
 * signed in goes to login and comes back, the answer is {@code private, no-store}, and the gate is the bean's
 * ({@link WaiverCommands#canReadRecords}): no signature and no right to read it are both 404.
 */
public class WaiverPdfServlet extends SignedInServlet {

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final Caller caller = callerOf(req);
        if (!caller.isAuthenticated()) {
            resp.sendRedirect(loginUrl(req));
            return;
        }
        final String tripId = req.getParameter("trip");
        final String person = req.getParameter("person");
        final String waiverId = req.getParameter("waiver");
        final int version = parseVersion(req.getParameter("version"));
        final Person.Id subject = (person == null || person.isBlank()) ? null : Person.Id.from(person);
        final byte[] pdf = (subject == null || version <= 0) ? null
                : new WaiverCommands(() -> caller).signedPdf(tripId, subject, waiverId, version);
        if (pdf == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        writeNoStore(resp, "application/pdf",
                ContentDisposition.attachment(fileName(tripId, subject, waiverId, version)), pdf);
    }

    /** The file name from the signature row itself; only asked once the bean has let the caller read it. */
    private static String fileName(final String tripId, final Person.Id subject, final String waiverId,
            final int version) {
        final WaiverAcceptance row = DAO.getInstance().getWaiverAcceptances(tripId, subject).stream()
                .filter(a -> a.getWaiverId().equals(waiverId) && a.getVersion() == version)
                .findFirst().orElse(null);
        return (row == null) ? "Signed document.pdf" : WaiverPdf.fileName(row.getTitle(), row.getSubjectName());
    }

    private static int parseVersion(final String value) {
        try {
            return (value == null) ? 0 : Integer.parseInt(value.trim());
        } catch (final NumberFormatException ex) {
            return 0;
        }
    }
}
