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
import java.util.Locale;
import java.util.Map;
import org.paulsens.trip.action.WaiverCommands;
import org.paulsens.trip.action.WaiverPdf;
import org.paulsens.trip.action.WaiverRequirement;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripWaiver;
import org.paulsens.trip.model.WaiverAcceptance;

/**
 * A trip's required documents, for one traveler: what to read, where they stand, and the one write that signs.
 * A THIN wrapper over {@link WaiverCommands} bound to this caller, so the rules are exactly the registration
 * page's. See {@code docs/waivers.md} and the "Waivers" section of {@code docs/api-contract.md}.
 *
 * <p>The SUBJECT is {@code ?personId=} (reads) or the body's {@code personId} (the signature), the website's
 * acting-for without a session: the caller signs AS themselves, for themselves or someone they manage.
 */
@Path("trips/{tripId}/waivers")
@TripApi
public class TripWaiversResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.WAIVERS_V1;
    private static final String CSRF_MESSAGE = "Missing " + CSRF_HEADER + " header.";

    @Override
    protected String versionedType() {
        return V1;
    }

    /** Seam: the bean bound to THIS caller; tests substitute. */
    protected WaiverCommands waivers() {
        return new WaiverCommands(this::caller);
    }

    /**
     * {@code {personId, satisfied, waivers:[{waiverId, title, version, acceptOlder, bodyHtml, sha256, standing,
     * agreeText, authorityText, acceptance?}]}} for the subject. 403 for a caller who may not act for them.
     */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response list(@PathParam("tripId") final String tripId, @QueryParam("personId") final String personId) {
        final Trip trip = requireTrip(tripId);
        final Person.Id subject = subjectOf(personId);
        final WaiverCommands waivers = waivers();
        if (!waivers.canReadRecords(trip, subject)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to read this traveler's documents.");
        }
        final Person person = requireSubject(subject);
        final List<WaiverRequirement> reqs = waivers.requirements(trip, subject);
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final WaiverRequirement req : reqs) {
            rows.add(requirementJson(req, person));
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("personId", subject.getValue());
        out.put("satisfied", reqs.stream().allMatch(WaiverRequirement::isSatisfied));
        out.put("waivers", rows);
        return ok(out);
    }

    /**
     * Signs: body {@code {personId, version, agreed, authorityAttested, client?}} ({@code client} = IOS, ANDROID
     * or API). Answers {@code {acceptance, satisfied}}; 422 {@code MISSING_ATTESTATION} unless both are true,
     * 409 {@code NOT_PINNED} when the trip no longer requires that version, 403 for a subject the caller may not
     * act for, 404 for an unknown document or person.
     */
    @POST
    @Path("{waiverId}/accept")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response accept(@PathParam("tripId") final String tripId, @PathParam("waiverId") final String waiverId,
            @HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, CSRF_MESSAGE);
        }
        final Trip trip = requireTrip(tripId);
        final Person.Id subject = subjectOf(str(body, "personId"));
        final WaiverCommands waivers = waivers();
        final WaiverCommands.AcceptResult result = waivers.accept(trip, subject, waiverId, intOf(body, "version"),
                bool(body, "agreed"), bool(body, "authorityAttested"),
                WaiverCommands.ClientInfo.of(clientOf(str(body, "client")), request));
        if (!result.ok()) {
            return acceptRefusal(result);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("acceptance", acceptanceJson(result.acceptance()));
        out.put("satisfied", waivers.isSatisfied(trip, subject));
        return ok(out);
    }

    /** The signed copy as a PDF ({@code ?personId=&version=}); 404 when there is none or it may not be read. */
    @GET
    @Path("{waiverId}/pdf")
    @Produces("application/pdf")
    public Response pdf(@PathParam("tripId") final String tripId, @PathParam("waiverId") final String waiverId,
            @QueryParam("personId") final String personId, @QueryParam("version") final Integer version) {
        final Trip trip = requireTrip(tripId);
        final Person.Id subject = subjectOf(personId);
        final WaiverCommands waivers = waivers();
        final byte[] pdf = (version == null) ? null : waivers.signedPdf(tripId, subject, waiverId, version);
        if (pdf == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such signed document.");
        }
        final WaiverAcceptance row = waivers.acceptancesFor(trip, subject).stream()
                .filter(a -> waiverId.equals(a.getWaiverId()) && a.getVersion() == version)
                .findFirst().orElse(null);
        return Response.ok(pdf, "application/pdf")
                .header("Content-Disposition", attachmentDisposition(WaiverPdf.fileName(
                        row == null ? "Document" : row.getTitle(), row == null ? "" : row.getSubjectName())))
                .header("Cache-Control", "private, no-store").build();
    }

    /**
     * Replaces the trip's required documents (registration admin or trip manager): body
     * {@code {waivers:[{waiverId, version, acceptOlder}]}}, {@code acceptOlder} defaulting to true. Answers the
     * pins; 400 {@code VALIDATION_FAILED} for an unknown document or version, 403 without the gate.
     */
    @PUT
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response replace(@PathParam("tripId") final String tripId, @HeaderParam(CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, CSRF_MESSAGE);
        }
        final Trip trip = requireTrip(tripId);
        final WaiverCommands waivers = waivers();
        if (!waivers.canConfigure(trip)) {
            return error(403, ApiErrors.FORBIDDEN, "Registration admin or trip manager required.");
        }
        final String problem = waivers.replacePins(trip, pinsOf(body));
        if (problem != null) {
            return error(400, ApiErrors.VALIDATION_FAILED, problem);
        }
        final List<Map<String, Object>> pins = new ArrayList<>();
        for (final WaiverCommands.PinRow row : waivers.pinRows(trip)) {
            final Map<String, Object> pin = new LinkedHashMap<>();
            pin.put("waiverId", row.waiverId());
            pin.put("title", row.title());
            pin.put("version", row.version());
            pin.put("acceptOlder", row.acceptOlder());
            pin.put("latestVersion", row.latest());
            pins.add(pin);
        }
        return ok(Map.of("waivers", pins));
    }

    private Response acceptRefusal(final WaiverCommands.AcceptResult result) {
        return switch (result.code()) {
            case WaiverCommands.AcceptResult.NOT_ALLOWED -> error(403, ApiErrors.FORBIDDEN, result.message());
            case WaiverCommands.AcceptResult.NOT_FOUND -> error(404, ApiErrors.NOT_FOUND, result.message());
            case WaiverCommands.AcceptResult.NOT_PINNED -> error(409, result.code(), result.message());
            case WaiverCommands.AcceptResult.MISSING_ATTESTATION -> error(422, result.code(), result.message());
            default -> error(500, ApiErrors.STORE_FAILED, result.message());
        };
    }

    private Map<String, Object> requirementJson(final WaiverRequirement req, final Person traveler) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("waiverId", req.getWaiverId());
        row.put("title", req.getTitle());
        row.put("version", req.getVersion());
        row.put("acceptOlder", req.isAcceptOlder());
        row.put("bodyHtml", req.getBodyHtml());
        row.put("sha256", req.getSha256());
        row.put("standing", req.getStandingKey());
        row.put("agreeText", WaiverCommands.agreeText(req.getTitle()));
        row.put("authorityText", WaiverCommands.authorityText(fullName(traveler)));
        if (req.getAcceptance() != null) {
            row.put("acceptance", acceptanceJson(req.getAcceptance()));
        }
        return row;
    }

    private Map<String, Object> acceptanceJson(final WaiverAcceptance a) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", a.getVersion());
        out.put("acceptedAt", a.getAcceptedAt());
        out.put("signerId", a.getSignerId() == null ? null : a.getSignerId().getValue());
        out.put("signerName", a.getSignerName());
        out.put("onBehalf", a.isOnBehalf());
        out.put("pdfUrl", absoluteUrl("/api/trips/" + a.getTripId() + "/waivers/" + a.getWaiverId()
                + "/pdf?personId=" + a.getPersonId().getValue() + "&version=" + a.getVersion()));
        return out;
    }

    private static String fullName(final Person person) {
        final String full = ((person.getFirst() == null ? "" : person.getFirst().trim()) + " "
                + (person.getLast() == null ? "" : person.getLast().trim())).trim();
        return full.isEmpty() ? person.getPreferredName() : full;
    }

    @SuppressWarnings("unchecked")
    private static List<TripWaiver> pinsOf(final Map<String, Object> body) {
        final Object list = (body == null) ? null : body.get("waivers");
        final List<TripWaiver> pins = new ArrayList<>();
        if (list instanceof List<?> items) {
            for (final Object item : items) {
                if (item instanceof Map<?, ?> map) {
                    final Map<String, Object> pin = (Map<String, Object>) map;
                    pins.add(TripWaiver.builder().waiverId(str(pin, "waiverId")).version(intOf(pin, "version"))
                            .acceptOlder(!Boolean.FALSE.equals(pin.get("acceptOlder"))).build());
                }
            }
        }
        return pins;
    }

    static WaiverAcceptance.Client clientOf(final String value) {
        if (value == null) {
            return WaiverAcceptance.Client.API;
        }
        try {
            return WaiverAcceptance.Client.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            return WaiverAcceptance.Client.API;
        }
    }

    private static String str(final Map<String, Object> body, final String key) {
        final Object value = (body == null) ? null : body.get(key);
        return (value == null) ? null : value.toString();
    }

    private static int intOf(final Map<String, Object> body, final String key) {
        final Object value = (body == null) ? null : body.get(key);
        return (value instanceof Number n) ? n.intValue() : 0;
    }

    private static boolean bool(final Map<String, Object> body, final String key) {
        return Boolean.TRUE.equals(body == null ? null : body.get(key));
    }
}
