package org.paulsens.trip.model;

import java.util.List;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.FakeData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class PersonTest {

    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(Person.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    public void nicknameIsCorrect() {
        final List<Person> people = FakeData.getFakePeople();
        Assert.assertEquals(people.get(0).getNickname(), "Joe");
        Assert.assertEquals(people.get(0).getPreferredName(), "Joe");
        Assert.assertEquals(people.get(0).getFirst(), "Joseph");
        Assert.assertEquals(people.get(1).getNickname(), "Ken");
        Assert.assertEquals(people.get(1).getPreferredName(), "Ken");
        Assert.assertEquals(people.get(1).getFirst(), "Kenneth");
        Assert.assertNull(people.get(2).getNickname());
        Assert.assertEquals(people.get(2).getPreferredName(), "Kevin");
        Assert.assertEquals(people.get(2).getFirst(), "Kevin");
        Assert.assertEquals(people.get(3).getNickname(), "Trinity");
        Assert.assertEquals(people.get(3).getPreferredName(), "Trinity");
        Assert.assertEquals(people.get(3).getFirst(), "Trinity");
        Assert.assertEquals(people.get(4).getNickname(), "Dave");
        Assert.assertEquals(people.get(4).getPreferredName(), "Dave");
        Assert.assertEquals(people.get(4).getFirst(), "David");
        Assert.assertEquals(people.get(5).getNickname(), "Matt");
        Assert.assertEquals(people.get(5).getPreferredName(), "Matt");
        Assert.assertEquals(people.get(5).getFirst(), "Matthew");
    }
}
