package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Pass-through {@link CacheClient} for {@code trip.cache.mode=off}: every read is a miss and writes are discarded,
 * so all data comes straight from DynamoDB on every request. Emergency fallback only -- acceptable at this app's
 * scale, but it re-runs the full table scans per request.
 */
public final class NoopCacheClient implements CacheClient {

    @Override
    public Optional<String> getValue(final String key) {
        return Optional.empty();
    }

    @Override
    public boolean putValue(final String key, final String value, final Duration ttl) {
        return true;
    }

    @Override
    public boolean removeKey(final String key) {
        return true;
    }

    @Override
    public Map<String, String> getHash(final String key) {
        return Map.of();
    }

    @Override
    public Map<String, String> getHashFields(final String key, final Collection<String> fields) {
        return Map.of();
    }

    @Override
    public boolean putHashField(final String key, final String field, final String value) {
        return true;
    }

    @Override
    public boolean putHashFields(final String key, final Map<String, String> fields) {
        return true;
    }

    @Override
    public boolean removeHashField(final String key, final String field) {
        return true;
    }

    @Override
    public boolean addSetMembers(final String key, final Collection<String> members) {
        return true;
    }

    @Override
    public boolean removeSetMember(final String key, final String member) {
        return true;
    }

    @Override
    public boolean addSortedSetEntries(final String key, final Collection<String> entries) {
        return true;
    }

    @Override
    public boolean removeSortedSetEntries(final String key, final Collection<String> entries) {
        return true;
    }

    @Override
    public List<String> getSortedSetByPrefix(final String key, final String prefix, final int limit) {
        return List.of();
    }

    @Override
    public boolean addScoredEntries(final String key, final Map<String, Double> memberScores) {
        return true;
    }

    @Override
    public List<String> getRangeByScore(
            final String key, final double minScore, final double maxScore, final boolean reverse, final int limit) {
        return List.of();
    }

    @Override
    public Set<String> getSetMembers(final String key) {
        return Set.of();
    }

    @Override
    public boolean expire(final String key, final Duration ttl) {
        return true;
    }

    @Override
    public Optional<Long> increment(final String key, final long delta, final Duration ttl) {
        // Cache off: rate limiters treat empty as "cache down" and fall back to an in-JVM bucket.
        return Optional.empty();
    }

    @Override
    public boolean trimSortedSet(final String key, final int maxSize) {
        return true;
    }

    @Override
    public boolean publish(final String channel, final String payload) {
        // Nothing is cached and nothing is listening; a lost nudge costs latency, never a message.
        return false;
    }

    @Override
    public AutoCloseable subscribe(final Collection<String> channels, final BiConsumer<String, String> onMessage) {
        return () -> { };
    }

    @Override
    public boolean tryAcquireLock(final String key, final Duration ttl) {
        // Nothing is cached; acquiring is harmless and unused on the miss path.
        return true;
    }

    @Override
    public boolean releaseLock(final String key) {
        return true;
    }

    @Override
    public boolean clearNamespace(final String prefix) {
        return true;
    }
}
