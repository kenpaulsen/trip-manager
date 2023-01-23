package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import nl.jqno.equalsverifier.EqualsVerifier;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class PrivilegeTest {
    @Test
    public void testGetName() {
        final String name = RandomData.genAlpha(14);
        final Privilege priv = new Privilege(name, RandomData.genAlpha(5), List.of());
        assertEquals(priv.getName(), name);
    }

    @Test
    public void testGetDescription() {
        final String desc = RandomData.genAlpha(14);
        final Privilege priv = new Privilege(
                RandomData.genAlpha(5), desc, FakeData.getFakePeople().stream().map(Person::getId).toList());
        assertEquals(priv.getDescription(), desc);
    }

    @Test
    public void testGetPeople() {
        final Person.Id id1 = FakeData.getFakePeople().get(0).getId();
        final Person.Id id2 = FakeData.getFakePeople().get(1).getId();
        final List<Person.Id> people = new ArrayList<>();
        people.add(id1);
        people.add(id2);
        final Privilege priv = new Privilege(RandomData.genAlpha(7), RandomData.genAlpha(5), people);
        assertEquals(priv.getPeople().size(), 2);
        assertEquals(priv.getPeople().get(0), id1);
        assertEquals(priv.getPeople().get(1), id2);
    }

    @Test
    public void canSerializePrivileges() throws Exception {
        final Privilege before = new Privilege(RandomData.genAlpha(5), RandomData.genAlpha(9),
                FakeData.getFakePeople().stream().map(Person::getId).toList());
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final String valueAsString = mapper.writeValueAsString(before);
        final Privilege after = mapper.readValue(valueAsString, Privilege.class);
        assertEquals(after, before);
    }

    @Test
    public void testTestEquals() {
        EqualsVerifier.forClass(Privilege.class).verify();
    }
}