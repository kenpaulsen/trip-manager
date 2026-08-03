package org.paulsens.trip.api;

import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.PassCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.api.dto.PersonDto;
import org.paulsens.trip.api.mapper.PersonMapper;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;

/**
 * Sign-in for API clients.
 *
 * <p>A native client has no JSF login page to post to, so this is the way in. It establishes the same
 * {@code HttpSession} the browser login establishes, with the same attributes, and every other resource then
 * authenticates against it through {@link TripAuthFilter}. Bearer tokens are the intended end state and are
 * tracked separately; nothing here should grow into a second, parallel identity model in the meantime.
 */
@Slf4j
@Path("auth")
public class AuthResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.AUTH_V1;

    /**
     * Set on the session at login and read by {@code AuditActor}.
     *
     * <p>Not exposed by {@code PersonCommands} the way {@code ACTIVE_USER_ID} is, because until now only the
     * XHTML login set it, by assigning {@code sessionScope.loginEmail} directly. Leaving it unset here would
     * cost every audited API write its actor EMAIL while still recording an id -- a half-known actor, which
     * looks like a populated record until someone tries to read it.
     */
    private static final String LOGIN_EMAIL = "loginEmail";
    /** The admin's own id, retained across "act as user" so the pages can tell who is really signed in. */
    private static final String ADMIN_USER = "aUser";

    @Override
    protected String versionedType() {
        return V1;
    }

    /**
     * Signs in and establishes a session.
     *
     * <p>Deliberately NOT bound by {@link TripApi}: it is the one endpoint that must work without a session.
     * Every other method on this resource carries the binding individually.
     */
    @POST
    @Path("login")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response login(final Map<String, Object> body) {
        final String email = string(body == null ? null : body.get("email"));
        final String password = string(body == null ? null : body.get("password"));
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "Email and password are required.");
        }
        final Creds creds = Beans.get(PassCommands.class).login(email.trim(), password);
        if (creds == null) {
            // One message for "no such account" and for "wrong password", on purpose: distinguishing them turns
            // this endpoint into an account-enumeration oracle, which is worse over an API than over a form.
            return error(401, ApiErrors.NOT_AUTHENTICATED, "Email or password is incorrect.");
        }
        establishSession(email.trim(), creds);
        return ok(identity(creds));
    }

    /**
     * Populates a fresh session with what the rest of the application expects to find on it.
     *
     * <p>The old session is invalidated FIRST. Reusing whatever session id the caller arrived with lets an
     * attacker who can plant a cookie fix the victim's session id before they sign in, and then ride the
     * authenticated session afterwards. Invalidating means the id they planted is not the id that ends up
     * authenticated.
     *
     * <p>All four attributes are set because all four are read somewhere: {@code userId} by the auth filter,
     * {@code userRole} by {@code PersonCommands.hasRole}, {@code loginEmail} by {@code AuditActor}, and
     * {@code aUser} by the admin pages. The browser login sets the same four across two of its steps; an API
     * session that sets fewer is subtly different from a real one in ways that surface far from here.
     */
    private void establishSession(final String email, final Creds creds) {
        final HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        final HttpSession session = request.getSession(true);
        final String role = creds.getPriv();
        session.setAttribute(PersonCommands.ACTIVE_USER_ID, creds.getUserId());
        session.setAttribute(PersonCommands.ACTIVE_USER_ROLE, role);
        session.setAttribute(LOGIN_EMAIL, email);
        session.setAttribute(ADMIN_USER, role != null && role.contains("admin") ? creds.getUserId() : null);
    }

    /** Ends the session. Idempotent: signing out when already signed out is a success, not a 401. */
    @POST
    @Path("logout")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response logout(@HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ok(Map.of("signedOut", true));
    }

    /**
     * Who the caller is, as the client should display them, plus what they are allowed to do.
     *
     * <p>Returns the caller's own record, so it is {@code SELF} and nothing is redacted. The privilege flags are
     * included so a client can hide affordances it would only be refused on -- while every endpoint still checks
     * for itself, because a flag on a JSON body is a hint, not a gate.
     */
    @GET
    @Path("me")
    @TripApi
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response me() {
        final Person.Id me = personId();
        final Person person = Beans.get(PersonCommands.class).getPerson(me);
        if (person == null) {
            // The session names a person who is no longer there -- a deleted account with a live session.
            return error(404, ApiErrors.NOT_FOUND, "Signed-in person not found.");
        }
        final ApiPrivileges privileges = privileges();
        final PersonDto dto = PersonMapper.INSTANCE.toDto(person).redactedFor(AccessLevel.SELF);
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("person", dto);
        result.put("siteAdmin", privileges.isSiteAdmin());
        result.put("privileges", privilegeFlags(privileges));
        return ok(result);
    }

    private static Map<String, Boolean> privilegeFlags(final ApiPrivileges privileges) {
        final Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put(ApiPrivileges.PEOPLE_ADMIN, privileges.has(ApiPrivileges.PEOPLE_ADMIN));
        flags.put(ApiPrivileges.PRIVILEGE_ADMIN, privileges.has(ApiPrivileges.PRIVILEGE_ADMIN));
        flags.put(ApiPrivileges.CONFIG_ADMIN, privileges.has(ApiPrivileges.CONFIG_ADMIN));
        flags.put(ApiPrivileges.AUDIT_ADMIN, privileges.has(ApiPrivileges.AUDIT_ADMIN));
        flags.put(ApiPrivileges.MEDIA_ADMIN, privileges.has(ApiPrivileges.MEDIA_ADMIN));
        flags.put(ApiPrivileges.EMAIL_ADMIN, privileges.has(ApiPrivileges.EMAIL_ADMIN));
        flags.put(ApiPrivileges.SITE_DEPLOYER, privileges.has(ApiPrivileges.SITE_DEPLOYER));
        flags.put(ApiPrivileges.ADD_TRIP, privileges.has(ApiPrivileges.ADD_TRIP));
        return flags;
    }

    /** The minimum a client needs to proceed after a successful login. */
    private Map<String, Object> identity(final Creds creds) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", creds.getUserId() == null ? null : creds.getUserId().getValue());
        result.put("role", creds.getPriv());
        result.put("csrfHeader", CSRF_HEADER);
        return result;
    }

    private static String string(final Object value) {
        return value == null ? null : value.toString();
    }
}
