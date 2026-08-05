package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Semantic BLOCKING primitives for the shared cache, called from virtual threads. Implementations must never
 * throw for ordinary cache unavailability: reads resolve to a miss (empty result) and writes resolve to
 * {@code false}, so the data layer can always fall back to the source of truth (DynamoDB). Implementations
 * must never run caller code on an I/O event loop (see {@link #subscribe}); since the virtual-threads port
 * the calling thread simply waits for the command itself.
 */
public interface CacheClient {

    /** Gets a single string value (point entries), empty on miss or cache error. */
    Optional<String> getValue(String key);

    /**
     * Sets a single string value. When {@code ttl} is non-null and positive, the key expires after that duration;
     * when {@code ttl} is null, the key has no hard expiry.
     */
    boolean putValue(String key, String value, Duration ttl);

    /** Removes a key of any type (value, hash, or set). */
    boolean removeKey(String key);

    /** Returns the full hash at {@code key}, empty map on miss or cache error. */
    Map<String, String> getHash(String key);

    /** Returns only the fields of the hash that exist, keyed by field name (HMGET semantics). */
    Map<String, String> getHashFields(String key, Collection<String> fields);

    /** Sets one field of a hash. */
    boolean putHashField(String key, String field, String value);

    /** Sets many fields of a hash at once (no removal of existing fields -- overlay semantics). */
    boolean putHashFields(String key, Map<String, String> fields);

    /** Removes one field of a hash. */
    boolean removeHashField(String key, String field);

    /** Adds members to a set. */
    boolean addSetMembers(String key, Collection<String> members);

    /**
     * Adds entries to a lexicographic sorted set (ZADD with score 0). Used by search indexes; entries sort as
     * plain strings.
     */
    boolean addSortedSetEntries(String key, Collection<String> entries);

    /** Removes entries from a lexicographic sorted set (ZREM). Missing entries are ignored. */
    boolean removeSortedSetEntries(String key, Collection<String> entries);

    /**
     * Returns up to {@code limit} entries of the sorted set at {@code key} that start with {@code prefix}
     * (ZRANGEBYLEX), in lexicographic order. Empty list on miss or cache error.
     */
    List<String> getSortedSetByPrefix(String key, String prefix, int limit);

    /**
     * Adds/updates members of a sorted set with explicit numeric scores (ZADD). Existing members are re-scored.
     */
    boolean addScoredEntries(String key, Map<String, Double> memberScores);

    /**
     * Returns members of the sorted set at {@code key} whose score is within [{@code minScore}, {@code maxScore}]
     * (inclusive), ordered by score. When {@code reverse} is true, highest score first (ZREVRANGEBYSCORE);
     * otherwise lowest first (ZRANGEBYSCORE). At most {@code limit} members (non-positive means no limit).
     * Empty list on miss or cache error.
     */
    List<String> getRangeByScore(
            String key, double minScore, double maxScore, boolean reverse, int limit);

    /** Removes one member from a set. */
    boolean removeSetMember(String key, String member);

    /** Returns all members of a set, empty on miss or cache error. */
    Set<String> getSetMembers(String key);

    /**
     * Applies a time-to-live to an existing key (resets the idle clock). Used for {@link CacheKeys#GC_TTL} hygiene
     * on entity data, not for soft-revalidate coherence.
     */
    boolean expire(String key, Duration ttl);

    /**
     * Atomically increments a counter by {@code delta} (INCRBY). When {@code ttl} is non-null and positive and
     * the key was newly created (result == delta), applies that TTL for memory hygiene. Returns empty on cache
     * error so callers can distinguish "count is 1" from "cache is down" — rate limiters must fail open rather
     * than silence a whole trip.
     */
    Optional<Long> increment(String key, long delta, Duration ttl);

    /**
     * Trims a scored sorted set to at most {@code maxSize} members by removing the lowest-score (oldest) entries
     * (ZREMRANGEBYRANK 0 -(maxSize+1)). No-op when maxSize &lt;= 0. Returns false on cache error.
     */
    boolean trimSortedSet(String key, int maxSize);

    /**
     * Tries to acquire a distributed lock ({@code SET key NX EX ttl}). Returns {@code true} if this caller holds
     * the lock. On cache errors, returns {@code false} (skip background work). The TTL is crash safety only;
     * callers should {@link #releaseLock} when done. Overlapping holders after expiry are expected under slow
     * reloads — entity merge is designed to tolerate duplicate refreshes.
     */
    boolean tryAcquireLock(String key, Duration ttl);

    /**
     * Best-effort unlock ({@code DEL}). Unconditional: if the lock TTL expired and another instance re-acquired,
     * this may delete their lock (duplicate refreshes remain safe). Prefer relying on short crash TTLs over
     * token-compare release complexity.
     */
    boolean releaseLock(String key);

    /**
     * Removes every key starting with {@code prefix}. Used by the admin "clear all caches" action and by tests;
     * never called on the hot path.
     */
    boolean clearNamespace(String prefix);

    /**
     * Publishes a payload to a channel. Errors never propagate: returns {@code false}.
     *
     * <p>Payloads must be <b>nudges, not messages</b> -- a small "there is something new up to timestamp T", never
     * the content itself. A subscriber that receives one (or that reconnects, or times out) runs its ordinary
     * cursor read, so a dropped nudge costs latency and never a message. It also means a blue/green deploy with two
     * builds subscribed to the same channel cannot mis-parse each other's traffic.
     */
    boolean publish(String channel, String payload);

    /**
     * Subscribes to the given channels until the returned handle is closed.
     *
     * <p>Channel names must always be <b>enumerable</b>: ElastiCache Serverless does not support PSUBSCRIBE, so
     * never design for pattern subscription.
     *
     * <p>{@code onMessage} receives (channel, payload) on a fresh virtual thread -- never an I/O event loop.
     * That is not a style preference: a callback that runs on the event loop and then performs a blocking
     * cache read (as every DAO read does) deadlocks the loop it is running on.
     *
     * <p>Implementations that cannot subscribe return a no-op handle rather than throwing.
     */
    AutoCloseable subscribe(Collection<String> channels, java.util.function.BiConsumer<String, String> onMessage);

    /** Releases connections/resources. The client is unusable afterwards. */
    default void close() {
    }
}
