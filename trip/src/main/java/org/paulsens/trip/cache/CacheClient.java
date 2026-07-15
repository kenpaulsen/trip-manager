package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Semantic async primitives for the shared cache. Implementations must never throw from the returned futures for
 * ordinary cache unavailability: reads resolve to a miss (empty result) and writes resolve to {@code false}, so the
 * data layer can always fall back to the source of truth (DynamoDB). Implementations must also complete all returned
 * futures on a general-purpose worker pool (never an I/O event loop), because callers block on these futures --
 * including from within Jackson deserialization.
 */
public interface CacheClient {

    /** Gets a single string value (point entries), empty on miss or cache error. */
    CompletableFuture<Optional<String>> getValue(String key);

    /** Sets a single string value with a time-to-live. */
    CompletableFuture<Boolean> putValue(String key, String value, Duration ttl);

    /** Removes a key of any type (value, hash, or set). */
    CompletableFuture<Boolean> removeKey(String key);

    /** Returns the full hash at {@code key}, empty map on miss or cache error. */
    CompletableFuture<Map<String, String>> getHash(String key);

    /** Returns only the fields of the hash that exist, keyed by field name (HMGET semantics). */
    CompletableFuture<Map<String, String>> getHashFields(String key, Collection<String> fields);

    /** Sets one field of a hash. */
    CompletableFuture<Boolean> putHashField(String key, String field, String value);

    /** Sets many fields of a hash at once (no removal of existing fields -- overlay semantics). */
    CompletableFuture<Boolean> putHashFields(String key, Map<String, String> fields);

    /** Removes one field of a hash. */
    CompletableFuture<Boolean> removeHashField(String key, String field);

    /** Adds members to a set. */
    CompletableFuture<Boolean> addSetMembers(String key, Collection<String> members);

    /** Removes one member from a set. */
    CompletableFuture<Boolean> removeSetMember(String key, String member);

    /** Returns all members of a set, empty on miss or cache error. */
    CompletableFuture<Set<String>> getSetMembers(String key);

    /** Applies a time-to-live to an existing key, resetting any previous TTL. */
    CompletableFuture<Boolean> expire(String key, Duration ttl);

    /**
     * Ensures the key has a time-to-live without sliding an existing one (EXPIRE NX semantics). Used by
     * write-through updates: a fresh key gets bounded, but frequent writes must not extend a loaded hash's
     * expiry forever (that would keep changes written directly to the database invisible indefinitely).
     */
    default CompletableFuture<Boolean> expireIfNoTtl(final String key, final Duration ttl) {
        return expire(key, ttl);
    }

    /**
     * Removes every key starting with {@code prefix}. Used by the admin "clear all caches" action and by tests;
     * never called on the hot path.
     */
    CompletableFuture<Boolean> clearNamespace(String prefix);

    /** Releases connections/resources. The client is unusable afterwards. */
    default void close() {
    }
}
