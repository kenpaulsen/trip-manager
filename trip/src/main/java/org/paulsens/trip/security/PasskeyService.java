package org.paulsens.trip.security;

import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AttestationConveyancePreference;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Creds;
import org.paulsens.trip.model.PasskeyCredential;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.util.RandomData;
import org.paulsens.trip.cache.Cached;

/**
 * WebAuthn (passkey) ceremonies over the Yubico library: registration binds a new keypair to the signed-in
 * person; assertion signs a challenge with a registered key and answers the {@link Creds} to establish a
 * session with. Challenges live in the shared cache for five minutes, single-use, deleted BEFORE verification
 * so nothing can be replayed.
 *
 * <p>The relying-party id is derived per request from the host, because this app serves several domains and
 * passkeys are domain-scoped by the browser. A key registered on one domain never appears on another --
 * that's WebAuthn, not a bug; the person uses their password or an email code there.
 *
 * <p>No attestation ({@code NONE}): this site has no allowlist of authenticator models and wants none --
 * attestation buys enterprise device policy at the price of metadata plumbing.
 */
@Slf4j
public class PasskeyService {

    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final String RP_NAME = "Visit Queen of Peace";
    private static final PasskeyService INSTANCE = new PasskeyService();

    private final CacheClient cacheClient;
    private final ConfigCommands config;

    public static PasskeyService getInstance() {
        return INSTANCE;
    }

    PasskeyService() {
        this(DAO.getInstance().getCacheClient(), new ConfigCommands());
    }

    /** Public so tests (also outside this package) can wire their own cache and config. */
    public PasskeyService(final CacheClient cacheClient, final ConfigCommands config) {
        this.cacheClient = cacheClient;
        this.config = config;
    }

    public boolean enabled() {
        return config.getBoolean(KnownSettings.LOGIN_PASSKEY_ENABLED);
    }

