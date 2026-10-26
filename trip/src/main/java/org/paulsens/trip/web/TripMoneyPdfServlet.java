package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.paulsens.trip.action.BudgetCommands;
import org.paulsens.trip.action.BudgetPdf;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.ExpenseCommands;
import org.paulsens.trip.action.ExpenseReportPdf;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Trip;

/**
 * The trip's money documents as PDF downloads: {@code GET /reports/budget?trip=<id>} (the budget) and
 * {@code GET /reports/expense-report?trip=<id>[&receipts=true]} (the expense report, with its receipts appended
 * when asked). Linked from the two pages' "Download PDF" buttons.
 *
 * <p>The same rules as {@code RoomInvoicePdfServlet}: the URLs do NOT end in {@code .pdf} ({@code PdfRedirectFilter}
 * sends those to the media CDN in production), the file name travels in {@code Content-Disposition}, a visitor
 * not signed in goes to login and comes back, and the answer is {@code private, no-store}. The gate is the
 * pages' own ({@code TripFinanceGate}, through the two beans built on THIS request's caller): an unknown trip and
 * a caller who may not read it are both 404, so the URL does not confirm that a trip exists.
 */
public class TripMoneyPdfServlet extends HttpServlet {

    static final String LOGIN = "/account/login.jsf?to=";
    static final String BUDGET_PATH = "/reports/budget";
    static final String EXPENSES_PATH = "/reports/expense-report";

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final Caller caller = callerOf(req);
        if (!caller.isAuthenticated()) {
            final String back = req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
            resp.sendRedirect(req.getContextPath() + LOGIN + URLEncoder.encode(back, StandardCharsets.UTF_8));
            return;
        }
        final String tripId = req.getParameter("trip");
        final String path = req.getServletPath();
        final byte[] pdf;
        final String fileName;
        if (BUDGET_PATH.equals(path)) {
            pdf = new BudgetCommands(() -> caller).budgetPdf(tripId);
            fileName = BudgetPdf.fileName(titleOf(tripId));
        } else if (EXPENSES_PATH.equals(path)) {
            pdf = new ExpenseCommands(() -> caller).reportPdf(tripId, "true".equals(req.getParameter("receipts")));
            fileName = ExpenseReportPdf.fileName(titleOf(tripId));
        } else {
            pdf = null;
            fileName = null;
        }
        if (pdf == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        resp.setContentType("application/pdf");
        resp.setContentLength(pdf.length);
        resp.setHeader("Content-Disposition", RoomInvoicePdfServlet.disposition(fileName));
        resp.setHeader("Cache-Control", "private, no-store");
        resp.getOutputStream().write(pdf);
    }

    /** The trip's title for the file name; only asked once the bean has already let the caller read it. */
    private static String titleOf(final String tripId) {
        return DAO.getInstance().getTrip(tripId, Cached.YES).map(Trip::getTitle).orElse(null);
    }

    /** Seam for tests; production resolves the caller from the request's session. */
    protected Caller callerOf(final HttpServletRequest req) {
        return Caller.of(req.getSession(false));
    }
}
