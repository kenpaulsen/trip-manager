package org.paulsens.trip.util;

import java.io.IOException;
import java.util.Locale;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.testng.Assert;

/**
 * Saved people for tests in any package. Every person is new and nothing is kept in a static: the suite shares
 * ONE DAO and cache and runs its classes in filesystem order, so a remembered row would be gone after another
 * class's {@code clearAllCaches()}.
 */
public final class TestPeople {

    private static final long INDEX_WAIT_MILLIS = 5_000;

    private TestPeople() {
    }

    /** Ten lowercase letters: unique enough for a name or a mailbox in the shared store. */
    public static String unique() {
        return RandomData.genAlpha(10).toLowerCase(Locale.ROOT);
    }

    /** A saved person with arbitrary names and a unique address. */
    public static Person savedPerson() {
        return save(Person.builder().first(RandomData.genAlpha(6)).last(RandomData.genAlpha(8))
                .email(address("p")).build());
    }

    /** A saved person with this first name (which also leads the address) and an arbitrary last name. */
    public static Person savedPerson(final String tag) {
        return savedPerson(tag, RandomData.genAlpha(8));
    }

    /** A saved person with both names chosen, for a test that reads them back. */
    public static Person savedPerson(final String first, final String last) {
        return save(Person.builder().first(first).last(last).email(address(first)).build());
    }

    /**
     * A unique address led by the tag. An {@code admin*} or {@code user*} tag is refused: local mode answers a
     * login for such an address whatever the store holds (they are the sign-in personas), which silently makes
     * "has no login" and "is not a site admin" untestable.
     */
    public static String address(final String tag) {
        final String low = tag.toLowerCase(Locale.ROOT);
        if (low.startsWith("admin") || low.startsWith("user")) {
            throw new IllegalArgumentException("'" + tag + "' would make a local-mode login persona's address");
        }
        return low + "." + unique() + "@example.com";
    }

    /** Saves a person the test built itself (a birthdate, a chosen address), failing the test if refused. */
    public static Person save(final Person person) {
        try {
            Assert.assertTrue(DAO.getInstance().savePerson(person), "test setup: save person");
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
        return person;
    }

    /**
     * A person with NO address, saved through the page bean as the people pages save one. For the chat and
     * push tests, where a member who suddenly has a mailbox would start receiving mail.
     */
    public static Person.Id unreachable(final String first, final String last) {
        final PersonCommands people = new PersonCommands();
        final Person who = people.createPerson();
        who.setFirst(first);
        who.setLast(last);
        Assert.assertTrue(people.savePerson(who), "test setup: save " + first);
        return who.getId();
    }

    /**
     * Blocks until the address resolves to its person. {@code savePerson} writes the email index asynchronously,
     * so a flow that finds the person BY EMAIL must wait for the mapping rather than race it.
     */
    public static void awaitEmailIndex(final String email) {
        final long deadline = System.currentTimeMillis() + INDEX_WAIT_MILLIS;
        try {
            while (DAO.getInstance().getPersonByEmail(email, Cached.NO) == null) {
                Assert.assertTrue(System.currentTimeMillis() < deadline, "email mapping never appeared");
                Thread.sleep(20);
            }
        } catch (final InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
