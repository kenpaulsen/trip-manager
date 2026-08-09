package org.paulsens.trip.api;

import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.audit.Audit;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.PasskeyCredential;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.security.PasskeyService;
import org.paulsens.trip.security.RememberMeService;
import org.paulsens.trip.web.Sessions;

/**
 * The passkey (WebAuthn) ceremonies' HTTP edge. Registration and management require the signed-in session
 * (plus the CSRF header on writes); the two login endpoints are sessionless like {@code auth/login} -- they
 * ARE a way in. Every endpoint is inert while the feature setting is off.
 *
 * <p>The JS half lives in {@code passkey.js} (medjugorje); the JSON bodies here are the browser's own
 * WebAuthn structures passed through the Yubico library, not hand-built DTOs.
 */
@Slf4j
@Path("auth/passkey")
public class PasskeyResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.AUTH_V1;

    private final PasskeyService passkeys;

    public PasskeyResource() {
        this(PasskeyService.getInstance());
    }

    // Package-private: tests hand in a service wired to their own cache/config.
    PasskeyResource(final PasskeyService passkeys) {
        this.passkeys = passkeys;
    }

    @Override
    protected String versionedType() {
        return V1;
    }

    /** Whether to offer anything passkey-shaped, and how many keys the caller has -- drives all the UI. */
    @GET
    @Path("status")
    @TripApi
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response status() {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", passkeys.enabled());
        result.put("count", passkeys.enabled() ? DAO.getInstance().getPasskeysForUser(personId()).size() : 0);
        return ok(result);
    }

    @GET
    @Path("mine")
    @TripApi
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response mine() {
        if (!passkeys.enabled()) {
            return ok(List.of());
        }
        final List<Map<String, Object>> keys = DAO.getInstance().getPasskeysForUser(personId()).stream()
                .map(PasskeyResource::describe)
                .toList();
        return ok(keys);
    }

    @POST
    @Path("register/start")
    @TripApi
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response registerStart(@HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!passkeys.enabled()) {
            return error(404, ApiErrors.NOT_FOUND, "Passkeys are not enabled.");
        }
        final String email = sessionEmail();
        if (email == null) {
            return error(401, ApiErrors.NOT_AUTHENTICATED, "Not signed in.");
        }
        try {
            final String options = passkeys.startRegistration(personId(), email,
                    request.getServerName(), request.isSecure(), request.getSession().getId());
            return ok(Map.of("publicKey", options));
        } catch (final Exception ex) {
            log.warn("Passkey registration could not start.", ex);
            return error(500, ApiErrors.INTERNAL, "Could not start passkey registration.");
        }
    }

    @POST
    @Path("register/finish")
    @TripApi
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response registerFinish(@HeaderParam(CSRF_HEADER) final String csrf, final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!passkeys.enabled()) {
            return error(404, ApiErrors.NOT_FOUND, "Passkeys are not enabled.");
        }
        final String email = sessionEmail();
        final String credential = string(body == null ? null : body.get("credential"));
        if (email == null || credential == null || credential.isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "Missing credential.");
        }
        final String label = string(body.get("label"));
        final PasskeyCredential passkey = passkeys.finishRegistration(personId(), email,
                request.getServerName(), request.isSecure(), request.getSession().getId(), credential, label);
        if (passkey == null) {
            return error(400, ApiErrors.BAD_REQUEST, "Passkey registration failed.");
        }
        Audit.builder(AuditAction.PASSKEY_REGISTER, AuditOutcome.SUCCESS)
                .actor(email, personId().getValue())
                .message("Registered passkey '" + passkey.getLabel() + "' for " + passkey.getRpId())
                .log();
        return ok(describe(passkey));
    }

    /** Sessionless, like {@code auth/login}: this is the login page asking before anyone is signed in. */
    @POST
    @Path("login/start")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response loginStart() {
        if (!passkeys.enabled()) {
            return error(404, ApiErrors.NOT_FOUND, "Passkeys are not enabled.");
        }
        try {
            final PasskeyService.StartedAssertion started = passkeys
                    .startAssertion(request.getServerName(), request.isSecure());
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("challengeToken", started.challengeToken());
            result.put("publicKey", started.publicKeyOptionsJson());
            return ok(result);
        } catch (final Exception ex) {
            log.warn("Passkey assertion could not start.", ex);
            return error(500, ApiErrors.INTERNAL, "Could not start passkey sign-in.");
        }
    }

    /** Sessionless; success establishes the session and answers the same identity payload as login. */
    @POST
    @Path("login/finish")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response loginFinish(final Map<String, Object> body) {
        final String challengeToken = string(body == null ? null : body.get("challengeToken"));
        final String credential = string(body == null ? null : body.get("credential"));
        if (challengeToken == null || challengeToken.isBlank() || credential == null || credential.isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "Missing challengeToken or credential.");
        }
        final Creds creds = passkeys.finishAssertion(request.getServerName(),
                request.isSecure(), challengeToken, credential);
        if (creds == null) {
            // One message for every failure mode -- see auth/login's enumeration note.
            return error(401, ApiErrors.NOT_AUTHENTICATED, "Passkey sign-in failed.");
        }
        Sessions.establish(request, creds.getEmail(), creds);
        RememberMeService.getInstance().issue(request, response, creds);
        DAO.getInstance().updateLastLogin(creds);
        Audit.builder(AuditAction.LOGIN, AuditOutcome.SUCCESS)
                .actor(creds.getEmail(), creds.getUserId() == null ? null : creds.getUserId().getValue())
                .message("Logged in via passkey")
                .log();
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", creds.getUserId() == null ? null : creds.getUserId().getValue());
        result.put("role", creds.getPriv());
        result.put("csrfHeader", CSRF_HEADER);
        return ok(result);
    }

    @DELETE
    @Path("{credentialId}")
    @TripApi
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response delete(@HeaderParam(CSRF_HEADER) final String csrf,
            @PathParam("credentialId") final String credentialId) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Person.Id owner = personId();
        if (!Boolean.TRUE.equals(DAO.getInstance().deletePasskey(credentialId, owner))) {
            // Owner mismatch and not-found read the same: whether someone ELSE has this key is not an
            // answerable question.
            return error(404, ApiErrors.NOT_FOUND, "No such passkey.");
        }
        Audit.builder(AuditAction.PASSKEY_DELETE, AuditOutcome.SUCCESS)
                .actor(sessionEmail(), owner.getValue())
                .message("Removed a passkey")
                .log();
        return ok(Map.of("deleted", true));
    }

    private String sessionEmail() {
        final HttpSession session = request.getSession(false);
        final Object email = session == null ? null : session.getAttribute(Sessions.LOGIN_EMAIL);
        return email == null ? null : email.toString();
    }

    private static Map<String, Object> describe(final PasskeyCredential passkey) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("credentialId", passkey.getCredentialId());
        result.put("label", passkey.getLabel());
        result.put("domain", passkey.getRpId());
        result.put("created", passkey.getCreated());
        result.put("lastUsed", passkey.getLastUsed());
        return result;
    }

    private static String string(final Object value) {
        return value == null ? null : value.toString();
    }
}
