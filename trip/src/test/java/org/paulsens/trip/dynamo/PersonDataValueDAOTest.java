package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class PersonDataValueDAOTest {
    private PersonDataValueDAO dao;

    @BeforeMethod
    public void setup() {
        dao = new PersonDataValueDAO(new ObjectMapper().findAndRegisterModules(), FakeData.createFakePersistence());
    }

    @Test
    public void saveAndRetrievePersonDataValue() throws IOException {
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(Person.Id.newInstance())
                .dataId(DataId.newInstance())
                .type("note")
                .content("Hello World")
                .build();
        assertTrue(get(dao.savePersonDataValue(pdv)));
        final Optional<PersonDataValue> found = get(dao.getPersonDataValue(pdv.getUserId(), pdv.getDataId()));
        assertTrue(found.isPresent());
        assertEquals(found.get(), pdv);
    }

    @Test
    public void getPersonDataValuesReturnsEmptyForUnknownPerson() {
        final Map<DataId, PersonDataValue> result = get(dao.getPersonDataValues(Person.Id.newInstance()));
        assertTrue(result.isEmpty());
    }

    @Test
    public void getPersonDataValueReturnsEmptyForUnknownDataId() {
        assertTrue(get(dao.getPersonDataValue(Person.Id.newInstance(), DataId.newInstance())).isEmpty());
    }

    @Test
    public void multipleValuesForSamePerson() throws IOException {
        final Person.Id pid = Person.Id.newInstance();
        for (int i = 0; i < 4; i++) {
            get(dao.savePersonDataValue(PersonDataValue.builder()
                    .userId(pid)
                    .dataId(DataId.newInstance())
                    .type("type" + i)
                    .content("content" + i)
                    .build()));
        }
        assertEquals(get(dao.getPersonDataValues(pid)).size(), 4);
    }

    @Test
    public void valuesForDifferentPeopleAreIsolated() throws IOException {
        final Person.Id p1 = Person.Id.newInstance();
        final Person.Id p2 = Person.Id.newInstance();
        get(dao.savePersonDataValue(PersonDataValue.builder()
                .userId(p1).dataId(DataId.newInstance()).type("a").content("x").build()));
        get(dao.savePersonDataValue(PersonDataValue.builder()
                .userId(p1).dataId(DataId.newInstance()).type("b").content("y").build()));
        get(dao.savePersonDataValue(PersonDataValue.builder()
                .userId(p2).dataId(DataId.newInstance()).type("c").content("z").build()));
        assertEquals(get(dao.getPersonDataValues(p1)).size(), 2);
        assertEquals(get(dao.getPersonDataValues(p2)).size(), 1);
    }

    @Test
    public void saveIsIdempotent() throws IOException {
        final Person.Id pid = Person.Id.newInstance();
        final PersonDataValue pdv = PersonDataValue.builder()
                .userId(pid).dataId(DataId.newInstance()).type("t").content("c").build();
        get(dao.savePersonDataValue(pdv));
        get(dao.savePersonDataValue(pdv));
        assertEquals(get(dao.getPersonDataValues(pid)).size(), 1);
    }

    @Test
    public void updateReplacesInCache() throws IOException {
        final Person.Id pid = Person.Id.newInstance();
        final DataId did = DataId.newInstance();
        get(dao.savePersonDataValue(PersonDataValue.builder()
                .userId(pid).dataId(did).type("t").content("original").build()));
        assertEquals(get(dao.getPersonDataValue(pid, did)).get().getContent(), "original");
        get(dao.savePersonDataValue(PersonDataValue.builder()
                .userId(pid).dataId(did).type("t").content("updated").build()));
        assertEquals(get(dao.getPersonDataValue(pid, did)).get().getContent(), "updated");
    }

    @Test
    public void clearCacheWorks() throws IOException {
        final Person.Id pid = Person.Id.newInstance();
        get(dao.savePersonDataValue(PersonDataValue.builder()
                .userId(pid).dataId(DataId.newInstance()).type("t").content("c").build()));
        assertEquals(get(dao.getPersonDataValues(pid)).size(), 1);
        dao.clearCache();
        assertEquals(get(dao.getPersonDataValues(pid)).size(), 0);
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
        get(dao.savePersonDataValue(pdv));
        final PersonDataValue found = get(dao.getPersonDataValue(pdv.getUserId(), pdv.getDataId())).orElse(null);
        assertNotNull(found);
        assertEquals(found.getContent(), content);
    }

    private <T> T get(final CompletableFuture<T> future) {
        try {
            return future.get(1_000, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException | ExecutionException | TimeoutException ex) {
            throw new RuntimeException(ex);
        }
    }
}
