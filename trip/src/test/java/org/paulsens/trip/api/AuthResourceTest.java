package org.paulsens.trip.api;

import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.PassCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link AuthResource}: the way in for a client with no JSF login page.
 *
 * <p>Two properties here are security rather than behaviour, and both are invisible to a test that only checks
 * status codes: a failed login must not reveal WHICH half was wrong, and a successful one must not keep the
 * session id the caller arrived with.
 */
public class AuthResourceTest extends ResourceTestSupport {

    private static final Person.Id ME = Person.Id.from("auth-me");

    private PassCommands passes;
    private AuthResource resource;
    private HttpSession freshSession;
    private Map<String, Object> freshAttributes;

    @BeforeMethod
    public void bindBeans() {
        passes = bindMock(PassCommands.class);
        bindMock(PersonCommands.class);
        freshAttributes = new HashMap<>();
        freshSession = Mockito.mock(HttpSession.class);
        Mockito.doAnswer(call -> freshAttributes.put(call.getArgument(0), call.getArgument(1)))
                .when(freshSession).setAttribute(ArgumentMatchers.anyString(), ArgumentMatchers.any());
        Mockito.when(request.getSession(true)).thenReturn(freshSession);
        resource = resource(new AuthResource());
    }

    private static Creds creds(final String role) {
        final Creds creds = new Creds("me@example.com", ME, "hashed");
        creds.setPriv(role);
        return creds;
    }

