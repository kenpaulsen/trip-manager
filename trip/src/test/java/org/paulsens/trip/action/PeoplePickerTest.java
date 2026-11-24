package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The filter and order two people autocompletes share. Whose roster it is handed is each bean's business and
 * each bean's test ({@code ContentPersonTest}, {@code OrgCommandsTest}); this pins only the narrowing.
 */
public class PeoplePickerTest {

    private static Person person(final String first, final String preferred, final String last, final String email) {
        return Person.builder().first(first).nickname(preferred).last(last).email(email).build();
    }

    private static List<String> lastNames(final List<Person> people) {
        return people.stream().map(Person::getLast).toList();
    }

    private final Person zed = person("Zedekiah", "Zed", "Young", "zed@example.com");
    private final Person ann = person("Annabel", "Ann", "Baker", "ann@example.org");
    private final Person bob = person("Robert", "Bob", "adams", "bob@example.com");
    private final List<Person> roster = List.of(zed, ann, bob);

    @Test
    public void aBlankQueryIsTheWholeRosterByLastNameWhateverItsCase() {
        Assert.assertEquals(lastNames(PeoplePicker.pick(roster, null, 25)), List.of("adams", "Baker", "Young"));
        Assert.assertEquals(lastNames(PeoplePicker.pick(roster, "   ", 25)), List.of("adams", "Baker", "Young"));
    }

    @Test
    public void aQueryMatchesAnyNameOrTheEmailWithoutRegardToCase() {
        Assert.assertEquals(PeoplePicker.pick(roster, " ANNABEL ", 25), List.of(ann), "first name, padded");
        Assert.assertEquals(PeoplePicker.pick(roster, "bob", 25), List.of(bob), "preferred name");
        Assert.assertEquals(PeoplePicker.pick(roster, "youn", 25), List.of(zed), "last name");
        Assert.assertEquals(lastNames(PeoplePicker.pick(roster, "example.com", 25)), List.of("adams", "Young"));
        Assert.assertTrue(PeoplePicker.pick(roster, "nobody", 25).isEmpty());
    }

    /** A missing name or email is no text at all: it once joined in as the word "null". */
    @Test
    public void aMissingNameOrEmailIsNotSearchableAsTheWordNull() {
        final Person bare = person("Cy", null, null, null);
        final List<Person> withBare = List.of(zed, bare);
        Assert.assertTrue(PeoplePicker.pick(withBare, "null", 25).isEmpty());
        Assert.assertEquals(PeoplePicker.pick(withBare, "cy", 25), List.of(bare), "still found by the name it has");
        final Person nuller = person("Ada", null, "Nullman", null);
        Assert.assertEquals(PeoplePicker.pick(List.of(zed, nuller), "null", 25), List.of(nuller), "a real name");
    }

    @Test
    public void theLimitCutsAfterTheSortAndNothingOutsideTheRosterIsEverAnswered() {
        Assert.assertEquals(lastNames(PeoplePicker.pick(roster, "", 2)), List.of("adams", "Baker"));
        Assert.assertTrue(PeoplePicker.pick(List.of(), "ann", 25).isEmpty(), "an empty roster offers nobody");
    }
}
