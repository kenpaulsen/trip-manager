package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class PersonTest {
    private static final List<Person> people = FakeData.getFakePeople();
    private static final String OLD_SERIALIZED_PERSON_0 = "{\"id\":\"" + people.get(0).getId().getValue() + "\","
            + "\"nickname\":\"Joe\",\"first\":\"Joseph\",\"middle\":\"Bob\",\"last\":\"Smith\","
            + "\"birthdate\":\"1947-02-11\",\"email\":\"user1\",\"address\":{},\"passport\":{},\"managedUsers\":[]}";

    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(Person.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    public void canSerializeToFromJson() throws Exception {
        final ObjectMapper mapper = DynamoUtils.getInstance().getMapper();
        final Person before = people.get(0);
        final String personStr = mapper.writeValueAsString(people.get(0));
        System.out.println(personStr);
        final Person after = mapper.readValue(personStr, Person.class);
        Assert.assertEquals(after, before, "To/from json failed!");
    }

    @Test
    public void canReadOldStuff() throws Exception {
        final ObjectMapper mapper = DynamoUtils.getInstance().getMapper();
        final Person after = mapper.readValue(OLD_SERIALIZED_PERSON_0, Person.class);
        Assert.assertEquals(after, people.get(0), "Reading old json failed!");
    }

    @Test
    public void canReadEmergencyContactInfo() throws Exception {
        final String contactName = "Jaye J.";
        final String contactPhone = "abc123";
        final ObjectMapper mapper = DynamoUtils.getInstance().getMapper();
        final Person before = new Person(Person.Id.from(RandomData.genAlpha(19)), null, "Kevin", "David", "Paulsen",
                LocalDate.of(1987, 9, 27), null,"user3", null, null, null, null, null, contactName, contactPhone);
        final String personStr = mapper.writeValueAsString(before);
        final Person after = mapper.readValue(personStr, Person.class);
        Assert.assertEquals(after.getEmergencyContactName(), contactName);
        Assert.assertEquals(after.getEmergencyContactPhone(), contactPhone);
    }

    @Test
    public void nicknameIsCorrect() {
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
