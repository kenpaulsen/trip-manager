package org.paulsens.trip.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.interfaces.ECPrivateKey;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.LocalMode;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * The push credentials: ONE Secrets Manager secret ({@code trip/push}) holding the APNs signing key and the
 * VAPID key pair, shaped
 * {@code {"apns":{"keyId","teamId","key":"<PEM>"},"vapid":{"publicKey","privateKey","subject"}}}.
 *
 * <p>Resolution follows {@code ProcessorSecrets}: sysprop {@value #SECRET_PROP} / env {@value #SECRET_ENV}
 * names the secret; local mode uses none (no AWS from tests or laptops, ever) unless the sysprop-only dev
 * opt-in {@value #FILE_PROP} points at a file with the same JSON, for exercising real APNs sandbox / Safari
 * pushes from a laptop. Reads are cached {@value #CACHE_TTL_MILLIS}ms so a VAPID rotation needs no restart.
 * Read-only: the secret is written by {@code medjugorje/scripts/push-secret.sh}, never from the app.
 */
@Slf4j
public final class PushSecrets {
    static final String SECRET_PROP = "trip.push.secret";
    static final String SECRET_ENV = "TRIP_PUSH_SECRET";
    static final String FILE_PROP = "trip.push.secretFile";
    static final long CACHE_TTL_MILLIS = 60_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicReference<PushSecrets> INSTANCE = new AtomicReference<>();

    /** The APNs token-auth key: {@code kid}, {@code iss}, and the ES256 signer. */
    public record ApnsKey(String keyId, String teamId, ECPrivateKey key) {
    }

    /** The VAPID pair: the public key browsers subscribe with (base64url point) and the ES256 signer. */
    public record VapidKey(String publicKey, String privateKey, String subject, ECPrivateKey key) {
    }

    /** What the secret held; either half may be absent (an APNs-only or a web-only deployment). */
    record Loaded(ApnsKey apns, VapidKey vapid) {
        static final Loaded NONE = new Loaded(null, null);
    }

    private final Store store;
    private final VapidKey ephemeral;
    private final AtomicLong loadedAt = new AtomicLong();
    private volatile Loaded loaded = Loaded.NONE;

    public static PushSecrets getInstance() {
        return INSTANCE.updateAndGet(
                existing -> (existing != null) ? existing : create(LocalMode.isLocal(), prop(), fileProp()));
    }

    /** Package-private so the resolution ladder is testable (LocalMode is fixed for a test JVM). */
    static PushSecrets create(final boolean local, final String secretId, final String secretFile) {
        if (secretFile != null) {
            log.warn("Push secrets come from the file {} ({}): real keys on this machine", secretFile, FILE_PROP);
            return new PushSecrets(new FileStore(Path.of(secretFile)));
        }
        if (local) {
            // A throwaway VAPID pair, minted once per JVM: the profile page's "This browser" flow needs a
            // real key to call subscribe() with, and the browser tests run against the local container.
            // Every SEND still lands in the LoggingPushGateway (PushRuntime checks isEphemeral), so a
            // browser that subscribes locally never receives anything through a real push service.
            return new PushSecrets(null, ephemeralVapid());
        }
        if (secretId == null) {
            log.warn("No push secret configured ({} / {} unset); push notifications cannot be sent until "
                    + "it is set.", SECRET_PROP, SECRET_ENV);
            return new PushSecrets(null);
        }
        return new PushSecrets(new SecretsManagerStore(secretId, null));
    }

    /** Test seam: hand in a fake {@link Store} (or null for "nothing configured"). */
    PushSecrets(final Store store) {
        this(store, null);
    }

    private PushSecrets(final Store store, final VapidKey ephemeral) {
        this.store = store;
        this.ephemeral = ephemeral;
        if (ephemeral != null) {
            loaded = new Loaded(null, ephemeral);
        }
    }

    /** Whether the VAPID pair was minted in this JVM rather than read from a secret (local mode). */
    public boolean isEphemeral() {
        return ephemeral != null;
    }

    /** A fresh P-256 pair in VAPID's wire shapes. Package-private so tests can mint subscriptions too. */
    static VapidKey ephemeralVapid() {
        final java.security.KeyPair pair = EcKeys.generate();
        final ECPrivateKey key = (ECPrivateKey) pair.getPrivate();
        final byte[] scalar = new byte[32];
        final byte[] raw = key.getS().toByteArray();
        final int start = Math.max(0, raw.length - scalar.length);
        System.arraycopy(raw, start, scalar, scalar.length - (raw.length - start), raw.length - start);
        return new VapidKey(EcKeys.encodeUrl(EcKeys.pointOf((java.security.interfaces.ECPublicKey) pair.getPublic())),
                EcKeys.encodeUrl(scalar), "mailto:local@unitetrip.test", key);
    }

    public Optional<ApnsKey> apns() {
        refreshIfStale();
        return Optional.ofNullable(loaded.apns());
    }

    public Optional<VapidKey> vapid() {
        refreshIfStale();
        return Optional.ofNullable(loaded.vapid());
    }

    /** Whether either transport has a key. */
    public boolean isConfigured() {
        return apns().isPresent() || vapid().isPresent();
    }

    private void refreshIfStale() {
        if (store == null) {
            return;
        }
        final long last = loadedAt.get();
        final long now = System.currentTimeMillis();
        if (now - last < CACHE_TTL_MILLIS || !loadedAt.compareAndSet(last, now)) {
            return;
        }
        try {
            loaded = parse(store.read());
        } catch (final RuntimeException ex) {
            // Keep serving the last-known keys: a transient Secrets Manager blip must not fail a push that
            // yesterday's keys would have served.
            log.error("Unable to refresh push secrets; serving cached values", ex);
        }
    }

    /**
     * Parses the secret JSON. A half that is missing or malformed is logged and left absent rather than
     * failing the other half: a bad VAPID entry must not take APNs down with it.
     */
    static Loaded parse(final String json) {
        if (json == null || json.isBlank()) {
            return Loaded.NONE;
        }
        final JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (final IOException ex) {
            throw new IllegalStateException("Push secret is not JSON", ex);
        }
        return new Loaded(apnsOf(root.path("apns")), vapidOf(root.path("vapid")));
    }

    private static ApnsKey apnsOf(final JsonNode node) {
        final String keyId = text(node, "keyId");
        final String teamId = text(node, "teamId");
        final String pem = text(node, "key");
        if (keyId == null || teamId == null || pem == null) {
            return null;
        }
        try {
            return new ApnsKey(keyId, teamId, EcKeys.privateKeyFromPem(pem));
        } catch (final GeneralSecurityException | IllegalArgumentException ex) {
            log.error("The APNs key in the push secret is not a PKCS#8 EC private key", ex);
            return null;
        }
    }

    private static VapidKey vapidOf(final JsonNode node) {
        final String publicKey = text(node, "publicKey");
        final String privateKey = text(node, "privateKey");
        final String subject = text(node, "subject");
        if (publicKey == null || privateKey == null || subject == null) {
            return null;
        }
        try {
            final byte[] point = EcKeys.decodeUrl(publicKey);
            if (point.length != EcKeys.POINT_LENGTH) {
                throw new GeneralSecurityException("VAPID public key must be a 65-byte uncompressed point");
            }
            return new VapidKey(publicKey.trim(), privateKey.trim(), subject.trim(),
                    EcKeys.privateKeyFromScalar(EcKeys.decodeUrl(privateKey)));
        } catch (final GeneralSecurityException | IllegalArgumentException ex) {
            log.error("The VAPID keys in the push secret are not a base64url P-256 pair", ex);
            return null;
        }
    }

    private static String text(final JsonNode node, final String field) {
        final JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String prop() {
        final String fromProp = System.getProperty(SECRET_PROP);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        final String fromEnv = System.getenv(SECRET_ENV);
        return (fromEnv == null || fromEnv.isBlank()) ? null : fromEnv.trim();
    }

    /** Sysprop only, like {@code trip.cache.local.useConfigured}: an env var is too easy to leave set. */
    private static String fileProp() {
        final String fromProp = System.getProperty(FILE_PROP);
        return (fromProp == null || fromProp.isBlank()) ? null : fromProp.trim();
    }

    /** Where the JSON comes from: Secrets Manager in AWS, a file on a developer laptop, a fake in tests. */
    interface Store {
        String read();
    }

    static final class FileStore implements Store {
        private final Path path;

        FileStore(final Path path) {
            this.path = path;
        }

        @Override
        public String read() {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (final IOException ex) {
                throw new IllegalStateException("Cannot read the push secret file " + path, ex);
            }
        }
    }

    static final class SecretsManagerStore implements Store {
        private final String secretId;
        /** Test seam; null means build a real per-call client (the Pepper pattern). */
        private final Supplier<SecretsManagerClient> clients;

        SecretsManagerStore(final String secretId, final Supplier<SecretsManagerClient> clients) {
            this.secretId = secretId;
            this.clients = clients;
        }

        @Override
        public String read() {
            try (SecretsManagerClient client = client()) {
                return client.getSecretValue(GetSecretValueRequest.builder().secretId(secretId).build())
                        .secretString();
            }
        }

        // Per-call client like Pepper's and ProcessorSecrets': reads are cached a minute. Package-private
        // (with resolveRegion) so the no-network construction path is testable.
        SecretsManagerClient client() {
            if (clients != null) {
                return clients.get();
            }
            return SecretsManagerClient.builder()
                    .region(resolveRegion())
                    .credentialsProvider(DefaultCredentialsProvider.builder().build())
                    .build();
        }

        static Region resolveRegion() {
            final String dynamoRegion = System.getProperty("trip.dynamo.region",
                    System.getenv().getOrDefault("TRIP_DYNAMO_REGION", ""));
            return dynamoRegion.isBlank() ? Region.US_WEST_2 : Region.of(dynamoRegion);
        }
    }
}
