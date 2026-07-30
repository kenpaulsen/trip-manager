package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Pass-through {@link CacheClient} for {@code trip.cache.mode=off}: every read is a miss and writes are discarded,
 * so all data comes straight from DynamoDB on every request. Emergency fallback only -- acceptable at this app's
 * scale, but it re-runs the full table scans per request.
 */
public final class NoopCacheClient implements CacheClient {
    private static final CompletableFuture<Boolean> TRUE = CompletableFuture.completedFuture(true);

    @Override
    public CompletableFuture<Optional<String>> getValue(final String key) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Boolean> putValue(final String key, final String value, final Duration ttl) {
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> removeKey(final String key) {
        return TRUE;
    }

    @Override
    public CompletableFuture<Map<String, String>> getHash(final String key) {
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletableFuture<Map<String, String>> getHashFields(final String key, final Collection<String> fields) {
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletableFuture<Boolean> putHashField(final String key, final String field, final String value) {
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> putHashFields(final String key, final Map<String, String> fields) {
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> removeHashField(final String key, final String field) {
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> addSetMembers(final String key, final Collection<String> members) {
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> removeSetMember(final String key, final String member) {
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> addSortedSetEntries(final String key, final Collection<String> entries) {
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> removeSortedSetEntries(final String key, final Collection<String> entries) {
        return TRUE;
    }

    @Override
    public CompletableFuture<List<String>> getSortedSetByPrefix(final String key, final String prefix, final int limit) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<Boolean> addScoredEntries(final String key, final Map<String, Double> memberScores) {
        return TRUE;
    }

    @Override
    public CompletableFuture<List<String>> getRangeByScore(
            final String key, final double minScore, final double maxScore, final boolean reverse, final int limit) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<Set<String>> getSetMembers(final String key) {
        return CompletableFuture.completedFuture(Set.of());
    }

    @Override
    public CompletableFuture<Boolean> expire(final String key, final Duration ttl) {
        return TRUE;
    }

    @Override
    public CompletableFuture<Optional<Long>> increment(final String key, final long delta, final Duration ttl) {
        // Cache off: rate limiters treat empty as "cache down" and fall back to an in-JVM bucket.
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Boolean> trimSortedSet(final String key, final int maxSize) {
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> publish(final String channel, final String payload) {
        // Nothing is cached and nothing is listening; a lost nudge costs latency, never a message.
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public AutoCloseable subscribe(final Collection<String> channels, final BiConsumer<String, String> onMessage) {
        return () -> { };
    }

    @Override
    public CompletableFuture<Boolean> tryAcquireLock(final String key, final Duration ttl) {
        // Nothing is cached; acquiring is harmless and unused on the miss path.
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> releaseLock(final String key) {
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> clearNamespace(final String prefix) {
        return TRUE;
    }
}
