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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import org.paulsens.trip.action.BudgetCommands;
import org.paulsens.trip.action.BudgetPdf;
import org.paulsens.trip.action.BudgetViews;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.web.ContentDisposition;

/**
 * A trip's planning budget: a THIN wrapper over {@link BudgetCommands} with the session's caller, so the
 * gates, validation and audit are exactly the page's. Bodies are the bean's own scalar forms in JSON (money in
 * dollars); the browser tests own their fixtures through it and the apps may later read it. See
 * {@code docs/budget.md}.
 *
 * <p>Writes answer 403 without the CSRF header or the edit gate, 400 {@code VALIDATION_FAILED} (with the
 * refusal) when the bean refuses, and 404 for an unknown trip.
 */
@Path("budget")
@TripApi
public class BudgetResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.BUDGET_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    private BudgetCommands budget() {
        return new BudgetCommands(this::caller);
    }

    /** The whole budget: {@code {inputs, lines, lodging, summary}}, summary being the page's computed figures. */
    @GET
    @Path("trips/{tripId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(@PathParam("tripId") final String tripId) {
        requireTrip(tripId);
        final BudgetCommands budget = budget();
        if (!budget.canViewBudget(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.shared.financeViewerRequired"));
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("inputs", budget.inputsFormFor(tripId));
        out.put("lines", budget.lineFormsFor(tripId));
        out.put("lodging", budget.lodgingFormsFor(tripId));
        out.put("summary", budget.budgetPage(tripId));
        return ok(out);
    }

    /** The budget as a PDF. */
    @GET
    @Path("trips/{tripId}/pdf")
    @Produces("application/pdf")
    public Response pdf(@PathParam("tripId") final String tripId) {
        final org.paulsens.trip.model.Trip trip = requireTrip(tripId);
        final byte[] pdf = budget().budgetPdf(tripId);
        if (pdf == null) {
            return error(403, ApiErrors.FORBIDDEN, Text.get("api.shared.financeViewerRequired"));
        }
        return Response.ok(pdf, "application/pdf").header("Content-Disposition",
                ContentDisposition.attachment(BudgetPdf.fileName(trip.getTitle())))
                .header("Cache-Control", "private, no-store").build();
    }

    /** Body: {@code {people, tripNights, perPersonBudget, toBill}}. Answers {@code {ok:true}}. */
    @PUT
    @Path("trips/{tripId}/inputs")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response saveInputs(@PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        final BudgetViews.InputsForm form = new BudgetViews.InputsForm();
        form.setPeople(JsonBody.intOf(body, "people", 0));
        form.setTripNights(JsonBody.intOf(body, "tripNights", 0));
        form.setPerPersonBudget(JsonBody.dbl(body, "perPersonBudget"));
        form.setToBill(JsonBody.dbl(body, "toBill"));
        return budget().saveInputs(tripId, form) ? ok(Map.of("ok", true))
                : error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.budget.inputsNotSaved"));
    }

    /**
     * Body: {@code {kind, category, name, amount, perNight, note, payee, onSite, percent, percentOf}}; a line
     * with {@code percentOf} takes {@code percent} (12.5 = 12.5%) of that line and ignores {@code amount}.
     * {@code onSite} defaults to true. Answers {@code {id}}.
     */
    @POST
    @Path("trips/{tripId}/lines")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response createLine(@PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        return saveLine(tripId, null, csrf, body);
    }

    /** Edits a line; body as {@link #createLine}. */
    @PUT
    @Path("trips/{tripId}/lines/{lineId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response updateLine(@PathParam("tripId") final String tripId, @PathParam("lineId") final String lineId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        return saveLine(tripId, lineId, csrf, body);
    }

    @DELETE
    @Path("trips/{tripId}/lines/{lineId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response deleteLine(@PathParam("tripId") final String tripId, @PathParam("lineId") final String lineId,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        return budget().deleteLine(tripId, lineId) ? ok(Map.of("ok", true))
                : error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.budget.lineNotDeleted"));
    }

    /** Body: {@code {offerId, nights, people, rooms, singles, payee, note, onSite}}. Answers {@code {id}}. */
    @POST
    @Path("trips/{tripId}/lodging")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response createLodging(@PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        return saveLodging(tripId, null, csrf, body);
    }

    /** Edits a lodging line; body as {@link #createLodging}. */
    @PUT
    @Path("trips/{tripId}/lodging/{lineId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response updateLodging(@PathParam("tripId") final String tripId, @PathParam("lineId") final String lineId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        return saveLodging(tripId, lineId, csrf, body);
    }

    @DELETE
    @Path("trips/{tripId}/lodging/{lineId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response deleteLodging(@PathParam("tripId") final String tripId, @PathParam("lineId") final String lineId,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        return budget().deleteLodgingLine(tripId, lineId) ? ok(Map.of("ok", true))
                : error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.budget.lodgingLineNotDeleted"));
    }

    /**
     * Fills this trip's EMPTY budget from another of the same organization's budgets. Body:
     * {@code {fromTripId}}. Answers {@code {ok:true}}; 400 when the budget already has lines or the source is
     * not one this trip may clone (another org's, unreadable, or without lines).
     */
    @POST
    @Path("trips/{tripId}/clone")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response cloneFrom(@PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        return budget().cloneBudget(tripId, JsonBody.str(body, "fromTripId")) ? ok(Map.of("ok", true))
                : error(400, ApiErrors.VALIDATION_FAILED, Text.get("api.budget.notCloned"));
    }

    // ------------------------------------------------------------------ plumbing

    private Response saveLine(final String tripId, final String lineId, final String csrf,
            final Map<String, Object> body) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        final BudgetCommands budget = budget();
        final BudgetViews.LineForm form = budget.newLineForm(JsonBody.str(body, "kind"),
                JsonBody.str(body, "category"));
        form.setId(lineId);
        form.setName(JsonBody.str(body, "name"));
        form.setAmount(JsonBody.dbl(body, "amount"));
        form.setPerNight(JsonBody.bool(body, "perNight", false));
        form.setNote(JsonBody.str(body, "note"));
        form.setPayee(JsonBody.str(body, "payee"));
        form.setOnSite(JsonBody.bool(body, "onSite", true));
        form.setPercentOf(JsonBody.str(body, "percentOf"));
        form.setPercentMode(form.getPercentOf() != null);
        form.setPercent(JsonBody.dbl(body, "percent"));
        if (!budget.saveLine(tripId, form)) {
            return error(400, ApiErrors.VALIDATION_FAILED, JsonBody.problemOr(form.getProblem(),
                    Text.get("api.budget.lineNotSaved")));
        }
        return ok(Map.of("id", form.getId()));
    }

    private Response saveLodging(final String tripId, final String lineId, final String csrf,
            final Map<String, Object> body) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        final BudgetCommands budget = budget();
        final BudgetViews.LodgingLineForm form = budget.newLodgingForm(tripId);
        form.setId(lineId);
        form.setOfferId(JsonBody.str(body, "offerId"));
        form.setNights(JsonBody.intOf(body, "nights", form.getNights()));
        form.setPeople(JsonBody.intOf(body, "people", form.getPeople()));
        form.setRooms(JsonBody.intOf(body, "rooms", 0));
        form.setSingles(JsonBody.intOf(body, "singles", 0));
        form.setPayee(JsonBody.str(body, "payee"));
        form.setNote(JsonBody.str(body, "note"));
        form.setOnSite(JsonBody.bool(body, "onSite", true));
        if (!budget.saveLodgingLine(tripId, form)) {
            return error(400, ApiErrors.VALIDATION_FAILED,
                    JsonBody.problemOr(form.getProblem(), Text.get("api.budget.lodgingLineNotSaved")));
        }
        return ok(Map.of("id", form.getId()));
    }

    /** The shared write preamble: 404 unknown trip, 403 without CSRF or the edit gate; null to proceed. */
    private Response refuseWrite(final String tripId, final String csrf) {
        requireTrip(tripId);
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, Text.get("api.shared.csrfMissing", CSRF_HEADER));
        }
        return budget().canEditBudget(tripId) ? null : error(403, ApiErrors.FORBIDDEN,
                Text.get("api.shared.tripManagerOrFinanceAdminRequired"));
    }
}
