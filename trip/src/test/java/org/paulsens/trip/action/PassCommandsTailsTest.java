package org.paulsens.trip.action;

import jakarta.faces.context.FacesContext;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.paulsens.trip.cache.Cached;

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
                    .getPersonByEmail(person.getEmail(), Cached.NO) == null) {
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
        Mockito.when(dao.adminGetCredsByEmail("bob-owns-this@example.org", Cached.NO))
                .thenReturn(new Creds("bob-owns-this@example.org", Person.Id.from("bob"), "pw"));
        Mockito.when(dao.savePerson(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
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
    public void userTypeOfFailsClosedOffAFacesThread() {
        final Person target = personWithCreds("Reset");
        // The admin set-password path is gone (org migration, 2026-08); the surviving admin-facing
        // credential read is the display-safe type string, and it inherits the view-flag gate.
        Assert.assertNull(pass.userTypeOf(target.getEmail()));
    }

    /**
     * An email change must be a MOVE, not a copy. The pass table is keyed by email, so re-keying the login can
     * only write the new row; left alone, the old one survives naming this person, the next account created
     * under that address takes it over, and every credential that reaches an account through an email --
     * passkeys, remember-me cookies, API tokens -- follows the address to a stranger. That is not
     * hypothetical: it is how an admin's passkey signed into somebody else's account (2026-09-02).
     *
     * <p>The DAO is mocked for the same reason as the revert test above: the by-email creds reads go straight
     * to the pass table, which the fake store does not serve.
     */
    @Test
    public void changingAnEmailRemovesTheAbandonedRowAndRestampsEveryCredential() throws Exception {
        final Person.Id id = Person.Id.from("mover");
        final Person mover = new Person();
        mover.setId(id);
        mover.setEmail("new@example.org");
        final org.paulsens.trip.dynamo.DAO dao = Mockito.mock(org.paulsens.trip.dynamo.DAO.class);
        // Nobody else holds the new address; the old one is still this person's login.
        Mockito.when(dao.adminGetCredsByEmail("new@example.org", Cached.NO)).thenReturn(null);
        Mockito.when(dao.adminGetCredsByEmail("old@example.org", Cached.NO))
                .thenReturn(new Creds("old@example.org", id, "pw"));
        Mockito.when(dao.saveCreds(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        try (MockedStatic<org.paulsens.trip.dynamo.DAO> daoStatic =
                Mockito.mockStatic(org.paulsens.trip.dynamo.DAO.class)) {
            daoStatic.when(org.paulsens.trip.dynamo.DAO::getInstance).thenReturn(dao);

            Assert.assertTrue(pass.setEmail(mover, "old@example.org", "new@example.org"));
        }

        Mockito.verify(dao).removeCredsAfterRekey("old@example.org", id);
        Mockito.verify(dao).updatePasskeyEmailForUser(id, "new@example.org");
        Mockito.verify(dao).updateAuthTokenEmailForUser(id, "new@example.org");
    }

    /** A refused change must leave every credential exactly where it was. */
    @Test
    public void aRefusedEmailChangeMovesNothing() throws Exception {
        final Person alice = new Person();
        alice.setId(Person.Id.from("alice"));
        alice.setEmail("bob-owns-this@example.org");
        final org.paulsens.trip.dynamo.DAO dao = Mockito.mock(org.paulsens.trip.dynamo.DAO.class);
        Mockito.when(dao.adminGetCredsByEmail("bob-owns-this@example.org", Cached.NO))
                .thenReturn(new Creds("bob-owns-this@example.org", Person.Id.from("bob"), "pw"));
        Mockito.when(dao.savePerson(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        try (MockedStatic<org.paulsens.trip.dynamo.DAO> daoStatic =
                Mockito.mockStatic(org.paulsens.trip.dynamo.DAO.class)) {
            daoStatic.when(org.paulsens.trip.dynamo.DAO::getInstance).thenReturn(dao);

            Assert.assertFalse(pass.setEmail(alice, "alice@example.org", "bob-owns-this@example.org"));
        }

        Mockito.verify(dao, Mockito.never()).removeCredsAfterRekey(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        Mockito.verify(dao, Mockito.never()).updatePasskeyEmailForUser(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }
}
