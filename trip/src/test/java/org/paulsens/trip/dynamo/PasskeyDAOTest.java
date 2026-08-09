package org.paulsens.trip.dynamo;

import java.time.Instant;
import org.paulsens.trip.model.PasskeyCredential;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

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
        Assert.assertEquals(DAO.getInstance().getPasskey(id).orElseThrow(), stored);

        Assert.assertTrue(DAO.getInstance().deletePasskey(id, who));
        Assert.assertTrue(DAO.getInstance().getPasskey(id).isEmpty());
    }

    @Test
    public void deleteIsOwnerChecked() {
        final Person.Id owner = Person.Id.from("pk-owner");
        final String id = RandomData.genSecureToken(12);
        Assert.assertTrue(DAO.getInstance().savePasskey(passkey(id, owner, "owner@example.org", "localhost")));

        Assert.assertFalse(DAO.getInstance().deletePasskey(id, Person.Id.from("pk-somebody-else")),
                "someone else's id must not delete this key, whatever the page sent");
        Assert.assertFalse(DAO.getInstance().deletePasskey(id, null));
        Assert.assertTrue(DAO.getInstance().getPasskey(id).isPresent(), "the key must survive the attempts");
        Assert.assertTrue(DAO.getInstance().deletePasskey(id, owner));
    }

    @Test
    public void lookupsAreScopedByUserAndByEmailPlusDomain() {
        final Person.Id who = Person.Id.from("pk-scoped-" + System.nanoTime());
        final String email = "pk-scoped@example.org";
        DAO.getInstance().savePasskey(passkey(RandomData.genSecureToken(12), who, email, "localhost"));
        DAO.getInstance().savePasskey(passkey(RandomData.genSecureToken(12), who, email, "unitetrip.com"));

        Assert.assertEquals(DAO.getInstance().getPasskeysForUser(who).size(), 2);
        // Domain-scoped, like the browser: the other domain's key must not be offered.
        Assert.assertEquals(DAO.getInstance().getPasskeysForEmailAndRp(email, "localhost").size(), 1);
        Assert.assertEquals(DAO.getInstance().getPasskeysForEmailAndRp(email, "unitetrip.com").size(), 1);
        Assert.assertEquals(DAO.getInstance().getPasskeysForEmailAndRp(email, "example.org").size(), 0);
    }

    @Test
    public void blankLookupsAnswerEmpty() {
        Assert.assertTrue(DAO.getInstance().getPasskey(null).isEmpty());
        Assert.assertTrue(DAO.getInstance().getPasskey("").isEmpty());
        Assert.assertTrue(DAO.getInstance().getPasskeysForUser(null).isEmpty());
        Assert.assertTrue(DAO.getInstance().getPasskeysForEmailAndRp(null, "x").isEmpty());
        Assert.assertTrue(DAO.getInstance().getPasskeysForEmailAndRp("a@b", null).isEmpty());
    }
}