    /**
     * Starts a registration ceremony for the signed-in person and answers the browser-ready
     * {@code navigator.credentials.create} options JSON. Existing credentials become excludeCredentials
     * automatically (the library asks the repository), so the same authenticator cannot register twice.
     */
    public String startRegistration(final Person.Id userId, final String email, final String host,
            final boolean secure, final String sessionKey) throws Exception {
        final String lowEmail = email.toLowerCase(Locale.ROOT);
        final RelyingParty rp = relyingParty(host, secure);
        final PublicKeyCredentialCreationOptions options = rp.startRegistration(StartRegistrationOptions.builder()
                .user(UserIdentity.builder()
                        .name(lowEmail)
                        .displayName(lowEmail)
                        .id(userHandle(userId))
                        .build())
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        // PREFERRED, not REQUIRED: discoverable keys make the autofill login work, but an old
                        // security key that cannot store one may still register (it just won't autofill).
                        .residentKey(ResidentKeyRequirement.PREFERRED)
                        .userVerification(UserVerificationRequirement.PREFERRED)
                        .build())
                .build());
        cacheClient.putValue(CacheKeys.webauthnRegKey(hashedKey(sessionKey)), options.toJson(), CHALLENGE_TTL);
        return options.toCredentialsCreateJson();
    }

    /** Verifies the browser's registration response and stores the credential. Null on any failure. */
    public PasskeyCredential finishRegistration(final Person.Id userId, final String email, final String host,
            final boolean secure, final String sessionKey, final String credentialJson, final String label) {
        final String regKey = CacheKeys.webauthnRegKey(hashedKey(sessionKey));
        final String optionsJson = cacheClient.getValue(regKey).orElse(null);
        cacheClient.removeKey(regKey);      // single-use, gone BEFORE verification
        if (optionsJson == null) {
            return null;
        }
        try {
            final PublicKeyCredentialCreationOptions options =
                    PublicKeyCredentialCreationOptions.fromJson(optionsJson);
            final RegistrationResult result = relyingParty(host, secure).finishRegistration(
                    FinishRegistrationOptions.builder()
                            .request(options)
                            .response(com.yubico.webauthn.data.PublicKeyCredential
                                    .parseRegistrationResponseJson(credentialJson))
                            .build());
            final long now = Instant.now().getEpochSecond();
            final PasskeyCredential passkey = new PasskeyCredential(
                    result.getKeyId().getId().getBase64Url(),
                    userId,
                    email.toLowerCase(Locale.ROOT),
                    userHandle(userId).getBase64Url(),
                    result.getPublicKeyCose().getBase64Url(),
                    result.getSignatureCount(),
                    result.getKeyId().getTransports()
                            .map(PasskeyService::joinTransports)
                            .orElse(null),
                    label == null || label.isBlank() ? "Passkey" : label.trim(),
                    rpIdFor(host),
                    now,
                    now);
            return Boolean.TRUE.equals(DAO.getInstance().savePasskey(passkey)) ? passkey : null;
        } catch (final Exception ex) {
            // One generic failure for the page; the reason (bad origin, malformed response, replay) only
            // matters here.
            log.info("Passkey registration failed: {}", ex.toString());
            return null;
        }
    }

    /**
     * Starts a username-less assertion (the autofill flow: nobody has typed anything yet). Answers the
     * challenge token plus the {@code navigator.credentials.get} options JSON.
     */
    public StartedAssertion startAssertion(final String host, final boolean secure) throws Exception {
        final AssertionRequest request = relyingParty(host, secure).startAssertion(
                StartAssertionOptions.builder()
                        .userVerification(UserVerificationRequirement.PREFERRED)
                        .build());
        final String challengeToken = RandomData.genSecureToken(16);
        cacheClient.putValue(CacheKeys.webauthnAssertKey(challengeToken), request.toJson(), CHALLENGE_TTL);
        return new StartedAssertion(challengeToken, request.toCredentialsGetJson());
    }

    /**
     * Verifies an assertion and answers the {@link Creds} to sign in with -- the caller establishes the
     * session. Null on any failure, silently: at the login page, "why" is information for a guesser.
     */
    public Creds finishAssertion(final String host, final boolean secure, final String challengeToken,
            final String credentialJson) {
        final String assertKey = CacheKeys.webauthnAssertKey(challengeToken);
        final String requestJson = cacheClient.getValue(assertKey).orElse(null);
        cacheClient.removeKey(assertKey);   // single-use, gone BEFORE verification
        if (requestJson == null || !enabled()) {
            return null;
        }
        try {
            final AssertionResult result = relyingParty(host, secure).finishAssertion(
                    FinishAssertionOptions.builder()
                            .request(AssertionRequest.fromJson(requestJson))
                            .response(com.yubico.webauthn.data.PublicKeyCredential
                                    .parseAssertionResponseJson(credentialJson))
                            .build());
            if (!result.isSuccess()) {
                return null;
            }
            final String credentialId = result.getCredential().getCredentialId().getBase64Url();
            final PasskeyCredential passkey = DAO.getInstance().getPasskey(credentialId, Cached.NO).orElse(null);
            if (passkey == null) {
                return null;
            }
            // Sign-count regression would normally mean a cloned authenticator, but cloud-synced passkeys
            // report 0 forever, so it is a log line, not a lockout.
            if (result.getSignatureCount() < passkey.getSignCount()) {
                log.warn("Passkey {} signature counter went backwards ({} < {})", credentialId,
                        result.getSignatureCount(), passkey.getSignCount());
            }
            passkey.setSignCount(result.getSignatureCount());
            passkey.setLastUsed(Instant.now().getEpochSecond());
            DAO.getInstance().savePasskey(passkey);
            // Role and account state come from the pass table NOW; the key only proves who is holding it.
            return DAO.getInstance().getCredsForCodeLogin(passkey.getEmail(), Cached.NO);
        } catch (final Exception ex) {
            log.info("Passkey assertion failed: {}", ex.toString());
            return null;
        }
    }

    /**
     * The registrable domain for this request's host: {@code localhost} stays itself (the embedded harness
     * and local containers), anything else keeps its last two labels ({@code www.visitqueenofpeace.com} ->
     * {@code visitqueenofpeace.com}). Fine for this all-.com estate; a future .co.uk domain breaks the
     * heuristic and gets to fix it.
     */
    static String rpIdFor(final String host) {
        if (host == null || host.isEmpty() || "localhost".equalsIgnoreCase(host) || host.matches("[0-9.:]+")) {
            return "localhost";
        }
        final String[] labels = host.toLowerCase(Locale.ROOT).split("\\.");
        return labels.length <= 2 ? host.toLowerCase(Locale.ROOT)
                : labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    private RelyingParty relyingParty(final String host, final boolean secure) {
        final String rpId = rpIdFor(host);
        return RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder().id(rpId).name(RP_NAME).build())
                .credentialRepository(new DynamoCredentialRepository(rpId))
                // The exact request origin; allowOriginPort covers the embedded harness's random port.
                .origins(Set.of((secure ? "https://" : "http://") + host))
                .allowOriginPort(true)
                .attestationConveyancePreference(AttestationConveyancePreference.NONE)
                // Counter regression is logged in finishAssertion, not enforced: cloud-synced passkeys
                // (iCloud, Google) legitimately share one credential across devices, and the library's strict
                // check would lock exactly those people out.
                .validateSignatureCounter(false)
                .build();
    }

    private static String joinTransports(final Set<com.yubico.webauthn.data.AuthenticatorTransport> transports) {
        return transports.stream()
                .map(com.yubico.webauthn.data.AuthenticatorTransport::getId)
                .collect(Collectors.joining(","));
    }

    private static ByteArray userHandle(final Person.Id userId) {
        return new ByteArray(userId.getValue().getBytes(StandardCharsets.UTF_8));
    }

    /** Session ids are secrets; they become cache keys only in hashed form. */
    private static String hashedKey(final String sessionKey) {
        return Digests.sha256Base64(sessionKey == null ? "" : sessionKey);
    }

    /** The two halves the login page needs to run {@code navigator.credentials.get}. */
    public record StartedAssertion(String challengeToken, String publicKeyOptionsJson) {
    }

    /** {@link CredentialRepository} over the passkeys table, scoped (like the browser) to one rp id. */
    // Package-private (not private) so its lookups are testable directly; the library calls them by contract.
    record DynamoCredentialRepository(String rpId) implements CredentialRepository {

        @Override
        public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(final String username) {
            return DAO.getInstance().getPasskeysForEmailAndRp(username, rpId, Cached.NO).stream()
                    .map(DynamoCredentialRepository::descriptorOf)
                    .collect(Collectors.toSet());
        }

        @Override
        public Optional<ByteArray> getUserHandleForUsername(final String username) {
            return DAO.getInstance().getPasskeysForEmailAndRp(username, rpId, Cached.NO).stream()
                    .findFirst()
                    .map(passkey -> b64(passkey.getUserHandle()));
        }

        @Override
        public Optional<String> getUsernameForUserHandle(final ByteArray userHandle) {
            final Person.Id userId = Person.Id.from(new String(userHandle.getBytes(), StandardCharsets.UTF_8));
            return DAO.getInstance().getPasskeysForUser(userId, Cached.NO).stream()
                    .filter(passkey -> rpId.equals(passkey.getRpId()))
                    .findFirst()
                    .map(PasskeyCredential::getEmail);
        }

        @Override
        public Optional<RegisteredCredential> lookup(final ByteArray credentialId, final ByteArray userHandle) {
            return DAO.getInstance().getPasskey(credentialId.getBase64Url(), Cached.NO)
                    .filter(passkey -> rpId.equals(passkey.getRpId()))
                    .map(DynamoCredentialRepository::registeredOf);
        }

        @Override
        public Set<RegisteredCredential> lookupAll(final ByteArray credentialId) {
            return DAO.getInstance().getPasskey(credentialId.getBase64Url(), Cached.NO).stream()
                    .map(DynamoCredentialRepository::registeredOf)
                    .collect(Collectors.toSet());
        }

        /** Stored fields are values this class wrote; one that no longer parses is a corrupt row. */
        private static ByteArray b64(final String base64Url) {
            try {
                return ByteArray.fromBase64Url(base64Url);
            } catch (final com.yubico.webauthn.data.exception.Base64UrlException ex) {
                throw new IllegalStateException("Stored passkey field is not base64url", ex);
            }
        }

        private static PublicKeyCredentialDescriptor descriptorOf(final PasskeyCredential passkey) {
            return PublicKeyCredentialDescriptor.builder()
                    .id(b64(passkey.getCredentialId()))
                    .build();
        }

        private static RegisteredCredential registeredOf(final PasskeyCredential passkey) {
            return RegisteredCredential.builder()
                    .credentialId(b64(passkey.getCredentialId()))
                    .userHandle(b64(passkey.getUserHandle()))
                    .publicKeyCose(b64(passkey.getPublicKeyCose()))
                    .signatureCount(passkey.getSignCount())
                    .build();
        }
    }
}
