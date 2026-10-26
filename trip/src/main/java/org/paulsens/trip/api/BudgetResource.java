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
import org.paulsens.trip.action.BudgetViews;

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
    private static final String CSRF_MESSAGE = "Missing " + CSRF_HEADER + " header.";
    private static final String EDIT_REQUIRED = "Trip manager or finance admin required.";

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
            return error(403, ApiErrors.FORBIDDEN, "Finance viewer required.");
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("inputs", budget.inputsFormFor(tripId));
        out.put("lines", budget.lineFormsFor(tripId));
        out.put("lodging", budget.lodgingFormsFor(tripId));
        out.put("summary", budget.budgetPage(tripId));
        return ok(out);
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
        form.setPeople(intOf(body, "people", 0));
        form.setTripNights(intOf(body, "tripNights", 0));
        form.setPerPersonBudget(dbl(body, "perPersonBudget"));
        form.setToBill(dbl(body, "toBill"));
        return budget().saveInputs(tripId, form) ? ok(Map.of("ok", true))
                : error(400, ApiErrors.VALIDATION_FAILED, "The inputs were not saved.");
    }

    /** Body: {@code {kind, category, name, amount, perNight, note, payee}}. Answers {@code {id}}. */
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
                : error(400, ApiErrors.VALIDATION_FAILED, "The line was not deleted.");
    }

    /** Body: {@code {offerId, nights, people, rooms, singles, payee, note}}. Answers {@code {id}}. */
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
                : error(400, ApiErrors.VALIDATION_FAILED, "The lodging line was not deleted.");
    }

    // ------------------------------------------------------------------ plumbing

    private Response saveLine(final String tripId, final String lineId, final String csrf,
            final Map<String, Object> body) {
        final Response refused = refuseWrite(tripId, csrf);
        if (refused != null) {
            return refused;
        }
        final BudgetCommands budget = budget();
        final BudgetViews.LineForm form = budget.newLineForm(str(body, "kind"), str(body, "category"));
        form.setId(lineId);
        form.setName(str(body, "name"));
        form.setAmount(dbl(body, "amount"));
        form.setPerNight(Boolean.TRUE.equals(body == null ? null : body.get("perNight")));
        form.setNote(str(body, "note"));
        form.setPayee(str(body, "payee"));
        if (!budget.saveLine(tripId, form)) {
            return error(400, ApiErrors.VALIDATION_FAILED, problemOr(form.getProblem(), "The line was not saved."));
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
        form.setOfferId(str(body, "offerId"));
        form.setNights(intOf(body, "nights", form.getNights()));
        form.setPeople(intOf(body, "people", form.getPeople()));
        form.setRooms(intOf(body, "rooms", 0));
        form.setSingles(intOf(body, "singles", 0));
        form.setPayee(str(body, "payee"));
        form.setNote(str(body, "note"));
        if (!budget.saveLodgingLine(tripId, form)) {
            return error(400, ApiErrors.VALIDATION_FAILED,
                    problemOr(form.getProblem(), "The lodging line was not saved."));
        }
        return ok(Map.of("id", form.getId()));
    }

    /** The shared write preamble: 404 unknown trip, 403 without CSRF or the edit gate; null to proceed. */
    private Response refuseWrite(final String tripId, final String csrf) {
        requireTrip(tripId);
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, CSRF_MESSAGE);
        }
        return budget().canEditBudget(tripId) ? null : error(403, ApiErrors.FORBIDDEN, EDIT_REQUIRED);
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

    private static int intOf(final Map<String, Object> body, final String key, final int fallback) {
        final Object value = (body == null) ? null : body.get(key);
        return (value instanceof Number n) ? n.intValue() : fallback;
    }
}
