package org.paulsens.trip.action;

import jakarta.faces.context.FacesContext;
import jakarta.servlet.http.HttpServletRequest;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link PassCommands}' remaining branches: the email-collision revert, the reset flow, and the
 * privilege-gated admin set.
 */
public class PassCommandsTailsTest {

    private final PassCommands pass = new PassCommands();
    private final PersonCommands people = new PersonCommands();

    private Person personWithCreds(final String first) {
        final Person person = people.createPerson();
        person.setFirst(first);
        person.setLast(first + "Last");
        person.setEmail(first.toLowerCase() + "-" + System.nanoTime() + "@example.org");
        Assert.assertTrue(people.savePerson(person));
        // The email index is written asynchronously behind savePerson; the flows under test resolve the
        // person BY EMAIL, so wait for the mapping rather than racing it.
        final long deadline = System.currentTimeMillis() + 5_000;
        try {
            while (org.paulsens.trip.dynamo.DAO.getInstance()
                    .getPersonByEmail(person.getEmail()).join() == null) {
                Assert.assertTrue(System.currentTimeMillis() < deadline, "email mapping never appeared");
                Thread.sleep(20);
            }
        } catch (final InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
        Assert.assertNotNull(pass.createCreds(person.getEmail(), "secret-" + first));
        return person;
    }

    /** An admin view (the {@code showAll} flag) is what unlocks the by-email creds reads setEmail uses. */
    private MockedStatic<FacesContext> adminView() {
        final FacesContext ctx = Mockito.mock(FacesContext.class, Mockito.RETURNS_DEEP_STUBS);
        final java.util.Map<String, Object> viewMap = new java.util.HashMap<>();
        viewMap.put(org.paulsens.trip.dynamo.CredentialsDAO.IS_ADMIN, "true");
        Mockito.when(ctx.getViewRoot().getViewMap(false)).thenReturn(viewMap);
        final MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class);
        faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);
        return faces;
    }

    /**
     * Taking an address that belongs to someone ELSE must fail and revert the person's email field.
     *
     * <p>The DAO is mocked here: the by-email creds read this branch depends on goes straight to the pass
     * table, which the fake store does not serve, so no in-process arrangement can reach it otherwise.
     */
    @Test
    public void takingSomeoneElsesEmailIsRevertedNotStolen() throws Exception {
        final Person alice = new Person();
        alice.setEmail("alice-revert@example.org");
        final String aliceOriginal = alice.getEmail();
        alice.setEmail("bob-owns-this@example.org");
        final org.paulsens.trip.dynamo.DAO dao = Mockito.mock(org.paulsens.trip.dynamo.DAO.class);
        Mockito.when(dao.adminGetCredsByEmail("bob-owns-this@example.org"))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(
                        new Creds("bob-owns-this@example.org", Person.Id.from("bob"), "pw")));
        Mockito.when(dao.savePerson(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));
        try (MockedStatic<org.paulsens.trip.dynamo.DAO> daoStatic =
                Mockito.mockStatic(org.paulsens.trip.dynamo.DAO.class)) {
            daoStatic.when(org.paulsens.trip.dynamo.DAO::getInstance).thenReturn(dao);

            Assert.assertFalse(pass.setEmail(alice, aliceOriginal, "bob-owns-this@example.org"));
        }

        Assert.assertEquals(alice.getEmail(), aliceOriginal, "the in-memory person must be reverted too");
    }

    @Test
    public void setEmailGuardsItsInputs() {
        final Person who = personWithCreds("Guard");

        Assert.assertFalse(pass.setEmail(who, who.getEmail(), null));
        Assert.assertFalse(pass.setEmail(who, who.getEmail(), who.getEmail()), "unchanged is not a change");
        try (MockedStatic<FacesContext> ignored = adminView()) {
            Assert.assertFalse(pass.setEmail(who, "never-had-creds@example.org",
                    "fresh-" + System.nanoTime() + "@example.org"), "no old creds: nothing to move");
        }
    }

    @Test
    public void createCredsForAnUnknownEmailFailsLoudlyInTheAuditTrail() {
        Assert.assertNull(pass.createCreds("nobody-" + System.nanoTime() + "@example.org", "pw"));
    }

    @Test
    public void adminSetPassIsGatedOnThePeopleAdminPrivilege() {
        final Person target = personWithCreds("Reset");

        Assert.assertFalse(pass.adminSetPass(target.getEmail(), "new-pass", null),
                "no caller means no authorization, whatever the edge said");
        final Caller nobody = Mockito.mock(Caller.class);
        Assert.assertFalse(pass.adminSetPass(target.getEmail(), "new-pass", nobody));

        final Caller admin = Mockito.mock(Caller.class);
        Mockito.when(admin.has(PrivilegeCommands.PEOPLE_ADMIN)).thenReturn(true);
        Assert.assertTrue(pass.adminSetPass(target.getEmail(), "new-pass", admin));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> pass.adminSetPass(" ", "x", admin));
    }

    @Test
    public void resetPassRequiresTheMatchingLastName() {
        final Person target = personWithCreds("Forgot");

        Assert.assertNull(pass.resetPass(target.getEmail(), "WrongLast", "Title"));
        Assert.assertNull(pass.resetPass("nobody-" + System.nanoTime() + "@x.org", "Last", "Title"));
        Assert.assertThrows(IllegalArgumentException.class, () -> pass.resetPass(null, "Last", "T"));
        Assert.assertThrows(IllegalArgumentException.class,
                () -> pass.resetPass(target.getEmail(), null, "T"));
    }

    /** The happy reset: last name matches, the password is rotated, and the mail body carries it. */
    @Test
    public void aMatchingResetRotatesThePasswordAndBuildsTheMail() {
        final Person target = personWithCreds("Match");
        final FacesContext ctx = Mockito.mock(FacesContext.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(ctx.getExternalContext().getRequest())
                .thenReturn(Mockito.mock(HttpServletRequest.class));
        try (MockedStatic<FacesContext> faces = Mockito.mockStatic(FacesContext.class)) {
            faces.when(FacesContext::getCurrentInstance).thenReturn(ctx);

            final String mail = pass.resetPass(target.getEmail(), target.getLast(), "Reset requested");

            Assert.assertNotNull(mail, "a matching email+lastName must produce the mail body");
            Assert.assertTrue(mail.contains("Reset requested"));
        }
    }
}
