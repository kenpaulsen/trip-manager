package org.paulsens.trip.dynamo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import static org.testng.Assert.*;

public class FakeDataTest {

    @BeforeClass
    public void init() {
        FakeData.initFakeData();
    }

    @Test
    public void initFakeDataCreatesPeople() {
        final List<Person> people = FakeData.getFakePeople();
        assertNotNull(people);
        assertFalse(people.isEmpty());
    }

    @Test
    public void initFakeDataCreatesTrips() {
        final List<Trip> trips = FakeData.getFakeTrips();
        assertNotNull(trips);
        assertFalse(trips.isEmpty());
    }

    @Test
    public void fakePeopleHaveIds() {
        for (final Person person : FakeData.getFakePeople()) {
            assertNotNull(person.getId(), "All fake people should have IDs");
        }
    }

    @Test
    public void fakePeopleHaveNames() {
        for (final Person person : FakeData.getFakePeople()) {
            assertNotNull(person.getFirst(), "All fake people should have first names");
            assertNotNull(person.getLast(), "All fake people should have last names");
        }
    }

    @Test
    public void fakeTripsHaveIds() {
        for (final Trip trip : FakeData.getFakeTrips()) {
            assertNotNull(trip.getId(), "All fake trips should have IDs");
        }
    }

    @Test
    public void fakeTripsHaveTripEvents() {
        for (final Trip trip : FakeData.getFakeTrips()) {
            assertFalse(trip.getTripEvents().isEmpty(),
                    "Trip '" + trip.getTitle() + "' should have trip events");
        }
    }

    @Test
    public void createFakePersistenceReturnsNonNull() {
        assertNotNull(FakeData.createFakePersistence());
    }

    @Test
    public void fakePersistencePutItemReturnsSuccess() {
        final Persistence p = FakeData.createFakePersistence();
        final PutItemResponse resp = get(p.putItem(b -> b.tableName("test")));
        assertTrue(resp.sdkHttpResponse().isSuccessful());
    }

    @Test
    public void fakePersistenceScanReturnsEmpty() {
        final Persistence p = FakeData.createFakePersistence();
        final ScanResponse resp = get(p.scan(b -> b.tableName("test")));
        assertTrue(resp.items().isEmpty());
    }

    @Test
    public void fakePersistenceQueryReturnsEmpty() {
        final Persistence p = FakeData.createFakePersistence();
        final QueryResponse resp = get(p.query(b -> b.tableName("test")));
        assertTrue(resp.items().isEmpty());
    }

    @Test
    public void createFakePersistenceWithQueryMonitorCallsMonitor() {
        final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger(0);
        final Persistence p = FakeData.createFakePersistenceWithQueryMonitor(q -> count.incrementAndGet());
        assertEquals(count.get(), 0);
        get(p.query(b -> b.tableName("test")));
        assertEquals(count.get(), 1);
        get(p.query(b -> b.tableName("test2")));
        assertEquals(count.get(), 2);
    }

    @Test
    public void queryMonitorPersistenceStillReturnsEmptyResults() {
        final Persistence p = FakeData.createFakePersistenceWithQueryMonitor(q -> {});
        final QueryResponse resp = get(p.query(b -> b.tableName("test")));
        assertTrue(resp.items().isEmpty());
    }

    @Test
    public void getDefaultOptionsReturnsNonEmpty() {
        final List<RegistrationOption> options = FakeData.getDefaultOptions();
        assertNotNull(options);
        assertFalse(options.isEmpty());
    }

    @Test
    public void getDefaultOptionsHaveDescriptions() {
        for (final RegistrationOption opt : FakeData.getDefaultOptions()) {
            assertNotNull(opt.getShortDesc(), "Option should have a short description");
            assertNotNull(opt.getLongDesc(), "Option should have a long description");
        }
    }

    @Test
    public void getTestUserCredsForAdminReturnsAdminPriv() {
        final GetItemRequest req = GetItemRequest.builder()
                .tableName(CredentialsDAO.PASS_TABLE)
                .key(Map.of(CredentialsDAO.EMAIL, AttributeValue.builder().s("admin123").build()))
                .build();
        final Map<String, AttributeValue> creds = FakeData.getTestUserCreds(req);
        assertNotNull(creds);
        assertEquals(creds.get(CredentialsDAO.PRIV).s(), "admin");
    }

    @Test
    public void getTestUserCredsForUserReturnsUserPriv() {
        final GetItemRequest req = GetItemRequest.builder()
                .tableName(CredentialsDAO.PASS_TABLE)
                .key(Map.of(CredentialsDAO.EMAIL, AttributeValue.builder().s("userABC").build()))
                .build();
        final Map<String, AttributeValue> creds = FakeData.getTestUserCreds(req);
        assertNotNull(creds);
        assertEquals(creds.get(CredentialsDAO.PRIV).s(), "user");
    }

    @Test
    public void getTestUserCredsForUnknownReturnsNull() {
        final GetItemRequest req = GetItemRequest.builder()
                .tableName(CredentialsDAO.PASS_TABLE)
                .key(Map.of(CredentialsDAO.EMAIL, AttributeValue.builder().s("unknown@test.com").build()))
                .build();
        assertNull(FakeData.getTestUserCreds(req));
    }

    @Test
    public void getTestUserCredsEmailIsCaseInsensitive() {
        final GetItemRequest req = GetItemRequest.builder()
                .tableName(CredentialsDAO.PASS_TABLE)
                .key(Map.of(CredentialsDAO.EMAIL, AttributeValue.builder().s("ADMIN_user").build()))
                .build();
        final Map<String, AttributeValue> creds = FakeData.getTestUserCreds(req);
        assertNotNull(creds);
        assertEquals(creds.get(CredentialsDAO.EMAIL).s(), "admin_user");
    }

    @Test
    public void getTestUserCredsIncludesLastLogin() {
        final GetItemRequest req = GetItemRequest.builder()
                .tableName(CredentialsDAO.PASS_TABLE)
                .key(Map.of(CredentialsDAO.EMAIL, AttributeValue.builder().s("admin").build()))
                .build();
        final Map<String, AttributeValue> creds = FakeData.getTestUserCreds(req);
        assertNotNull(creds);
        assertNotNull(creds.get(CredentialsDAO.LAST_LOGIN));
    }

    private <T> T get(final CompletableFuture<T> future) {
        try {
            return future.get(1_000, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException | ExecutionException | TimeoutException ex) {
            throw new RuntimeException(ex);
        }
    }
}
