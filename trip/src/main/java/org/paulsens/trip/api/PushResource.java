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
import org.paulsens.trip.action.PushCommands;
import org.paulsens.trip.push.PushDevices;
import org.paulsens.trip.push.PushSender;

/**
 * Push notification devices and preferences ({@code docs/push-notifications.md} "Wire contract"). Self-scoped
 * by construction: the person is always the bearer/session identity, never a path parameter. Wraps
 * {@link PushCommands}, the same bean the profile page uses.
 *
 * <p>Tokens and endpoints go IN and never come out: listings carry a short id (token suffix / endpoint
 * hash), and removal accepts that id or (iOS) the full token the client already holds.
 */
@Path("push")
@TripApi
public class PushResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.PUSH_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    /** The app registers (or re-registers) its APNs token. 400 bad hex/environment, 403 selector not the caller's. */
    @PUT
    @Path("devices")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response registerDevice(@HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final PushDevices.Outcome outcome = push().registerIos(personId(), string(body, "token"),
                string(body, "environment"), string(body, "selector"), string(body, "label"),
                string(body, "appVersion"), actor());
        return outcome.ok() ? ok(devicesBody(outcome)) : refusal(outcome);
    }

    /** Idempotent: removing a device that is already gone is a success. */
    @DELETE
    @Path("devices/{id}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response removeDevice(@PathParam("id") final String idOrToken,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        push().removeDevice(personId(), idOrToken, actor());
        return ok(Map.of("removed", true));
    }

    /** A browser subscribes: {@code {endpoint, keys:{p256dh, auth}, label}}; the origin is this request's host. */
    @PUT
    @Path("webpush")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response subscribeWeb(@HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Object keys = body == null ? null : body.get("keys");
        final Map<?, ?> keyMap = keys instanceof Map<?, ?> map ? map : Map.of();
        final PushDevices.Outcome outcome = push().registerWeb(personId(), string(body, "endpoint"),
                stringOf(keyMap.get("p256dh")), stringOf(keyMap.get("auth")), string(body, "label"), origin(),
                actor());
        return outcome.ok() ? ok(devicesBody(outcome)) : refusal(outcome);
    }

    /** The logout hook and the Disable button. Idempotent. */
    @POST
    @Path("webpush/unsubscribe")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response unsubscribeWeb(@HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        push().unsubscribeWeb(personId(), string(body, "endpoint"), actor());
        return ok(Map.of("removed", true));
    }

    /** The VAPID public key browsers subscribe with; 404 when web push is not configured on this deployment. */
    @GET
    @Path("webpush/key")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response webPushKey() {
        final String key = push().vapidPublicKey();
        if (key == null) {
            return error(404, ApiErrors.NOT_FOUND, "Web push is not configured.");
        }
        return ok(Map.of("publicKey", key));
    }

    /** The caller's master switch, quiet hours and devices. */
    @GET
    @Path("prefs")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response prefs() {
        final PushCommands push = push();
        return ok(push.describe(push.prefsOf(personId())));
    }

    /** Absent = unchanged, {@code ""} clears; times {@code HH:mm}, zone an IANA id. Answers the full prefs. */
    @PUT
    @Path("prefs")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response savePrefs(@HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Object enabled = body == null ? null : body.get("enabled");
        final PushCommands push = push();
        final PushDevices.Outcome outcome = push.setPrefs(personId(),
                enabled == null ? null : Boolean.valueOf(String.valueOf(enabled)),
                string(body, "quietHoursStart"), string(body, "quietHoursEnd"), string(body, "timeZone"));
        return outcome.ok() ? ok(push.describe(outcome.prefs())) : refusal(outcome);
    }

    /** A test push to the caller's own devices; reports what each device answered. */
    @POST
    @Path("test")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response test(@HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final PushSender.Report report = push().sendTest(personId());
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", report.sent());
        result.put("outcomes", report.outcomes().stream().map(PushResource::describe).toList());
        if (report.skipped() != null) {
            result.put("skipped", report.skipped());
        }
        return ok(result);
    }

    private static Map<String, Object> describe(final PushSender.DeviceOutcome outcome) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", outcome.kind());
        result.put("label", outcome.label() == null ? "" : outcome.label());
        result.put("outcome", outcome.outcome().name());
        return result;
    }

    private Map<String, Object> devicesBody(final PushDevices.Outcome outcome) {
        return Map.of("devices", push().describe(outcome.prefs()).get("devices"));
    }

    /** The one mapping for registry refusals. */
    private Response refusal(final PushDevices.Outcome outcome) {
        return switch (outcome.code() == null ? "" : outcome.code()) {
            case PushDevices.REFUSED_SELECTOR -> error(403, ApiErrors.FORBIDDEN, outcome.message());
            case PushDevices.REFUSED_STORE -> error(500, ApiErrors.STORE_FAILED, outcome.message());
            case PushDevices.REFUSED_BAD_TIME, PushDevices.REFUSED_BAD_ZONE ->
                    error(400, ApiErrors.VALIDATION_FAILED, outcome.message());
            default -> error(400, ApiErrors.BAD_REQUEST, outcome.message());
        };
    }

    /** {@code scheme://host[:port]} of this request -- the site the browser subscribed on. */
    private String origin() {
        final String root = absoluteUrl("/");
        return root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
    }

    private static String string(final Map<String, Object> body, final String key) {
        return body == null ? null : stringOf(body.get(key));
    }

    private static String stringOf(final Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static PushCommands push() {
        return Beans.get(PushCommands.class);
    }
}
