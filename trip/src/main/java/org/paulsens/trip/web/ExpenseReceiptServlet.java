package org.paulsens.trip.web;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.ExpenseCommands;
import org.paulsens.trip.action.ExpenseReceipts;
import org.paulsens.trip.action.ExpenseReceipts.Served;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.MediaItem;

/**
 * {@code GET /expense-receipts/{mediaId}[/thumb]}: one expense-report receipt, streamed to someone who may read
 * that trip's expense report ({@link ExpenseCommands#canViewExpenses}). Receipts are financial documents, so
 * this is the ONLY way out for one: they are never given a media-CDN URL, and the media library, its pickers
 * and the media API all treat a receipt as absent ({@code docs/expense-report.md}, "Receipts").
 *
 * <p>The path carries the row id and nothing else, so it never ends in {@code .pdf}: {@code PdfRedirectFilter}
 * 301s every such URL to the CDN in production (and stands aside locally, where a {@code .pdf} URL would pass
 * every test). The file name travels in {@code Content-Disposition}. A visitor who is not signed in goes to
 * login and comes back here; an unknown id, or a row that is not a receipt, is 404; a signed-in caller who may
 * not read the report gets 403. Bytes come from S3, or the local-mode store, through {@link ExpenseReceipts}.
 */
public class ExpenseReceiptServlet extends HttpServlet {

    static final String LOGIN = "/account/login.jsf?to=";

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
        final Caller caller = callerOf(req);
        if (!caller.isAuthenticated()) {
            final String back = req.getRequestURI();
            resp.sendRedirect(req.getContextPath() + LOGIN + URLEncoder.encode(back, StandardCharsets.UTF_8));
            return;
        }
        final String path = req.getPathInfo();
        final String[] parts = (path == null) ? new String[0] : path.substring(1).split("/");
        final boolean thumb = parts.length == 2 && "thumb".equals(parts[1]);
        if (parts.length == 0 || parts[0].isBlank() || parts.length > 2 || (parts.length == 2 && !thumb)) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        final MediaItem item = DAO.getInstance().getMedia(parts[0], Cached.YES).orElse(null);
        final String tripId = (item == null) ? null : ExpenseReceipts.tripOfSlot(item.getSlot());
        if (tripId == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (!new ExpenseCommands(() -> caller).canViewExpenses(tripId)) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        final Served served = new ExpenseReceipts(() -> caller).served(item, thumb).orElse(null);
        if (served == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        resp.setContentType(served.contentType());
        resp.setContentLength(served.bytes().length);
        resp.setHeader("Content-Disposition", inline(item.getTitle()));
        // Money paperwork: never kept by a browser or anything in between, and never sniffed into something else.
        resp.setHeader("Cache-Control", "private, no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.getOutputStream().write(served.bytes());
    }

    /** RFC 6266 {@code inline}: an ASCII fallback name plus the real one percent-encoded. */
    static String inline(final String title) {
        final String name = (title == null || title.isBlank()) ? "receipt" : title;
        final String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'");
        final String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "inline; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    /** Seam for tests; production resolves the caller from the request's session. */
    protected Caller callerOf(final HttpServletRequest req) {
        return Caller.of(req.getSession(false));
    }
}
