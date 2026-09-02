package org.paulsens.trip.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.PasskeyCredential;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.paulsens.trip.cache.Cached;

/**
 * Full WebAuthn ceremonies against {@link PasskeyService}, with {@link WebAuthnTestFixture} playing the
 * authenticator -- real P-256 keys, real signatures, so the Yubico verification actually runs. This is also
 * the round-trip proof for the options JSON that travels through the cache (where a Jackson version skew
 * would first show).
 */
public class PasskeyServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HOST = "localhost";
    private static final String ORIGIN = "http://localhost";

    private InMemoryCacheClient cache;
    private PasskeyService service;

    @BeforeMethod
    public void fresh() {
        cache = new InMemoryCacheClient();
        final ConfigCommands config = Mockito.mock(ConfigCommands.class);
        Mockito.when(config.getBoolean(KnownSettings.LOGIN_PASSKEY_ENABLED)).thenReturn(true);
        service = new PasskeyService(cache, config);
    }

    /**
     * A person of this test's own making, with a real id and an address whose {@code pass} row resolves back
     * to that same id (local mode answers for any "user"-prefixed address). Sign-in cross-checks the two -- a
     * key proves an id; the pass table is keyed by a mutable email -- so a test that invents an id unrelated
     * to its address is testing a shape production never has. Owning the fixture rather than borrowing a
     * seeded persona also keeps it independent of what the rest of the suite has done to the caches.
     */
    private static Person newUser(final String tag) {
        final Person person = new Person();
        person.setFirst(tag);
        person.setLast("Passkey");
        person.setEmail("user-" + tag.toLowerCase(java.util.Locale.ROOT) + "-" + System.nanoTime()
                + "@example.org");
        try {
            Assert.assertTrue(DAO.getInstance().savePerson(person));
            // savePerson writes the email index asynchronously and these flows resolve BY email; wait for the
            // mapping rather than racing it.
            final long deadline = System.currentTimeMillis() + 5_000;
            while (DAO.getInstance().getPersonByEmail(person.getEmail(), Cached.NO) == null) {
                Assert.assertTrue(System.currentTimeMillis() < deadline, "email mapping never appeared");
                Thread.sleep(20);
            }
        } catch (final java.io.IOException | InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
        return person;
    }

    private PasskeyCredential register(final WebAuthnTestFixture authenticator, final Person.Id userId,
            final String email, final String sessionKey) throws Exception {
        final String options = service.startRegistration(userId, email, HOST, false, sessionKey);
        final String challenge = MAPPER.readTree(options).path("publicKey").path("challenge").asText();
        Assert.assertFalse(challenge.isEmpty(), "the create() options must carry the challenge");
        return service.finishRegistration(userId, email, HOST, false, sessionKey,
                authenticator.registrationResponseJson(challenge, ORIGIN, HOST), "Test device");
    }

    @Test
    public void registerThenSignInRoundTrip() throws Exception {
        final WebAuthnTestFixture authenticator = WebAuthnTestFixture.create();
        final Person person = newUser("Round");
        final Person.Id userId = person.getId();

        final PasskeyCredential registered =
                register(authenticator, userId, person.getEmail(), "sess-round-trip");
        Assert.assertNotNull(registered, "registration should verify and store the credential");
        Assert.assertEquals(registered.getRpId(), "localhost");
        Assert.assertEquals(registered.getLabel(), "Test device");

        final PasskeyService.StartedAssertion started = service.startAssertion(HOST, false);
        final String challenge = MAPPER.readTree(started.publicKeyOptionsJson())
                .path("publicKey").path("challenge").asText();
        final Creds creds = service.finishAssertion(HOST, false, started.challengeToken(),
                authenticator.assertionResponseJson(challenge, ORIGIN, HOST, 1,
                        userId.getValue().getBytes(StandardCharsets.UTF_8)));

        Assert.assertNotNull(creds, "a valid assertion should answer the account's creds");
        Assert.assertEquals(creds.getEmail(), person.getEmail());
        Assert.assertEquals(creds.getUserId(), userId, "the key's owner, not whoever holds its address");
        // The stored row advanced its counter and lastUsed.
        final PasskeyCredential used = DAO.getInstance().getPasskey(registered.getCredentialId(),
                Cached.NO).orElseThrow();
        Assert.assertEquals(used.getSignCount(), 1L);
    }

    @Test
    public void aChallengeIsSingleUse() throws Exception {
        final WebAuthnTestFixture authenticator = WebAuthnTestFixture.create();
        final Person person = newUser("Single");
        final Person.Id userId = person.getId();
        Assert.assertNotNull(register(authenticator, userId, person.getEmail(), "sess-single-use"));

        final PasskeyService.StartedAssertion started = service.startAssertion(HOST, false);
        final String challenge = MAPPER.readTree(started.publicKeyOptionsJson())
                .path("publicKey").path("challenge").asText();
        final String response = authenticator.assertionResponseJson(challenge, ORIGIN, HOST, 1,
                userId.getValue().getBytes(StandardCharsets.UTF_8));

        Assert.assertNotNull(service.finishAssertion(HOST, false, started.challengeToken(), response));
        Assert.assertNull(service.finishAssertion(HOST, false, started.challengeToken(), response),
                "replaying the same challenge token must be refused");
    }

    @Test
    public void aRegistrationChallengeIsSingleUseToo() throws Exception {
        final WebAuthnTestFixture authenticator = WebAuthnTestFixture.create();
        final Person.Id userId = Person.Id.from("user4");
        final String options = service.startRegistration(userId, "user4", HOST, false, "sess-reg-once");
        final String challenge = MAPPER.readTree(options).path("publicKey").path("challenge").asText();
        final String response = authenticator.registrationResponseJson(challenge, ORIGIN, HOST);

        Assert.assertNotNull(service.finishRegistration(userId, "user4", HOST, false, "sess-reg-once",
                response, null));
        Assert.assertNull(service.finishRegistration(userId, "user4", HOST, false, "sess-reg-once",
                response, null), "the stored ceremony state must be consumed by the first finish");
    }

    @Test
    public void theWrongKeySignatureIsRefused() throws Exception {
        final WebAuthnTestFixture registeredKey = WebAuthnTestFixture.create();
        final WebAuthnTestFixture wrongKey = WebAuthnTestFixture.create();
        final Person.Id userId = Person.Id.from("user5");
        Assert.assertNotNull(register(registeredKey, userId, "user5", "sess-wrong-key"));

        final PasskeyService.StartedAssertion started = service.startAssertion(HOST, false);
        final String challenge = MAPPER.readTree(started.publicKeyOptionsJson())
                .path("publicKey").path("challenge").asText();
        // The response claims the registered credential's id but is signed by a DIFFERENT key: what a forged
        // login attempt looks like. (wrongKey has registeredKey's id spoofed via unknown-credential path --
        // its own id was never registered, so lookup fails; either way, refused.)
        Assert.assertNull(service.finishAssertion(HOST, false, started.challengeToken(),
                wrongKey.assertionResponseJson(challenge, ORIGIN, HOST, 1,
                        userId.getValue().getBytes(StandardCharsets.UTF_8))),
                "an assertion from an unregistered key must be refused");
    }

    @Test
    public void aWrongOriginIsRefused() throws Exception {
        final WebAuthnTestFixture authenticator = WebAuthnTestFixture.create();
        final Person.Id userId = Person.Id.from("user6");
        final String options = service.startRegistration(userId, "user6", HOST, false, "sess-origin");
        final String challenge = MAPPER.readTree(options).path("publicKey").path("challenge").asText();

        Assert.assertNull(service.finishRegistration(userId, "user6", HOST, false, "sess-origin",
                authenticator.registrationResponseJson(challenge, "https://evil.example.com", HOST), null),
                "a phishing origin must be refused -- this is the property passkeys exist for");
    }

    @Test
    public void disabledMeansAssertionsFail() throws Exception {
        final WebAuthnTestFixture authenticator = WebAuthnTestFixture.create();
        final Person.Id userId = Person.Id.from("user2");
        Assert.assertNotNull(register(authenticator, userId, "user2", "sess-disable"));
        final PasskeyService.StartedAssertion started = service.startAssertion(HOST, false);
        final String challenge = MAPPER.readTree(started.publicKeyOptionsJson())
                .path("publicKey").path("challenge").asText();

        final ConfigCommands off = Mockito.mock(ConfigCommands.class);
        Mockito.when(off.getBoolean(KnownSettings.LOGIN_PASSKEY_ENABLED)).thenReturn(false);
        final PasskeyService disabled = new PasskeyService(cache, off);

        Assert.assertNull(disabled.finishAssertion(HOST, false, started.challengeToken(),
                authenticator.assertionResponseJson(challenge, ORIGIN, HOST, 1,
                        userId.getValue().getBytes(StandardCharsets.UTF_8))),
                "the setting is the kill switch for signing in, whatever is registered");
    }

    /** A synced passkey reporting a LOWER counter must still sign in (logged, not locked). */
    @Test
    public void aCounterRegressionIsLoggedNotLockedOut() throws Exception {
        final WebAuthnTestFixture authenticator = WebAuthnTestFixture.create();
        final Person person = newUser("Counter");
        final Person.Id userId = person.getId();
        Assert.assertNotNull(register(authenticator, userId, person.getEmail(), "sess-counter"));

        PasskeyService.StartedAssertion started = service.startAssertion(HOST, false);
        String challenge = MAPPER.readTree(started.publicKeyOptionsJson())
                .path("publicKey").path("challenge").asText();
        Assert.assertNotNull(service.finishAssertion(HOST, false, started.challengeToken(),
                authenticator.assertionResponseJson(challenge, ORIGIN, HOST, 5,
                        userId.getValue().getBytes(StandardCharsets.UTF_8))));

        // Another device of the synced passkey reports a lower counter.
        started = service.startAssertion(HOST, false);
        challenge = MAPPER.readTree(started.publicKeyOptionsJson())
                .path("publicKey").path("challenge").asText();
        Assert.assertNotNull(service.finishAssertion(HOST, false, started.challengeToken(),
                authenticator.assertionResponseJson(challenge, ORIGIN, HOST, 2,
                        userId.getValue().getBytes(StandardCharsets.UTF_8))),
                "a counter regression must not lock out a cloud-synced passkey");
    }

    @Test
    public void theRepositoryAnswersHandlesAndRefusesCorruptRows() throws Exception {
        final WebAuthnTestFixture authenticator = WebAuthnTestFixture.create();
        final Person.Id userId = Person.Id.from("repo-user-" + System.nanoTime());
        final String email = "repo-" + System.nanoTime() + "@example.org";
        final String options = service.startRegistration(userId, email, HOST, false, "sess-repo");
        final String challenge = MAPPER.readTree(options).path("publicKey").path("challenge").asText();
        Assert.assertNotNull(service.finishRegistration(userId, email, HOST, false, "sess-repo",
                authenticator.registrationResponseJson(challenge, ORIGIN, HOST), null));

        final PasskeyService.DynamoCredentialRepository repository =
                new PasskeyService.DynamoCredentialRepository("localhost");
        Assert.assertTrue(repository.getUserHandleForUsername(email).isPresent());
        Assert.assertTrue(repository.getUserHandleForUsername("nobody-here@example.org").isEmpty());
        Assert.assertEquals(repository.getCredentialIdsForUsername(email).size(), 1);

        // A row whose stored userHandle is not base64url is corrupt: loudly refused, never limped past.
        final String corruptEmail = "corrupt-" + System.nanoTime() + "@example.org";
        DAO.getInstance().savePasskey(new PasskeyCredential("corrupt-id-" + System.nanoTime(),
                Person.Id.from("corrupt"), corruptEmail, "not!!valid@@base64", "cose", 0, null, "x",
                "localhost", 1L, 1L));
        Assert.assertThrows(IllegalStateException.class,
                () -> repository.getUserHandleForUsername(corruptEmail));
    }

    @Test
    public void rpIdDerivationCoversTheEstate() {
        Assert.assertEquals(PasskeyService.rpIdFor("localhost"), "localhost");
        Assert.assertEquals(PasskeyService.rpIdFor("127.0.0.1"), "localhost");
        Assert.assertEquals(PasskeyService.rpIdFor(null), "localhost");
        Assert.assertEquals(PasskeyService.rpIdFor("visitqueenofpeace.com"), "visitqueenofpeace.com");
        Assert.assertEquals(PasskeyService.rpIdFor("www.visitqueenofpeace.com"), "visitqueenofpeace.com");
        Assert.assertEquals(PasskeyService.rpIdFor("my.centermirmedjugorje.com"), "centermirmedjugorje.com");
        Assert.assertEquals(PasskeyService.rpIdFor("unitetrip.com"), "unitetrip.com");
        Assert.assertEquals(PasskeyService.rpIdFor("WWW.UniteTrip.COM"), "unitetrip.com");
    }

    /** The JSON in the DB row is what a later assertion verifies against -- pin the whole persisted shape. */
    @Test
    public void theStoredCredentialCarriesEverythingVerificationNeeds() throws Exception {
        final WebAuthnTestFixture authenticator = WebAuthnTestFixture.create();
        final Person.Id userId = Person.Id.from("user3");
        final PasskeyCredential stored = register(authenticator, userId, "user3", "sess-shape");

        Assert.assertNotNull(stored);
        Assert.assertEquals(stored.getCredentialId(), authenticator.credentialIdBase64Url());
        Assert.assertEquals(stored.getUserId(), userId);
        Assert.assertEquals(stored.getEmail(), "user3");
        Assert.assertEquals(stored.getTransports(), "internal");
        Assert.assertNotNull(stored.getPublicKeyCose());
        Assert.assertNotNull(stored.getCreated());
    }

    /**
     * The bug this cross-check exists for (production, 2026-09-02). A key registered while its owner used one
     * address kept that address on its row; the owner's login later moved to a new one, leaving the old row
     * behind; a second account then claimed the abandoned address. Resolving the account by the address the
     * key remembers handed an admin's passkey to the stranger who now holds it -- so the id on the row that
     * comes back must be the id the key proved, and here it is not.
     */
    @Test
    public void aKeyWhoseAddressNowBelongsToSomebodyElseIsRefused() throws Exception {
        final WebAuthnTestFixture authenticator = WebAuthnTestFixture.create();
        // The key's owner has no person row of their own -- so the lookup can only fall back to the address
        // the key remembers, which resolves to a DIFFERENT account. That is the takeover, exactly.
        final Person.Id ghost = Person.Id.from("ghost-" + System.nanoTime());
        final String strangersAddress = newUser("Stranger").getEmail();
        Assert.assertNotNull(register(authenticator, ghost, strangersAddress, "sess-stolen"));

        final PasskeyService.StartedAssertion started = service.startAssertion(HOST, false);
        final String challenge = MAPPER.readTree(started.publicKeyOptionsJson())
                .path("publicKey").path("challenge").asText();
        Assert.assertNull(service.finishAssertion(HOST, false, started.challengeToken(),
                authenticator.assertionResponseJson(challenge, ORIGIN, HOST, 1,
                        ghost.getValue().getBytes(StandardCharsets.UTF_8))),
                "a key must never sign into an account it does not name, whoever holds its old address");
    }

    /**
     * The other half of the same rule: a row whose address is merely STALE -- the owner is still the owner,
     * they just changed their email -- keeps working, and the row heals on the way through. Failing closed
     * here would mean an admin fixing a typo silently killed that person's passkeys.
     */
    @Test
    public void aStaleAddressStillSignsInAndTheRowHeals() throws Exception {
        final WebAuthnTestFixture authenticator = WebAuthnTestFixture.create();
        final Person person = newUser("Stale");
        // Registered under an address that is no longer this person's: what an email change leaves behind.
        final String oldAddress = "old-" + System.nanoTime() + "@example.org";
        final PasskeyCredential registered = register(authenticator, person.getId(), oldAddress, "sess-stale");
        Assert.assertNotNull(registered);

        final PasskeyService.StartedAssertion started = service.startAssertion(HOST, false);
        final String challenge = MAPPER.readTree(started.publicKeyOptionsJson())
                .path("publicKey").path("challenge").asText();
        final Creds creds = service.finishAssertion(HOST, false, started.challengeToken(),
                authenticator.assertionResponseJson(challenge, ORIGIN, HOST, 1,
                        person.getId().getValue().getBytes(StandardCharsets.UTF_8)));

        Assert.assertNotNull(creds, "the owner is still the owner; only their address moved");
        Assert.assertEquals(creds.getUserId(), person.getId());
        Assert.assertEquals(DAO.getInstance().getPasskey(registered.getCredentialId(), Cached.NO)
                .orElseThrow().getEmail(), person.getEmail(), "the stale row should heal on first use");
    }
}
