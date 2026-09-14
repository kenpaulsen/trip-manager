package org.paulsens.trip.push;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import lombok.Value;

/**
 * One registered push target inside a person's {@link PushPrefs} row: an APNs device token or a Web Push
 * subscription. One class rather than two because the row stores a list of them and the sender iterates it;
 * {@link #getKind()} says which fields are meaningful.
 *
 * <p>Immutable and Jackson-shaped, like every stored model. The listing {@link #id()} is what leaves the
 * server -- a token or an endpoint is a credential to push to that device and is never echoed back.
 */
@Value
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PushDevice implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** Which transport this device is reached through. Wire spelling is lower case ({@code ios} / {@code web}). */
    public enum Kind {
        IOS("ios"),
        WEB("web");

        private final String wire;

        Kind(final String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }

        /** Null for anything unrecognised; a row written by hand must not blow up the whole listing. */
        @JsonCreator
        public static Kind fromWire(final String value) {
            if (value == null) {
                return null;
            }
            for (final Kind kind : values()) {
                if (kind.wire.equalsIgnoreCase(value.trim())) {
                    return kind;
                }
            }
            return null;
        }
    }

    public static final String ENV_PRODUCTION = "production";
    public static final String ENV_SANDBOX = "sandbox";

    Kind kind;
    /** iOS: the APNs device token, lower-case hex. */
    String token;
    /** iOS: {@code production} or {@code sandbox} -- picks the APNs host. */
    String environment;
    /** iOS: the refresh-token selector of the sign-in that registered it, so revoking that sign-in prunes it. */
    String selector;
    String label;
    /** iOS: the app build that registered, for support questions. */
    String appVersion;
    /** Web: the push service endpoint URL. */
    String endpoint;
    /** Web: the subscription's P-256 public key, base64url. */
    String p256dh;
    /** Web: the subscription's auth secret, base64url. */
    String auth;
    /** Web: the site the browser subscribed on ({@code https://acme.unitetrip.com}). */
    String origin;
    /** Epoch seconds. */
    long registeredAt;
    /** Epoch seconds; the eviction order when the row is full. */
    long lastSeenAt;

    @JsonCreator
    public PushDevice(
            @JsonProperty("kind") final Kind kind,
            @JsonProperty("token") final String token,
            @JsonProperty("environment") final String environment,
            @JsonProperty("selector") final String selector,
            @JsonProperty("label") final String label,
            @JsonProperty("appVersion") final String appVersion,
            @JsonProperty("endpoint") final String endpoint,
            @JsonProperty("p256dh") final String p256dh,
            @JsonProperty("auth") final String auth,
            @JsonProperty("origin") final String origin,
            @JsonProperty("registeredAt") final Long registeredAt,
            @JsonProperty("lastSeenAt") final Long lastSeenAt) {
        this.kind = kind;
        this.token = token == null ? null : token.toLowerCase(Locale.ROOT);
        this.environment = environment;
        this.selector = selector;
        this.label = label;
        this.appVersion = appVersion;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.origin = origin;
        this.registeredAt = registeredAt == null ? 0L : registeredAt;
        this.lastSeenAt = lastSeenAt == null ? this.registeredAt : lastSeenAt;
    }

    public static PushDevice ios(final String token, final String environment, final String selector,
            final String label, final String appVersion, final long now) {
        return new PushDevice(Kind.IOS, token, environment, selector, label, appVersion, null, null, null, null,
                now, now);
    }

    public static PushDevice web(final String endpoint, final String p256dh, final String auth, final String label,
            final String origin, final long now) {
        return new PushDevice(Kind.WEB, null, null, null, label, null, endpoint, p256dh, auth, origin, now, now);
    }

    /**
     * The listing id: the last 8 hex characters of an iOS token, the first 16 hex characters of the SHA-256
     * of a web endpoint. Enough to tell devices apart in a list and to name one for removal; not enough to
     * push to it.
     */
    @JsonIgnore
    public String id() {
        if (kind == Kind.IOS) {
            return token == null ? "" : token.substring(Math.max(0, token.length() - 8));
        }
        return endpoint == null ? "" : endpointId(endpoint);
    }

    /** The listing id a web endpoint maps to, so an unsubscribe by endpoint and a remove by id agree. */
    public static String endpointId(final String endpoint) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(endpoint.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", ex);
        }
    }

    /** True when this entry is a re-registration of the same physical target as {@code other}. */
    @JsonIgnore
    public boolean sameTargetAs(final PushDevice other) {
        if (other == null || other.kind != kind) {
            return false;
        }
        return kind == Kind.IOS ? token != null && token.equals(other.token)
                : endpoint != null && endpoint.equals(other.endpoint);
    }

    /** Whether {@code idOrToken} names this device: its listing id, or (iOS) its full token. */
    @JsonIgnore
    public boolean matches(final String idOrToken) {
        if (idOrToken == null || idOrToken.isBlank()) {
            return false;
        }
        final String wanted = idOrToken.trim().toLowerCase(Locale.ROOT);
        return wanted.equals(id()) || (kind == Kind.IOS && wanted.equals(token));
    }

    @JsonIgnore
    public boolean isSandbox() {
        return ENV_SANDBOX.equals(environment);
    }

    /** The same device seen again now, with the mutable details a re-registration may change. */
    public PushDevice refreshed(final PushDevice fresh, final long now) {
        return new PushDevice(kind, token, fresh.environment == null ? environment : fresh.environment,
                fresh.selector == null ? selector : fresh.selector, fresh.label == null ? label : fresh.label,
                fresh.appVersion == null ? appVersion : fresh.appVersion, endpoint,
                fresh.p256dh == null ? p256dh : fresh.p256dh, fresh.auth == null ? auth : fresh.auth,
                fresh.origin == null ? origin : fresh.origin, registeredAt, now);
    }

    public PushDevice seenAt(final long now) {
        return new PushDevice(kind, token, environment, selector, label, appVersion, endpoint, p256dh, auth,
                origin, registeredAt, now);
    }
}
