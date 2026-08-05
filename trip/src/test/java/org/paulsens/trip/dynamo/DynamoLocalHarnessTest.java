package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Proves the harness gives the DAOs a REAL store: a write survives losing the cache. */
public class DynamoLocalHarnessTest {

    @Test
    public void aWrittenRowSurvivesWithoutAnyCache() throws Exception {
        final PersonDAO dao = new PersonDAO(
                new ObjectMapper().findAndRegisterModules(), DynamoLocal.persistence());
        final Person person = Person.builder()
                .id(Person.Id.from(DynamoLocal.uniqueId("harness")))
                .first("Harness")
                .last("Person")
                .build();

        Assert.assertTrue(dao.savePerson(person));

        // A SECOND DAO: no shared cache, so this can only be answered by the store itself.
        final PersonDAO fresh = new PersonDAO(
                new ObjectMapper().findAndRegisterModules(), DynamoLocal.persistence());
        final Optional<Person> found = fresh.getPerson(person.getId());

        Assert.assertTrue(found.isPresent(), "The row must come back from the engine, not from a cache");
        Assert.assertEquals(found.get().getFirst(), "Harness");
    }
}
