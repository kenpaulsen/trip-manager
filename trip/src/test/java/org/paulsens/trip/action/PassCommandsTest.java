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
        Assert.assertThrows(IllegalArgumentException.class, () -> commands.resetPass(null, "Last", "t"));
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

    @Test
    public void resetPassNeedsTheMatchingLastName() {
        Assert.assertNull(commands.resetPass("user2", "WrongName", "Title"));
        Assert.assertNull(commands.resetPass("nobody@nowhere.example", "Last", "Title"));
    }

    @Test
    public void resetPassAnswersTheEmailBodyWithTheLoginUrl() {
        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            final FacesContext ctx = Mockito.mock(FacesContext.class);
            final ExternalContext external = Mockito.mock(ExternalContext.class);
            final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);
            Mockito.when(ctx.getExternalContext()).thenReturn(external);
            Mockito.when(external.getRequest()).thenReturn(request);
            Mockito.when(request.getServerPort()).thenReturn(443);
            Mockito.when(request.getScheme()).thenReturn("https");
            Mockito.when(request.getServerName()).thenReturn("trips.example.org");
            Mockito.when(request.getContextPath()).thenReturn("");

            // The fake admin person's last name is "user".
            final String body = commands.resetPass("admin", "user", "Password Reset");

            Assert.assertNotNull(body);
            Assert.assertTrue(body.startsWith("Password Reset"));
            Assert.assertTrue(body.contains("https://trips.example.org/account/login.jsf"),
                    "The login URL must be derived from the request");
        }
    }

    @Test
    public void aNonStandardPortSurvivesIntoTheLoginUrl() {
        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            final FacesContext ctx = Mockito.mock(FacesContext.class);
            final ExternalContext external = Mockito.mock(ExternalContext.class);
            final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);
            Mockito.when(ctx.getExternalContext()).thenReturn(external);
            Mockito.when(external.getRequest()).thenReturn(request);
            Mockito.when(request.getServerPort()).thenReturn(8080);
            Mockito.when(request.getScheme()).thenReturn("http");
            Mockito.when(request.getServerName()).thenReturn("localhost");
            Mockito.when(request.getContextPath()).thenReturn("");

            final String body = commands.resetPass("admin", "user", "Reset");

            Assert.assertTrue(body.contains("http://localhost:8080/account/login.jsf"));
        }
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
}
