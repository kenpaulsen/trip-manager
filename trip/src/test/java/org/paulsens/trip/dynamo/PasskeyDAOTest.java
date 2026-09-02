package org.paulsens.trip.dynamo;

import java.time.Instant;
import org.paulsens.trip.model.PasskeyCredential;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.paulsens.trip.cache.Cached;

/** {@link PasskeyDAO} round-trips and the owner check, against the in-memory fake store. */
public class PasskeyDAOTest {

    private static PasskeyCredential passkey(final String credentialId, final Person.Id who, final String email,
            final String rpId) {
        final long now = Instant.now().getEpochSecond();
        return new PasskeyCredential(credentialId, who, email, "handle-" + who.getValue(), "cose-bytes", 0,
                "internal", "Test", rpId, now, now);
    }

    @Test
    public void saveGetDeleteRoundTrip() {
        final Person.Id who = Person.Id.from("pk-round-trip");
        final String id = RandomData.genSecureToken(12);
        final PasskeyCredential stored = passkey(id, who, "pk@example.org", "localhost");

        Assert.assertTrue(DAO.getInstance().savePasskey(stored));
        Assert.assertEquals(DAO.getInstance().getPasskey(id, Cached.NO).orElseThrow(), stored);

        Assert.assertTrue(DAO.getInstance().deletePasskey(id, who));
        Assert.assertTrue(DAO.getInstance().getPasskey(id, Cached.NO).isEmpty());
    }

    @Test
    public void deleteIsOwnerChecked() {
        final Person.Id owner = Person.Id.from("pk-owner");
        final String id = RandomData.genSecureToken(12);
        Assert.assertTrue(DAO.getInstance().savePasskey(passkey(id, owner, "owner@example.org", "localhost")));

        Assert.assertFalse(DAO.getInstance().deletePasskey(id, Person.Id.from("pk-somebody-else")),
                "someone else's id must not delete this key, whatever the page sent");
        Assert.assertFalse(DAO.getInstance().deletePasskey(id, null));
        Assert.assertTrue(DAO.getInstance().getPasskey(id, Cached.NO).isPresent(), "the key must survive the attempts");
        Assert.assertTrue(DAO.getInstance().deletePasskey(id, owner));
    }

    @Test
    public void lookupsAreScopedByUserAndByEmailPlusDomain() {
        final Person.Id who = Person.Id.from("pk-scoped-" + System.nanoTime());
        final String email = "pk-scoped@example.org";
        DAO.getInstance().savePasskey(passkey(RandomData.genSecureToken(12), who, email, "localhost"));
        DAO.getInstance().savePasskey(passkey(RandomData.genSecureToken(12), who, email, "unitetrip.com"));

        Assert.assertEquals(DAO.getInstance().getPasskeysForUser(who, Cached.NO).size(), 2);
        // Domain-scoped, like the browser: the other domain's key must not be offered.
        Assert.assertEquals(DAO.getInstance().getPasskeysForEmailAndRp(email, "localhost", Cached.NO).size(), 1);
        Assert.assertEquals(DAO.getInstance().getPasskeysForEmailAndRp(email, "unitetrip.com", Cached.NO).size(), 1);
        Assert.assertEquals(DAO.getInstance().getPasskeysForEmailAndRp(email, "example.org", Cached.NO).size(), 0);
    }

    @Test
    public void blankLookupsAnswerEmpty() {
        Assert.assertTrue(DAO.getInstance().getPasskey(null, Cached.NO).isEmpty());
        Assert.assertTrue(DAO.getInstance().getPasskey("", Cached.NO).isEmpty());
        Assert.assertTrue(DAO.getInstance().getPasskeysForUser(null, Cached.NO).isEmpty());
        Assert.assertTrue(DAO.getInstance().getPasskeysForEmailAndRp(null, "x", Cached.NO).isEmpty());
        Assert.assertTrue(DAO.getInstance().getPasskeysForEmailAndRp("a@b", null, Cached.NO).isEmpty());
    }

    /**
     * A row's email is a snapshot taken at registration; an email change re-stamps every one of that person's
     * keys, so no key is left naming an address its owner no longer holds (where a later account could claim
     * it). Only that person's rows move, and only where the address actually differs.
     */
    @Test
    public void updateEmailForUserRestampsOnlyThatPersonsKeys() {
        final Person.Id mover = Person.Id.from("pk-mover-" + System.nanoTime());
        final Person.Id bystander = Person.Id.from("pk-bystander-" + System.nanoTime());
        final String movedOne = RandomData.genSecureToken(12);
        final String movedTwo = RandomData.genSecureToken(12);
        final String untouched = RandomData.genSecureToken(12);
        Assert.assertTrue(DAO.getInstance().savePasskey(passkey(movedOne, mover, "old@example.org", "localhost")));
        Assert.assertTrue(DAO.getInstance().savePasskey(
                passkey(movedTwo, mover, "old@example.org", "other.example")));
        Assert.assertTrue(DAO.getInstance().savePasskey(
                passkey(untouched, bystander, "old@example.org", "localhost")));

        // Mixed case in, lowercase out: the stored address is a lookup key, not display text.
        Assert.assertEquals(DAO.getInstance().updatePasskeyEmailForUser(mover, "New@Example.org"), 2,
                "every domain's key moves -- the address is the person's, not the domain's");

        Assert.assertEquals(email(movedOne), "new@example.org");
        Assert.assertEquals(email(movedTwo), "new@example.org");
        Assert.assertEquals(email(untouched), "old@example.org", "somebody else's key must not move");
        Assert.assertEquals(DAO.getInstance().updatePasskeyEmailForUser(mover, "new@example.org"), 0,
                "re-running the move rewrites nothing");
    }

    @Test
    public void updateEmailForUserIgnoresMissingArguments() {
        Assert.assertEquals(DAO.getInstance().updatePasskeyEmailForUser(null, "a@b.org"), 0);
        Assert.assertEquals(DAO.getInstance().updatePasskeyEmailForUser(Person.Id.from("pk-none"), null), 0);
        Assert.assertEquals(DAO.getInstance().updatePasskeyEmailForUser(Person.Id.from("pk-none"), ""), 0);
    }

    private static String email(final String credentialId) {
        return DAO.getInstance().getPasskey(credentialId, Cached.NO).orElseThrow().getEmail();
    }
}
