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
        Assert.assertEquals(flags.get(ApiPrivileges.PEOPLE_ADMIN), Boolean.FALSE);
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
}
