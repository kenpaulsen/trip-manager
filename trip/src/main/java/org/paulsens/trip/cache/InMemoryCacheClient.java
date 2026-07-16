package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Map-backed {@link CacheClient} used for {@code local=true} mode and unit tests -- no external processes required.
 * Hard TTLs are ignored (entries live for the JVM lifetime). Locks are process-local. In local mode the fake
 * {@link org.paulsens.trip.dynamo.Persistence} returns empty scans, so this cache effectively IS the local
 * datastore, seeded by write-through saves from FakeData.
 */
public final class InMemoryCacheClient implements CacheClient {
    private static final CompletableFuture<Boolean> TRUE = CompletableFuture.completedFuture(true);
    private static final CompletableFuture<Boolean> FALSE = CompletableFuture.completedFuture(false);

    // Values are String (point), ConcurrentMap<String, String> (hash), or Set<String> (set).
    private final ConcurrentMap<String, Object> store = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> locks = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Optional<String>> getValue(final String key) {
        final Object value = store.get(key);
        return CompletableFuture.completedFuture(
                (value instanceof String str) ? Optional.of(str) : Optional.empty());
    }

    @Override
    public CompletableFuture<Boolean> putValue(final String key, final String value, final Duration ttl) {
        store.put(key, value);
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> removeKey(final String key) {
        store.remove(key);
        return TRUE;
    }

    @Override
    public CompletableFuture<Map<String, String>> getHash(final String key) {
        return CompletableFuture.completedFuture(Map.copyOf(hash(key)));
    }

    @Override
    public CompletableFuture<Map<String, String>> getHashFields(final String key, final Collection<String> fields) {
        return CompletableFuture.completedFuture(selectHashFields(hash(key), fields));
    }

    private static Map<String, String> selectHashFields(
            final Map<String, String> hash, final Collection<String> fields) {
        final Map<String, String> result = new HashMap<>();
        for (final String field : fields) {
            final String value = hash.get(field);
            if (value != null) {
                result.put(field, value);
            }
        }
        return result;
    }

    @Override
    public CompletableFuture<Boolean> putHashField(final String key, final String field, final String value) {
        hash(key).put(field, value);
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> putHashFields(final String key, final Map<String, String> fields) {
        hash(key).putAll(fields);
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> removeHashField(final String key, final String field) {
        hash(key).remove(field);
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> addSetMembers(final String key, final Collection<String> members) {
        set(key).addAll(members);
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> removeSetMember(final String key, final String member) {
        set(key).remove(member);
        return TRUE;
    }

    @Override
    public CompletableFuture<Set<String>> getSetMembers(final String key) {
        return CompletableFuture.completedFuture(Set.copyOf(set(key)));
    }

    @Override
    public CompletableFuture<Boolean> expire(final String key, final Duration ttl) {
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> tryAcquireLock(final String key, final Duration ttl) {
        final long now = System.currentTimeMillis();
        final long expireAt = now + Math.max(1L, ttl == null ? 5_000L : ttl.toMillis());
        if (locks.putIfAbsent(key, expireAt) == null) {
            return TRUE;
        }
        final Long existing = locks.get(key);
        if (existing != null && existing <= now && locks.replace(key, existing, expireAt)) {
            return TRUE;
        }
        return FALSE;
    }

    @Override
    public CompletableFuture<Boolean> releaseLock(final String key) {
        locks.remove(key);
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> clearNamespace(final String prefix) {
        store.keySet().removeIf(key -> key.startsWith(prefix));
        locks.keySet().removeIf(key -> key.startsWith(prefix));
        return TRUE;
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<String, String> hash(final String key) {
        return (ConcurrentMap<String, String>) store.computeIfAbsent(key, k -> new ConcurrentHashMap<String, String>());
    }

    @SuppressWarnings("unchecked")
    private Set<String> set(final String key) {
        return (Set<String>) store.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
    }
}
