package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.PasskeyCredential;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.paulsens.trip.cache.Cached;

/**
 * {@link PasskeyResource}'s edge behaviour: auth gating, CSRF on writes, the feature switch, and the
 * owner-checked delete. The ceremonies themselves are {@code PasskeyServiceTest}'s job -- these tests reach
 * the real service (feature OFF by default in config), so most paths here are the refusals.
 */
public class PasskeyResourceTest extends ResourceTestSupport {

    private static final Person.Id ME = Person.Id.from("passkey-me");

    private PasskeyResource resource;

    @BeforeMethod
    public void freshResource() {
        resource = resource(new PasskeyResource());
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

    @Test
    public void statusReportsDisabledWithZeroKeys() {
        signedInAs(ME);
        final Response response = resource.status();
        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        // The suite runs with the setting at its default (false): the one state we can rely on here.
        Assert.assertEquals(body.get("enabled"), false);
        Assert.assertEquals(body.get("count"), 0);
    }

    @Test
    public void mineAnswersAnEmptyListWhileDisabled() {
        signedInAs(ME);
        final Response response = resource.mine();
        assertOk(response);
        Assert.assertEquals(response.getEntity(), List.of());
    }

    @Test
    public void writesRequireTheCsrfHeader() {
        signedInAs(ME);
        assertError(resource.registerStart(null), 403, ApiErrors.CSRF);
        assertError(resource.registerFinish(null, Map.of()), 403, ApiErrors.CSRF);
        assertError(resource.delete(null, "whatever"), 403, ApiErrors.CSRF);
    }

    @Test
    public void registrationIsRefusedWhileTheFeatureIsOff() {
        signedInAs(ME);
        assertError(resource.registerStart(CSRF_OK), 404, ApiErrors.NOT_FOUND);
        assertError(resource.registerFinish(CSRF_OK, Map.of("credential", "{}")), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void loginEndpointsAreRefusedWhileTheFeatureIsOff() {
        anonymous();
        assertError(resource.loginStart(), 404, ApiErrors.NOT_FOUND);
        // loginFinish fails on the (long-gone) challenge before it ever needs the setting; the message must
        // be the generic one either way.
        assertError(resource.loginFinish(Map.of("challengeToken", "x", "credential", "{}")),
                401, ApiErrors.NOT_AUTHENTICATED);
        assertError(resource.loginFinish(Map.of()), 400, ApiErrors.BAD_REQUEST);
    }

    /** The whole lifecycle through the resource with the feature ON: register, list, sign in, delete. */
    @Test
    public void theFullLifecycleWorksWhenEnabled() throws Exception {
        final org.paulsens.trip.action.ConfigCommands on =
                org.mockito.Mockito.mock(org.paulsens.trip.action.ConfigCommands.class);
        org.mockito.Mockito.when(on.getBoolean(org.paulsens.trip.config.KnownSettings.LOGIN_PASSKEY_ENABLED))
                .thenReturn(true);
        final org.paulsens.trip.security.PasskeyService enabled = new org.paulsens.trip.security.PasskeyService(
                new org.paulsens.trip.cache.InMemoryCacheClient(), on);
        final PasskeyResource live = resource(new PasskeyResource(enabled));
        // A REAL seeded person: sign-in cross-checks the key's owner id against the pass row its email
        // resolves to, so the two must agree here as they do in production.
        final Person me = newUser("Lifecycle");
        signedInAs(me.getId());
        session("loginEmail", me.getEmail());
        org.mockito.Mockito.when(request.getSession()).thenReturn(session);
        org.mockito.Mockito.when(request.getSession(true)).thenReturn(session);
        org.mockito.Mockito.when(session.getId()).thenReturn("resource-test-session");
        org.mockito.Mockito.when(request.getServerName()).thenReturn("localhost");
        org.mockito.Mockito.when(request.isSecure()).thenReturn(false);

        // Register.
        final Response startResponse = live.registerStart(CSRF_OK);
        assertOk(startResponse);
        @SuppressWarnings("unchecked")
        final String options = (String) ((Map<String, Object>) startResponse.getEntity()).get("publicKey");
        final String challenge = new com.fasterxml.jackson.databind.ObjectMapper().readTree(options)
                .path("publicKey").path("challenge").asText();
        final org.paulsens.trip.security.WebAuthnTestFixture authenticator =
                org.paulsens.trip.security.WebAuthnTestFixture.create();
        final Response finishResponse = live.registerFinish(CSRF_OK, Map.of(
                "credential", authenticator.registrationResponseJson(challenge, "http://localhost", "localhost"),
                "label", "Resource test"));
        assertOk(finishResponse);

        // It shows up in status and mine.
        @SuppressWarnings("unchecked")
        final Map<String, Object> status = (Map<String, Object>) live.status().getEntity();
        Assert.assertEquals(status.get("enabled"), true);
        Assert.assertEquals(status.get("count"), 1);
        @SuppressWarnings("unchecked")
        final List<Map<String, Object>> mine = (List<Map<String, Object>>) live.mine().getEntity();
        Assert.assertEquals(mine.size(), 1);
        Assert.assertEquals(mine.get(0).get("label"), "Resource test");

        // Sign in with it (sessionless endpoints).
        final Response loginStart = live.loginStart();
        assertOk(loginStart);
        @SuppressWarnings("unchecked")
        final Map<String, Object> startBody = (Map<String, Object>) loginStart.getEntity();
        final String assertChallenge = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree((String) startBody.get("publicKey")).path("publicKey").path("challenge").asText();
        final Response loginFinish = live.loginFinish(Map.of(
                "challengeToken", startBody.get("challengeToken"),
                "credential", authenticator.assertionResponseJson(assertChallenge, "http://localhost",
                        "localhost", 1, me.getId().getValue()
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertOk(loginFinish);
        @SuppressWarnings("unchecked")
        final Map<String, Object> identity = (Map<String, Object>) loginFinish.getEntity();
        Assert.assertEquals(identity.get("role"), "user");
        Assert.assertEquals(identity.get("userId"), me.getId().getValue());
        Assert.assertEquals(identity.get("csrfHeader"), BaseResource.CSRF_HEADER);

        // And its owner can remove it.
        assertOk(live.delete(CSRF_OK, authenticator.credentialIdBase64Url()));
        Assert.assertTrue(DAO.getInstance().getPasskey(authenticator.credentialIdBase64Url(), Cached.NO).isEmpty());
    }

    @Test
    public void deleteIsOwnerCheckedAndSilentAboutOtherPeoplesKeys() {
        signedInAs(ME);
        final String someoneElsesKey = RandomData.genSecureToken(12);
        final long now = Instant.now().getEpochSecond();
        DAO.getInstance().savePasskey(new PasskeyCredential(someoneElsesKey, Person.Id.from("passkey-other"),
                "other@example.org", "handle", "cose", 0, null, "Their phone", "localhost", now, now));

        assertError(resource.delete(CSRF_OK, someoneElsesKey), 404, ApiErrors.NOT_FOUND);
        assertError(resource.delete(CSRF_OK, "never-existed"), 404, ApiErrors.NOT_FOUND);
        Assert.assertTrue(DAO.getInstance().getPasskey(someoneElsesKey, Cached.NO).isPresent(),
                "the other person's key must survive");
    }
}
