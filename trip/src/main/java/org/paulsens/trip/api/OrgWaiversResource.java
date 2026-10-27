package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.WaiverCommands;
import org.paulsens.trip.model.WaiverDocument;
import org.paulsens.trip.model.WaiverVersion;

/**
 * An organization's document library (org admins only), a THIN wrapper over {@link WaiverCommands}: list, read
 * a version, create, publish the next version, retire. There is no delete and no edit-in-place -- every change
 * of title or text is a new version, because signatures name versions. See {@code docs/waivers.md}.
 */
@Path("orgs/{orgId}/waivers")
@TripApi
public class OrgWaiversResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.WAIVERS_V1;
    private static final String CSRF_MESSAGE = "Missing " + CSRF_HEADER + " header.";
    private static final String ADMIN_REQUIRED = "Organization admin required.";

    @Override
    protected String versionedType() {
        return V1;
    }

    /** Seam: the bean bound to THIS caller; tests substitute. */
    protected WaiverCommands waivers() {
        return new WaiverCommands(this::caller);
    }

    /** {@code [{id, title, retired, currentVersion, versions:[{number, title, publishedAt, publishedBy, note}]}]}. */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response list(@PathParam("orgId") final String orgId) {
        final WaiverCommands waivers = waivers();
        if (!waivers.canManage(orgId)) {
            return error(403, ApiErrors.FORBIDDEN, ADMIN_REQUIRED);
        }
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final WaiverDocument doc : waivers.documents(orgId)) {
            out.add(summary(doc));
        }
        return ok(out);
    }

    /** One document with {@code bodyHtml} of {@code ?version=} (default the newest). */
    @GET
    @Path("{id}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(@PathParam("orgId") final String orgId, @PathParam("id") final String id,
            @QueryParam("version") final Integer version) {
        final WaiverCommands waivers = waivers();
        if (!waivers.canManage(orgId)) {
            return error(403, ApiErrors.FORBIDDEN, ADMIN_REQUIRED);
        }
        final WaiverDocument doc = waivers.document(orgId, id);
        final WaiverVersion text = (doc == null) ? null
                : (version == null ? doc.getCurrent() : doc.version(version));
        if (text == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such document or version.");
        }
        final Map<String, Object> out = summary(doc);
        out.put("version", text.getNumber());
        out.put("bodyHtml", text.getBodyHtml());
        out.put("sha256", text.getSha256());
        return ok(out);
    }

    /** Body {@code {title, bodyHtml, note?}}; answers the new document's summary. */
    @POST
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response create(@PathParam("orgId") final String orgId, @HeaderParam(CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, CSRF_MESSAGE);
        }
        return answer(waivers().createDocument(orgId, str(body, "title"), str(body, "bodyHtml"), str(body, "note")));
    }

    /** Body {@code {bodyHtml, title?, note?}} ({@code title} defaults to the current one): the next version. */
    @POST
    @Path("{id}/versions")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response publish(@PathParam("orgId") final String orgId, @PathParam("id") final String id,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, CSRF_MESSAGE);
        }
        final WaiverCommands waivers = waivers();
        final WaiverDocument existing = waivers.document(orgId, id);
        final String title = (str(body, "title") != null || existing == null) ? str(body, "title")
                : existing.getTitle();
        return answer(waivers.publishVersion(orgId, id, title, str(body, "bodyHtml"), str(body, "note")));
    }

    /** Body {@code {retired}}: hides the document from the trip pickers (or restores it). */
    @PUT
    @Path("{id}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response update(@PathParam("orgId") final String orgId, @PathParam("id") final String id,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, CSRF_MESSAGE);
        }
        return answer(waivers().setRetired(orgId, id, Boolean.TRUE.equals(body == null ? null : body.get("retired"))));
    }

    private Response answer(final WaiverCommands.Result result) {
        return switch (result.code()) {
            case WaiverCommands.Result.OK -> ok(summary(result.document()));
            case WaiverCommands.Result.NOT_ALLOWED -> error(403, ApiErrors.FORBIDDEN, result.message());
            case WaiverCommands.Result.NOT_FOUND -> error(404, ApiErrors.NOT_FOUND, result.message());
            case WaiverCommands.Result.CONFLICT -> error(409, ApiErrors.CONFLICT, result.message());
            case WaiverCommands.Result.VALIDATION_FAILED ->
                    error(400, ApiErrors.VALIDATION_FAILED, result.message());
            default -> error(500, ApiErrors.STORE_FAILED, result.message());
        };
    }

    private static Map<String, Object> summary(final WaiverDocument doc) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", doc.getId());
        out.put("title", doc.getTitle());
        out.put("retired", doc.isRetired());
        out.put("currentVersion", doc.getCurrentNumber());
        final List<Map<String, Object>> versions = new ArrayList<>();
        for (final WaiverVersion v : doc.getVersions()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("number", v.getNumber());
            row.put("title", v.getTitle());
            row.put("publishedAt", v.getPublishedAt());
            row.put("publishedBy", v.getPublishedBy() == null ? null : v.getPublishedBy().getValue());
            row.put("note", v.getNote());
            versions.add(row);
        }
        out.put("versions", versions);
        return out;
    }

    private static String str(final Map<String, Object> body, final String key) {
        final Object value = (body == null) ? null : body.get(key);
        return (value == null) ? null : value.toString();
    }
}
