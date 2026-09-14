package org.paulsens.trip.push;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.dynamo.LocalMode;

/**
 * Which {@link PushGateway} serves each device kind: the real client when the secret holds that half,
 * the recording {@link LoggingPushGateway} in local mode (the container's end-to-end recipe reads it), and a
 * FAILED-answering recorder in production with no secret (one WARN, then quiet).
 *
 * <p>Real clients are cached per key identity (APNs key id / VAPID public key), so a rotated secret picks up
 * a fresh signer within {@code PushSecrets}' 60 s without a restart.
 */
public final class PushRuntime {

    private static final Map<PushDevice.Kind, PushGateway> OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<String, PushGateway> REAL = new ConcurrentHashMap<>();
    private static final LoggingPushGateway LOCAL = new LoggingPushGateway();
    private static final LoggingPushGateway UNCONFIGURED = new LoggingPushGateway(PushOutcome.FAILED,
            "no push secret is configured for this device kind (TRIP_PUSH_SECRET / trip.push.secret)");
    private static volatile Supplier<PushSecrets> secrets = PushSecrets::getInstance;

    private PushRuntime() {
    }

    public static PushGateway gatewayFor(final PushDevice.Kind kind) {
        final PushGateway override = OVERRIDES.get(kind);
        if (override != null) {
            return override;
        }
        final PushSecrets loaded = secrets.get();
        if (loaded.isEphemeral()) {
            // Local mode's throwaway VAPID pair exists for subscribe(); sends must never leave the JVM.
            return LOCAL;
        }
        if (kind == PushDevice.Kind.IOS) {
            return loaded.apns().map(key -> REAL.computeIfAbsent("apns:" + key.keyId(),
                    ignored -> ApnsClient.create(key, new ConfigCommands()))).orElseGet(PushRuntime::fallback);
        }
        return loaded.vapid().map(key -> REAL.computeIfAbsent("vapid:" + key.publicKey(),
                ignored -> WebPushClient.create(key))).orElseGet(PushRuntime::fallback);
    }

    /** The recorder every local-mode send lands in, for the local recipe and tests. */
    public static LoggingPushGateway recorder() {
        return LOCAL;
    }

    /** Test seam: pins a gateway for a kind (null restores the resolved one). */
    public static void setGateway(final PushDevice.Kind kind, final PushGateway gateway) {
        if (gateway == null) {
            OVERRIDES.remove(kind);
        } else {
            OVERRIDES.put(kind, gateway);
        }
    }

    /** Test seam: where the keys come from. */
    static void setSecrets(final Supplier<PushSecrets> supplier) {
        secrets = supplier == null ? PushSecrets::getInstance : supplier;
        REAL.clear();
    }

    private static PushGateway fallback() {
        return LocalMode.isLocal() ? LOCAL : UNCONFIGURED;
    }
}
