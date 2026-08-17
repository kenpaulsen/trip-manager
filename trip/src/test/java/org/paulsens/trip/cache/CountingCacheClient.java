package org.paulsens.trip.cache;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test helper: an {@link InMemoryCacheClient}-backed client that counts the READ operations the near-cache
 * is supposed to absorb ({@code getValue} / {@code getHash} / {@code getHashFields}). The near-cache tests
 * assert on the count the way {@code RenderPathCacheTest} asserts on persistence reads.
 *
 * <p>Public (unlike {@link ForwardingCacheClient}) because the DAO-level test in {@code org.paulsens.trip.dynamo}
 * needs it too.
 */
public class CountingCacheClient extends ForwardingCacheClient {

    public final AtomicInteger reads = new AtomicInteger();
    /** The (operation, key) of every counted read, newest last — the "which read was that?" diagnostic. */
    public final java.util.List<String> readLog = new java.util.concurrent.CopyOnWriteArrayList<>();

    public CountingCacheClient() {
        this(new InMemoryCacheClient());
    }

    public CountingCacheClient(final CacheClient delegate) {
        super(delegate);
    }

    @Override
    public Optional<String> getValue(final String key) {
        reads.incrementAndGet();
        readLog.add("getValue " + key);
        return super.getValue(key);
    }

    @Override
    public Map<String, String> getHash(final String key) {
        reads.incrementAndGet();
        readLog.add("getHash " + key);
        return super.getHash(key);
    }

    @Override
    public Map<String, String> getHashFields(final String key, final Collection<String> fields) {
        reads.incrementAndGet();
        readLog.add("getHashFields " + key + " " + fields);
        return super.getHashFields(key, fields);
    }
}
