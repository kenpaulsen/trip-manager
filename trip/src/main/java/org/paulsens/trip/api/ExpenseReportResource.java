package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.action.ExpenseCommands;
import org.paulsens.trip.action.ExpenseReceipts;
import org.paulsens.trip.action.ExpenseReportPdf;
import org.paulsens.trip.action.ExpenseViews;
import org.paulsens.trip.action.ExpenseViews.ReceiptRow;
import org.paulsens.trip.action.PhotoUploadBean;
import org.paulsens.trip.api.dto.RotateRequest;
import org.paulsens.trip.media.QuarterTurn;
import org.paulsens.trip.model.MediaItem;

/**
 * A trip's expense report over REST, a thin wrapper of {@link ExpenseCommands} (the {@link BudgetResource}
 * shape): the same gates, validation and refusals as the page, never a second implementation. Receipts are
 * uploaded as RAW bodies like profile photos (the API servlet has no multipart-config and must not get one),
 * and every receipt URL in an answer is this app's gated {@code /expense-receipts/...}, absolute, never the
 * media CDN. See {@code docs/expense-report.md}.
 */
@Path("expenses")
@TripApi
public class ExpenseReportResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.EXPENSE_REPORT_V1;
    private static final String CSRF_MESSAGE = "Missing " + CSRF_HEADER + " header.";
    private static final String EDIT_REQUIRED = "Trip manager or finance admin required.";
    private static final String SETTLE_REQUIRED = "Finance admin required.";

    @Override
    protected String versionedType() {
        return V1;
    }

    private ExpenseCommands expenses() {
        return new ExpenseCommands(this::caller);
    }

    private ExpenseReceipts receipts() {
        return new ExpenseReceipts(this::caller);
    }

    /**
     * The whole report: {@code {header, currencies, lines, payments, receipts, summary}}, summary being the page's
     * computed figures and {@code receipts} every receipt with the line it belongs to (null = the report).
     */
    @GET
    @Path("trips/{tripId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(@PathParam("tripId") final String tripId) {
        requireTrip(tripId);
        final ExpenseCommands expenses = expenses();
        if (!expenses.canViewExpenses(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Finance viewer required.");
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("header", expenses.headerFormFor(tripId));
        out.put("currencies", expenses.currencyFormsFor(tripId));
        final List<ExpenseViews.ExpenseForm> lines = expenses.lineFormsFor(tripId);
        out.put("lines", lines);
        out.put("payments", expenses.paymentFormsFor(tripId));
        final List<Map<String, Object>> receipts = new ArrayList<>();
        addReceipts(receipts, expenses.receiptRowsFor(tripId, null), null);
        for (final ExpenseViews.ExpenseForm line : lines) {
            addReceipts(receipts, expenses.receiptRowsFor(tripId, line.getId()), line.getId());
        }
        out.put("receipts", receipts);
        out.put("summary", expenses.reportPage(tripId));
        return ok(out);
    }

    private void addReceipts(final List<Map<String, Object>> out, final List<ReceiptRow> rows, final String lineId) {
        for (final ReceiptRow row : rows) {
            final Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("id", row.getMediaId());
            receipt.put("lineId", lineId);
            receipt.put("title", row.getTitle());
            receipt.put("pdf", row.isPdf());
            receipt.put("url", absoluteUrl(row.getUrl()));
            receipt.put("thumbUrl", absoluteUrl(row.getThumbUrl()));
            out.add(receipt);
        }
    }

    /** Body: {@code {submittedBy, cashAdvance, cashAdvanceDetails, paymentInstructions}}. Answers {@code {ok:true}}. */
    @PUT
    @Path("trips/{tripId}/header")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response saveHeader(@PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        final ExpenseViews.HeaderForm form = new ExpenseViews.HeaderForm();
        form.setSubmittedBy(str(body, "submittedBy"));
        form.setCashAdvance(dbl(body, "cashAdvance"));
        form.setCashAdvanceDetails(str(body, "cashAdvanceDetails"));
        form.setPaymentInstructions(str(body, "paymentInstructions"));
        return expenses().saveHeader(tripId, form) ? ok(Map.of("ok", true))
                : error(400, ApiErrors.VALIDATION_FAILED, problemOr(form.getProblem(), "The report was not saved."));
    }

    /** Stamps the report submitted by the caller, now. 400 while it has no expenses. */
    @POST
    @Path("trips/{tripId}/submit")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response submit(@PathParam("tripId") final String tripId, @HeaderParam(CSRF_HEADER) final String csrf) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        return expenses().submit(tripId) ? ok(Map.of("ok", true))
                : error(400, ApiErrors.VALIDATION_FAILED, "The report was not submitted: add an expense first.");
    }

    /**
     * Emails the report to the trip organization's treasurer (the {@code finance.treasurer.email} setting) as
     * one PDF with its receipts. 400 when no treasurer is set or the send fails.
     */
    @POST
    @Path("trips/{tripId}/email")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response emailTreasurer(@PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        return expenses().emailTreasurer(tripId) ? ok(Map.of("ok", true))
                : error(400, ApiErrors.VALIDATION_FAILED, "The report was not emailed: no treasurer email is set "
                        + "for the trip's organization, or the send failed.");
    }

    /** The report as a PDF; {@code receipts=true} appends the receipts. */
    @GET
    @Path("trips/{tripId}/pdf")
    @Produces("application/pdf")
    public Response pdf(@PathParam("tripId") final String tripId, @QueryParam("receipts") final boolean receipts) {
        final org.paulsens.trip.model.Trip trip = requireTrip(tripId);
        final byte[] pdf = expenses().reportPdf(tripId, receipts);
        if (pdf == null) {
            return error(403, ApiErrors.FORBIDDEN, "Finance viewer required.");
        }
        return Response.ok(pdf, "application/pdf").header("Content-Disposition",
                attachmentDisposition(ExpenseReportPdf.fileName(trip.getTitle())))
                .header("Cache-Control", "private, no-store").build();
    }

    /** Takes the submit stamp back off. */
    @POST
    @Path("trips/{tripId}/unsubmit")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response unsubmit(@PathParam("tripId") final String tripId, @HeaderParam(CSRF_HEADER) final String csrf) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        return expenses().unsubmit(tripId) ? ok(Map.of("ok", true))
                : error(400, ApiErrors.VALIDATION_FAILED, "The submission was not withdrawn.");
    }

    /** Body: {@code {code, rate, note}}, rate = how many of the currency one US dollar buys. Answers {@code {code}}. */
    @POST
    @Path("trips/{tripId}/currencies")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response createCurrency(@PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        return saveCurrency(tripId, str(body, "code"), false, csrf, body);
    }

    /** Changes a currency's rate or note; body {@code {rate, note}}. */
    @PUT
    @Path("trips/{tripId}/currencies/{code}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response updateCurrency(@PathParam("tripId") final String tripId, @PathParam("code") final String code,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        return saveCurrency(tripId, code, true, csrf, body);
    }

    /** Removes a currency no expense uses; 400 while one does. */
    @DELETE
    @Path("trips/{tripId}/currencies/{code}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response deleteCurrency(@PathParam("tripId") final String tripId, @PathParam("code") final String code,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        return expenses().deleteCurrency(tripId, code) ? ok(Map.of("ok", true))
                : error(400, ApiErrors.VALIDATION_FAILED, "The currency is in use and was not removed.");
    }

    /**
     * Body: {@code {date, category, description, currency, amount, budgetLineId, note, paidStatus}}: {@code date}
     * an ISO date or null ("Various"), {@code amount} in the line's own currency, {@code currency} USD or one the
     * report has, or a new code together with {@code rate} to add it in the same save, {@code paidStatus}
     * NOT_PAID (the default), ORG or REIMBURSED. Answers {@code {id}}.
     */
    @POST
    @Path("trips/{tripId}/lines")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response createLine(@PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        return saveLine(tripId, null, csrf, body);
    }

    /** Edits an expense; body as {@link #createLine}. Its receipts stay with it. */
    @PUT
    @Path("trips/{tripId}/lines/{lineId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response updateLine(@PathParam("tripId") final String tripId, @PathParam("lineId") final String lineId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        return saveLine(tripId, lineId, csrf, body);
    }

    /** Deletes an expense and its receipts. Deleting one that is already gone succeeds. */
    @DELETE
    @Path("trips/{tripId}/lines/{lineId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response deleteLine(@PathParam("tripId") final String tripId, @PathParam("lineId") final String lineId,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        return expenses().deleteLine(tripId, lineId) ? ok(Map.of("ok", true))
                : error(400, ApiErrors.VALIDATION_FAILED, "The expense was not deleted.");
    }

    /**
     * Stores one receipt from a raw body (an image or a PDF; the bytes decide, not the declared type) on
     * {@code lineId}, or on the report itself without it. {@code name} is the file name to show. Answers
     * {@code {id, url, thumbUrl}}; 413 over the size cap; 400 for anything else refused.
     */
    @POST
    @Path("trips/{tripId}/receipts")
    @Consumes({"image/*", "application/pdf", MediaType.APPLICATION_OCTET_STREAM})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response uploadReceipt(@PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            @HeaderParam("Content-Length") final Long contentLength,
            @QueryParam("lineId") final String lineId,
            @QueryParam("name") final String name,
            final InputStream body) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        final Optional<byte[]> bytes;
        try {
            bytes = readUpload(body, contentLength, PhotoUploadBean.MAX_UPLOAD_BYTES);
        } catch (final IOException ex) {
            return error(400, ApiErrors.BAD_REQUEST, "The upload could not be read.");
        }
        if (bytes.isEmpty()) {
            return error(413, ApiErrors.PAYLOAD_TOO_LARGE,
                    "A receipt can be at most " + (PhotoUploadBean.MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB.");
        }
        final ExpenseReceipts.Outcome outcome = receipts().store(tripId, lineId, bytes.get(), name);
        if (!outcome.ok()) {
            return error(400, ApiErrors.VALIDATION_FAILED, outcome.problem());
        }
        final String path = ExpenseReceipts.path(outcome.mediaId());
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", outcome.mediaId());
        out.put("url", absoluteUrl(path));
        out.put("thumbUrl", absoluteUrl(path + ExpenseReceipts.THUMB));
        return ok(out);
    }

    /** Unlinks and deletes one receipt of this report. */
    @DELETE
    @Path("trips/{tripId}/receipts/{receiptId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response deleteReceipt(@PathParam("tripId") final String tripId,
            @PathParam("receiptId") final String receiptId, @HeaderParam(CSRF_HEADER) final String csrf) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        final String problem = receipts().remove(tripId, receiptId);
        return (problem == null) ? ok(Map.of("ok", true)) : error(400, ApiErrors.VALIDATION_FAILED, problem);
    }

    /**
     * Turns one image receipt a quarter, in place: {@code {"direction": "left" | "right"}}. Answers
     * {@code {id, title, pdf:false, url, thumbUrl, rev}}, both URLs ending in {@code ?r={rev}} so a cached
     * copy of the old turn is never shown. 400 for a bad direction, 404 when the receipt is not on this
     * report, 409 while it is already being rotated, 422 for a PDF or an undecodable image.
     */
    @POST
    @Path("trips/{tripId}/receipts/{receiptId}/rotate")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response rotateReceipt(@PathParam("tripId") final String tripId,
            @PathParam("receiptId") final String receiptId, @HeaderParam(CSRF_HEADER) final String csrf,
            final RotateRequest body) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        final Optional<QuarterTurn> turn = (body == null) ? Optional.empty() : body.turn();
        if (turn.isEmpty()) {
            return error(400, ApiErrors.VALIDATION_FAILED, "direction must be left or right.");
        }
        final ExpenseReceipts.RotateOutcome outcome = receipts().rotate(tripId, receiptId, turn.get());
        if (!outcome.ok()) {
            return rotateError(outcome);
        }
        final MediaItem item = outcome.item();
        final String path = ExpenseReceipts.path(item.getId());
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", item.getId());
        out.put("title", (item.getTitle() == null) ? "Receipt" : item.getTitle());
        out.put("pdf", false);
        out.put("url", absoluteUrl(path + item.getCacheBuster()));
        out.put("thumbUrl", absoluteUrl(path + ExpenseReceipts.THUMB + item.getCacheBuster()));
        out.put("rev", item.getRev());
        return ok(out);
    }

    private Response rotateError(final ExpenseReceipts.RotateOutcome outcome) {
        return switch (outcome.code()) {
            case ExpenseReceipts.RotateOutcome.NOT_ALLOWED -> error(403, ApiErrors.FORBIDDEN, outcome.problem());
            case ExpenseReceipts.RotateOutcome.NOT_ON_REPORT -> error(404, ApiErrors.NOT_FOUND, outcome.problem());
            case ExpenseReceipts.RotateOutcome.BUSY -> error(409, ApiErrors.CONFLICT, outcome.problem());
            case ExpenseReceipts.RotateOutcome.PDF, ExpenseReceipts.RotateOutcome.REJECTED ->
                    error(422, ApiErrors.VALIDATION_FAILED, outcome.problem());
            default -> error(500, ApiErrors.STORE_FAILED, outcome.problem());
        };
    }

    /**
     * Records a payment against the report. Body: {@code {date, amount, method, note, lineIds}}: {@code date} an
     * ISO date, {@code amount} SIGNED dollars (negative = the submitter paid the organization back),
     * {@code lineIds} the expenses it settles (each still to be paid; may be empty). Finance admins only.
     * Answers {@code {id}}.
     */
    @POST
    @Path("trips/{tripId}/payments")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response createPayment(@PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        return savePayment(tripId, null, csrf, body);
    }

    /** Edits a payment; body as {@link #createPayment}. */
    @PUT
    @Path("trips/{tripId}/payments/{paymentId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response updatePayment(@PathParam("tripId") final String tripId,
            @PathParam("paymentId") final String paymentId, @HeaderParam(CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        return savePayment(tripId, paymentId, csrf, body);
    }

    /** Deletes a payment; its expenses are unpaid again. Deleting one that is already gone succeeds. */
    @DELETE
    @Path("trips/{tripId}/payments/{paymentId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response deletePayment(@PathParam("tripId") final String tripId,
            @PathParam("paymentId") final String paymentId, @HeaderParam(CSRF_HEADER) final String csrf) {
        final Response refused = refuseSettle(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        return expenses().deletePayment(tripId, paymentId) ? ok(Map.of("ok", true))
                : error(400, ApiErrors.VALIDATION_FAILED, "The payment was not deleted.");
    }

    // ------------------------------------------------------------------ plumbing

    private Response savePayment(final String tripId, final String paymentId, final String csrf,
            final Map<String, Object> body) {
        final Response refused = refuseSettle(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        final ExpenseViews.PaymentForm form = new ExpenseViews.PaymentForm();
        form.setId(paymentId);
        form.setAmount(dbl(body, "amount"));
        form.setMethod(str(body, "method"));
        form.setNote(str(body, "note"));
        form.setLineIds(strings(body, "lineIds"));
        try {
            form.setDate(date(body, "date"));
        } catch (final DateTimeParseException ex) {
            return error(400, ApiErrors.VALIDATION_FAILED, "date must be a date like 2026-10-20.");
        }
        if (!expenses().recordPayment(tripId, form)) {
            return error(400, ApiErrors.VALIDATION_FAILED, problemOr(form.getProblem(), "The payment was not saved."));
        }
        return ok(Map.of("id", form.getId()));
    }

    /** The payment preamble: 404 unknown trip, 403 without CSRF or the settle gate; null to proceed. */
    private Response refuseSettle(final String tripId, final String csrf) {
        requireTrip(tripId);
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, CSRF_MESSAGE);
        }
        return expenses().canRecordPayments(tripId) ? null : error(403, ApiErrors.FORBIDDEN, SETTLE_REQUIRED);
    }

    private Response saveCurrency(final String tripId, final String code, final boolean existing,
            final String csrf, final Map<String, Object> body) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        final ExpenseViews.CurrencyForm form = new ExpenseViews.CurrencyForm();
        form.setCode(code);
        form.setRate(dbl(body, "rate"));
        form.setNote(str(body, "note"));
        form.setExisting(existing);
        if (!expenses().saveCurrency(tripId, form)) {
            return error(400, ApiErrors.VALIDATION_FAILED,
                    problemOr(form.getProblem(), "The currency was not saved."));
        }
        return ok(Map.of("code", form.getCode()));
    }

    private Response saveLine(final String tripId, final String lineId, final String csrf,
            final Map<String, Object> body) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        final ExpenseViews.ExpenseForm form = new ExpenseViews.ExpenseForm();
        form.setId(lineId);
        form.setCategory(str(body, "category"));
        form.setDescription(str(body, "description"));
        form.setAmount(dbl(body, "amount"));
        form.setBudgetLineId(str(body, "budgetLineId"));
        form.setNote(str(body, "note"));
        form.setPaidStatus(str(body, "paidStatus"));
        final String currency = str(body, "currency");
        if (dbl(body, "rate") != null) {
            form.setCurrency(ExpenseViews.NEW_CURRENCY);
            form.setNewCode(currency);
            form.setNewRate(dbl(body, "rate"));
        } else {
            form.setCurrency((currency == null) ? "USD" : currency);
        }
        try {
            form.setDate(date(body, "date"));
        } catch (final DateTimeParseException ex) {
            return error(400, ApiErrors.VALIDATION_FAILED, "date must be a date like 2026-09-20, or null.");
        }
        if (!expenses().saveLine(tripId, form)) {
            return error(400, ApiErrors.VALIDATION_FAILED, problemOr(form.getProblem(), "The expense was not saved."));
        }
        return ok(Map.of("id", form.getId()));
    }

    /** The shared write preamble: 404 unknown trip, 403 without CSRF or the edit gate; null to proceed. */
    private Response refuseWrite(final String tripId, final String csrf) {
        requireTrip(tripId);
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, CSRF_MESSAGE);
        }
        return expenses().canEditExpenses(tripId) ? null : error(403, ApiErrors.FORBIDDEN, EDIT_REQUIRED);
    }

    private static String problemOr(final String problem, final String fallback) {
        return (problem == null || problem.isBlank()) ? fallback : problem;
    }

    private static String str(final Map<String, Object> body, final String key) {
        final Object value = (body == null) ? null : body.get(key);
        return (value == null) ? null : value.toString();
    }

    private static Double dbl(final Map<String, Object> body, final String key) {
        final Object value = (body == null) ? null : body.get(key);
        return (value instanceof Number n) ? n.doubleValue() : null;
    }

    /** A JSON array as strings; empty when absent or not an array. */
    private static List<String> strings(final Map<String, Object> body, final String key) {
        final List<String> out = new ArrayList<>();
        final Object value = (body == null) ? null : body.get(key);
        if (value instanceof List<?> list) {
            for (final Object item : list) {
                if (item != null) {
                    out.add(item.toString());
                }
            }
        }
        return out;
    }

    /** An ISO date ("2026-09-20"), or null when absent or blank. */
    private static LocalDate date(final Map<String, Object> body, final String key) {
        final String text = str(body, key);
        return (text == null || text.isBlank()) ? null : LocalDate.parse(text.trim());
    }
}
