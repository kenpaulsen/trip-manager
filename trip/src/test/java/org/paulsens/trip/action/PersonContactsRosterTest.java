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
import static org.testng.Assert.assertTrue;

/** The trip Contacts page's rule: everyone up to the limit, a search beyond it, never more than the limit. */
public class PersonContactsRosterTest {
    private final PersonCommands people = new PersonCommands();
    private final String marker = "Zqcap" + RandomData.genAlpha(6);
    private final List<Person.Id> big = new ArrayList<>();
    private Person needle;

    @BeforeClass
    void seed() throws IOException {
        for (int i = 0; i <= PersonCommands.CONTACTS_AUTO_LIMIT; i++) {
            big.add(saved("Member" + RandomData.genAlpha(5), marker, null).getId());
        }
        needle = saved("Needle", "Unique" + RandomData.genAlpha(6), "nick" + RandomData.genAlpha(5));
        big.add(needle.getId());
    }

    @Test
    public void aRosterThatFitsIsListedWhole() {
        final List<Person.Id> small = big.subList(0, 3);
        assertEquals(people.contactsRoster(small, null), small);
        assertEquals(people.contactsRoster(small, "  "), small, "A blank query is no query");
        assertTrue(people.contactsRoster(null, "x").isEmpty());
        assertEquals(people.getContactsAutoLimit(), PersonCommands.CONTACTS_AUTO_LIMIT);
    }

    @Test
    public void aRosterOverTheLimitWaitsForASearch() {
        assertTrue(people.contactsRoster(big, null).isEmpty(), "Nobody until a search narrows it");
    }

    @Test
    public void aSearchNarrowsByEveryToken() {
        assertEquals(people.contactsRoster(big, needle.getLast()), List.of(needle.getId()));
        assertEquals(people.contactsRoster(big, "needle " + needle.getLast().toUpperCase()),
                List.of(needle.getId()), "Every token, any case");
        assertEquals(people.contactsRoster(big, needle.getNickname()), List.of(needle.getId()));
        assertEquals(people.contactsRoster(big, needle.getEmail()), List.of(needle.getId()));
        assertTrue(people.contactsRoster(big, "needle nobody-has-this").isEmpty());
    }

    @Test
    public void aBroadSearchIsCappedAndSortable() {
        final List<Person.Id> hits = people.contactsRoster(big, marker);
        assertEquals(hits.size(), PersonCommands.CONTACTS_AUTO_LIMIT);
        hits.sort(null);
        hits.add(needle.getId());
    }

    private static Person saved(final String first, final String last, final String nickname) throws IOException {
        final Person person = Person.builder().first(first).last(last).nickname(nickname)
                .email("contacts." + RandomData.genAlpha(10) + "@example.com").build();
        assertTrue(DAO.getInstance().savePerson(person));
        return person;
    }
}
