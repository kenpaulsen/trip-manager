package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.util.TripThreads;

/**
 * In-JVM near-cache decorating the shared-cache client: point and hash reads whose caller declared
 * {@link Cached#YES} (via {@link NearCacheContext}) are served from heap, so a render that today costs a
 * hundred sequential Valkey round trips costs none once warm. Everything else -- declared-fresh reads,
 * unbound background threads, keys outside the {@code t1:} data namespace, and every non-point/hash
 * operation (sets, sorted sets, locks, counters, pub/sub) -- forwards untouched.
 *
 * <p><b>Freshness model</b> (stale-while-revalidate): an entry serves for up to the hard TTL. On a hit
 * whose last health check is older than the check interval, the timestamp is CAS-reset FIRST -- so a herd
 * of concurrent hits elects exactly one refresher -- and the winner re-reads the delegate on a background
 * virtual thread ({@link TripThreads}, capped by {@link RefreshPermits}) and replaces the entry. The
 * foreground read never waits. Same-JVM writes are exact, not eventual: every mutating operation drops the
 * entry for its key after forwarding, so read-your-writes holds for everything written through this
 * process. What remains eventual is only data written behind this JVM's back (scripts, console edits, a
 * future second host), bounded by the health check once traffic touches the entry and by the hard TTL
 * always.
 *
 * <p><b>What is stored</b>: the delegate's own strings/maps -- deliberately NOT deserialized objects. The
 * typed caches above deserialize per read, so every caller keeps getting a fresh object to mutate; a heap
 * of shared parsed objects would silently break that contract (callers do mutate DAO results). Hash reads
 * are answered with defensive copies for the same reason.
 *
 * <p><b>Memory</b>: deliberately unbounded (accepted trade -- entries are the same JSON the shared cache
 * holds, a few MB). No sweeper threads: expiry is lazy-on-read, and the whole map drops on
 * {@link #clearNamespace} or when tuning disables the cache.
 *
 * <p><b>Tuning</b> comes from the admin settings ({@code cache.near.ttlSeconds} /
 * {@code cache.near.checkSeconds}) -- but settings reads flow through ConfigDAO, which sits ON TOP of this
 * cache, so the read path must never consult them (the {@link RefreshPermits} dependency-cycle rule).
 * Instead the values live in volatile fields seeded from compile-time defaults, re-synced OUTSIDE the read
 * path: the admin Settings save pushes {@link #resyncTuning()} directly, and a lazy background re-sync
 * (piggybacked on reads, {@value #TUNING_SYNC_MILLIS}ms apart) catches script-side edits. The sysprops
 * {@value #TTL_SYSPROP} / {@value #CHECK_SYSPROP} PIN a value against both -- the emergency lever that
 * works even when the settings table is unreachable ({@code -Dtrip.cache.near.ttlSeconds=0} disables the
 * near-cache outright).
 */
@Slf4j
public final class NearCacheClient implements CacheClient {

    public static final String TTL_SYSPROP = "trip.cache.near.ttlSeconds";
    public static final String CHECK_SYSPROP = "trip.cache.near.checkSeconds";

    /**
     * Since event-driven invalidation became the primary freshness mechanism, the health check is only
     * the convergence path for ordinary writes from OTHER JVMs (CLI tools, a future second task -- plain
     * write-through does not broadcast) and for missed broadcasts; the TTL is the absolute backstop
     * should both paths fail. Hence hours/minutes, not minutes/seconds.
     */
    static final long DEFAULT_TTL_SECONDS = 24L * 60 * 60;
    static final long DEFAULT_CHECK_SECONDS = 300L;
    private static final long TUNING_SYNC_MILLIS = 60_000L;

    /** Only keys in the shared data namespace are ever near-cached (never sessions, chat, login codes). */
    private static final String CACHEABLE_PREFIX = CacheKeys.FORMAT_VERSION;

    /** Marks a cached "the delegate answered empty" for point reads (CHM values cannot be null). */
    private static final Object MISS = new Object();

    private static final String OP_VALUE = "v";
    private static final String OP_HASH = "h";
    private static final String OP_FIELDS = "f:";
    /** Joins field names into an op key; a control character, so no realistic field name collides. */
    private static final String FIELD_SEP = String.valueOf((char) 1);

