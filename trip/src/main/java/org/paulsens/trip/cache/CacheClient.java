package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
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

    /**
     * Sets a single string value. When {@code ttl} is non-null and positive, the key expires after that duration;
     * when {@code ttl} is null, the key has no hard expiry.
     */
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

    /**
     * Adds entries to a lexicographic sorted set (ZADD with score 0). Used by search indexes; entries sort as
     * plain strings.
     */
    CompletableFuture<Boolean> addSortedSetEntries(String key, Collection<String> entries);

    /** Removes entries from a lexicographic sorted set (ZREM). Missing entries are ignored. */
    CompletableFuture<Boolean> removeSortedSetEntries(String key, Collection<String> entries);

    /**
     * Returns up to {@code limit} entries of the sorted set at {@code key} that start with {@code prefix}
     * (ZRANGEBYLEX), in lexicographic order. Empty list on miss or cache error.
     */
    CompletableFuture<List<String>> getSortedSetByPrefix(String key, String prefix, int limit);

    /**
     * Adds/updates members of a sorted set with explicit numeric scores (ZADD). Existing members are re-scored.
     */
    CompletableFuture<Boolean> addScoredEntries(String key, Map<String, Double> memberScores);

    /**
     * Returns members of the sorted set at {@code key} whose score is within [{@code minScore}, {@code maxScore}]
     * (inclusive), ordered by score. When {@code reverse} is true, highest score first (ZREVRANGEBYSCORE);
     * otherwise lowest first (ZRANGEBYSCORE). At most {@code limit} members (non-positive means no limit).
     * Empty list on miss or cache error.
     */
    CompletableFuture<List<String>> getRangeByScore(
            String key, double minScore, double maxScore, boolean reverse, int limit);

    /** Removes one member from a set. */
    CompletableFuture<Boolean> removeSetMember(String key, String member);

    /** Returns all members of a set, empty on miss or cache error. */
    CompletableFuture<Set<String>> getSetMembers(String key);

    /**
     * Applies a time-to-live to an existing key (resets the idle clock). Used for {@link CacheKeys#GC_TTL} hygiene
     * on entity data, not for soft-revalidate coherence.
     */
    CompletableFuture<Boolean> expire(String key, Duration ttl);

    /**
     * Tries to acquire a distributed lock ({@code SET key NX EX ttl}). Returns {@code true} if this caller holds
     * the lock. On cache errors, returns {@code false} (skip background work). The TTL is crash safety only;
     * callers should {@link #releaseLock} when done. Overlapping holders after expiry are expected under slow
     * reloads — entity merge is designed to tolerate duplicate refreshes.
     */
    CompletableFuture<Boolean> tryAcquireLock(String key, Duration ttl);

    /**
     * Best-effort unlock ({@code DEL}). Unconditional: if the lock TTL expired and another instance re-acquired,
     * this may delete their lock (duplicate refreshes remain safe). Prefer relying on short crash TTLs over
     * token-compare release complexity.
     */
    CompletableFuture<Boolean> releaseLock(String key);

    /**
     * Removes every key starting with {@code prefix}. Used by the admin "clear all caches" action and by tests;
     * never called on the hot path.
     */
    CompletableFuture<Boolean> clearNamespace(String prefix);

    /** Releases connections/resources. The client is unusable afterwards. */
    default void close() {
    }
}
