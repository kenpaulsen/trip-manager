package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class CredentialsDAOTest {
    private CredentialsDAO dao;
    private PersonDAO personDao;
    private Persistence persistence;

    @BeforeClass
    public void init() {
        FakeData.initFakeData();
    }

    @BeforeMethod
    public void setup() {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        persistence = FakeData.createFakePersistence();
        personDao = new PersonDAO(mapper, persistence);
        dao = new CredentialsDAO(persistence, personDao);
    }

    @Test
    public void getCredsByEmailAndPassReturnsNullForNullEmail() {
        assertNull(get(dao.getCredsByEmailAndPass(null, "pass")));
    }

    @Test
    public void getCredsByEmailAndPassReturnsNullForEmptyEmail() {
        assertNull(get(dao.getCredsByEmailAndPass("", "pass")));
    }

    @Test
    public void getCredsByEmailAndPassReturnsNullForNullPass() {
        assertNull(get(dao.getCredsByEmailAndPass("user@test.com", null)));
    }

    @Test
    public void getCredsByEmailAndPassReturnsNullForEmptyPass() {
        assertNull(get(dao.getCredsByEmailAndPass("user@test.com", "")));
    }

    @Test
    public void getCredsByEmailAndPassReturnsNullForBothNull() {
        assertNull(get(dao.getCredsByEmailAndPass(null, null)));
    }

    @Test
    public void saveAndRetrieveCreds() {
        final List<Map<String, AttributeValue>> captured = new ArrayList<>();
        final Persistence capturingPersistence = new Persistence() {
            @Override
            public CompletableFuture<PutItemResponse> putItem(Consumer<PutItemRequest.Builder> putItemRequest) {
                final PutItemRequest.Builder builder = PutItemRequest.builder();
                putItemRequest.accept(builder);
                captured.add(builder.build().item());
                return Persistence.super.putItem(putItemRequest);
            }
        };
        final CredentialsDAO capturingDao = new CredentialsDAO(capturingPersistence, personDao);
        final Person.Id userId = Person.Id.newInstance();
        final Creds creds = new Creds("test@example.com", userId, "user", "mypass", null);
        assertTrue(get(capturingDao.saveCreds(creds)));
        assertEquals(captured.size(), 1);
        final Map<String, AttributeValue> saved = captured.get(0);
        assertEquals(saved.get("email").s(), "test@example.com");
        assertEquals(saved.get("userId").s(), userId.getValue());
        assertEquals(saved.get("priv").s(), "user");
        assertEquals(saved.get("pass").s(), "mypass");
    }

    @Test
    public void saveCredsPreservesLastLogin() {
        final List<Map<String, AttributeValue>> captured = new ArrayList<>();
        final Persistence capturingPersistence = new Persistence() {
            @Override
            public CompletableFuture<PutItemResponse> putItem(Consumer<PutItemRequest.Builder> putItemRequest) {
                final PutItemRequest.Builder builder = PutItemRequest.builder();
                putItemRequest.accept(builder);
                captured.add(builder.build().item());
                return Persistence.super.putItem(putItemRequest);
            }
        };
        final CredentialsDAO capturingDao = new CredentialsDAO(capturingPersistence, personDao);
        final long lastLogin = Instant.now().getEpochSecond();
        final Creds creds = new Creds("login@example.com", Person.Id.newInstance(), "user", "pass", lastLogin);
        assertTrue(get(capturingDao.saveCreds(creds)));
        assertEquals(captured.size(), 1);
        final Map<String, AttributeValue> saved = captured.get(0);
        assertEquals(saved.get("lastLogin").n(), "" + lastLogin);
    }

    @Test
    public void saveCredsWithNullLastLogin() {
        final List<Map<String, AttributeValue>> captured = new ArrayList<>();
        final Persistence capturingPersistence = new Persistence() {
            @Override
            public CompletableFuture<PutItemResponse> putItem(Consumer<PutItemRequest.Builder> putItemRequest) {
                final PutItemRequest.Builder builder = PutItemRequest.builder();
                putItemRequest.accept(builder);
                captured.add(builder.build().item());
                return Persistence.super.putItem(putItemRequest);
            }
        };
        final CredentialsDAO capturingDao = new CredentialsDAO(capturingPersistence, personDao);
        final Creds creds = new Creds("nologin@example.com", Person.Id.newInstance(), "user", "pass", null);
        assertTrue(get(capturingDao.saveCreds(creds)));
        assertEquals(captured.size(), 1);
        final Map<String, AttributeValue> saved = captured.get(0);
        assertFalse(saved.containsKey("lastLogin"), "Null lastLogin should not be stored");
    }

    @Test
    public void updateLastLoginSetsTimestamp() {
        final Creds creds = new Creds("update@test.com", Person.Id.newInstance(), "user", "pass", null);
        final Long prev = dao.updateLastLogin(creds);
        assertNull(prev);
        assertNotNull(creds.getLastLogin());
        assertTrue(creds.getLastLogin() > 0);
    }

    @Test
    public void updateLastLoginReturnsPreviousValue() {
        final long originalLogin = Instant.now().getEpochSecond() - 3600;
        final Creds creds = new Creds("prev@test.com", Person.Id.newInstance(), "user", "pass", originalLogin);
        final Long prev = dao.updateLastLogin(creds);
        assertEquals(prev, Long.valueOf(originalLogin));
        assertTrue(creds.getLastLogin() > originalLogin);
    }

    @Test
    public void updateLastLoginWithNullCredsReturnsNull() {
        assertNull(dao.updateLastLogin(null));
    }

    @Test
    public void updateLastLoginSkipsSaveIfRecentLogin() {
        final AtomicInteger putItemCount = new AtomicInteger(0);
        final Persistence countingPersistence = new Persistence() {
            @Override
            public CompletableFuture<PutItemResponse> putItem(Consumer<PutItemRequest.Builder> putItemRequest) {
                putItemCount.incrementAndGet();
                return Persistence.super.putItem(putItemRequest);
            }
        };
        final CredentialsDAO countingDao = new CredentialsDAO(countingPersistence, personDao);
        final long recentLogin = Instant.now().getEpochSecond();
        final Creds creds = new Creds("recent@test.com", Person.Id.newInstance(), "user", "pass", recentLogin);
        final Long prev = countingDao.updateLastLogin(creds);
        assertEquals(prev, Long.valueOf(recentLogin));
        assertEquals(putItemCount.get(), 0, "Should not save when last login was within 2 seconds");
    }

    @Test
    public void getCredsByEmailAdminOnlyReturnsNullForNullEmail() {
        assertNull(get(dao.getCredsByEmailAdminOnly(null, Person.Id.newInstance())));
    }

    @Test
    public void getCredsByEmailAdminOnlyReturnsNullForEmptyEmail() {
        assertNull(get(dao.getCredsByEmailAdminOnly("", Person.Id.newInstance())));
    }

    @Test
    public void createCredsReturnsEmptyWhenPersonNotFound() {
        assertTrue(dao.createCreds("nonexistent@test.com").isEmpty());
    }

    @Test
    public void createCredsSucceedsWhenPersonExists() throws Exception {
        final String email = RandomData.genAlpha(8) + "@test.com";
        final Person person = Person.builder().first("Cred").last("User").email(email).build();
        get(personDao.savePerson(person));
        final var result = dao.createCreds(email);
        assertTrue(result.isPresent());
        final Creds creds = result.get();
        assertEquals(creds.getEmail(), email.toLowerCase());
        assertEquals(creds.getUserId(), person.getId());
        assertEquals(creds.getPriv(), Creds.USER_PRIV);
    }

    @Test
    public void adminGetCredsByEmailReturnsNullForNullEmail() {
        // FacesContext is null in tests, so this should return null
        assertNull(get(dao.adminGetCredsByEmail(null)));
    }

    @Test
    public void adminGetCredsByEmailReturnsNullForEmptyEmail() {
        assertNull(get(dao.adminGetCredsByEmail("")));
    }

    @Test
    public void adminGetCredsByEmailReturnsNullWithoutFacesContext() {
        // FacesContext.getCurrentInstance() returns null in unit tests
        assertNull(get(dao.adminGetCredsByEmail("valid@test.com")));
    }

    private <T> T get(final CompletableFuture<T> future) {
        try {
            return future.get(1_000, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException | ExecutionException | TimeoutException ex) {
            throw new RuntimeException(ex);
        }
    }
}
