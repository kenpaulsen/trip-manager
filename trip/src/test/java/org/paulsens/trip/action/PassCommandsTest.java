package org.paulsens.trip.action;

import jakarta.faces.component.UIViewRoot;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.dynamo.CredentialsDAO;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link PassCommands} against local-mode fake data, where any email starting {@code admin}/{@code user} signs in
 * with that word as the password.
 *
 * <p>The behaviours worth pinning are the refusals: the view-map admin check failing CLOSED off a Faces thread,
 * a password change refused when the credentials belong to somebody else, and an email change refused when the
 * new address is already someone else's login.
 */
public class PassCommandsTest {

    private final PassCommands commands = new PassCommands();

    @Test
    public void aValidLoginAnswersCredsAndABadOneAnswersNull() {
        final Creds good = commands.login("admin", "admin");
        Assert.assertNotNull(good);
        Assert.assertEquals(good.getPriv(), "admin");

        Assert.assertNull(commands.login("admin", "wrong-password"));
        Assert.assertNull(commands.login("stranger@nowhere", "whatever"));
    }

    @Test
    public void blankCredentialsAreAnErrorNotAFailedLogin() {
        Assert.assertThrows(IllegalArgumentException.class, () -> commands.login(" ", "pass"));
        Assert.assertThrows(IllegalArgumentException.class, () -> commands.login("a@b", " "));
        Assert.assertThrows(IllegalArgumentException.class, () -> commands.getCreds(null, "x"));
        Assert.assertThrows(IllegalArgumentException.class, () -> commands.adminGetCreds(" "));
        Assert.assertThrows(IllegalArgumentException.class, () -> commands.adminSetPass(" ", "x"));
        // The Caller form refuses a null caller rather than throwing: authorization failure, not bad input.
        Assert.assertFalse(commands.adminSetPass("a@b", "x", null));
        Assert.assertThrows(IllegalArgumentException.class, () -> commands.getCredsByAdmin(" ", null));
    }

    @Test
    public void userExistsChecksTheEmail() {
        Assert.assertTrue(commands.userExistsWithEmail("admin"));
        Assert.assertFalse(commands.userExistsWithEmail("nobody@nowhere.example"));
    }

    /** Off a Faces thread there is no view map: the check must fail CLOSED, not blow up. */
    @Test
    public void theViewMapAdminSetPassFailsClosedWithoutAFacesContext() {
        Assert.assertFalse(commands.adminSetPass("user2", "newpass"));
    }

    @Test
    public void theViewMapAdminSetPassHonoursTheFlagWhenPresent() {
        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            final FacesContext ctx = Mockito.mock(FacesContext.class);
            final UIViewRoot viewRoot = Mockito.mock(UIViewRoot.class);
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);
            Mockito.when(ctx.getViewRoot()).thenReturn(viewRoot);

            // Flag absent: refused.
            Mockito.when(viewRoot.getViewMap(false)).thenReturn(Map.of());
            Assert.assertFalse(commands.adminSetPass("user2", "newpass"));

