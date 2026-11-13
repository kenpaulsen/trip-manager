package org.paulsens.trip.action;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
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
        final List<Person.Id> listed = people.contactsRoster(small, null);
        assertEquals(listed, people.sortedIds(small));
        assertEquals(people.contactsRoster(small, "  "), listed, "A blank query is no query");
        assertTrue(people.contactsRoster(null, "x").isEmpty());
        assertEquals(people.getPeopleListLimit(), LIMIT);
    }

    @Test
    public void aRosterOverTheLimitWaitsForASearch() {
        assertTrue(people.contactsRoster(big, null).isEmpty(), "Nobody until a search narrows it");
    }

    @Test
    public void aSearchNarrowsByEveryWordEachMatchingAnywhere() {
        final List<Person.Id> just = List.of(needle.getId());
        assertEquals(people.contactsRoster(big, needle.getLast()), just);
        assertEquals(people.contactsRoster(big, needle.getLast().substring(2, 7)), just, "inside the last name");
        assertEquals(people.contactsRoster(big, "eedl " + needle.getLast().toUpperCase()), just,
                "Every word, any case");
        assertEquals(people.contactsRoster(big, needle.getNickname()), just);
        assertEquals(people.contactsRoster(big, needle.getEmail()), just, "the email from its start");
        assertEquals(people.contactsRoster(big, needle.getCell().substring(10)), just, "cell digits anywhere");
        assertTrue(people.contactsRoster(big, "needle nobody-has-this").isEmpty());
    }

    @Test
    public void aBroadSearchIsCappedSortedAndMutable() {
        final List<Person.Id> hits = people.contactsRoster(big, marker);
        assertEquals(hits.size(), LIMIT);
        assertEquals(hits, people.sortedIds(hits), "last, first order");
        hits.add(needle.getId());
    }

    @Test
    public void listOrSearchFlagsAQueryNeededAndATruncation() {
        final List<Person> everyone = big.stream().map(people::getPerson).toList();
        assertSame(people.listOrSearch(everyone, null), PeopleResult.NEEDS_QUERY);
        assertSame(people.listOrSearch(List.of(), "x"), PeopleResult.NONE);
        assertSame(people.listOrSearch(null, null), PeopleResult.NONE);

        final PeopleResult broad = people.listOrSearch(everyone, marker);
        assertEquals(broad.rows().size(), LIMIT);
        assertTrue(broad.truncated(), "more matched than are shown");
        assertFalse(broad.needsQuery());

        final PeopleResult one = people.listOrSearch(everyone, "needle");
        assertEquals(one.rows(), List.of(people.getPerson(needle.getId())));
        assertFalse(one.truncated());

        final PeopleResult few = people.listOrSearch(everyone.subList(0, 5), "");
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

    private static Person saved(final String first, final String last, final String nickname, final String cell)
            throws IOException {
        final Person person = Person.builder().first(first).last(last).nickname(nickname).cell(cell)
                .email("contacts." + RandomData.genAlpha(10) + "@example.com").build();
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }
}
