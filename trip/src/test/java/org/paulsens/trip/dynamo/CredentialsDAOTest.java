package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.security.PasswordHasher;
import org.paulsens.trip.security.Pepper;
import org.paulsens.trip.util.RandomData;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class CredentialsDAOTest {
    // A fixed test pepper: deterministic, and never reaches AWS Secrets Manager (unlike PasswordHasher.getInstance()).
    private static final PasswordHasher HASHER =
            new PasswordHasher(Pepper.of(1, "credentials-dao-test-pepper-key-123".getBytes(StandardCharsets.UTF_8)));

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
        dao = new CredentialsDAO(persistence, personDao, HASHER);
    }

    @Test
    public void getCredsByEmailAndPassReturnsNullForNullEmail() {
        assertNull(dao.getCredsByEmailAndPass(null, "pass"));
    }

    @Test
    public void getCredsByEmailAndPassReturnsNullForEmptyEmail() {
        assertNull(dao.getCredsByEmailAndPass("", "pass"));
    }

    @Test
    public void getCredsByEmailAndPassReturnsNullForNullPass() {
        assertNull(dao.getCredsByEmailAndPass("user@test.com", null));
    }

    @Test
    public void getCredsByEmailAndPassReturnsNullForEmptyPass() {
        assertNull(dao.getCredsByEmailAndPass("user@test.com", ""));
    }

    @Test
    public void getCredsByEmailAndPassReturnsNullForBothNull() {
        assertNull(dao.getCredsByEmailAndPass(null, null));
    }

    @Test
    public void saveAndRetrieveCreds() {
        final List<Map<String, AttributeValue>> captured = new ArrayList<>();
        final Persistence capturingPersistence = new Persistence() {
            @Override
            public PutItemResponse putItem(Consumer<PutItemRequest.Builder> putItemRequest) {
                final PutItemRequest.Builder builder = PutItemRequest.builder();
                putItemRequest.accept(builder);
                captured.add(builder.build().item());
                return Persistence.super.putItem(putItemRequest);
            }
        };
        final CredentialsDAO capturingDao = new CredentialsDAO(capturingPersistence, personDao, HASHER);
        final Person.Id userId = Person.Id.newInstance();
        final Creds creds = new Creds("test@example.com", userId, "user", "mypass", null);
        assertTrue(capturingDao.saveCreds(creds));
        assertEquals(captured.size(), 1);
        final Map<String, AttributeValue> saved = captured.get(0);
        assertEquals(saved.get("email").s(), "test@example.com");
        assertEquals(saved.get("userId").s(), userId.getValue());
        assertEquals(saved.get("priv").s(), "user");
        // The password must be stored hashed, never as the plaintext, and the hash must verify.
        assertNotEquals(saved.get("pass").s(), "mypass", "the plaintext password must not be persisted");
        assertTrue(HASHER.isHashed(saved.get("pass").s()));
        assertTrue(HASHER.verify("mypass", saved.get("pass").s()));
    }

    @Test
    public void saveCredsDoesNotRehashAnAlreadyHashedPassword() {
        final List<Map<String, AttributeValue>> captured = new ArrayList<>();
        final Persistence capturingPersistence = new Persistence() {
            @Override
            public PutItemResponse putItem(Consumer<PutItemRequest.Builder> putItemRequest) {
                final PutItemRequest.Builder builder = PutItemRequest.builder();
                putItemRequest.accept(builder);
                captured.add(builder.build().item());
                return Persistence.super.putItem(putItemRequest);
            }
        };
        final CredentialsDAO capturingDao = new CredentialsDAO(capturingPersistence, personDao, HASHER);
        // Re-saving a Creds whose password is already an envelope (e.g. after a lastLogin or email change) must
        // store it verbatim -- hashing it again would make the stored value unverifiable.
        final String alreadyHashed = HASHER.hash("secret");
        final Creds creds = new Creds("rehash@example.com", Person.Id.newInstance(), "user", alreadyHashed, null);
        assertTrue(capturingDao.saveCreds(creds));
        assertEquals(captured.get(0).get("pass").s(), alreadyHashed, "an existing hash must be stored as-is");
    }

    @Test
    public void saveCredsPreservesLastLogin() {
        final List<Map<String, AttributeValue>> captured = new ArrayList<>();
        final Persistence capturingPersistence = new Persistence() {
            @Override
            public PutItemResponse putItem(Consumer<PutItemRequest.Builder> putItemRequest) {
                final PutItemRequest.Builder builder = PutItemRequest.builder();
                putItemRequest.accept(builder);
                captured.add(builder.build().item());
                return Persistence.super.putItem(putItemRequest);
            }
        };
        final CredentialsDAO capturingDao = new CredentialsDAO(capturingPersistence, personDao, HASHER);
        final long lastLogin = Instant.now().getEpochSecond();
        final Creds creds = new Creds("login@example.com", Person.Id.newInstance(), "user", "pass", lastLogin);
        assertTrue(capturingDao.saveCreds(creds));
        assertEquals(captured.size(), 1);
        final Map<String, AttributeValue> saved = captured.get(0);
        assertEquals(saved.get("lastLogin").n(), "" + lastLogin);
    }

    @Test
    public void saveCredsWithNullLastLogin() {
        final List<Map<String, AttributeValue>> captured = new ArrayList<>();
        final Persistence capturingPersistence = new Persistence() {
            @Override
            public PutItemResponse putItem(Consumer<PutItemRequest.Builder> putItemRequest) {
                final PutItemRequest.Builder builder = PutItemRequest.builder();
                putItemRequest.accept(builder);
                captured.add(builder.build().item());
                return Persistence.super.putItem(putItemRequest);
            }
        };
        final CredentialsDAO capturingDao = new CredentialsDAO(capturingPersistence, personDao, HASHER);
        final Creds creds = new Creds("nologin@example.com", Person.Id.newInstance(), "user", "pass", null);
        assertTrue(capturingDao.saveCreds(creds));
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
            public PutItemResponse putItem(Consumer<PutItemRequest.Builder> putItemRequest) {
                putItemCount.incrementAndGet();
                return Persistence.super.putItem(putItemRequest);
            }
        };
        final CredentialsDAO countingDao = new CredentialsDAO(countingPersistence, personDao, HASHER);
        final long recentLogin = Instant.now().getEpochSecond();
        final Creds creds = new Creds("recent@test.com", Person.Id.newInstance(), "user", "pass", recentLogin);
        final Long prev = countingDao.updateLastLogin(creds);
        assertEquals(prev, Long.valueOf(recentLogin));
        assertEquals(putItemCount.get(), 0, "Should not save when last login was within 2 seconds");
    }

    @Test
    public void getCredsByEmailAdminOnlyReturnsNullForNullEmail() {
        assertNull(dao.getCredsByEmailAdminOnly(null, Person.Id.newInstance()));
    }

    @Test
    public void getCredsByEmailAdminOnlyReturnsNullForEmptyEmail() {
        assertNull(dao.getCredsByEmailAdminOnly("", Person.Id.newInstance()));
    }

    @Test
    public void createCredsReturnsEmptyWhenPersonNotFound() {
        assertTrue(dao.createCreds("nonexistent@test.com").isEmpty());
    }

    @Test
    public void createCredsSucceedsWhenPersonExists() throws Exception {
        final String email = RandomData.genAlpha(8) + "@test.com";
        final Person person = Person.builder().first("Cred").last("User").email(email).build();
        personDao.savePerson(person);
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
        assertNull(dao.adminGetCredsByEmail(null));
    }

    @Test
    public void adminGetCredsByEmailReturnsNullForEmptyEmail() {
        assertNull(dao.adminGetCredsByEmail(""));
    }

    @Test
    public void adminGetCredsByEmailReturnsNullWithoutFacesContext() {
        // FacesContext.getCurrentInstance() returns null in unit tests
        assertNull(dao.adminGetCredsByEmail("valid@test.com"));
    }

    @Test
    public void getCredsByEmailAndPassVerifiesAStoredHash() {
        final List<Map<String, AttributeValue>> puts = new ArrayList<>();
        final CredentialsDAO stubDao = daoOverStoredPass(HASHER.hash("secret"), puts);
        assertNotNull(stubDao.getCredsByEmailAndPass("who@test.com", "secret"), "correct password must pass");
        assertNull(stubDao.getCredsByEmailAndPass("who@test.com", "wrong"), "wrong password must fail");
        assertTrue(puts.isEmpty(), "verifying a current hash must not rewrite the row");
    }

    @Test
    public void getCredsByEmailAndPassUpgradesALegacyPlaintextRow() {
        final List<Map<String, AttributeValue>> puts = new ArrayList<>();
        final CredentialsDAO stubDao = daoOverStoredPass("legacyPlain", puts);
        assertNotNull(stubDao.getCredsByEmailAndPass("who@test.com", "legacyPlain"),
                "a legacy plaintext row must still authenticate");
        assertEquals(puts.size(), 1, "a correct login against plaintext must upgrade the row exactly once");
        final String rewritten = puts.get(0).get("pass").s();
        assertTrue(HASHER.isHashed(rewritten), "the upgraded row must be hashed");
        assertTrue(HASHER.verify("legacyPlain", rewritten), "and the hash must verify the same password");
    }

    @Test
    public void getCredsByEmailAndPassDoesNotUpgradeOnAFailedLogin() {
        final List<Map<String, AttributeValue>> puts = new ArrayList<>();
        final CredentialsDAO stubDao = daoOverStoredPass("legacyPlain", puts);
        assertNull(stubDao.getCredsByEmailAndPass("who@test.com", "wrong"));
        assertTrue(puts.isEmpty(), "a failed login must never rewrite the stored password");
    }

    @Test
    public void getCredsByEmailAndPassNoLongerAutoCreatesFromLastName() {
        // A person exists but has no credentials row. The old behavior auto-created creds using the last name as the
        // password; that vector is gone, so an unknown login now simply fails and writes nothing.
        final List<Map<String, AttributeValue>> puts = new ArrayList<>();
        final Persistence stub = new Persistence() {
            @Override
            public GetItemResponse getItem(Consumer<GetItemRequest.Builder> req) {
                return GetItemResponse.builder().build(); // hasItem() == false
            }
            @Override
            public PutItemResponse putItem(Consumer<PutItemRequest.Builder> req) {
                final PutItemRequest.Builder b = PutItemRequest.builder();
                req.accept(b);
                puts.add(b.build().item());
                return Persistence.super.putItem(req);
            }
        };
        final CredentialsDAO stubDao = new CredentialsDAO(stub, personDao, HASHER);
        assertNull(stubDao.getCredsByEmailAndPass("stranger@test.com", "Smith"),
                "a login for a person without credentials must fail rather than self-provision");
        assertTrue(puts.isEmpty(), "no credentials should be created on a failed login");
    }

    // Builds a DAO whose getItem always returns a pass-table row with the given stored password (hash or plaintext),
    // capturing any putItem writes so a test can assert whether (and how) the row was rewritten.
    private CredentialsDAO daoOverStoredPass(final String storedPass, final List<Map<String, AttributeValue>> puts) {
        final Map<String, AttributeValue> row = Map.of(
                CredentialsDAO.EMAIL, AttributeValue.builder().s("who@test.com").build(),
                CredentialsDAO.USER_ID, AttributeValue.builder().s(Person.Id.newInstance().getValue()).build(),
                CredentialsDAO.PRIV, AttributeValue.builder().s("user").build(),
                CredentialsDAO.PW, AttributeValue.builder().s(storedPass).build());
        final Persistence stub = new Persistence() {
            @Override
            public GetItemResponse getItem(Consumer<GetItemRequest.Builder> req) {
                return GetItemResponse.builder().item(row).build();
            }
            @Override
            public PutItemResponse putItem(Consumer<PutItemRequest.Builder> req) {
                final PutItemRequest.Builder b = PutItemRequest.builder();
                req.accept(b);
                puts.add(b.build().item());
                return Persistence.super.putItem(req);
            }
        };
        return new CredentialsDAO(stub, personDao, HASHER);
    }
    }

