package org.paulsens.trip.util;

import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The fixture's one rule: no saved person gets a local-mode persona's address. Local mode answers a login for
 * any {@code admin*} or {@code user*} address, so a fixture that minted one would quietly turn "has no login"
 * and "is not a site admin" tests green.
 */
public class TestPeopleTest {

    @Test
    public void aPersonaTagIsRefusedInAnyCase() {
        for (final String tag : new String[] {"admin", "Admin", "ADMINISTRATOR", "user", "User2", "users"}) {
            Assert.assertThrows(IllegalArgumentException.class, () -> TestPeople.address(tag));
            Assert.assertThrows(IllegalArgumentException.class, () -> TestPeople.savedPerson(tag));
            Assert.assertThrows(IllegalArgumentException.class, () -> TestPeople.savedPerson(tag, "Last"));
        }
    }

    @Test
    public void anyOtherTagLeadsAUniqueLowercaseAddress() {
        final String first = TestPeople.address("Sitewide");
        Assert.assertTrue(first.matches("sitewide\\.[a-z]{10}@example\\.com"), first);
        Assert.assertNotEquals(TestPeople.address("Sitewide"), first);
        Assert.assertTrue(TestPeople.address("Madmin").startsWith("madmin."), "only a LEADING persona name counts");
    }

    @Test
    public void aSavedPersonIsInTheStoreUnderTheNamesAsked() {
        final Person ann = TestPeople.savedPerson("Ann", "Alpha");
        final Person stored = DAO.getInstance().getPerson(ann.getId(), Cached.NO).orElseThrow();
        Assert.assertEquals(stored.getFirst(), "Ann");
        Assert.assertEquals(stored.getLast(), "Alpha");
        Assert.assertTrue(stored.getEmail().startsWith("ann."), stored.getEmail());
    }
}
