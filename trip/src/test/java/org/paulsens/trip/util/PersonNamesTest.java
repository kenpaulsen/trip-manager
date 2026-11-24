package org.paulsens.trip.util;

import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

public class PersonNamesTest {

    @Test
    public void joinsThePreferredNameAndTheLastName() {
        Assert.assertEquals(PersonNames.display(person("Ann", null, "Jones")), "Ann Jones");
        Assert.assertEquals(PersonNames.display(person("Robert", "Bob", "Smith")), "Bob Smith",
                "a nickname is the preferred name");
    }

    /** The bug this class exists for: a missing part used to be spelled into the name as "null". */
    @Test
    public void aMissingPartIsDroppedNotSpelledAsNull() {
        Assert.assertEquals(PersonNames.display(person("Ann", null, null)), "Ann");
        Assert.assertEquals(PersonNames.display(person("Ann", null, "  ")), "Ann");
        Assert.assertEquals(PersonNames.display(person(null, null, "Jones")), "Jones");
        Assert.assertEquals(PersonNames.display(person(null, null, null)), "");
        Assert.assertEquals(PersonNames.display(null), "");
    }

    /** Each part is trimmed before the join, so a padded part cannot put two spaces inside the name. */
    @Test
    public void aPaddedPartIsTrimmedBeforeTheJoin() {
        Assert.assertEquals(PersonNames.display(person(" Ann ", null, "  Jones ")), "Ann Jones");
        Assert.assertEquals(PersonNames.display(person("Robert", " Bob  ", "Smith\t")), "Bob Smith");
        Assert.assertEquals(PersonNames.display(person(null, null, "  Jones ")), "Jones");
        Assert.assertEquals(PersonNames.display(person("\u2003", null, "Jones")), "Jones",
                "a part of only Unicode spaces is no part");
        Assert.assertEquals(PersonNames.display(person("Ann", null, "\u0001")), "Ann");
        Assert.assertEquals(PersonNames.displayOr(person(" ", null, "\u2003"), "id-1"), "id-1");
    }

    @Test
    public void theFallbackStandsInForNobodyAndForNoName() {
        Assert.assertEquals(PersonNames.displayOr(person("Ann", null, "Jones"), "id-1"), "Ann Jones");
        Assert.assertEquals(PersonNames.displayOr(person(null, null, null), "id-1"), "id-1");
        Assert.assertEquals(PersonNames.displayOr(null, "id-1"), "id-1");
    }

    private static Person person(final String first, final String nickname, final String last) {
        final Person person = new Person();
        person.setFirst(first);
        person.setNickname(nickname);
        person.setLast(last);
        return person;
    }
}
