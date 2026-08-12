package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.util.List;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class FamilyDAOTest {
    private static DAO dao;

    @BeforeClass
    public void initTests() {
        dao = DAO.getInstance();
    }

    @Test
    public void saveAndReadBack() throws IOException {
        final Family family = newFamily();
        assertTrue(dao.saveFamily(family));
        assertEquals(family.getVersion(), 1L, "A successful save bumps the in-memory version");

        final Family read = dao.getFamily(family.getId()).orElseThrow();
        assertEquals(read, family);
    }

    @Test
    public void creatingTheSameFamilyTwiceIsRejected() throws IOException {
        final Family family = newFamily();
        assertTrue(dao.saveFamily(family));

        final Family imposter = Family.builder().id(family.getId()).build();  // version 0 => create-only
        try {
            dao.saveFamily(imposter);
            throw new AssertionError("Second version-0 save of the same id must be rejected");
        } catch (final ConditionalCheckFailedException expected) {
            assertEquals(imposter.getVersion(), 0L, "A rejected save must not bump the version");
        }
    }

    @Test
    public void staleVersionIsRejectedAndRetryAfterRereadSucceeds() throws IOException {
        final Family family = newFamily();
        assertTrue(dao.saveFamily(family));                       // stored version 1

        final Family other = dao.getFamily(family.getId()).orElseThrow();   // loaded at version 1
        other.getMemberIds().add(Person.Id.from("added-by-other"));
        assertTrue(dao.saveFamily(other));                        // stored version 2

        family.getMemberIds().add(Person.Id.from("added-by-loser"));
        try {
            dao.saveFamily(family);                               // still believes version 1
            throw new AssertionError("A stale-version save must be rejected");
        } catch (final ConditionalCheckFailedException expected) {
            assertEquals(family.getVersion(), 1L, "The loser keeps its loaded version so it can retry");
        }

        final Family reread = dao.getFamily(family.getId()).orElseThrow();
        reread.getMemberIds().add(Person.Id.from("added-by-loser"));
        assertTrue(dao.saveFamily(reread), "Retry after re-read must succeed");
        assertEquals(reread.getVersion(), 3L);
    }

    @Test
    public void cachedReadsReturnCopies() throws IOException {
        final Family family = newFamily();
        assertTrue(dao.saveFamily(family));

        final Family first = dao.getFamily(family.getId()).orElseThrow();
        first.getMemberIds().add(Person.Id.from("mutated-locally"));

        final Family second = dao.getFamily(family.getId()).orElseThrow();
        assertEquals(second.getMemberIds().size(), 1,
                "Mutating a read copy must not leak into later reads (the documented DAO copy contract)");
    }

    @Test
    public void deleteIsIdempotent() throws IOException {
        final Family family = newFamily();
        assertTrue(dao.saveFamily(family));
        assertTrue(dao.deleteFamily(family.getId()));
        assertTrue(dao.getFamily(family.getId()).isEmpty());
        assertTrue(dao.deleteFamily(family.getId()), "Deleting an absent family is not an error");
    }

    @Test
    public void nullIdReadsEmpty() {
        assertTrue(dao.getFamily(null).isEmpty());
    }

    @Test
    public void aCorruptRowReadsEmptyRatherThanThrowing() {
        // A standalone DAO over its own store, so the corrupt row can be written past the serializer.
        final InMemoryPersistence persistence = new InMemoryPersistence();
        final FamilyDAO familyDao = new FamilyDAO(
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), persistence,
                new org.paulsens.trip.cache.InMemoryCacheClient());
        final String id = "corrupt-" + RandomData.genAlpha(8);
        final java.util.Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item =
                java.util.Map.of(
                        "id", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                .s(id).build(),
                        "content", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                .s("{not json").build());
        persistence.putItem(b -> b.tableName(FamilyDAO.FAMILY_TABLE).item(item));
        assertTrue(familyDao.getFamily(Family.Id.from(id)).isEmpty(),
                "Unparseable content answers empty, never throws");
    }

    private static Family newFamily() {
        final Person.Id creator = Person.Id.from(RandomData.genAlpha(8));
        return Family.builder()
                .id(Family.Id.from(RandomData.genAlpha(10)))
                .memberIds(List.of(creator))
                .managerIds(List.of(creator))
                .createdBy(creator)
                .build();
    }
}
