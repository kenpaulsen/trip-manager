package org.paulsens.trip.cache;

import java.time.Duration;

/**
 * Single authority for shared-cache key names. Every key written by the data layer starts with {@link #FORMAT_VERSION}
 * so an incompatible change to the cached JSON format can be rolled out by bumping the prefix (old instances keep
 * reading their own keys; abandoned keys age out via TTL).
 */
public final class CacheKeys {
    /** Bump (t1: -> t2:) on any incompatible change to the cached value format. */
    public static final String FORMAT_VERSION = "t1:";

    // Whole-table hashes (field = entity id, value = entity JSON).
    public static final String PEOPLE = FORMAT_VERSION + "people";
    public static final String TRIPS = FORMAT_VERSION + "trips";
    public static final String PRIVS = FORMAT_VERSION + "privs";

    // Per-partition hashes (append the partition id).
    public static final String REG_PREFIX = FORMAT_VERSION + "reg:";
    public static final String TX_PREFIX = FORMAT_VERSION + "tx:";
    public static final String TODO_PREFIX = FORMAT_VERSION + "todo:";
    public static final String PDV_PREFIX = FORMAT_VERSION + "pdv:";

    // Point entries (append the entity id).
    public static final String TRIP_EVENT_PREFIX = FORMAT_VERSION + "trip_event:";

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

    /** Default TTL for loaded hashes -- bounds staleness from writers that bypass the cache. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(1);
    /** Point entries are only ever written through this layer, so they can live longer. */
    public static final Duration POINT_TTL = Duration.ofHours(24);
    /**
     * Binding member sets must outlive their loaded marker: the marker expiring first forces a reload while the
     * sets still hold valid write-through members. A set expiring before its marker would silently read as empty.
     */
    public static final Duration BIND_SET_TTL = Duration.ofHours(24);
    public static final Duration BIND_MARKER_TTL = Duration.ofHours(1);

    private CacheKeys() {
    }
}
