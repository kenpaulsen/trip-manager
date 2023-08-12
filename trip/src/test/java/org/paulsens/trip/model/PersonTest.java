package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

public class PersonTest {
    private static final List<Person> people = FakeData.getFakePeople();
    private static final String OLD_SERIALIZED_PERSON_0 = "{\"id\":\"" + people.get(0).getId().getValue() + "\","
            + "\"nickname\":\"Joe\",\"first\":\"Joseph\",\"middle\":\"Bob\",\"last\":\"Smith\",\"sex\":\"Male\","
            + "\"birthdate\":\"1947-02-11\",\"email\":\"user1\",\"address\":{},\"passport\":{},\"managedUsers\":[]}";

    @Test
    public void equalsTest() {
        EqualsVerifier.forClass(Person.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    public void canSerializeToFromJson() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Person before = people.get(0);
        final String personStr = mapper.writeValueAsString(people.get(0));
        final Person after = mapper.readValue(personStr, Person.class);
        Assert.assertEquals(after, before, "To/from json failed!");
    }

    @Test
    public void canReadOldStuff() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Person after = mapper.readValue(OLD_SERIALIZED_PERSON_0, Person.class);
        Assert.assertEquals(after, people.get(0), "Reading old json failed!");
    }

    @Test
    public void canReadWithOutSexField() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Person before = Person.builder()
                .id(Person.Id.from(RandomData.genAlpha(5)))
                .first(RandomData.genAlpha(12))
                .last(RandomData.genAlpha(18))
                .cell(RandomData.genString(10, new char[] {'1', '2', '3', '4', '5', '6', '7', '8', '9'}))
                .birthdate(LocalDate.now())
                .build();
        final String json = mapper.writeValueAsString(before);
        Assert.assertFalse(json.contains("sex"), "No sex specified, yet it appears in json: " + json);
        final Person after = mapper.readValue(json, Person.class);
        Assert.assertEquals(after, before, "Reading json w/o sex failed!");
    }

    @Test
    public void canReadEmergencyContactInfo() throws Exception {
        final String contactName = " Jaye J.";
        final String contactPhone = "abc123 ";
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Person before = new Person(Person.Id.from(RandomData.genAlpha(19)), null, " Kevin", "David ", " Paulsen ",
                null, LocalDate.of(1987, 9, 27), null,"user3", null, null, null, null, null, contactName, contactPhone);
        final String personStr = mapper.writeValueAsString(before);
        final Person after = mapper.readValue(personStr, Person.class);
        Assert.assertEquals(after.getEmergencyContactName(), contactName.trim());
        Assert.assertEquals(after.getEmergencyContactPhone(), contactPhone.trim());
        Assert.assertEquals(after.getFirst(), "Kevin");
        Assert.assertEquals(after.getMiddle(), "David");
        Assert.assertEquals(after.getLast(), "Paulsen");
    }

    @Test
    public void test() {
    }

    @Test
    public void nicknameIsCorrect() {
        Assert.assertEquals(people.get(0).getNickname(), "Joe");
        Assert.assertEquals(people.get(0).getPreferredName(), "Joe");
        Assert.assertEquals(people.get(0).getFirst(), "Joseph");
        Assert.assertNull(people.get(1).getNickname());
        Assert.assertEquals(people.get(1).getPreferredName(), "admin");
        Assert.assertEquals(people.get(1).getFirst(), "admin");
        Assert.assertEquals(people.get(2).getNickname(), "Ken");
        Assert.assertEquals(people.get(2).getPreferredName(), "Ken");
        Assert.assertEquals(people.get(2).getFirst(), "Kenneth");
        Assert.assertNull(people.get(3).getNickname());
        Assert.assertEquals(people.get(3).getPreferredName(), "Kevin");
        Assert.assertEquals(people.get(3).getFirst(), "Kevin");
        Assert.assertEquals(people.get(4).getNickname(), "Trinity");
        Assert.assertEquals(people.get(4).getPreferredName(), "Trinity");
        Assert.assertEquals(people.get(4).getFirst(), "Trinity");
        Assert.assertEquals(people.get(5).getNickname(), "Dave");
        Assert.assertEquals(people.get(5).getPreferredName(), "Dave");
        Assert.assertEquals(people.get(5).getFirst(), "David");
        Assert.assertEquals(people.get(6).getNickname(), "Matt");
        Assert.assertEquals(people.get(6).getPreferredName(), "Matt");
        Assert.assertEquals(people.get(6).getFirst(), "Matthew");
    }
}
