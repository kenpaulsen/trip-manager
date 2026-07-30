package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

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
    /** Channel to listeners. Process-local by design -- see {@link #publish}. */
    private final ConcurrentMap<String, List<BiConsumer<String, String>>> subscribers = new ConcurrentHashMap<>();

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
    public CompletableFuture<Boolean> addSortedSetEntries(final String key, final Collection<String> entries) {
        final ConcurrentMap<String, Double> zset = sortedSet(key);
        entries.forEach(e -> zset.put(e, 0.0d));
        return TRUE;
    }

    @Override
    public CompletableFuture<Boolean> removeSortedSetEntries(final String key, final Collection<String> entries) {
        sortedSet(key).keySet().removeAll(entries);
        return TRUE;
    }

    @Override
    public CompletableFuture<List<String>> getSortedSetByPrefix(final String key, final String prefix, final int limit) {
        final List<String> result = sortedSet(key).keySet().stream()
                .filter(member -> member.startsWith(prefix))
                .sorted()
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .toList();
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Boolean> addScoredEntries(final String key, final Map<String, Double> memberScores) {
        sortedSet(key).putAll(memberScores);
        return TRUE;
    }

    @Override
    public CompletableFuture<List<String>> getRangeByScore(
            final String key, final double minScore, final double maxScore, final boolean reverse, final int limit) {
        final Comparator<Map.Entry<String, Double>> byScore = Map.Entry.comparingByValue();
        final List<String> result = sortedSet(key).entrySet().stream()
                .filter(e -> e.getValue() >= minScore && e.getValue() <= maxScore)
                .sorted(reverse ? byScore.reversed() : byScore)
                .map(Map.Entry::getKey)
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .toList();
        return CompletableFuture.completedFuture(result);
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
    public CompletableFuture<Optional<Long>> increment(final String key, final long delta, final Duration ttl) {
        // TTLs are ignored here (same as every other write); counters live for the JVM lifetime. Rate-limit
        // keys put the window index in the key so this is still correct under tests.
        final Object prev = store.get(key);
        long current = 0L;
        if (prev instanceof String str) {
            try {
                current = Long.parseLong(str);
            } catch (final NumberFormatException ex) {
                current = 0L;
            }
        }
        final long next = current + delta;
        store.put(key, String.valueOf(next));
        return CompletableFuture.completedFuture(Optional.of(next));
    }

    @Override
    public CompletableFuture<Boolean> trimSortedSet(final String key, final int maxSize) {
        if (maxSize <= 0) {
            return TRUE;
        }
        final ConcurrentMap<String, Double> zset = sortedSet(key);
        if (zset.size() <= maxSize) {
            return TRUE;
        }
        final List<String> ordered = zset.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
        final int toRemove = ordered.size() - maxSize;
        for (int i = 0; i < toRemove; i++) {
            zset.remove(ordered.get(i));
        }
        return TRUE;
    }

    /**
     * Process-local pub/sub: a subscriber in THIS JVM sees a publish from THIS JVM, and nothing else.
     *
     * <p>Explicitly process-local, the same honest limitation as {@code MediaEvents}. That is correct for local
     * mode and unit tests, and it is exactly why the real-time path must be exercised against a real Valkey before
     * anyone concludes fan-out works: sticky sessions plus a single task make an in-JVM registry look perfect and
     * break silently the moment there are two.
     */
    @Override
    public CompletableFuture<Boolean> publish(final String channel, final String payload) {
        final List<BiConsumer<String, String>> listeners = subscribers.get(channel);
        if (listeners != null) {
            for (final BiConsumer<String, String> listener : List.copyOf(listeners)) {
                listener.accept(channel, payload);
            }
        }
        return TRUE;
    }

    @Override
    public AutoCloseable subscribe(final Collection<String> channels, final BiConsumer<String, String> onMessage) {
        if (channels == null || channels.isEmpty() || onMessage == null) {
            return () -> { };
        }
        for (final String channel : channels) {
            subscribers.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>()).add(onMessage);
        }
        return () -> unsubscribe(channels, onMessage);
    }

    private void unsubscribe(final Collection<String> channels, final BiConsumer<String, String> onMessage) {
        for (final String channel : channels) {
            final List<BiConsumer<String, String>> listeners = subscribers.get(channel);
            if (listeners != null) {
                listeners.remove(onMessage);
            }
        }
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

    // Sorted sets mirror Redis: one member->score map per key. Lex ops (addSortedSetEntries / getSortedSetByPrefix)
    // use score 0 and sort by member; scored ops (addScoredEntries / getRangeByScore) use the scores.
    @SuppressWarnings("unchecked")
    private ConcurrentMap<String, Double> sortedSet(final String key) {
        return (ConcurrentMap<String, Double>) store.computeIfAbsent(key, k -> new ConcurrentHashMap<String, Double>());
    }
}
