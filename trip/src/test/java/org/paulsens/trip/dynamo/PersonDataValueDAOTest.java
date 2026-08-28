package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class PersonDataValueDAOTest {
    private PersonDataValueDAO dao;

    @BeforeMethod
    public void setup() {
        dao = new PersonDataValueDAO(new ObjectMapper().findAndRegisterModules(), DynamoLocal.persistence());
    }

    @Test
    public void saveAndRetrievePersonDataValue() throws IOException {
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(Person.Id.newInstance())
                .dataId(DataId.newInstance())
                .type("note")
                .content("Hello World")
                .build();
        assertTrue(dao.savePersonDataValue(pdv));
        final Optional<PersonDataValue> found = dao.getPersonDataValue(pdv.getUserId(), pdv.getDataId());
        assertTrue(found.isPresent());
        assertEquals(found.get(), pdv);
    }

    @Test
    public void getPersonDataValuesReturnsEmptyForUnknownPerson() {
        final Map<DataId, PersonDataValue> result = dao.getPersonDataValues(Person.Id.newInstance());
        assertTrue(result.isEmpty());
    }

    @Test
    public void getPersonDataValueReturnsEmptyForUnknownDataId() {
        assertTrue(dao.getPersonDataValue(Person.Id.newInstance(), DataId.newInstance()).isEmpty());
    }

    @Test
    public void multipleValuesForSamePerson() throws IOException {
        final Person.Id pid = Person.Id.newInstance();
        for (int i = 0; i < 4; i++) {
            dao.savePersonDataValue(PersonDataValue.builder()
                    .userId(pid)
                    .dataId(DataId.newInstance())
                    .type("type" + i)
                    .content("content" + i)
                    .build());
        }
        assertEquals(dao.getPersonDataValues(pid).size(), 4);
    }

    @Test
    public void valuesForDifferentPeopleAreIsolated() throws IOException {
        final Person.Id p1 = Person.Id.newInstance();
        final Person.Id p2 = Person.Id.newInstance();
        dao.savePersonDataValue(PersonDataValue.builder()
                .userId(p1).dataId(DataId.newInstance()).type("a").content("x").build());
        dao.savePersonDataValue(PersonDataValue.builder()
                .userId(p1).dataId(DataId.newInstance()).type("b").content("y").build());
        dao.savePersonDataValue(PersonDataValue.builder()
                .userId(p2).dataId(DataId.newInstance()).type("c").content("z").build());
        assertEquals(dao.getPersonDataValues(p1).size(), 2);
        assertEquals(dao.getPersonDataValues(p2).size(), 1);
    }

    @Test
    public void saveIsIdempotent() throws IOException {
        final Person.Id pid = Person.Id.newInstance();
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(pid).dataId(DataId.newInstance()).type("t").content("c").build();
        dao.savePersonDataValue(pdv);
        dao.savePersonDataValue(pdv);
        assertEquals(dao.getPersonDataValues(pid).size(), 1);
    }

    @Test
    public void updateReplacesInCache() throws IOException {
        final Person.Id pid = Person.Id.newInstance();
        final DataId did = DataId.newInstance();
        dao.savePersonDataValue(PersonDataValue.builder()
                .userId(pid).dataId(did).type("t").content("original").build());
        assertEquals(dao.getPersonDataValue(pid, did).get().getContent(), "original");
        dao.savePersonDataValue(PersonDataValue.builder()
                .userId(pid).dataId(did).type("t").content("updated").build());
        assertEquals(dao.getPersonDataValue(pid, did).get().getContent(), "updated");
    }

    /**
     * Clearing the cache must not lose data.
     *
     * <p>This asserted the OPPOSITE until the DAO tests moved onto a real engine: that the row was GONE after a
     * clear. That was true only because the fake persistence stored nothing, so the cache WAS the store. The
     * real invariant is that a clear drops the cached copy and the next read is served by the store.
     */
    @Test
    public void clearingTheCacheDoesNotLoseTheRow() throws IOException {
        final Person.Id pid = Person.Id.newInstance();
        dao.savePersonDataValue(PersonDataValue.builder()
                .userId(pid).dataId(DataId.newInstance()).type("t").content("c").build());
        assertEquals(dao.getPersonDataValues(pid).size(), 1);

        dao.clearCache();

        assertEquals(dao.getPersonDataValues(pid).size(), 1);
    }

    @Test
    public void complexContentIsPreserved() throws IOException {
        final Map<String, String> content = Map.of("key1", "val1", "key2", "val2");
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(Person.Id.newInstance())
                .dataId(DataId.newInstance())
                .type("map")
                .content(content)
                .build();
        dao.savePersonDataValue(pdv);
        final PersonDataValue found = dao.getPersonDataValue(pdv.getUserId(), pdv.getDataId()).orElse(null);
        assertNotNull(found);
        assertEquals(found.getContent(), content);
    }
    }

