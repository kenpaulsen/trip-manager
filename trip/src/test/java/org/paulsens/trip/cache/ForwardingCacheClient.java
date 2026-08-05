package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    public Optional<String> getValue(final String key) {
        return delegate.getValue(key);
    }

    @Override
    public boolean putValue(final String key, final String value, final Duration ttl) {
        return delegate.putValue(key, value, ttl);
    }

    @Override
    public boolean removeKey(final String key) {
        return delegate.removeKey(key);
    }

    @Override
    public Map<String, String> getHash(final String key) {
        return delegate.getHash(key);
    }

    @Override
    public Map<String, String> getHashFields(final String key, final Collection<String> fields) {
        return delegate.getHashFields(key, fields);
    }

    @Override
    public boolean putHashField(final String key, final String field, final String value) {
        return delegate.putHashField(key, field, value);
    }

    @Override
    public boolean putHashFields(final String key, final Map<String, String> fields) {
        return delegate.putHashFields(key, fields);
    }

    @Override
    public boolean removeHashField(final String key, final String field) {
        return delegate.removeHashField(key, field);
    }

    @Override
    public boolean addSetMembers(final String key, final Collection<String> members) {
        return delegate.addSetMembers(key, members);
    }

    @Override
    public boolean addSortedSetEntries(final String key, final Collection<String> entries) {
        return delegate.addSortedSetEntries(key, entries);
    }

    @Override
    public boolean removeSortedSetEntries(final String key, final Collection<String> entries) {
        return delegate.removeSortedSetEntries(key, entries);
    }

    @Override
    public List<String> getSortedSetByPrefix(final String key, final String prefix, final int limit) {
        return delegate.getSortedSetByPrefix(key, prefix, limit);
    }

    @Override
    public boolean addScoredEntries(final String key, final Map<String, Double> memberScores) {
        return delegate.addScoredEntries(key, memberScores);
    }

    @Override
    public List<String> getRangeByScore(
            final String key, final double minScore, final double maxScore, final boolean reverse, final int limit) {
        return delegate.getRangeByScore(key, minScore, maxScore, reverse, limit);
    }

    @Override
    public boolean removeSetMember(final String key, final String member) {
        return delegate.removeSetMember(key, member);
    }

    @Override
    public Set<String> getSetMembers(final String key) {
        return delegate.getSetMembers(key);
    }

    @Override
    public boolean expire(final String key, final Duration ttl) {
        return delegate.expire(key, ttl);
    }

    @Override
    public Optional<Long> increment(final String key, final long delta, final Duration ttl) {
        return delegate.increment(key, delta, ttl);
    }

    @Override
    public boolean trimSortedSet(final String key, final int maxSize) {
        return delegate.trimSortedSet(key, maxSize);
    }

    @Override
    public boolean tryAcquireLock(final String key, final Duration ttl) {
        return delegate.tryAcquireLock(key, ttl);
    }

    @Override
    public boolean releaseLock(final String key) {
        return delegate.releaseLock(key);
    }

    @Override
    public boolean clearNamespace(final String prefix) {
        return delegate.clearNamespace(prefix);
    }

    @Override
    public boolean publish(final String channel, final String payload) {
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
