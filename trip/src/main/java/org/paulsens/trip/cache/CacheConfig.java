package org.paulsens.trip.cache;

import java.util.Locale;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves shared-cache settings from system properties with environment-variable fallback, mirroring the
 * {@code trip.dynamo.region} / {@code TRIP_DYNAMO_REGION} pattern used by the DynamoDB layer:
 * <ul>
 *     <li>{@code trip.cache.mode} / {@code TRIP_CACHE_MODE}: {@code valkey}, {@code memory}, or {@code off}.
 *         Defaults to {@code valkey} when a URI is configured, otherwise {@code memory} (today's per-JVM
 *         semantics -- also the emergency rollback switch).</li>
 *     <li>{@code trip.valkey.uri} / {@code TRIP_VALKEY_URI}: e.g. {@code rediss://trip-cache-xxx.serverless
 *         .use1.cache.amazonaws.com:6379} (production) or {@code redis://localhost:6379} (local docker).</li>
 *     <li>{@code trip.valkey.protocol} / {@code TRIP_VALKEY_PROTOCOL}: {@code cluster} or {@code standalone}.
 *         Defaults to {@code cluster} for {@code rediss://} URIs (ElastiCache Serverless speaks the cluster
 *         protocol behind its TLS endpoint) and {@code standalone} otherwise (local single-node Valkey).</li>
 * </ul>
 */
@Slf4j
@Getter
public final class CacheConfig {
    public enum Mode { VALKEY, MEMORY, OFF }

    private final Mode mode;
    private final String valkeyUri;
    private final boolean cluster;

    private CacheConfig(final Mode mode, final String valkeyUri, final boolean cluster) {
        this.mode = mode;
        this.valkeyUri = valkeyUri;
        this.cluster = cluster;
    }

    public static CacheConfig resolve() {
        final String uri = prop("trip.valkey.uri", "TRIP_VALKEY_URI");
        final String rawMode = prop("trip.cache.mode", "TRIP_CACHE_MODE");
        final Mode mode = parseMode(rawMode, uri);
        if (mode == Mode.VALKEY && (uri == null)) {
            log.error("Cache mode is 'valkey' but no trip.valkey.uri / TRIP_VALKEY_URI is set! "
                    + "Falling back to per-JVM in-memory caching (single-instance semantics).");
            return new CacheConfig(Mode.MEMORY, null, false);
        }
        return new CacheConfig(mode, uri, isCluster(uri));
    }

    private static Mode parseMode(final String rawMode, final String uri) {
        if (rawMode == null || rawMode.isBlank()) {
            return (uri == null) ? Mode.MEMORY : Mode.VALKEY;
        }
        try {
            return Mode.valueOf(rawMode.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            log.error("Unknown trip.cache.mode '{}'; expected valkey, memory, or off. Using 'memory'.", rawMode);
            return Mode.MEMORY;
        }
    }

    private static boolean isCluster(final String uri) {
        final String protocol = prop("trip.valkey.protocol", "TRIP_VALKEY_PROTOCOL");
        if (protocol != null && !protocol.isBlank()) {
            return "cluster".equalsIgnoreCase(protocol.trim());
        }
        return uri != null && uri.startsWith("rediss://");
    }

    private static String prop(final String sysProp, final String envVar) {
        String value = System.getProperty(sysProp);
        if (value == null || value.isBlank()) {
            value = System.getenv(envVar);
        }
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
