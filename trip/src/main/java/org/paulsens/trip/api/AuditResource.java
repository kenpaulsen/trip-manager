package org.paulsens.trip.api;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.AuditViewCommands;
import org.paulsens.trip.api.mapper.AuditMapper;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.AuditPage;

/**
 * Reading the audit trail.
 *
 * <p><b>Read only, permanently.</b> {@code AuditCommands} -- the writer -- is deliberately not exposed anywhere
 * on this API and should not be. An endpoint that let a client assert an audit record would let it forge
 * history, which is the one thing the trail exists to prevent; the records are append-only in IAM as well as in
 * code for the same reason. Audit entries are a side effect of real operations and nothing else.
 *
 * <p>Everything here needs {@code auditAdmin}. The trail is a log of what everyone did, so read access to it is
 * read access to a summary of every other privilege's activity. The reads are scoped like the page
 * ({@code AuditViewCommands.scopeFor}): {@code org=} names one organization's trail (its own
 * {@code auditAdmin@org} holders may read it), and without it the answer is the host's own view.
 */
@Slf4j
@Path("audit")
@TripApi
public class AuditResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.AUDIT_V1;
    private static final int MAX_LIMIT = 500;

    @Override
    protected String versionedType() {
        return V1;
    }

    /**
     * A page of the trail, newest first, optionally filtered.
     *
     * <p>Paged by a {@code before} cursor rather than an offset: the partition is time-ordered and grows at the
     * head, so an offset would shift under a caller between pages and quietly skip records.
     */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response page(
            @QueryParam("org") final String org,
            @QueryParam("before") final String before,
            @QueryParam("actor") final String actor,
            @QueryParam("action") final String action,
            @QueryParam("outcome") final String outcome,
            @QueryParam("text") final String text,
            @QueryParam("limit") @DefaultValue("50") final int limit) {
        if (!mayRead(org)) {
            return error(403, ApiErrors.FORBIDDEN, "Audit access required.");
        }
        final Instant cursor;
        try {
            cursor = parseCursor(before);
        } catch (final DateTimeParseException ex) {
            return error(400, ApiErrors.BAD_REQUEST, "before must be an ISO-8601 instant.");
        }
        final AuditPage page = Beans.get(AuditViewCommands.class)
                .getPage(blankToNull(org), cursor, actor, action, outcome, text,
                        Math.min(Math.max(limit, 1), MAX_LIMIT));
        return ok(toDto(page));
    }

    /** The newest page with no filters, for an initial render. */
    @GET
    @Path("recent")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response recent(@QueryParam("org") final String org,
            @QueryParam("limit") @DefaultValue("50") final int limit) {
        if (!mayRead(org)) {
            return error(403, ApiErrors.FORBIDDEN, "Audit access required.");
        }
        return ok(toDto(Beans.get(AuditViewCommands.class)
                .getRecent(blankToNull(org), Math.min(Math.max(limit, 1), MAX_LIMIT))));
    }

    /**
     * The current filter as CSV.
     *
     * <p>Served as {@code text/csv} rather than wrapped in JSON: this is a file a human opens in a spreadsheet,
     * and base64-in-JSON would make every client decode it before it could be saved.
     */
    @GET
    @Path("export.csv")
    @Produces("text/csv")
    public Response export(
            @QueryParam("org") final String org,
            @QueryParam("before") final String before,
            @QueryParam("actor") final String actor,
            @QueryParam("action") final String action,
            @QueryParam("outcome") final String outcome,
            @QueryParam("text") final String text) {
        if (!mayRead(org)) {
            return error(403, ApiErrors.FORBIDDEN, "Audit access required.");
        }
        final Instant cursor;
        try {
            cursor = parseCursor(before);
        } catch (final DateTimeParseException ex) {
            return error(400, ApiErrors.BAD_REQUEST, "before must be an ISO-8601 instant.");
        }
        final String csv = Beans.get(AuditViewCommands.class)
                .toCsv(blankToNull(org), cursor, actor, action, outcome, text);
        return Response.ok(csv)
                .type("text/csv")
                .header("Vary", "Accept")
                .header("Content-Disposition", "attachment; filename=\"audit.csv\"")
                .build();
    }

    /** The filter vocabularies, so a client builds its dropdowns from the server's enums rather than guessing. */
    @GET
    @Path("vocabularies")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response vocabularies() {
        if (!privileges().has(ApiPrivileges.AUDIT_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Audit access required.");
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("actions", Arrays.stream(AuditAction.values()).map(Enum::name).toList());
        result.put("outcomes", Arrays.stream(AuditOutcome.values()).map(Enum::name).toList());
        return ok(result);
    }

    /**
     * The wire form of a page.
     *
     * <p>{@code nextCursor} and {@code degraded} are surfaced deliberately. The trail is stored by day
     * partition, so a filtered query walks backwards a day at a time and can stop before it has searched the
     * whole range; a client that ignored {@code complete} would present a partial answer as if it were the
     * whole history, which for an audit trail is worse than an error.
     */
    private static Map<String, Object> toDto(final AuditPage page) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", page.getEvents().stream().map(AuditMapper.INSTANCE::toDto).toList());
        result.put("searchedBackTo", page.getSearchedBackTo());
        result.put("complete", page.isComplete());
        result.put("degraded", page.isDegraded());
        result.put("nextCursor", page.nextCursor());
        return result;
    }

    private static Instant parseCursor(final String before) {
        return (before == null || before.isBlank()) ? null : Instant.parse(before.trim());
    }

    /** The page's rule, against this request's caller: {@code AuditViewCommands.canView}. */
    private boolean mayRead(final String org) {
        return AuditViewCommands.canView(privileges().caller(), blankToNull(org));
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
