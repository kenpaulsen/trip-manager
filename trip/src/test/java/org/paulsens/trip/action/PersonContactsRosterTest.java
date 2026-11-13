package org.paulsens.trip.action;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.PersonMatch;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PrivacySettings;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * The people-list rule every list page shares ({@link PersonCommands#listOrSearch}), and the trip Contacts page's
 * use of it: everyone up to the limit, a search beyond it, never more than the limit, always last-name order.
 */
public class PersonContactsRosterTest {
    private static final int LIMIT = PersonCommands.PEOPLE_LIST_LIMIT;
    private static final PersonMatch.Reach ALL = PersonMatch.EVERY_FIELD;
    private final PersonCommands people = new PersonCommands();
    private final String marker = "Zqcap" + RandomData.genAlpha(6);
    private final List<Person.Id> big = new ArrayList<>();
    private Person needle;

    @BeforeClass
    void seed() throws IOException {
        for (int i = 0; i <= LIMIT; i++) {
            big.add(saved("Member" + RandomData.genAlpha(5), marker, null, null).getId());
        }
        needle = saved("Needle", "Unique" + RandomData.genAlpha(6), "nick" + RandomData.genAlpha(5),
                "(555) 210-" + RandomData.genSecureDigits(4));
        big.add(needle.getId());
    }

    @Test
    public void aRosterThatFitsIsListedWholeInNameOrder() {
        final List<Person.Id> small = big.subList(0, 3);
        final List<Person.Id> listed = ids(people.contactsSearch(small, null, false));
        assertEquals(listed, people.sortedIds(small));
        assertEquals(ids(people.contactsSearch(small, "  ", false)), listed, "A blank query is no query");
        assertSame(people.contactsSearch(null, "x", false), PeopleResult.NONE);
        assertSame(people.contactsSearch(List.of(), "x", false), PeopleResult.NONE);
        assertEquals(people.getPeopleListLimit(), LIMIT);
    }

    @Test
    public void aRosterOverTheLimitWaitsForASearch() {
        assertSame(people.contactsSearch(big, null, false), PeopleResult.NEEDS_QUERY, "Nobody until a search");
    }

    @Test
    public void aSearchNarrowsByEveryWordEachMatchingAnywhere() {
        final List<Person.Id> just = List.of(needle.getId());
        assertEquals(ids(people.contactsSearch(big, needle.getLast(), false)), just);
        assertEquals(ids(people.contactsSearch(big, needle.getLast().substring(2, 7), false)), just,
                "inside the last name");
        assertEquals(ids(people.contactsSearch(big, "eedl " + needle.getLast().toUpperCase(), false)), just,
                "Every word, any case");
        assertEquals(ids(people.contactsSearch(big, needle.getNickname(), false)), just);
        assertEquals(ids(people.contactsSearch(big, needle.getEmail(), false)), just, "the email from its start");
        assertEquals(ids(people.contactsSearch(big, needle.getCell().substring(10), false)), just,
                "shared cell digits anywhere");
        assertTrue(people.contactsSearch(big, "needle nobody-has-this", false).rows().isEmpty());
    }

    /** The review blocker of 2026-11-12: a row that stays as digits are typed reveals a hidden number. */
    @Test
    public void aCoTravelerCannotFindSomeoneByAHiddenCellOrEmail() throws IOException {
        final Person shy = saved("Shy", "Hidden" + RandomData.genAlpha(6), null,
                "(555) 777-" + RandomData.genSecureDigits(4));
        shy.getPrivacy().setCell(PrivacySettings.Visibility.PRIVATE);
        shy.getPrivacy().setEmail(PrivacySettings.Visibility.PRIVATE);
        assertTrue(DAO.getInstance().savePerson(shy));
        final List<Person.Id> roster = List.of(shy.getId(), needle.getId());
        final String digits = shy.getCell().substring(10);
        assertTrue(people.contactsSearch(roster, digits, false).rows().isEmpty(), "the peer's page hides it");
        assertTrue(people.contactsSearch(roster, shy.getEmail(), false).rows().isEmpty());
        assertEquals(ids(people.contactsSearch(roster, digits, true)), List.of(shy.getId()),
                "the admin view masks it, so it may be searched");
        assertEquals(ids(people.contactsSearch(roster, "hidden", false)), List.of(shy.getId()), "names always");
    }

    @Test
    public void aBroadSearchIsCappedSortedAndFlagged() {
        final PeopleResult hits = people.contactsSearch(big, marker, false);
        assertEquals(hits.rows().size(), LIMIT);
        assertTrue(hits.truncated());
        assertEquals(ids(hits), people.sortedIds(ids(hits)), "last, first order");
        hits.rows().add(needle);
    }

    @Test
    public void listOrSearchFlagsAQueryNeededAndATruncation() {
        final List<Person> everyone = big.stream().map(people::getPerson).toList();
        assertSame(people.listOrSearch(everyone, null, ALL), PeopleResult.NEEDS_QUERY);
        assertSame(people.listOrSearch(List.of(), "x", ALL), PeopleResult.NONE);
        assertSame(people.listOrSearch(null, null, ALL), PeopleResult.NONE);

        final PeopleResult broad = people.listOrSearch(everyone, marker, ALL);
        assertEquals(broad.rows().size(), LIMIT);
        assertTrue(broad.truncated(), "more matched than are shown");
        assertFalse(broad.needsQuery());

        final PeopleResult one = people.listOrSearch(everyone, "needle", ALL);
        assertEquals(one.rows(), List.of(people.getPerson(needle.getId())));
        assertFalse(one.truncated());

        final PeopleResult few = people.listOrSearch(everyone.subList(0, 5), "", ALL);
        assertEquals(few.rows().size(), 5, "a list that fits shows whole with no query");
        final List<Person> resorted = new ArrayList<>(few.rows());
        resorted.sort(Person.PEOPLE_SORTER);
        assertEquals(few.rows(), resorted);
    }

    @Test
    public void sortedIdsSkipsIdsThatNameNobody() {
        final Person.Id ghost = Person.Id.newInstance();
        final List<Person.Id> ids = people.sortedIds(List.of(ghost, needle.getId()));
        assertEquals(ids, List.of(needle.getId()));
        assertNotEquals(people.sortedIds(null), null);
        assertTrue(people.sortedIds(null).isEmpty());
    }

    @Test
    public void theAdminSearchAsksForAQueryWhenBlank() {
        assertSame(people.adminSearch(null), PeopleResult.NEEDS_QUERY);
        assertSame(people.adminSearch("  "), PeopleResult.NEEDS_QUERY);
    }

    @Test
    public void aRevealIsRememberedPerPersonAndFieldAndBounded() {
        final String id = needle.getId().getValue();
        assertFalse(people.isRevealed(null, id, "cell"));
        final String one = people.withReveal(null, id, "cell");
        assertTrue(people.isRevealed(one, id, "cell"));
        assertFalse(people.isRevealed(one, id, "email"), "per field");
        assertSame(people.withReveal(one, id, "cell"), one, "already there");
        assertSame(people.withReveal(one, "bad|id", "cell"), one, "a forged separator is ignored");
        assertSame(people.withReveal(one, id, ""), one);
        assertSame(people.withReveal(one, null, "cell"), one);
        assertFalse(people.isRevealed(one, id, "x:y"));
        String many = one;
        for (int i = 0; i < PersonCommands.MAX_REVEALS + 5; i++) {
            many = people.withReveal(many, id, "f" + i);
        }
        assertFalse(people.isRevealed(many, id, "cell"), "the oldest is forgotten past the bound");
        assertTrue(people.isRevealed(many, id, "f" + (PersonCommands.MAX_REVEALS + 4)));
        assertTrue(many.split("\\|").length <= PersonCommands.MAX_REVEALS + 1);
    }

    private static List<Person.Id> ids(final PeopleResult result) {
        return result.rows().stream().map(Person::getId).toList();
    }

    private static Person saved(final String first, final String last, final String nickname, final String cell)
            throws IOException {
        final Person person = Person.builder().first(first).last(last).nickname(nickname).cell(cell)
                .email("contacts." + RandomData.genAlpha(10) + "@example.com").build();
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }
}
