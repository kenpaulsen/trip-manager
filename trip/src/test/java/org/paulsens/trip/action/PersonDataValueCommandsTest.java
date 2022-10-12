package org.paulsens.trip.action;

import java.util.Map;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class PersonDataValueCommandsTest {
    @Test
    public void testCreatePersonDataValue() {
        final Person.Id pid = Person.Id.newInstance();
        final PersonDataValue.Id pdvId = PersonDataValue.Id.newInstance();
        assertNull(PersonDataValueCommands.getPersonDataValue(pid, pdvId));
        final String type = RandomData.genAlpha(17);
        final PersonDataValue pdv = PersonDataValueCommands.createPersonDataValue(pid, pdvId, type);
        assertEquals(pdv.getUserId(), pid);
        assertEquals(pdv.getDataId(), pdvId);
        assertEquals(pdv.getType(), type);
        assertNotNull(pdv.getContent());
    }

    @Test
    public void testSavePersonDataValue() {
        final Person.Id pid = Person.Id.newInstance();
        final PersonDataValue.Id pdvId = PersonDataValue.Id.newInstance();
        assertNull(PersonDataValueCommands.getPersonDataValue(pid, pdvId));
        final String type = RandomData.genAlpha(11);
        final PersonDataValue pdv = PersonDataValueCommands.createPersonDataValue(pid, pdvId, type);
        final String content = RandomData.genString(33, RandomData.ALPHA_NUM);
        pdv.setContent(content);
        assertTrue(PersonDataValueCommands.savePersonDataValue(pdv));
        assertEquals(PersonDataValueCommands.getPersonDataValue(pid, pdvId), pdv);
    }

    @Test
    public void testGetPersonDataValues() {
        final Person.Id pid = Person.Id.newInstance();
        assertEquals(PersonDataValueCommands.getPersonDataValues(pid), Map.of());
        final PersonDataValue pdv1 = PersonDataValueCommands.createPersonDataValue(
                pid, PersonDataValue.Id.newInstance(), RandomData.genAlpha(3));
        final PersonDataValue pdv2 = PersonDataValueCommands.createPersonDataValue(
                pid, PersonDataValue.Id.newInstance(), RandomData.genAlpha(3));
        final PersonDataValue pdv3 = PersonDataValueCommands.createPersonDataValue(
                pid, PersonDataValue.Id.newInstance(), RandomData.genAlpha(3));
        final PersonDataValue notSamePerson = PersonDataValueCommands.createPersonDataValue(
                Person.Id.newInstance(), PersonDataValue.Id.newInstance(), RandomData.genAlpha(3));
        assertEquals(PersonDataValueCommands.getPersonDataValues(pid), Map.of());
        assertTrue(PersonDataValueCommands.savePersonDataValue(pdv1));
        assertTrue(PersonDataValueCommands.savePersonDataValue(notSamePerson));
        assertTrue(PersonDataValueCommands.savePersonDataValue(pdv2));
        assertTrue(PersonDataValueCommands.savePersonDataValue(pdv3));
        final Map<PersonDataValue.Id, PersonDataValue> allForPerson = PersonDataValueCommands.getPersonDataValues(pid);
        assertEquals(allForPerson.size(), 3);
        assertEquals(allForPerson.get(pdv1.getDataId()), pdv1);
        assertEquals(allForPerson.get(pdv2.getDataId()), pdv2);
        assertEquals(allForPerson.get(pdv3.getDataId()), pdv3);
    }
}