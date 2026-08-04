package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.AuditAction;
import org.paulsens.trip.model.AuditOutcome;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * The last dynamo-layer tails: the binding-row parser against real rows, point-read error mapping, the
 * explicit endpoint seam, and the fake store's query-shape guards.
 */
public class MiscDynamoTailsTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeClass
    public void init() {
        FakeData.initFakeData();
    }

    /** A cold read against the real table walks the partition and PARSES each stored {@code type_id} key. */
    @Test
    public void aColdBindingReadParsesTheStoredRows() {
        final String tripId = "bind-parse-" + RandomData.genAlpha(6);
        final BindingDAO writer = new BindingDAO(DynamoLocal.persistence(), new InMemoryCacheClient());
        Assert.assertTrue(writer.saveBinding(tripId, BindingType.TRIP, "evt-1", BindingType.TRIP_EVENT, true)
                .join());

        // A different DAO with a cold cache: its read must query the table and parse the combined keys.
        final BindingDAO reader = new BindingDAO(DynamoLocal.persistence(), new InMemoryCacheClient());

        Assert.assertEquals(reader.getBindings(tripId, BindingType.TRIP, BindingType.TRIP_EVENT).join(),
                List.of("evt-1"));
    }

    @Test
    public void anUnknownTripEventReadsAsNull() {
        Assert.assertNull(new TripEventDAO(mapper, DynamoLocal.persistence())
                .getTripEvent("never-saved-" + RandomData.genAlpha(6)).join());
    }

    /** A failing point read maps to empty -- the page shows no privilege rather than a 500. */
    @Test
    public void aFailingPrivilegePointReadMapsToEmpty() {
        final Persistence failing = Mockito.mock(Persistence.class,
                AdditionalAnswers.delegatesTo(DynamoLocal.persistence()));
        Mockito.doReturn(CompletableFuture.failedFuture(new IllegalStateException("read refused")))
                .when(failing).getItem(ArgumentMatchers.any());

        Assert.assertTrue(new PrivilegesDAO(mapper, failing)
                .getPrivilege("anyPrivName").join().isEmpty());
    }

    /** A store failure during the index sink's close-drain is contained per event, never thrown. */
    @Test
    public void theDynamoAuditSinkContainsAFailingStoreOnDrain() {
        final DAO failing = Mockito.mock(DAO.class);
        Mockito.when(failing.saveAuditEvent(ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("index down")));
        try (MockedStatic<DAO> daoStatic = Mockito.mockStatic(DAO.class)) {
            daoStatic.when(DAO::getInstance).thenReturn(failing);
            final org.paulsens.trip.audit.DynamoAuditSink sink =
                    new org.paulsens.trip.audit.DynamoAuditSink();
            for (int i = 0; i < 3; i++) {
                sink.write(org.paulsens.trip.audit.Audit
                        .builder(AuditAction.PERSON, AuditOutcome.SUCCESS).message("d" + i).build());
            }
            sink.close(); // must drain the queue through the failure, not hang or throw
        }
    }

    /** The fake engine resolves a partition key from ANY {@code :placeholder}, not just the blessed names. */
    @Test
    public void theFakeStoreResolvesArbitraryPlaceholdersAndGuardsMissingValues() {
        final Persistence fake = FakeData.createFakePersistence();

        Assert.assertNotNull(fake.query(qb -> qb.tableName("registrations")
                .keyConditionExpression("tripId = :anything")
                .expressionAttributeValues(java.util.Map.of(":anything",
                        software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                .s("no-such-trip").build()))).join());

        Assert.assertNotNull(fake.query(qb -> qb.tableName("registrations")
                .keyConditionExpression("tripId = :x")).join(),
                "no values at all resolves to no partition, not an NPE");
    }

    /** The fake engine enforces DynamoDB's reserved-word rule so tests catch it before production does. */
    @Test
    public void theFakeStoreRejectsAnUnaliasedReservedWord() {
        final Persistence fake = FakeData.createFakePersistence();

        Assert.assertThrows(() -> fake.query(qb -> qb.tableName("audit")
                .keyConditionExpression("day = :d")
                .expressionAttributeValues(java.util.Map.of(":d",
                        software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                                .s("2026-01-01").build()))).join());
    }

    @Test
    public void privilegeLookupsGuardTheirNullInputs() {
        final PrivilegesDAO dao = new PrivilegesDAO(mapper, DynamoLocal.persistence());

        Assert.assertTrue(dao.getPrivilege(null).join().isEmpty());
        Assert.assertNotNull(dao.getTripPrivileges(null).join(), "a null trip means the global partition");
    }

    @Test
    public void chatLookupsGuardTheirNullInputsAndFailingPointReads() {
        final ChatDAO dao = new ChatDAO(mapper, DynamoLocal.persistence(),
                new org.paulsens.trip.cache.InMemoryCacheClient());
        Assert.assertTrue(dao.getChannel(null).join().isEmpty());
        Assert.assertTrue(dao.getMembership(null, org.paulsens.trip.model.Person.Id.from("p"))
                .join().isEmpty());
        Assert.assertTrue(dao.getMembership(org.paulsens.trip.model.chat.ChatChannel.Id.forTrip("t"), null)
                .join().isEmpty());

        // A failing point read logs and answers absent -- the chat page shows nothing, not a 500.
        final Persistence failing = Mockito.mock(Persistence.class,
                AdditionalAnswers.delegatesTo(DynamoLocal.persistence()));
        Mockito.doReturn(CompletableFuture.failedFuture(new IllegalStateException("read refused")))
                .when(failing).getItem(ArgumentMatchers.any());
        final ChatDAO broken = new ChatDAO(mapper, failing,
                new org.paulsens.trip.cache.InMemoryCacheClient());
        Assert.assertTrue(broken.getChannel(
                org.paulsens.trip.model.chat.ChatChannel.Id.forTrip("fail-" + RandomData.genAlpha(6)))
                .join().isEmpty());
        Assert.assertTrue(broken.getMembership(
                org.paulsens.trip.model.chat.ChatChannel.Id.forTrip("fail-" + RandomData.genAlpha(6)),
                org.paulsens.trip.model.Person.Id.from("p")).join().isEmpty());
    }

    /** The cache-facing toJson answers null on a serialization failure rather than poisoning the cache. */
    @Test
    public void aSerializerFailureAfterTheRowWriteDoesNotPoisonTheCache() throws Exception {
        // Fails from the SECOND serialization on: the row write succeeds, the cache serializer does not.
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final ObjectMapper flaky = Mockito.mock(ObjectMapper.class, AdditionalAnswers.delegatesTo(mapper));
        Mockito.doAnswer(invocation -> {
            if (calls.incrementAndGet() > 1) {
                throw new com.fasterxml.jackson.databind.JsonMappingException(null, "flaky");
            }
            return mapper.writeValueAsString(invocation.getArgument(0));
        }).when(flaky).writeValueAsString(ArgumentMatchers.any());

        final TripEventDAO dao = new TripEventDAO(flaky, DynamoLocal.persistence());
        final org.paulsens.trip.model.TripEvent event = new org.paulsens.trip.model.TripEvent(
                "flaky-" + RandomData.genAlpha(6), org.paulsens.trip.model.TripEvent.Type.EVENT, "T", null,
                java.time.LocalDateTime.now(), null, null, null);

        Assert.assertTrue(dao.saveTripEvent(event).join(),
                "the row landed; a cache-serializer failure must not fail the save");
    }

    /** The endpoint override seam: explicit, never inferred, and buildable without touching AWS. */
    @Test
    public void theDynamoPersistenceEndpointSeamBuildsAgainstALocalUri() {
        System.setProperty("trip.dynamo.endpoint", "http://localhost:1");
        try {
            Assert.assertNotNull(new DynamoPersistence());
        } finally {
            System.clearProperty("trip.dynamo.endpoint");
        }
    }
}
