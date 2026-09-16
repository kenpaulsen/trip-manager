package org.paulsens.trip.api;

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
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.LoginCodeCommands;
import org.paulsens.trip.action.MailAddressCommands;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.action.PassCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.api.dto.PersonDto;
import org.paulsens.trip.api.dto.RegisterAccountRequest;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.api.mapper.PersonMapper;
import org.paulsens.trip.model.AuthToken;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.security.PasswordPolicy;
import org.paulsens.trip.security.RememberMeService;
import org.paulsens.trip.security.TokenService;
import org.paulsens.trip.web.Sessions;

/**
 * Sign-in for API clients.
 *
 * <p>A native client has no JSF login page to post to, so this is the way in -- two ways, actually, and they
 * must not blur. The session endpoints ({@code login}, {@code code/*}) establish the same {@code HttpSession}
 * the browser login establishes, with the same attributes. The token endpoints ({@code token},
 * {@code token/refresh}, {@code token/revoke}) are the bearer flows from {@code docs/api-tokens.md} and are
 * strictly SESSIONLESS -- a token client that also holds a {@code JSESSIONID} is two parallel identity
 * models, which is the mess the interim design existed to avoid. Every other resource authenticates through
 * {@link TripAuthFilter}.
 */
@Slf4j
@Path("auth")
public class AuthResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.AUTH_V1;

    private final TokenService tokens;

    public AuthResource() {
        this(TokenService.getInstance());
    }

    // Package-private: tests hand in a service wired to their own config.
    AuthResource(final TokenService tokens) {
        this.tokens = tokens;
    }

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
        Sessions.establish(request, email.trim(), creds);
        RememberMeService.getInstance().issue(request, response, creds);
        return ok(identity(creds));
    }

    /**
     * Emails a login code. Not {@link TripApi}-bound for the same reason {@code login} is not, and the answer
     * is always the same "sent" regardless of whether the address has an account -- the enumeration argument
     * from {@code login}, only stronger, because this endpoint costs nothing to call.
     */
    @POST
    @Path("code/request")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response requestCode(final Map<String, Object> body) {
        final String email = string(body == null ? null : body.get("email"));
        if (email == null || email.isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "Email is required.");
        }
        Beans.get(LoginCodeCommands.class).requestCode(email);
        return ok(Map.of("sent", true));
    }

    /** Signs in with an emailed code; the success payload is identical to {@code login}'s. */
    @POST
    @Path("code/verify")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response verifyCode(final Map<String, Object> body) {
        final String email = string(body == null ? null : body.get("email"));
        final String code = string(body == null ? null : body.get("code"));
        if (email == null || email.isBlank() || code == null || code.isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "Email and code are required.");
        }
        final Creds creds = Beans.get(LoginCodeCommands.class).verifyAndLogin(email, code, request, response);
        if (creds == null) {
            // One message for every failure mode; see login's enumeration note.
            return error(401, ApiErrors.NOT_AUTHENTICATED, "Code is invalid or expired.");
        }
        return ok(identity(creds));
    }

    /**
     * Issues a bearer token pair for a native client: email plus exactly one of password or code, with
     * exactly {@code login}'s checks including the single indistinguishable failure message. Sessionless --
     * a token client that also holds a {@code JSESSIONID} is the two-parallel-identity-models mess the class
     * javadoc warns against, so nothing here may ever touch the session. See {@code docs/api-tokens.md}.
     */
    @POST
    @Path("token")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response token(final Map<String, Object> body) {
        if (!tokens.enabled()) {
            return error(404, ApiErrors.NOT_FOUND, "API tokens are not enabled.");
        }
        final String email = string(body == null ? null : body.get("email"));
        final String password = string(body == null ? null : body.get("password"));
        final String code = string(body == null ? null : body.get("code"));
        final boolean hasPassword = password != null && !password.isBlank();
        final boolean hasCode = code != null && !code.isBlank();
        if (email == null || email.isBlank() || hasPassword == hasCode) {
            return error(400, ApiErrors.BAD_REQUEST, "Email and exactly one of password or code are required.");
        }
        final AuthToken.Scope scope = requestedScope(body);
        if (scope == null) {
            return error(400, ApiErrors.BAD_REQUEST, "Unknown scope; use \"member\" or \"admin\".");
        }
        final Creds creds = hasPassword
                ? Beans.get(PassCommands.class).login(email.trim(), password)
                : Beans.get(LoginCodeCommands.class).verifyForToken(email, code);
        if (creds == null) {
            // One message per credential shape, matching login/verifyCode -- see login's enumeration note.
            return error(401, ApiErrors.NOT_AUTHENTICATED,
                    hasPassword ? "Email or password is incorrect." : "Code is invalid or expired.");
        }
        final TokenService.Grant grant = tokens.issue(creds, grantable(creds, scope), string(body.get("label")));
        if (grant == null) {
            return error(500, ApiErrors.STORE_FAILED, "Could not issue tokens; try again.");
        }
        return ok(grantBody(grant));
    }

    /**
     * Whether an account exists for the address: the native app's first sign-in step, mirroring the
     * website's "Login or Signup" page, which sends a known address to the password page and an unknown one
     * to the create-account page. The website has always answered this question, so the API answers it
     * too; gated with the token feature because it exists for token clients.
     */
    @POST
    @Path("lookup")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response lookup(final Map<String, Object> body) {
        if (!tokens.enabled()) {
            return error(404, ApiErrors.NOT_FOUND, "API tokens are not enabled.");
        }
        final String email = string(body == null ? null : body.get("email"));
        if (email == null || email.isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "Email is required.");
        }
        final boolean known = Beans.get(PassCommands.class).userExistsWithEmail(email.trim());
        return ok(Map.of("email", email.trim(), "known", known));
    }

    /**
     * Creates an account and signs it in, the way {@code account/createAccount.xhtml} does: person row,
     * credentials, the sign-up join for an organization host, the "new account" notice to the office --
     * then a token grant instead of a session. 409 when the address already has an account (the website
     * sends such a visitor to the password reset page; the app does the same with its own copy).
     */
    @POST
    @Path("register")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response register(final RegisterAccountRequest body) {
        if (!tokens.enabled()) {
            return error(404, ApiErrors.NOT_FOUND, "API tokens are not enabled.");
        }
        final String problem = registrationProblem(body);
        if (problem != null) {
            return error(400, ApiErrors.VALIDATION_FAILED, problem);
        }
        final AuthToken.Scope scope = body.scope() == null ? AuthToken.Scope.MEMBER
                : requestedScope(Map.of("scope", body.scope()));
        if (scope == null) {
            return error(400, ApiErrors.BAD_REQUEST, "Unknown scope; use \"member\" or \"admin\".");
        }
        final String email = body.email().trim();
        final PassCommands passes = Beans.get(PassCommands.class);
        // Both halves: a person row, or a login row without one. The latter is what an incompletely deleted
        // account leaves; creating a person against it produced an orphan with no login and a 500
        // (2026-09-16).
        if (passes.userExistsWithEmail(email)
                || DAO.getInstance().getCredsForCodeLogin(email, Cached.NO) != null) {
            return error(409, ApiErrors.CONFLICT, "An account with that email already exists.");
        }
        final PersonCommands people = Beans.get(PersonCommands.class);
        final Person person = people.createPerson();
        fill(person, body, email);
        if (people.emailTakenByAnother(person) || !people.savePerson(person)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not create the account.");
        }
        final AuditActor actor = new AuditActor(email, person.getId().getValue());
        Beans.get(AuditCommands.class).person(person, "CREATED", actor);
        final Creds creds = passes.createCreds(email, body.password());
        if (creds == null) {
            // Roll the person back rather than strand a row that owns the address with no way to sign in; the
            // soft delete frees the email index, so the person can simply try again.
            person.setEmail(null);
            person.delete();
            people.savePerson(person);
            return error(500, ApiErrors.STORE_FAILED, "The account could not be created; please try again.");
        }
        new OrgCommands(this::caller).joinSiteOrgOnSignup(person.getId());
        notifyOffice(person, actor);
        final TokenService.Grant grant = tokens.issue(creds, grantable(creds, scope), body.label());
        if (grant == null) {
            return error(500, ApiErrors.STORE_FAILED, "The account was created; sign in to continue.");
        }
        return ok(grantBody(grant));
    }

    /**
     * The scope a grant actually gets: what was asked for, or {@code member} when {@code admin} was asked for by
     * credentials that do not hold it. Granted, never escalated -- but also never refused once the credentials
     * have been verified. This used to answer 403, and the native client retried with {@code member}; that
     * retry is fine for a password and fatal for an emailed code, which is single-use and was already burned by
     * the first request (every non-admin code sign-in failed, 2026-09-16). Downgrading after verification also
     * tells an unauthenticated caller nothing about who is an admin, which a pre-verification check would.
     * The body's {@code scope} says what was granted, and clients store that, not what they asked for.
     */
    private AuthToken.Scope grantable(final Creds creds, final AuthToken.Scope requested) {
        return tokens.mayGrant(creds, requested) ? requested : AuthToken.Scope.MEMBER;
    }

    /** The create-account page's required fields, checked before anything is written. */
    private static String registrationProblem(final RegisterAccountRequest body) {
        if (body == null) {
            return "A request body is required.";
        }
        final String email = body.email() == null ? "" : body.email().trim();
        final int at = email.indexOf('@');
        if (at < 1 || at == email.length() - 1) {
            return "A valid email address is required.";
        }
        if (blank(body.first()) || blank(body.last())) {
            return "First and last name are required.";
        }
        if (body.sex() == null || parseSex(body.sex()) == null) {
            return "sex must be Male or Female.";
        }
        return PasswordPolicy.problem(body.password());
    }

    private static boolean blank(final String value) {
        return value == null || value.isBlank();
    }

    private static Person.Sex parseSex(final String value) {
        for (final Person.Sex sex : Person.Sex.values()) {
            if (sex.name().equalsIgnoreCase(value.trim())) {
                return sex;
            }
        }
        return null;
    }

    private static void fill(final Person person, final RegisterAccountRequest body, final String email) {
        person.setEmail(email);
        person.setNickname(trimmed(body.nickname()));
        person.setFirst(body.first().trim());
        person.setMiddle(trimmed(body.middle()));
        person.setLast(body.last().trim());
        person.setSex(parseSex(body.sex()));
        person.setCell(trimmed(body.cell()));
        person.setBirthdate(body.birthdate());
    }

    private static String trimmed(final String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * The create-account page's "New Account Created" note to the office, best effort: an account that
     * exists but whose notice failed to send is still an account, and must not read as a failed sign-up.
     */
    private static void notifyOffice(final Person person, final AuditActor actor) {
        try {
            final MailAddressCommands addresses = Beans.get(MailAddressCommands.class);
            final String to = addresses.recipientFor("account.notify.email", null);
            if (to == null || to.isBlank()) {
                return;
            }
            Beans.get(MailCommands.class).send(addresses.fromFor("account.notify.from"), to, null, null,
                    "New Account Created", person.getFirst() + " " + person.getLast() + " (" + person.getEmail()
                            + ") created a new account in the UniteTrip app.", actor);
        } catch (final RuntimeException ex) {
            log.warn("New-account notice not sent for {}", person.getEmail(), ex);
        }
    }

    /** Trades a refresh token for a fresh grant, rotating the refresh validator. Sessionless like token. */
    @POST
    @Path("token/refresh")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response refreshToken(final Map<String, Object> body) {
        if (!tokens.enabled()) {
            return error(404, ApiErrors.NOT_FOUND, "API tokens are not enabled.");
        }
        final String presented = string(body == null ? null : body.get("refreshToken"));
        if (presented == null || presented.isBlank()) {
            return error(400, ApiErrors.BAD_REQUEST, "A refreshToken is required.");
        }
        final TokenService.Grant grant = tokens.refresh(presented);
        if (grant == null) {
            // One message for every refusal -- expired, revoked, stolen, unknown -- on purpose.
            return error(401, ApiErrors.NOT_AUTHENTICATED, "Refresh token is invalid or expired.");
        }
        return ok(grantBody(grant));
    }

    /**
     * Revokes the presented refresh token and its access-token children. Idempotent like {@code logout}:
     * revoking what no longer exists is a success. No CSRF sentinel -- the authority is the token in the
     * body, not an ambient cookie, so a cross-origin form gains nothing here.
     */
    @POST
    @Path("token/revoke")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response revokeToken(final Map<String, Object> body) {
        if (!tokens.enabled()) {
            return error(404, ApiErrors.NOT_FOUND, "API tokens are not enabled.");
        }
        tokens.revoke(string(body == null ? null : body.get("refreshToken")));
        return ok(Map.of("revoked", true));
    }

    /**
     * The requested scope, defaulted to MEMBER; null means the request named a scope that does not exist
     * (a 400, never a silent downgrade -- a client that asked for admin should hear "no", not act surprised
     * later).
     */
    private static AuthToken.Scope requestedScope(final Map<String, Object> body) {
        final String raw = string(body == null ? null : body.get("scope"));
        if (raw == null || raw.isBlank() || "member".equalsIgnoreCase(raw.trim())) {
            return AuthToken.Scope.MEMBER;
        }
        return "admin".equalsIgnoreCase(raw.trim()) ? AuthToken.Scope.ADMIN : null;
    }

    /** The wire shape of a grant. refreshToken is OMITTED when unchanged -- "keep the one you presented". */
    private static Map<String, Object> grantBody(final TokenService.Grant grant) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("accessToken", grant.accessToken());
        result.put("accessExpiresIn", grant.accessExpiresIn());
        if (grant.refreshToken() != null) {
            result.put("refreshToken", grant.refreshToken());
        }
        result.put("scope", grant.scope());
        return result;
    }

    /**
     * The caller's signed-in devices: browser remember-me registrations and API refresh tokens, for the
     * profile page's devices fieldset ({@code docs/api-tokens.md}). {@link TripApi}-bound -- session or
     * token, a phone can manage itself. ACCESS rows are an implementation detail and are not listed.
     */
    @GET
    @Path("sessions")
    @TripApi
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response sessions() {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessions", tokens.sessionsFor(personId()).stream().map(AuthResource::describe).toList());
        return ok(result);
    }

    /**
     * Revokes one signed-in device, cascading to its access tokens. Owner-checked in the service; 404 for
     * missing and not-owned alike -- whether someone ELSE has this token is not an answerable question
     * (the passkey-delete rule). CSRF-checked for cookie callers; a bearer caller is exempt as everywhere.
     */
    @DELETE
    @Path("sessions/{selector}")
    @TripApi
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response revokeSession(@PathParam("selector") final String selector,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!tokens.revokeSession(personId(), selector)) {
            return error(404, ApiErrors.NOT_FOUND, "No such signed-in device.");
        }
        return ok(Map.of("revoked", true));
    }

    /** The wire shape of one signed-in device. Browser rows carry no label; the UI names them by kind. */
    private static Map<String, Object> describe(final AuthToken token) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("selector", token.getSelector());
        result.put("kind", token.getKind().name());
        result.put("label", token.getLabel());
        result.put("created", token.getCreated());
        result.put("lastUsed", token.getLastUsed());
        result.put("expires", token.getExpires());
        return result;
    }

    /** Ends the session. Idempotent: signing out when already signed out is a success, not a 401. */
    @POST
    @Path("logout")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response logout(@HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        RememberMeService.getInstance().revoke(request, response);
        // The page logout's funnel: invalidates AND expires the (domain-wide, on an org site) cookie.
        Sessions.logout(request, response);
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
        final Person person = findPerson(personId());
        if (person == null) {
            // The session names a person who is no longer there -- a deleted account with a live token or
            // session. 401, not 404: the native client reads 404 as "API tokens are switched off" and parks on
            // that screen with no way to sign out, which is exactly what a phone holding a deleted account's
            // token did (2026-09-16). Its tokens are revoked here too, so the refresh that follows the 401
            // fails and the client returns to the sign-in screen instead of retrying forever.
            if (personId() != null) {
                tokens.revokeAllFor(personId());
            }
            return error(401, ApiErrors.NOT_AUTHENTICATED, "Signed-in person not found.");
        }
        final ApiPrivileges privileges = privileges();
        final PersonDto dto = PersonMapper.INSTANCE.toDto(person).redactedFor(AccessLevel.SELF);
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("person", dto);
        result.put("siteAdmin", privileges.isSiteAdmin());
        result.put("privileges", privilegeFlags(privileges));
        // Self only, so it rides beside the redacted person rather than inside the shared PersonDto.
        result.put("defaultOrgId", person.getDefaultOrgId() == null ? null : person.getDefaultOrgId().getValue());
        return ok(result);
    }

    /**
     * Client hints for the GLOBAL privileges only. {@code peopleAdmin}, {@code emailAdmin}, and
     * {@code addTrip} are org-scoped now (org migration, 2026-08) and no longer appear -- old clients read
     * an absent flag as false, which is the correct answer for the retired global variants.
     */
    private static Map<String, Boolean> privilegeFlags(final ApiPrivileges privileges) {
        final Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put(ApiPrivileges.PRIVILEGE_ADMIN, privileges.has(ApiPrivileges.PRIVILEGE_ADMIN));
        flags.put(ApiPrivileges.CONFIG_ADMIN, privileges.has(ApiPrivileges.CONFIG_ADMIN));
        flags.put(ApiPrivileges.AUDIT_ADMIN, privileges.has(ApiPrivileges.AUDIT_ADMIN));
        flags.put(ApiPrivileges.MEDIA_ADMIN, privileges.has(ApiPrivileges.MEDIA_ADMIN));
        flags.put(ApiPrivileges.SITE_DEPLOYER, privileges.has(ApiPrivileges.SITE_DEPLOYER));
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
