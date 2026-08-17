package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The id-list round trip behind the session-scope migration's people pages: a view keeps
 * {@code toIds(searchResult)} and re-resolves with {@code getPeopleByIds} each request, so both halves must
 * tolerate null (a view whose list was never populated) without inventing entries.
 */
public class PersonIdListTest {

    @Test
    public void toIdsMapsEveryPersonAndToleratesNull() {
        final PersonCommands people = new PersonCommands();
        final Person a = Person.builder().id(Person.Id.from("idlist-a")).last("Alpha").build();
        final Person b = Person.builder().id(Person.Id.from("idlist-b")).last("Beta").build();
        Assert.assertEquals(people.toIds(List.of(a, b)),
                List.of(Person.Id.from("idlist-a"), Person.Id.from("idlist-b")));
        Assert.assertEquals(people.toIds(null), List.of(), "an unpopulated view list is empty, not an error");
        Assert.assertEquals(people.toIds(List.of()), List.of());
    }

    @Test
    public void getPeopleByIdsToleratesNull() {
        Assert.assertEquals(new PersonCommands().getPeopleByIds(null), List.of(),
                "an unpopulated view list must render an empty table, not an error");
    }
}
