package org.paulsens.trip.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * The application-level secret ("pepper") mixed into every password before it is BCrypt-hashed.
 *
 * <p>A pepper is a secret key that lives outside the database. Unlike the per-hash salt BCrypt already applies (which
 * only stops precomputed-table attacks and is stored alongside the hash), the pepper is never written next to the
 * hash, so an attacker who steals only the {@code pass} table cannot even begin an offline crack without also
 * stealing this key. The password is combined with the pepper via {@code HMAC-SHA256(password, key)} <em>before</em>
 * BCrypt, which both binds the key in cryptographically and sidesteps BCrypt's 72-byte input truncation.</p>
 *
 * <p>Every pepper has an integer <b>version</b>, and each stored hash records the version it was made with (see
 * {@link PasswordHasher}). Version {@code 0} means "no pepper" -- the password is fed to BCrypt directly. This
 * versioning is what makes the pepper safe to <em>introduce</em> or <em>rotate</em> after hashes already exist:
 * verification looks up the key for the version baked into the hash, and {@link PasswordHasher#needsRehash} flags any
 * hash made under an older version so a successful login can transparently re-hash it under the current one.</p>
 *
 * <p>This class is a pure value object -- a current version plus a map of version to key bytes. It does no I/O except
 * in {@link #resolve()}, which reads configuration and, in production, fetches the key from AWS Secrets Manager. Unit
 * tests build peppers directly with {@link #none()} and {@link #of(int, byte[])} and never touch AWS.</p>
 */
@Slf4j
public final class Pepper {
    /** System property / environment variable naming the Secrets Manager secret (name or ARN) that holds the key. */
    static final String SECRET_PROP = "trip.password.pepper.secret";
    static final String SECRET_ENV = "TRIP_PASSWORD_PEPPER_SECRET";
    /** Override that supplies the key directly (base64), bypassing Secrets Manager -- for dev and tests. */
    static final String KEY_PROP = "trip.password.pepper.key";
    static final String KEY_ENV = "TRIP_PASSWORD_PEPPER_KEY";
    static final String VERSION_PROP = "trip.password.pepper.version";
    static final String VERSION_ENV = "TRIP_PASSWORD_PEPPER_VERSION";

    private static final int NO_PEPPER = 0;
    private static final String HMAC_ALG = "HmacSHA256";

    private final int currentVersion;
    private final Map<Integer, byte[]> keysByVersion;

    Pepper(final int currentVersion, final Map<Integer, byte[]> keysByVersion) {
        this.currentVersion = currentVersion;
        this.keysByVersion = keysByVersion;
    }

    /** A pepper that applies no key: every password is BCrypt-hashed directly, under version 0. */
    public static Pepper none() {
        return new Pepper(NO_PEPPER, new HashMap<>());
    }

    /** A single-version pepper with the given key -- the common production shape and the one tests use. */
    public static Pepper of(final int version, final byte[] key) {
        if (version <= NO_PEPPER) {
            throw new IllegalArgumentException("A keyed pepper must have version >= 1, got " + version);
        }
        final Map<Integer, byte[]> keys = new HashMap<>();
        keys.put(version, key.clone());
        return new Pepper(version, keys);
    }

    public int currentVersion() {
        return currentVersion;
    }

    /** True if this pepper can verify a hash stamped with the given version (version 0 needs no key). */
    public boolean supportsVersion(final int version) {
        return version == NO_PEPPER || keysByVersion.containsKey(version);
    }

    /**
     * Produces the bytes actually fed to BCrypt for the given pepper version.
     *
     * <p>The result is always the base64 of a 32-byte digest -- 44 ASCII chars, no NUL bytes, comfortably under
     * BCrypt's 72-byte limit -- so neither truncation nor the historical NUL-byte bug can ever bite, regardless of
     * how long or what encoding the user's password is. Version 0 (no pepper) digests with plain SHA-256; any keyed
     * version digests with {@code HMAC-SHA256(password, key)}, which is the actual peppering step.</p>
     */
    byte[] preHash(final String password, final int version) {
        final byte[] raw = password.getBytes(StandardCharsets.UTF_8);
        if (version == NO_PEPPER) {
            try {
                return Base64.getEncoder().encode(MessageDigest.getInstance("SHA-256").digest(raw));
            } catch (final java.security.NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 unavailable", ex); // unreachable on any JVM
            }
        }
        final byte[] key = keysByVersion.get(version);
        if (key == null) {
            throw new IllegalStateException("No pepper key configured for version " + version
                    + "; cannot hash or verify passwords stamped with it.");
        }
        try {
            final Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(key, HMAC_ALG));
            return Base64.getEncoder().encode(mac.doFinal(raw));
        } catch (final java.security.GeneralSecurityException ex) {
            // HmacSHA256 is guaranteed present on every JVM and the key spec is always valid, so this is unreachable.
            throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
        }
    }

    /**
     * Resolves the active pepper from configuration.
     *
     * <ol>
     *   <li>If {@code trip.password.pepper.key} / {@code TRIP_PASSWORD_PEPPER_KEY} is set (base64), use it directly
     *       at version {@code trip.password.pepper.version} (default 1). This is the no-AWS path for local dev and
     *       integration tests that still want a real pepper.</li>
     *   <li>Otherwise, if the secret name is configured, fetch it from Secrets Manager. A failure here throws --
     *       the deployment asked for a pepper, so silently shipping unpeppered hashes would be worse than not
     *       starting.</li>
     *   <li>Otherwise, no pepper (version 0). Correct for local/test; in production this logs an error so a
     *       forgotten configuration is loud rather than silent.</li>
     * </ol>
     */
    public static Pepper resolve() {
        final String directKey = prop(KEY_PROP, KEY_ENV);
        if (directKey != null) {
            final int version = parseVersion(prop(VERSION_PROP, VERSION_ENV), 1);
            return Pepper.of(version, decodeKey(directKey, KEY_PROP + " / " + KEY_ENV));
        }
        final String secretId = prop(SECRET_PROP, SECRET_ENV);
        if (secretId == null) {
            log.error("No password pepper configured ({} / {} unset). Passwords will be hashed with BCrypt but "
                    + "WITHOUT a pepper (version 0). Set the secret in production.", SECRET_PROP, SECRET_ENV);
            return none();
        }
        return fromSecretsManager(secretId);
    }

    private static Pepper fromSecretsManager(final String secretId) {
        try (SecretsManagerAsyncClient client = SecretsManagerAsyncClient.builder()
                .region(resolveRegion())
                // Default chain: finds the ECS task role / instance role in AWS, and ~/.aws [default] on a laptop.
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build()) {
            final String secret = client
                    .getSecretValue(GetSecretValueRequest.builder().secretId(secretId).build())
                    .join()
                    .secretString();
            final Pepper pepper = parseSecret(secret);
            log.info("Loaded password pepper (current version {}) from Secrets Manager secret '{}'.",
                    pepper.currentVersion, secretId);
            return pepper;
        } catch (final RuntimeException ex) {
            // Surface the specific reason (bad base64, access denied, not found, ...) in the top-level message so it
            // reads clearly in the log without hunting through the cause chain.
            throw new IllegalStateException("Failed to load the password pepper from Secrets Manager secret '"
                    + secretId + "': " + describe(ex) + " Refusing to start with password hashing misconfigured.",
                    ex);
        }
    }

    // The secret lives in the same account/region as that deployment's DynamoDB, so a dedicated pepper-region
    // override falls back to the shared trip.dynamo.region / TRIP_DYNAMO_REGION, then to us-west-2. One --region on
    // the rotation script therefore points both the pepper read and the table writes at the same place.
    private static Region resolveRegion() {
        final String pepperRegion = prop("trip.password.pepper.region", "TRIP_PASSWORD_PEPPER_REGION");
        if (pepperRegion != null) {
            return Region.of(pepperRegion);
        }
        final String dynamoRegion = prop("trip.dynamo.region", "TRIP_DYNAMO_REGION");
        return dynamoRegion == null ? Region.US_WEST_2 : Region.of(dynamoRegion);
    }

    // Decodes a base64 pepper key, turning the JDK's opaque "Illegal base64 character" into an actionable message.
    private static byte[] decodeKey(final String value, final String source) {
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (final IllegalArgumentException ex) {
            throw new IllegalStateException("the pepper key from " + source + " is not valid base64. This usually "
                    + "means the key was stored literally (e.g. an unexpanded \"$KEY\") instead of the output of "
                    + "`openssl rand -base64 32`.", ex);
        }
    }

    // The most useful one-line reason. Unwraps only the JDK future wrappers (which merely restate their cause), so
    // our own explanatory message -- or the SDK's -- is what surfaces, not the raw JDK exception underneath it.
    private static String describe(final Throwable t) {
        Throwable cur = t;
        while ((cur instanceof java.util.concurrent.CompletionException
                || cur instanceof java.util.concurrent.ExecutionException)
                && cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }

    /**
     * Parses a Secrets Manager secret value. Accepts either a JSON object
     * {@code {"version":1,"key":"<base64>","keys":{"1":"<base64>"}}} (where {@code keys} optionally carries earlier
     * versions still needed to verify not-yet-rehashed logins during a rotation), or a bare base64 string treated as
     * the version-1 key.
     */
    static Pepper parseSecret(final String secret) {
        final String trimmed = secret.trim();
        if (!trimmed.startsWith("{")) {
            return Pepper.of(1, decodeKey(trimmed, "the secret value"));
        }
        try {
            final JsonNode root = new ObjectMapper().readTree(trimmed);
            final int version = root.path("version").asInt(1);
            final Map<Integer, byte[]> keys = new HashMap<>();
            if (root.hasNonNull("key")) {
                keys.put(version, decodeKey(root.get("key").asText(), "the secret's \"key\" field"));
            }
            final JsonNode extra = root.get("keys");
            if (extra != null && extra.isObject()) {
                final Iterator<Map.Entry<String, JsonNode>> fields = extra.fields();
                while (fields.hasNext()) {
                    final Map.Entry<String, JsonNode> field = fields.next();
                    keys.put(Integer.parseInt(field.getKey()),
                            decodeKey(field.getValue().asText(), "the secret's \"keys\"[" + field.getKey() + "]"));
                }
            }
            if (keys.get(version) == null) {
                throw new IllegalStateException("Pepper secret has no key for its current version " + version);
            }
            return new Pepper(version, keys);
        } catch (final com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Pepper secret is neither base64 nor valid JSON", ex);
        }
    }

    private static int parseVersion(final String raw, final int fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (final NumberFormatException ex) {
            log.error("Invalid pepper version '{}'; using {}.", raw, fallback);
            return fallback;
        }
    }

    private static String prop(final String sysProp, final String envVar) {
        String value = System.getProperty(sysProp);
        if (value == null || value.isBlank()) {
            value = System.getenv(envVar);
        }
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
