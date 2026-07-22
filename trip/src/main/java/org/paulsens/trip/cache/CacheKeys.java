package org.paulsens.trip.cache;

import java.time.Duration;

/**
 * Single authority for shared-cache key names. Every key written by the data layer starts with {@link #FORMAT_VERSION}
 * so an incompatible change to the cached JSON format can be rolled out by bumping the prefix (old instances keep
 * reading their own keys; abandoned keys age out via {@link #GC_TTL} or explicit clear).
 *
 * <p>Entity data uses <strong>soft revalidate</strong>: {@link #LOADED_AT} (or binding marker epoch) records the last
 * full load from Dynamo. After {@link #SOFT_TTL}, reads still return cache hits immediately and a background reload
 * may run (read-triggered; idle partitions can stay soft-stale until next access — admin clear for impatient cases).
 * Write-through does <em>not</em> refresh {@link #LOADED_AT}. Hard {@code EXPIRE} of {@link #GC_TTL} is only a
 * hygiene backstop for abandoned keys / GB-hour billing, not the primary coherence mechanism.</p>
 */
public final class CacheKeys {
    /** Bump (t1: -> t2:) on any incompatible change to the cached value format. */
    public static final String FORMAT_VERSION = "t1:";

    // Privileges are partitioned by trip: one hash per partition (PRIV_PREFIX + partition), field = base priv name,
    // value = Privilege JSON. The partition is the tripId for trip-scoped privileges, or PRIV_GLOBAL_PARTITION.
    // A single loaded marker (PRIV_LOADED) covers all partitions -- one scan rebuilds them together. See PrivilegeIndex.
    public static final String PRIV_PREFIX = FORMAT_VERSION + "priv:";
    public static final String PRIV_GLOBAL_PARTITION = "__global__";
    public static final String PRIV_LOADED = FORMAT_VERSION + "priv_loaded";

    public static String privPartitionKey(final String partition) {
        return PRIV_PREFIX + partition;
    }

    // Trips are point entries (no whole-table hash): TRIP_PREFIX + {tripId}.
    public static final String TRIP_PREFIX = FORMAT_VERSION + "trip:";

    // Trip date index: sorted set, member = tripId, score = endDate epoch millis. Plus a sibling loaded marker.
    public static final String TRIPS_BY_DATE = FORMAT_VERSION + "idx:trips";
    // Person -> trips reverse index: sorted set (lex), member = "userId|tripId". Shares TRIPS_BY_DATE's marker
    // (both are rebuilt by the same trip scan). See TripIndex.
    public static final String TRIPS_BY_PERSON = FORMAT_VERSION + "idx:person_trips";

    // People are point entries (no whole-table hash): PERSON_PREFIX + {personId}.
    public static final String PERSON_PREFIX = FORMAT_VERSION + "person:";

    // Email lookup hash: field = lowercased email, value = personId. Lazy cache in front of the email-index GSI
    // (and the authoritative store in local mode, where every save goes through write-through).
    public static final String EMAIL_IDX = FORMAT_VERSION + "email";

    // People search index: lexicographic sorted set of "token|personId" entries plus a sibling loaded marker
    // (value = epoch millis of last full build). See SearchIndex.
    public static final String PEOPLE_SEARCH = FORMAT_VERSION + "idx:people";
    public static final String SEARCH_LOADED_SUFFIX = ":loaded";

    /** Separator between a search token and the entity id in a search index entry. Stripped from tokens. */
    public static final char SEARCH_SEPARATOR = '|';

    /**
     * Soft revalidate age for search indexes: rebuilding means a full table scan, so it is much lazier than
     * {@link #SOFT_TTL}. Out-of-band writes through the DAO keep the index current; this only heals direct
     * database edits.
     */
    public static final Duration INDEX_SOFT_TTL = Duration.ofHours(24);

    // Per-partition hashes (append the partition id).
    public static final String REG_PREFIX = FORMAT_VERSION + "reg:";
    public static final String TX_PREFIX = FORMAT_VERSION + "tx:";
    public static final String TODO_PREFIX = FORMAT_VERSION + "todo:";
    public static final String PDV_PREFIX = FORMAT_VERSION + "pdv:";

    // Point entries (append the entity id). Sibling {@code :at} key holds epoch millis for soft revalidate.
    public static final String TRIP_EVENT_PREFIX = FORMAT_VERSION + "trip_event:";
    public static final String POINT_AT_SUFFIX = ":at";

    // Binding adjacency sets: BIND_PREFIX + {typeAndId} + ":" + {destTypeId}; loaded marker uses BIND_LOADED_SUFFIX.
    public static final String BIND_PREFIX = FORMAT_VERSION + "bind:";
    public static final String BIND_LOADED_SUFFIX = ":loaded";

    /**
     * Reserved pub/sub namespace for the future real-time chat feature: {@code chat:trip:{tripId}},
     * {@code chat:dm:{lowUserId}:{highUserId}}. Note ElastiCache Serverless does not support PSUBSCRIBE, so channel
     * names must always be enumerable by subscribers -- never design for pattern subscription.
     */
    public static final String CHAT_CHANNEL_PREFIX = "chat:";

    /**
     * Hash field marking a hash as fully loaded from the database. A hash without this field may still contain
     * valid write-through entries, but only a hash with it may satisfy a "list all" or "not found" answer.
     */
    public static final String LOADED_SENTINEL = "__loaded__";
    public static final String LOADED_VALUE = "1";

    /**
     * Hash field: epoch millis of last <em>full load</em> from Dynamo (only meaningful with {@link #LOADED_SENTINEL}).
     * Write-through does not update this. Missing or unparseable → treat as soft-stale (background revalidate).
     */
    public static final String LOADED_AT = "__loaded_at__";

    /**
     * Soft revalidate age: after this, cache hits still serve immediately but may trigger a background Dynamo reload.
     */
    public static final Duration SOFT_TTL = Duration.ofHours(1);

    /**
     * Idle hygiene TTL applied after full loads (and refreshed on write-through activity). Bounds abandoned key
     * growth and GB-hours; not used as the primary delete-heal mechanism (that is soft revalidate + reconcile).
     */
    public static final Duration GC_TTL = Duration.ofDays(7);

    /**
     * Crash-safety TTL for distributed refresh locks ({@code SET NX EX}). Long enough for a cold full-table scan;
     * release is best-effort. Overlapping refreshes after expiry are safe by design (overlay merge + snapshot
     * reconcile; {@link #LOADED_AT} is last-write-wins).
     */
    public static final Duration REFRESH_LOCK_TTL = Duration.ofSeconds(45);

    /** Prefix for refresh locks: {@link #refreshLockKey(String)}. */
    public static final String REFRESH_LOCK_PREFIX = FORMAT_VERSION + "refresh:";

    public static String refreshLockKey(final String dataKey) {
        return REFRESH_LOCK_PREFIX + dataKey;
    }

    public static String pointAtKey(final String pointKey) {
        return pointKey + POINT_AT_SUFFIX;
    }

    private CacheKeys() {
    }
}