    private final CacheClient delegate;
    /** Reads {ttlSeconds, checkSeconds} through the DAL with {@link Cached#NO}; null when unavailable. */
    private final Supplier<long[]> tuningReader;
    private final Supplier<Long> clock;

    private final ConcurrentHashMap<String, KeyEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong lastTuningSync = new AtomicLong();

    private final boolean ttlPinned = System.getProperty(TTL_SYSPROP) != null;
    private final boolean checkPinned = System.getProperty(CHECK_SYSPROP) != null;
    private volatile long ttlMillis = sysprop(TTL_SYSPROP, DEFAULT_TTL_SECONDS) * 1000L;
    private volatile long checkMillis = sysprop(CHECK_SYSPROP, DEFAULT_CHECK_SECONDS) * 1000L;

    public NearCacheClient(final CacheClient delegate, final Supplier<long[]> tuningReader) {
        this(delegate, tuningReader, System::currentTimeMillis);
    }

    /** Test seam: injectable clock, same pattern as the typed caches. */
    NearCacheClient(final CacheClient delegate, final Supplier<long[]> tuningReader, final Supplier<Long> clock) {
        this.delegate = delegate;
        this.tuningReader = tuningReader;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------- cacheable reads

    @Override
    public Optional<String> getValue(final String key) {
        if (!cacheable(key)) {
            return delegate.getValue(key);
        }
        return unwrapValue(cachedResult(key, OP_VALUE, () -> wrapValue(delegate.getValue(key))));
    }

    @Override
    public Map<String, String> getHash(final String key) {
        if (!cacheable(key)) {
            return delegate.getHash(key);
        }
        return copyOf(cachedResult(key, OP_HASH, () -> delegate.getHash(key)));
    }

    @Override
    public Map<String, String> getHashFields(final String key, final Collection<String> fields) {
        if (!cacheable(key)) {
            return delegate.getHashFields(key, fields);
        }
        return copyOf(cachedResult(key, fieldsOp(fields), () -> delegate.getHashFields(key, fields)));
    }

    // -------------------------------------------------------------------------- invalidating writes

    @Override
    public boolean putValue(final String key, final String value, final Duration ttl) {
        return invalidateAfter(key, delegate.putValue(key, value, ttl));
    }

    @Override
    public boolean removeKey(final String key) {
        return invalidateAfter(key, delegate.removeKey(key));
    }

    @Override
    public boolean putHashField(final String key, final String field, final String value) {
        return invalidateAfter(key, delegate.putHashField(key, field, value));
    }

    @Override
    public boolean putHashFields(final String key, final Map<String, String> fields) {
        return invalidateAfter(key, delegate.putHashFields(key, fields));
    }

    @Override
    public boolean removeHashField(final String key, final String field) {
        return invalidateAfter(key, delegate.removeHashField(key, field));
    }

    @Override
    public boolean addSetMembers(final String key, final Collection<String> members) {
        return invalidateAfter(key, delegate.addSetMembers(key, members));
    }

    @Override
    public boolean addSortedSetEntries(final String key, final Collection<String> entries) {
        return invalidateAfter(key, delegate.addSortedSetEntries(key, entries));
    }

    @Override
    public boolean removeSortedSetEntries(final String key, final Collection<String> entries) {
        return invalidateAfter(key, delegate.removeSortedSetEntries(key, entries));
    }

    @Override
    public boolean addScoredEntries(final String key, final Map<String, Double> memberScores) {
        return invalidateAfter(key, delegate.addScoredEntries(key, memberScores));
    }

    @Override
    public boolean removeSetMember(final String key, final String member) {
        return invalidateAfter(key, delegate.removeSetMember(key, member));
    }

    @Override
    public boolean expire(final String key, final Duration ttl) {
        return invalidateAfter(key, delegate.expire(key, ttl));
    }

    @Override
    public Optional<Long> increment(final String key, final long delta, final Duration ttl) {
        final Optional<Long> result = delegate.increment(key, delta, ttl);
        entries.remove(key);
        return result;
    }

    @Override
    public boolean trimSortedSet(final String key, final int maxSize) {
        return invalidateAfter(key, delegate.trimSortedSet(key, maxSize));
    }

    @Override
    public boolean clearNamespace(final String prefix) {
        final boolean result = delegate.clearNamespace(prefix);
        dropLocalNamespace(prefix);
        return result;
    }

    /**
     * Drops heap entries under {@code prefix} WITHOUT touching the delegate -- the remote-invalidation
     * path. When another instance broadcasts on {@code CacheKeys.CACHE_INVAL_CHANNEL} it has already
     * cleared the shared cache; each subscriber only needs to forget its own heap copies (re-clearing
     * Valkey here would SCAN it once per instance and could race a concurrent write-through).
     */
    public void dropLocalNamespace(final String prefix) {
        entries.keySet().removeIf(key -> key.startsWith(prefix));
    }

    // ------------------------------------------------------------------- forwarded (never cached)

    @Override
    public List<String> getSortedSetByPrefix(final String key, final String prefix, final int limit) {
        return delegate.getSortedSetByPrefix(key, prefix, limit);
    }

    @Override
    public List<String> getRangeByScore(
            final String key, final double minScore, final double maxScore, final boolean reverse, final int limit) {
        return delegate.getRangeByScore(key, minScore, maxScore, reverse, limit);
    }

    @Override
    public Set<String> getSetMembers(final String key) {
        return delegate.getSetMembers(key);
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
    public boolean publish(final String channel, final String payload) {
        return delegate.publish(channel, payload);
    }

    @Override
    public AutoCloseable subscribe(final Collection<String> channels, final BiConsumer<String, String> onMessage) {
        return delegate.subscribe(channels, onMessage);
    }

    @Override
    public void close() {
        entries.clear();
        delegate.close();
    }

    // ----------------------------------------------------------------------------------- tuning

    /**
     * Re-reads the two tuning settings through the DAL and applies them (sysprop-pinned values excluded).
     * Called synchronously by the admin Settings save, and by the lazy background re-sync. Never called
     * from the cache read path itself -- that is the recursion the volatile fields exist to prevent.
     */
    public void resyncTuning() {
        if (tuningReader == null) {
            return;
        }
        final long[] tuning;
        try {
            tuning = tuningReader.get();
        } catch (final RuntimeException ex) {
            log.warn("Near-cache tuning re-sync failed; keeping ttlMillis={} checkMillis={}",
                    ttlMillis, checkMillis, ex);
            return;
        }
        if (tuning == null || tuning.length != 2) {
            return;
        }
        applyTuning(tuning[0], tuning[1]);
    }

    private void applyTuning(final long ttlSeconds, final long checkSeconds) {
        if (!checkPinned) {
            checkMillis = checkSeconds * 1000L;
        }
        if (ttlPinned) {
            return;
        }
        final long newTtlMillis = ttlSeconds * 1000L;
        final boolean disabling = newTtlMillis <= 0 && ttlMillis > 0;
        ttlMillis = newTtlMillis;
        if (disabling) {
            // Nothing may keep serving from a cache the administrator just turned off.
            entries.clear();
        }
    }

    /** Test hooks: the effective tuning, without reaching into the volatiles by reflection. */
    long effectiveTtlMillis() {
        return ttlMillis;
    }

    long effectiveCheckMillis() {
        return checkMillis;
    }

    // ------------------------------------------------------------------------------- internals

    private boolean cacheable(final String key) {
        return ttlMillis > 0 && key.startsWith(CACHEABLE_PREFIX) && NearCacheContext.cacheAllowed();
    }

    /**
     * The near-cache read: expire-on-read, then health-check, then serve (loading through on first use).
     * The loader runs inside {@code computeIfAbsent}, so concurrent first reads of the same (key, op)
     * single-flight on the map bin rather than racing the delegate.
     */
    private Object cachedResult(final String key, final String opKey, final Supplier<Object> loader) {
        final long now = clock.get();
        final KeyEntry current = entries.get(key);
        if (current != null && now - current.loadedAt >= ttlMillis) {
            entries.remove(key, current);
        }
        final KeyEntry entry = entries.computeIfAbsent(key, k -> new KeyEntry(now));
        maybeHealthCheck(key, entry, now);
        maybeSyncTuning(now);
        return entry.results.computeIfAbsent(opKey, op -> loader.get());
    }

    /**
     * The user-specified herd control: the check timestamp is reset BEFORE the check runs, so of N
     * concurrent hits on a stale entry exactly one wins the CAS and refreshes; the rest (and the winner)
     * return the cached value immediately.
     */
    private void maybeHealthCheck(final String key, final KeyEntry entry, final long now) {
        final long last = entry.lastChecked.get();
        if (now - last < checkMillis || !entry.lastChecked.compareAndSet(last, now)) {
            return;
        }
        if (!RefreshPermits.tryAcquire()) {
            // At the global refresh cap: skip -- the timer is already reset, a later read re-triggers.
            return;
        }
        TripThreads.start(() -> refreshEntry(key, entry));
    }

    /**
     * Background health check: replays every operation this entry has answered against the delegate and
     * swaps in a rebuilt entry. The swap is a compare-and-replace on the ORIGINAL entry, so a write
     * invalidation that lands mid-refresh wins -- the refresh result is discarded rather than
     * resurrecting data a write just dropped.
     */
    private void refreshEntry(final String key, final KeyEntry entry) {
        try {
            final KeyEntry rebuilt = new KeyEntry(clock.get());
            for (final String opKey : entry.results.keySet()) {
                rebuilt.results.put(opKey, replay(key, opKey));
            }
            entries.replace(key, entry, rebuilt);
        } catch (final RuntimeException ex) {
            log.warn("Near-cache health check failed for '{}'; serving the cached entry until the TTL", key, ex);
        } finally {
            RefreshPermits.release();
        }
    }

    /** Re-executes the delegate operation an op key stands for; op keys carry their own parameters. */
    private Object replay(final String key, final String opKey) {
        if (OP_VALUE.equals(opKey)) {
            return wrapValue(delegate.getValue(key));
        }
        if (OP_HASH.equals(opKey)) {
            return delegate.getHash(key);
        }
        return delegate.getHashFields(key, List.of(opKey.substring(OP_FIELDS.length()).split(FIELD_SEP, -1)));
    }

    /** Lazy settings re-sync, spawned off the read path at most once per {@link #TUNING_SYNC_MILLIS}. */
    private void maybeSyncTuning(final long now) {
        if (tuningReader == null) {
            return;
        }
        final long last = lastTuningSync.get();
        if (now - last < TUNING_SYNC_MILLIS || !lastTuningSync.compareAndSet(last, now)) {
            return;
        }
        TripThreads.start(this::resyncTuning);
    }

    private static String fieldsOp(final Collection<String> fields) {
        return OP_FIELDS + String.join(FIELD_SEP, fields);
    }

    private static Object wrapValue(final Optional<String> value) {
        return value.isPresent() ? value.get() : MISS;
    }

    private static Optional<String> unwrapValue(final Object cached) {
        return cached == MISS ? Optional.empty() : Optional.of((String) cached);
    }

    /** Hash results leave as defensive copies: callers own what a CacheClient hands them. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> copyOf(final Object cached) {
        return new HashMap<>((Map<String, String>) cached);
    }

    private boolean invalidateAfter(final String key, final boolean result) {
        // Remove AFTER the delegate write: a read racing the write may briefly re-cache the old value,
        // but removing first would let it re-cache the old value AFTER our removal -- strictly worse.
        entries.remove(key);
        return result;
    }

    private static long sysprop(final String name, final long fallback) {
        final String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (final NumberFormatException ex) {
            log.warn("Ignoring unparseable {}='{}'", name, raw);
            return fallback;
        }
    }

    /** One cached key: every operation result answered for it, its birth time, and its check clock. */
    private static final class KeyEntry {
        private final ConcurrentHashMap<String, Object> results = new ConcurrentHashMap<>(4);
        private final long loadedAt;
        private final AtomicLong lastChecked;

        private KeyEntry(final long now) {
            this.loadedAt = now;
            this.lastChecked = new AtomicLong(now);
        }
    }
}
