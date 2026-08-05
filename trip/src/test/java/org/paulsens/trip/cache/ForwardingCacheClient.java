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
 * Test helper: forwards every {@link CacheClient} call to a delegate so a test can override just the one
 * or two operations it wants to observe or sabotage ({@code InMemoryCacheClient} is final on purpose).
 */
abstract class ForwardingCacheClient implements CacheClient {
    private final CacheClient delegate;

    protected ForwardingCacheClient(final CacheClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableFuture<Optional<String>> getValue(final String key) {
        return delegate.getValue(key);
    }

    @Override
    public CompletableFuture<Boolean> putValue(final String key, final String value, final Duration ttl) {
        return delegate.putValue(key, value, ttl);
    }

    @Override
    public CompletableFuture<Boolean> removeKey(final String key) {
        return delegate.removeKey(key);
    }

    @Override
    public CompletableFuture<Map<String, String>> getHash(final String key) {
        return delegate.getHash(key);
    }

    @Override
    public CompletableFuture<Map<String, String>> getHashFields(final String key, final Collection<String> fields) {
        return delegate.getHashFields(key, fields);
    }

    @Override
    public CompletableFuture<Boolean> putHashField(final String key, final String field, final String value) {
        return delegate.putHashField(key, field, value);
    }

    @Override
    public CompletableFuture<Boolean> putHashFields(final String key, final Map<String, String> fields) {
        return delegate.putHashFields(key, fields);
    }

    @Override
    public CompletableFuture<Boolean> removeHashField(final String key, final String field) {
        return delegate.removeHashField(key, field);
    }

    @Override
    public CompletableFuture<Boolean> addSetMembers(final String key, final Collection<String> members) {
        return delegate.addSetMembers(key, members);
    }

    @Override
    public CompletableFuture<Boolean> addSortedSetEntries(final String key, final Collection<String> entries) {
        return delegate.addSortedSetEntries(key, entries);
    }

    @Override
    public CompletableFuture<Boolean> removeSortedSetEntries(final String key, final Collection<String> entries) {
        return delegate.removeSortedSetEntries(key, entries);
    }

    @Override
    public CompletableFuture<List<String>> getSortedSetByPrefix(
            final String key, final String prefix, final int limit) {
        return delegate.getSortedSetByPrefix(key, prefix, limit);
    }

    @Override
    public CompletableFuture<Boolean> addScoredEntries(final String key, final Map<String, Double> memberScores) {
        return delegate.addScoredEntries(key, memberScores);
    }

    @Override
    public CompletableFuture<List<String>> getRangeByScore(
            final String key, final double minScore, final double maxScore, final boolean reverse, final int limit) {
        return delegate.getRangeByScore(key, minScore, maxScore, reverse, limit);
    }

    @Override
    public CompletableFuture<Boolean> removeSetMember(final String key, final String member) {
        return delegate.removeSetMember(key, member);
    }

    @Override
    public CompletableFuture<Set<String>> getSetMembers(final String key) {
        return delegate.getSetMembers(key);
    }

    @Override
    public CompletableFuture<Boolean> expire(final String key, final Duration ttl) {
        return delegate.expire(key, ttl);
    }

    @Override
    public CompletableFuture<Optional<Long>> increment(final String key, final long delta, final Duration ttl) {
        return delegate.increment(key, delta, ttl);
    }

    @Override
    public CompletableFuture<Boolean> trimSortedSet(final String key, final int maxSize) {
        return delegate.trimSortedSet(key, maxSize);
    }

    @Override
    public CompletableFuture<Boolean> tryAcquireLock(final String key, final Duration ttl) {
        return delegate.tryAcquireLock(key, ttl);
    }

    @Override
    public CompletableFuture<Boolean> releaseLock(final String key) {
        return delegate.releaseLock(key);
    }

    @Override
    public CompletableFuture<Boolean> clearNamespace(final String prefix) {
        return delegate.clearNamespace(prefix);
    }

    @Override
    public CompletableFuture<Boolean> publish(final String channel, final String payload) {
        return delegate.publish(channel, payload);
    }

    @Override
    public AutoCloseable subscribe(final Collection<String> channels, final BiConsumer<String, String> onMessage) {
        return delegate.subscribe(channels, onMessage);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