    @Test
    public void loginNeedsBothAnEmailAndAPassword() {
        assertError(resource.login(null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.login(Map.of("email", "me@example.com")), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.login(Map.of("password", "secret")), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.login(Map.of("email", "  ", "password", "secret")), 400, ApiErrors.BAD_REQUEST);
    }

    /**
     * One message for "no such account" and for "wrong password".
     *
     * <p>Distinguishing them turns this into an account-enumeration oracle, which is worse over an API than
     * over a form because it is cheap to script.
     */
    @Test
    public void aBadLoginDoesNotSayWhichHalfWasWrong() {
        Mockito.when(passes.login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenReturn(null);

        final Response unknownAccount = resource.login(Map.of("email", "nobody@example.com", "password", "x"));
        final Response wrongPassword = resource.login(Map.of("email", "me@example.com", "password", "wrong"));

        assertError(unknownAccount, 401, ApiErrors.NOT_AUTHENTICATED);
        assertError(wrongPassword, 401, ApiErrors.NOT_AUTHENTICATED);
        Assert.assertEquals(message(unknownAccount), message(wrongPassword),
                "The two failures must be indistinguishable to the caller");
    }

    @Test
    public void aSuccessfulLoginReturnsWhatAClientNeedsToProceed() {
        Mockito.when(passes.login("me@example.com", "secret")).thenReturn(creds("user"));

        final Response response = resource.login(Map.of("email", " me@example.com ", "password", "secret"));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("userId"), ME.getValue());
        Assert.assertEquals(body.get("role"), "user");
        // The client is told which header to send back, rather than having to hard-code it.
        Assert.assertEquals(body.get("csrfHeader"), BaseResource.CSRF_HEADER);
    }

    /**
     * Session fixation. An attacker who can plant a cookie fixes the victim's session id before they sign in and
     * rides the authenticated session afterwards; invalidating first means the planted id is not the one that
     * ends up authenticated.
     */
    @Test
    public void loginAbandonsTheSessionTheCallerArrivedWith() {
        Mockito.when(passes.login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(creds("user"));

        assertOk(resource.login(Map.of("email", "me@example.com", "password", "secret")));

        Mockito.verify(session).invalidate();
        Mockito.verify(request).getSession(true);
    }

    /**
     * All four attributes, because all four are read somewhere. A session missing loginEmail costs every audited
     * API write its actor email while still recording an id, which reads as a populated record until someone
     * tries to use it.
     */
    @Test
    public void loginPopulatesEverythingTheRestOfTheAppExpects() {
        Mockito.when(passes.login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(creds("user"));

        assertOk(resource.login(Map.of("email", "me@example.com", "password", "secret")));

        Assert.assertEquals(freshAttributes.get(PersonCommands.ACTIVE_USER_ID), ME);
        Assert.assertEquals(freshAttributes.get(PersonCommands.ACTIVE_USER_ROLE), "user");
        Assert.assertEquals(freshAttributes.get("loginEmail"), "me@example.com");
        Assert.assertNull(freshAttributes.get("aUser"), "An ordinary user is not an acting admin");
    }

    @Test
    public void anAdminLoginRetainsTheAdminsOwnId() {
        Mockito.when(passes.login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(creds("admin"));

        assertOk(resource.login(Map.of("email", "me@example.com", "password", "secret")));

        Assert.assertEquals(freshAttributes.get("aUser"), ME, "The admin pages need to know who is really here");
    }

    @Test
    public void requestCodeNeedsAnEmailAndAlwaysSaysSent() {
        final org.paulsens.trip.action.LoginCodeCommands codes =
                bindMock(org.paulsens.trip.action.LoginCodeCommands.class);

        assertError(resource.requestCode(null), 400, ApiErrors.BAD_REQUEST);
        assertError(resource.requestCode(Map.of("email", " ")), 400, ApiErrors.BAD_REQUEST);

        final Response known = resource.requestCode(Map.of("email", "me@example.com"));
        final Response unknown = resource.requestCode(Map.of("email", "nobody@example.com"));
        assertOk(known);
        assertOk(unknown);
        // The bodies must be identical whatever happened server-side: this endpoint is free to call, so any
        // difference is a scriptable account-enumeration oracle.
        Assert.assertEquals(entity(known), entity(unknown));
        Mockito.verify(codes).requestCode("me@example.com");
    }

    @Test
    public void verifyCodeFailuresAreIndistinguishable() {
        final org.paulsens.trip.action.LoginCodeCommands codes =
                bindMock(org.paulsens.trip.action.LoginCodeCommands.class);
        Mockito.when(codes.verifyAndLogin(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(null);

        assertError(resource.verifyCode(Map.of("email", "me@example.com")), 400, ApiErrors.BAD_REQUEST);
        final Response wrong = resource.verifyCode(Map.of("email", "me@example.com", "code", "000000"));
        final Response unknown = resource.verifyCode(Map.of("email", "nobody@example.com", "code", "123456"));
        assertError(wrong, 401, ApiErrors.NOT_AUTHENTICATED);
        assertError(unknown, 401, ApiErrors.NOT_AUTHENTICATED);
        Assert.assertEquals(message(wrong), message(unknown),
                "wrong code and unknown account must read the same");
    }

    @Test
    public void verifyCodeSuccessLooksExactlyLikeALoginSuccess() {
        final org.paulsens.trip.action.LoginCodeCommands codes =
                bindMock(org.paulsens.trip.action.LoginCodeCommands.class);
        Mockito.when(codes.verifyAndLogin(ArgumentMatchers.eq("me@example.com"), ArgumentMatchers.eq("123456"),
                ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(creds("user"));

        final Response response = resource.verifyCode(Map.of("email", "me@example.com", "code", "123456"));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("userId"), ME.getValue());
        Assert.assertEquals(body.get("role"), "user");
        Assert.assertEquals(body.get("csrfHeader"), BaseResource.CSRF_HEADER);
    }

    private static Object entity(final Response response) {
        return response.getEntity();
    }

    @Test
    public void logoutRequiresTheCsrfHeader() {
        assertError(resource.logout(null), 403, ApiErrors.CSRF);
        Mockito.verify(session, Mockito.never()).invalidate();
    }

    @Test
    public void logoutEndsTheSession() {
        assertOk(resource.logout(CSRF_OK));

        Mockito.verify(session).invalidate();
    }

    /** Signing out when already signed out is a success, not a 401. */
    @Test
    public void logoutIsIdempotent() {
        anonymous();

        assertOk(resource.logout(CSRF_OK));
    }

    @Test
    public void meAnswers404WhenTheSessionNamesSomebodyWhoIsGone() {
        signedInAs(ME);
        // A deleted account with a live session: getPerson answers a blank Person with a fresh id, so only the
        // findPerson id comparison catches it.
        Mockito.when(bean(PersonCommands.class).getPerson(ArgumentMatchers.any())).thenReturn(new Person());

        assertError(resource.me(), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void meReturnsTheCallersOwnUnredactedRecordAndTheirFlags() {
        signedInAs(ME);
        final Person me = new Person();
        me.setId(ME);
        me.setFirst("Ken");
        me.setCell("555-0100");
        Mockito.when(bean(PersonCommands.class).getPerson(ME)).thenReturn(me);

        final Response response = resource.me();

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("siteAdmin"), false);
        @SuppressWarnings("unchecked")
        final Map<String, Boolean> flags = (Map<String, Boolean>) body.get("privileges");
        // peopleAdmin/emailAdmin/addTrip are org-scoped now (org migration, 2026-08): their global flags
        // are gone, and old clients correctly read an absent flag as false.
        Assert.assertFalse(flags.containsKey(ApiPrivileges.PEOPLE_ADMIN));
        Assert.assertFalse(flags.containsKey(ApiPrivileges.EMAIL_ADMIN));
        Assert.assertTrue(flags.containsKey(ApiPrivileges.SITE_DEPLOYER));
    }

    @Test
    public void anAdminSeesEveryFlagSet() {
        signedInAsSiteAdmin(ME);
        final Person me = new Person();
        me.setId(ME);
        Mockito.when(bean(PersonCommands.class).getPerson(ME)).thenReturn(me);

        final Response response = resource.me();

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("siteAdmin"), true);
        @SuppressWarnings("unchecked")
        final Map<String, Boolean> flags = (Map<String, Boolean>) body.get("privileges");
        // A site admin short-circuits every named privilege, so none of these needs a row to exist.
        flags.forEach((name, granted) -> Assert.assertTrue(granted, name + " should be implied by site admin"));
    }

    @Test
    public void theProducedTypeIsTheAuthMediaType() {
        Assert.assertEquals(new AuthResource().versionedType(), ApiMediaTypes.AUTH_V1);
    }

    private static String message(final Response response) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        return String.valueOf(body.get("message"));
    }

    // ---- Bearer-token endpoints (docs/api-tokens.md) ----

    /** A resource whose token feature is ON, wired to its own config; the DAO underneath is the real fake. */
    private AuthResource tokenResource() {
        final org.paulsens.trip.action.ConfigCommands config =
                Mockito.mock(org.paulsens.trip.action.ConfigCommands.class);
        Mockito.when(config.getBoolean(org.paulsens.trip.config.KnownSettings.API_TOKEN_ENABLED))
                .thenReturn(true);
        Mockito.when(config.getInt(org.paulsens.trip.config.KnownSettings.API_TOKEN_ACCESS_MINUTES, 5, 240))
                .thenReturn(30);
        Mockito.when(config.getInt(org.paulsens.trip.config.KnownSettings.API_TOKEN_REFRESH_DAYS, 1, 365))
                .thenReturn(60);
        Mockito.when(config.getInt(org.paulsens.trip.config.KnownSettings.API_TOKEN_REFRESH_ADMIN_DAYS, 1, 30))
                .thenReturn(7);
        return resource(new AuthResource(new org.paulsens.trip.security.TokenService(config)));
    }

    /** The kill switch covers all three endpoints -- and off is the config DEFAULT, which is what this uses. */
    @Test
    public void tokenEndpointsAreRefusedWhileTheFeatureIsOff() {
        assertError(resource.token(Map.of("email", "me@example.com", "password", "x")),
                404, ApiErrors.NOT_FOUND);
        assertError(resource.refreshToken(Map.of("refreshToken", "a:b")), 404, ApiErrors.NOT_FOUND);
        assertError(resource.revokeToken(Map.of("refreshToken", "a:b")), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void tokenNeedsAnEmailAndExactlyOneCredential() {
        final AuthResource live = tokenResource();
        assertError(live.token(null), 400, ApiErrors.BAD_REQUEST);
        assertError(live.token(Map.of("email", "me@example.com")), 400, ApiErrors.BAD_REQUEST);
        assertError(live.token(Map.of("password", "secret")), 400, ApiErrors.BAD_REQUEST);
        assertError(live.token(Map.of("email", "me@example.com", "password", "secret", "code", "123456")),
                400, ApiErrors.BAD_REQUEST);
        assertError(live.token(Map.of("email", " ", "password", "secret")), 400, ApiErrors.BAD_REQUEST);
    }

    @Test
    public void aBadTokenLoginDoesNotSayWhichHalfWasWrong() {
        Mockito.when(passes.login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString())).thenReturn(null);
        final AuthResource live = tokenResource();

        final Response unknown = live.token(Map.of("email", "nobody@example.com", "password", "x"));
        final Response wrong = live.token(Map.of("email", "me@example.com", "password", "wrong"));

        assertError(unknown, 401, ApiErrors.NOT_AUTHENTICATED);
        assertError(wrong, 401, ApiErrors.NOT_AUTHENTICATED);
        Assert.assertEquals(message(unknown), message(wrong),
                "The two failures must be indistinguishable to the caller");
    }

    @Test
    public void anUnknownScopeIsRefusedNotSilentlyDowngraded() {
        assertError(tokenResource().token(Map.of("email", "me@example.com", "password", "x", "scope", "root")),
                400, ApiErrors.BAD_REQUEST);
    }

    @Test
    public void adminScopeIsForbiddenForMemberCredentials() {
        Mockito.when(passes.login(ArgumentMatchers.anyString(), ArgumentMatchers.anyString()))
                .thenReturn(creds("user"));
        assertError(tokenResource().token(
                        Map.of("email", "me@example.com", "password", "secret", "scope", "admin")),
                403, ApiErrors.FORBIDDEN);
    }

    /** The property the whole design hangs on: a token grant must never create or touch a session. */
    @Test
    public void aTokenGrantIsCompleteAndStrictlySessionless() {
        Mockito.when(passes.login("me@example.com", "secret")).thenReturn(creds("user"));

        final Response response = tokenResource().token(
                Map.of("email", " me@example.com ", "password", "secret", "label", "Ken's phone"));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertNotNull(body.get("accessToken"));
        Assert.assertNotNull(body.get("refreshToken"));
        Assert.assertEquals(body.get("accessExpiresIn"), 30 * 60L);
        Assert.assertEquals(body.get("scope"), "member");
        Mockito.verify(request, Mockito.never()).getSession(true);
        Mockito.verify(session, Mockito.never()).invalidate();
        Assert.assertTrue(freshAttributes.isEmpty(), "nothing may be stashed on any session");
    }

    @Test
    public void theCodePathVerifiesWithoutASessionToo() {
        final org.paulsens.trip.action.LoginCodeCommands codes =
                bindMock(org.paulsens.trip.action.LoginCodeCommands.class);
        Mockito.when(codes.verifyForToken("me@example.com", "123456")).thenReturn(creds("user"));

        assertOk(tokenResource().token(Map.of("email", "me@example.com", "code", "123456")));

        Mockito.verify(codes).verifyForToken("me@example.com", "123456");
        Mockito.verify(codes, Mockito.never()).verifyAndLogin(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.verify(request, Mockito.never()).getSession(true);
    }

    @Test
    public void refreshRoundTripsThroughTheEndpointAndRefusesGarbage() {
        // A REAL local-mode account, not the mocked creds: refresh re-reads credentials from the store, so a
        // grant issued to an account that does not exist there is (correctly) revoked at its first refresh.
        Mockito.when(passes.login("user2", "user")).thenReturn(new PassCommands().login("user2", "user"));
        final AuthResource live = tokenResource();
        @SuppressWarnings("unchecked")
        final Map<String, Object> grant = (Map<String, Object>)
                live.token(Map.of("email", "user2", "password", "user")).getEntity();

        final Response refreshed = live.refreshToken(Map.of("refreshToken", grant.get("refreshToken")));
        assertOk(refreshed);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) refreshed.getEntity();
        Assert.assertNotNull(body.get("accessToken"));
        Assert.assertNotNull(body.get("refreshToken"), "a CURRENT refresh answers the rotated token");

        assertError(live.refreshToken(null), 400, ApiErrors.BAD_REQUEST);
        assertError(live.refreshToken(Map.of("refreshToken", "unknown:validator")),
                401, ApiErrors.NOT_AUTHENTICATED);
        assertOk(live.revokeToken(Map.of("refreshToken", body.get("refreshToken"))));
    }

    @Test
    public void revokeIsIdempotentAndQuiet() {
        final AuthResource live = tokenResource();
        assertOk(live.revokeToken(null));
        assertOk(live.revokeToken(Map.of("refreshToken", "unknown:validator")));
        assertOk(live.revokeToken(Map.of("refreshToken", "garbage")));
    }
}