            // Flag present: allowed, and the underlying set succeeds against the fake store.
            Mockito.when(viewRoot.getViewMap(false)).thenReturn(Map.of(CredentialsDAO.IS_ADMIN, "true"));
            Assert.assertTrue(commands.adminSetPass("user2", "newpass"));
        }
    }

    @Test
    public void theCallerFormAuthorizesByPrivilegeNotViewState() {
        final PrivilegeCommands refusing = Mockito.mock(PrivilegeCommands.class);
        final Caller nobody = new Caller(Person.Id.from("p1"), false,
                org.paulsens.trip.audit.AuditActor.from(null), refusing);
        Assert.assertFalse(commands.adminSetPass("user2", "newpass", nobody));

        final Caller admin = new Caller(Person.Id.from("p1"), true,
                org.paulsens.trip.audit.AuditActor.from(null), refusing);
        Assert.assertTrue(commands.adminSetPass("user2", "newpass", admin));
    }

    @Test
    public void changingYourOwnPasswordNeedsMatchingConfirmationAndYourOwnCreds() {
        Assert.assertFalse(commands.setPass("user2", "user", "new", "different"),
                "Mismatched confirmation must refuse");
        Assert.assertFalse(commands.setPass("user2", "wrong-current", "new", "new"),
                "A wrong current password must refuse");
        Assert.assertTrue(commands.setPass("user2", "user", "new", "new"));
    }

    /**
     * The fake store hands the ADMIN person's id back for creds the email cannot resolve, so a mismatched
     * person/creds pair refuses. This is the "not your credentials" branch.
     */
    @Test
    public void aCredsPersonMismatchRefusesThePasswordChange() {
        // "admin2" resolves creds (starts with admin) whose userId falls back to the email, but no Person named
        // admin2 exists -- person null -> refused.
        Assert.assertFalse(commands.setPass("admin2", "admin", "new", "new"));
    }

    @Test
    public void deleteCredsReportsTheStoreAnswer() {
        // Local mode: removeCreds succeeds for non-admin, refuses admin creds.
        Assert.assertNotNull(commands.deleteCreds("user2"));
    }

    /**
     * The in-use branch needs credentials the local-mode fake cannot answer (adminGetCredsByEmail bypasses the
     * fake's test-creds table), so the credential lookups are stubbed on a spy while setEmail's own logic runs.
     */
    @Test
    public void anEmailAlreadyInUseByAnotherAccountIsRevertedAndRefused() {
        final PassCommands spied = Mockito.spy(new PassCommands());
        final Creds taken = new Creds("admin", Person.Id.from("the-real-admin"), "x");
        Mockito.doReturn(taken).when(spied).adminGetCreds("admin");

        final Person person = new Person();
        person.setId(Person.Id.from("someone-new"));
        person.setEmail("admin"); // the address they want, which belongs to the admin account

        Assert.assertFalse(spied.setEmail(person, "old@example.org", "admin"));
        Assert.assertEquals(person.getEmail(), "old@example.org", "The in-memory object must be reverted");
    }

    @Test
    public void aLegitimateEmailChangeRewritesTheOldCreds() {
        final PassCommands spied = Mockito.spy(new PassCommands());
        final Person person = new Person();
        person.setId(Person.Id.from("p-legit"));
        final Creds mine = new Creds("old@example.org", person.getId(), "x");
        Mockito.doReturn(null).when(spied).adminGetCreds("new@example.org");
        Mockito.doReturn(mine).when(spied).adminGetCreds("old@example.org");

        Assert.assertTrue(spied.setEmail(person, "old@example.org", "new@example.org"));
        Assert.assertEquals(mine.getEmail(), "new@example.org");
    }

    @Test
    public void anUnchangedOrNullEmailIsANoop() {
        final Person person = new Person();
        Assert.assertFalse(commands.setEmail(person, "same@example.org", "same@example.org"));
        Assert.assertFalse(commands.setEmail(person, "same@example.org", null));
    }

    @Test
    public void anEmailChangeForAnUnknownOldAddressRefuses() {
        final Person person = new Person();
        person.setId(Person.Id.from("p-x"));
        Assert.assertFalse(commands.setEmail(person, "nobody@nowhere.example", "brand-new@nowhere.example"));
    }

    /**
     * The family-member transition: a person who never had an email (so never had creds) is given one. Before
     * the blank guard, {@code adminGetCreds(null)} threw AFTER the person row was already saved -- the classic
     * half-committed failure. There are no creds to re-key, so this must simply answer false, quietly.
     */
    @Test
    public void givingAnEmailToAPersonWhoNeverHadOneDoesNotThrow() {
        final PassCommands spied = Mockito.spy(new PassCommands());
        Mockito.doReturn(null).when(spied).adminGetCreds("first-ever@example.org");
        final Person person = new Person();
        person.setId(Person.Id.from("family-member"));

        Assert.assertFalse(spied.setEmail(person, null, "first-ever@example.org"),
                "No creds existed, so nothing was re-keyed -- but no exception either");
        Assert.assertFalse(spied.setEmail(person, "  ", "first-ever@example.org"),
                "Blank behaves like null");
    }

    /**
     * The one-shot code-login flag is the ONLY thing that authorizes a password set without the current
     * password, it names no account (the email rides the session), and it dies on use.
     */
    @Test
    public void setPassAfterCodeLoginHonoursOnlyTheOneShotFlag() {
        final java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        final jakarta.servlet.http.HttpSession session = sessionOver(attrs);
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getSession(false)).thenReturn(session);

        try (MockedStatic<FacesContext> ignored = facesWithRequest(request)) {
            // Mismatched retype: refused, and the flag (not yet set) is not consumed either way.
            Assert.assertFalse(commands.setPassAfterCodeLogin("newpass", "different"));

            // No flag: refused.
            attrs.put(org.paulsens.trip.web.Sessions.LOGIN_EMAIL, "user2");
            Assert.assertFalse(commands.setPassAfterCodeLogin("newpass", "newpass"));

            // Flag present: allowed, flag consumed, and a second try is refused.
            attrs.put(org.paulsens.trip.web.Sessions.CODE_LOGIN, Boolean.TRUE);
            Assert.assertTrue(commands.setPassAfterCodeLogin("newpass", "newpass"));
            Assert.assertNull(attrs.get(org.paulsens.trip.web.Sessions.CODE_LOGIN));
            Assert.assertFalse(commands.setPassAfterCodeLogin("again", "again"));
        }
    }

    /** After the throttle's budget is spent, even the CORRECT password is refused for the window. */
    @Test
    public void repeatedWrongPasswordsThrottleTheAddress() {
        final int maxFails = new ConfigCommands()
                .getInt(org.paulsens.trip.config.KnownSettings.LOGIN_PASSWORD_MAX_FAILS, 0, 1000);
        // The throttled address is unique to this test: the counters live in the shared local cache, and
        // throttling "admin" here would break every other login test in the JVM.
        final String email = "user-throttle-" + System.nanoTime();
        for (int fail = 0; fail < maxFails; fail++) {
            Assert.assertNull(commands.login(email, "wrong-password"));
        }
        Assert.assertNull(commands.login(email, "user"),
                "the correct password must be refused while the address is throttled");
    }

    @Test
    public void createCredsPersistsAndAnswersTheNewCredentials() {
        final Creds creds = commands.createCreds("user3", "fresh-password");

        Assert.assertNotNull(creds);
        Assert.assertEquals(creds.getPass(), "fresh-password");
    }

    @Test
    public void getCredsByAdminAnswersNullOnAMiss() {
        Assert.assertNull(commands.getCredsByAdmin("nobody@nowhere.example", Person.Id.from("x")));
    }

    @Test
    public void wrongCredentialLookupsAnswerNullRatherThanThrowing() {
        Assert.assertNull(commands.getCreds("nobody@nowhere.example", "x"));
        Assert.assertNull(commands.adminGetCreds("nobody@nowhere.example"));
    }

    @Test
    public void loginSessionRotatesTheSessionAndSetsTheAttributes() {
        final jakarta.servlet.http.HttpSession oldSession = Mockito.mock(jakarta.servlet.http.HttpSession.class);
        final java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        final jakarta.servlet.http.HttpSession newSession = sessionOver(attrs);
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getSession(false)).thenReturn(oldSession);
        Mockito.when(request.getSession(true)).thenReturn(newSession);

        final Creds creds;
        try (MockedStatic<FacesContext> ignored = facesWithRequest(request)) {
            creds = commands.loginSession("admin", "admin");
        }
        Assert.assertNotNull(creds);
        Mockito.verify(oldSession).invalidate();
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ID), creds.getUserId());
        Assert.assertEquals(attrs.get(PersonCommands.ACTIVE_USER_ROLE), "admin");
        Assert.assertEquals(attrs.get(org.paulsens.trip.web.Sessions.LOGIN_EMAIL), "admin");
    }

    @Test
    public void aFailedLoginSessionTouchesNoSession() {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        try (MockedStatic<FacesContext> ignored = facesWithRequest(request)) {
            Assert.assertNull(commands.loginSession("admin", "wrong-password"));
        }
        Mockito.verify(request, Mockito.never()).getSession(ArgumentMatchers.anyBoolean());
    }

    @Test
    public void logoutInvalidatesTheSessionForReal() {
        final jakarta.servlet.http.HttpSession session = sessionOver(new java.util.HashMap<>());
        Mockito.when(session.getAttribute(org.paulsens.trip.web.Sessions.LOGIN_EMAIL)).thenReturn("admin");
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getSession(false)).thenReturn(session);

        try (MockedStatic<FacesContext> ignored = facesWithRequest(request)) {
            commands.logout();
        }
        Mockito.verify(session).invalidate();
    }

    @Test
    public void logoutWithoutASessionIsANoOp() {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getSession(false)).thenReturn(null);
        try (MockedStatic<FacesContext> ignored = facesWithRequest(request)) {
            commands.logout();
        }
    }

    /** A session whose attributes live in the given map, so tests can assert on the map directly. */
    private static jakarta.servlet.http.HttpSession sessionOver(final java.util.Map<String, Object> attrs) {
        final jakarta.servlet.http.HttpSession session = Mockito.mock(jakarta.servlet.http.HttpSession.class);
        Mockito.when(session.getAttribute(ArgumentMatchers.anyString()))
                .thenAnswer(call -> attrs.get(call.<String>getArgument(0)));
        Mockito.doAnswer(call -> attrs.put(call.getArgument(0), call.getArgument(1)))
                .when(session).setAttribute(ArgumentMatchers.anyString(), ArgumentMatchers.any());
        return session;
    }

    private static MockedStatic<FacesContext> facesWithRequest(final HttpServletRequest request) {
        final FacesContext ctx = Mockito.mock(FacesContext.class);
        final ExternalContext ext = Mockito.mock(ExternalContext.class);
        final MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class);
        faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);
        Mockito.when(ctx.getExternalContext()).thenReturn(ext);
        Mockito.when(ext.getRequest()).thenReturn(request);
        return faces;
    }
}
