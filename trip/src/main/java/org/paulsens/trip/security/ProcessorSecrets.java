package org.paulsens.trip.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.LocalMode;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;

/**
 * Payment-processor API secrets: ONE Secrets Manager secret holding a JSON object keyed by
 * {@code PaymentProcessorConfig} id, each value a map of that processor's secret fields
 * (e.g. {@code {"<configId>": {"clientSecret": "...", "clientSecretSandbox": "..."}}}).
 *
 * <p>One secret rather than one per config: flat $0.40/mo, a single IAM grant, and org admins can paste
 * credentials through the UI (the task role gets Get + Put on exactly this ARN). Secrets stay OUT of
 * DynamoDB so table readers -- console browsing, PITR snapshots, ops scripts, future analytics grants --
 * never see credential material.
 *
 * <p>Resolution follows {@link Pepper}: sysprop {@value #SECRET_PROP} / env {@value #SECRET_ENV} names the
 * hand-created secret; local mode uses a process-local map (no AWS from tests or laptops, ever). Reads are
 * cached briefly ({@value #CACHE_TTL_MILLIS}ms) -- payment starts are rare and rotation must not need a
 * restart. Writes are read-merge-write; concurrent admin writes are last-write-wins, acceptable for a rare
 * hand-operated action (deliberately NOT guarded with a cache lock: {@code NoopCacheClient} grants every
 * lock, so a lock here would be theater in one deployment mode).
 *
 * <p>A write replaces the WHOLE secret, so it merges into what the store holds NOW, read for that write, and
 * is refused when that read fails. It used to merge into the cached map, which a failed first read (or a
 * hand-edit that broke the JSON) leaves EMPTY: one organization saving its keys then erased every other
 * organization's. The cache is one immutable map swapped in a single assignment, so a reader never sees the
 * half-filled map a clear-then-fill refresh exposed.
 */
@Slf4j
public final class ProcessorSecrets {
    static final String SECRET_PROP = "trip.payment.secret";
    static final String SECRET_ENV = "TRIP_PAYMENT_SECRET";
    static final long CACHE_TTL_MILLIS = 60_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicReference<ProcessorSecrets> INSTANCE = new AtomicReference<>();

    /** Local-mode (and test) storage; also the write-through cache in AWS mode. Immutable; swapped whole. */
    private volatile Map<String, Map<String, String>> current = Map.of();
    private final AtomicLong loadedAt = new AtomicLong();
    private final Store store;

    public static ProcessorSecrets getInstance() {
        return INSTANCE.updateAndGet(
                existing -> (existing != null) ? existing : create(LocalMode.isLocal(), prop()));
    }

    /** Package-private so the resolution ladder is testable (LocalMode is fixed for a test JVM). */
    static ProcessorSecrets create(final boolean local, final String secretId) {
        if (local) {
            return new ProcessorSecrets(null);
        }
        if (secretId == null) {
            log.error("No payment secret configured ({} / {} unset); processor credentials will be "
                    + "unavailable until it is set.", SECRET_PROP, SECRET_ENV);
            return new ProcessorSecrets(null);
        }
        return new ProcessorSecrets(new SecretsManagerStore(secretId, null));
    }

    /** Test seam: hand in a fake {@link Store} (or null for pure in-memory). */
    ProcessorSecrets(final Store store) {
        this.store = store;
    }

    /** The secret fields for this config id; empty map when none are stored. Never null. */
    public Map<String, String> get(final String configId) {
        refreshIfStale();
        final Map<String, String> found = current.get(configId);
        return (found == null) ? Map.of() : found;
    }

    public boolean hasSecret(final String configId, final String field) {
        final String value = get(configId).get(field);
        return value != null && !value.isBlank();
    }

    /**
     * Merges these fields into the config's secret map (blank values DELETE the field) and persists. Returns
     * false when the backing store could not be read first or rejected the write -- the caller must surface
     * that, silently losing a pasted credential is the worst outcome available here, short of losing everyone
     * else's.
     */
    public synchronized boolean put(final String configId, final Map<String, String> fields) {
        final Map<String, Map<String, String>> stored = (store == null) ? current : readStoreForWrite();
        if (stored == null) {
            return false;
        }
        final Map<String, String> merged = new HashMap<>(stored.getOrDefault(configId, Map.of()));
        for (final Map.Entry<String, String> field : fields.entrySet()) {
            if (field.getValue() == null || field.getValue().isBlank()) {
                merged.remove(field.getKey());
            } else {
                merged.put(field.getKey(), field.getValue().trim());
            }
        }
        final Map<String, Map<String, String>> snapshot = new HashMap<>(stored);
        if (merged.isEmpty()) {
            snapshot.remove(configId);
        } else {
            snapshot.put(configId, Map.copyOf(merged));
        }
        if (store != null && !writeStore(snapshot)) {
            return false;
        }
        current = Map.copyOf(snapshot);
        loadedAt.set(System.currentTimeMillis());
        return true;
    }

    /** What the store holds right now, or null (logged) when it cannot be read or is not the expected JSON. */
    private Map<String, Map<String, String>> readStoreForWrite() {
        try {
            return parse(store.read());
        } catch (final RuntimeException ex) {
            log.error("Payment secrets could not be read, so nothing was written: a write replaces the whole "
                    + "secret and would have erased every entry this read did not return", ex);
            return null;
        }
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
            current = parse(store.read());
        } catch (final RuntimeException ex) {
            // Keep serving the last-known values: a transient Secrets Manager blip must not fail a payment
            // that yesterday's credentials would have served.
            log.error("Unable to refresh payment secrets; serving cached values", ex);
        }
    }

    private boolean writeStore(final Map<String, Map<String, String>> snapshot) {
        try {
            store.write(MAPPER.writeValueAsString(snapshot));
            return true;
        } catch (final RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.error("Unable to write payment secrets", ex);
            return false;
        }
    }

    private static Map<String, Map<String, String>> parse(final String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            final Map<String, Map<String, String>> parsed =
                    MAPPER.readValue(json, new TypeReference<Map<String, Map<String, String>>>() { });
            final Map<String, Map<String, String>> frozen = new HashMap<>();
            parsed.forEach((configId, fields) -> frozen.put(configId, Map.copyOf(fields)));
            return Map.copyOf(frozen);
        } catch (final java.io.IOException | RuntimeException ex) {
            throw new IllegalStateException("Payment secret is not the expected JSON object shape", ex);
        }
    }

    private static String prop() {
        final String fromProp = System.getProperty(SECRET_PROP);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        final String fromEnv = System.getenv(SECRET_ENV);
        return (fromEnv == null || fromEnv.isBlank()) ? null : fromEnv.trim();
    }

    /** The persistence behind the map: Secrets Manager in AWS, absent in local mode, fake in tests. */
    interface Store {
        String read();
        void write(String json);
    }

    static final class SecretsManagerStore implements Store {
        private final String secretId;
        /** Test seam; null means build a real per-call client (the Pepper pattern). */
        private final java.util.function.Supplier<SecretsManagerClient> clients;

        SecretsManagerStore(final String secretId,
                final java.util.function.Supplier<SecretsManagerClient> clients) {
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

        @Override
        public void write(final String json) {
            try (SecretsManagerClient client = client()) {
                client.putSecretValue(
                        PutSecretValueRequest.builder().secretId(secretId).secretString(json).build());
            }
        }

        // Per-call client like Pepper's: reads are cached a minute and writes are rare admin actions.
        // Package-private (with resolveRegion) so the no-network construction path is testable.
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
