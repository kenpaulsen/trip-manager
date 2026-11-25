package org.paulsens.trip.cache;

import java.lang.ref.SoftReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Prefix-search template, PARTITIONED by the first {@link #PREFIX_LENGTH} characters of each token: one
 * lexicographic sorted set of {@code token|entityId} entries per partition ({@code <key>:<partition>}), plus ONE
 * hash ({@code <key>:parts}) whose field per partition holds the epoch millis that partition was last fully loaded.
 * Reads are a single ZRANGEBYLEX on the query's own partition; writers diff-update their entity's entries.
 *
 * <p>Why partitioned (2026-11-24): the single-key index was written by ONE ZADD of every entry in the directory
 * (megabytes once a directory reaches a few thousand people); it could exceed the 3 s command timeout, and then its
 * loaded marker was never set and EVERY search rescanned the table. A partition is a few hundred entries, written
 * in {@link #WRITE_BATCH} chunks, so no command is large -- and partitions of at most 128 entries use Valkey's
 * compact listpack encoding.</p>
 *
 * <p>Loaded markers are PER PARTITION and set only after that partition's writes succeeded (owner decision
 * 2026-11-24): a search trusts its partition only when its field is present, so a partial load can never make an
 * entity look nonexistent -- an unloaded partition is answered from the loader (a table scan, always correct) and
 * written for next time. A field also counts only while younger than {@code gcTtl}: every write to a partition
 * pushes the partition key's expiry to at least the field's stamp plus {@code gcTtl}, so a field can never outlive
 * the entries it vouches for. The same hash is the partition registry: {@link #invalidate} and the daily reconcile
 * enumerate it (no SCAN). Partitions are marked even when empty, so a query nobody matches does not rescan
 * until the daily rebuild drops that field; a cold load is single-flight and reused for {@code reuseWindow}, so a
 * burst of never-seen prefixes costs one scan.</p>
 *
 * <p>Soft revalidation: when the searched partition's field is older than {@link CacheKeys#INDEX_SOFT_TTL}, a
 * lock-guarded background rebuild reloads every partition and reconciles away entries the loader no longer
 * produces (the self-heal for direct database edits).</p>
 *
 * <p>Caller contract: resolve the returned ids to entities and drop any that no longer match the query --
 * entries can be stale between rebuilds (e.g. a person renamed by a direct database edit).</p>
 */
@Slf4j
@Builder
public final class SearchIndex {
    /**
     * Characters that pick a token's partition, and so the shortest prefix that can be searched. Tokens shorter
     * than this are never stored.
     */
    public static final int PREFIX_LENGTH = 2;
    /** Upper bound of raw entries fetched per prefix query (ids then dedupe to fewer). */
    private static final int RANGE_FETCH_LIMIT = 500;
    /** Ceiling when reading a whole partition for reconcile. */
    private static final int RECONCILE_FETCH_LIMIT = 1_000_000;
    /** Most entries sent in one ZADD/ZREM: keeps every command small, well inside the command timeout. */
    static final int WRITE_BATCH = 1_000;
    private static final String PARTS_SUFFIX = ":parts";

    private final CacheClient cache;
    private final String key;
    @Builder.Default
    private final Duration softTtl = CacheKeys.INDEX_SOFT_TTL;
    @Builder.Default
    private final boolean softRevalidate = true;
    @Builder.Default
    private final Duration gcTtl = CacheKeys.GC_TTL;
    /** Full build: every live entity id mapped to its (already normalized) search tokens. */
    private final Supplier<Map<String, Set<String>>> loader;
    @Builder.Default
    private final Supplier<Long> clock = System::currentTimeMillis;
    /** Injectable jitter source in [0,1) for tests; production uses {@link Revalidator#randomJitter()}. */
    @Builder.Default
    private final Supplier<Double> ttlJitter = Revalidator::randomJitter;
    /**
     * How long one full load answers further cold searches. A prefix nobody has is a partition with no stamp,
     * and any two characters make one, so without reuse a burst of made-up prefixes (a script replaying a Find
     * button) would scan the person table once each.
     */
    @Builder.Default
    private final Duration reuseWindow = Duration.ofSeconds(60);

    /** In-process single-flight for the (single) background rebuild; keyed by the index key. */
    private final ConcurrentHashMap<String, Boolean> refreshing = new ConcurrentHashMap<>();
    /** Single-flight for the foreground load: concurrent cold searches wait for one scan, not one scan each. */
    private final ReentrantLock loadLock = new ReentrantLock();
    /**
     * The last foreground load, reused for {@link #reuseWindow}. Soft, so the heap can reclaim a directory-sized
     * map that nobody is reading; a cleared reference just means the next cold search scans.
     */
    private final AtomicReference<SoftReference<Load>> lastLoad = new AtomicReference<>(new SoftReference<>(null));
    private final AtomicLong coveringClears = new AtomicLong();

    /**
     * One full load: when it was read, its entries by partition, and whether THIS call ran the scan (only that
     * call hands the load to the background fill; a reuse never does).
     */
    private record Load(long at, Map<String, List<String>> parts, boolean scanned) {
        Load reused() {
            return new Load(at, parts, false);
        }
    }

    /** Normalizes a raw field or query into a search token (lowercase, trimmed, separator stripped). */
    public static String normalizeToken(final String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replace(CacheKeys.SEARCH_SEPARATOR, ' ');
    }

    /**
     * Returns entity ids whose tokens start with {@code rawPrefix} (deduped, index order). A prefix shorter than
     * {@link #PREFIX_LENGTH} matches nothing. May answer from a table scan when the prefix's partition is not
     * loaded, or schedule a background rebuild when it is soft-stale.
     */
    public List<String> searchIds(final String rawPrefix, final int limit) {
        final String prefix = normalizeToken(rawPrefix);
        if (prefix.length() < PREFIX_LENGTH) {
            return List.of();
        }
        final String partition = partitionOf(prefix);
        if (!softRevalidate) {
            return rangeIds(partition, prefix, limit);
        }
        final Optional<Long> loadedAt = loadedAt(partition);
        if (loadedAt.isEmpty()) {
            return loadPartitionAndAnswer(partition, prefix, limit);
        }
        maybeScheduleRebuild(loadedAt.get());
        return rangeIds(partition, prefix, limit);
    }

    /**
     * Write-through diff: removes entries for tokens the entity no longer has and adds entries for tokens it did
     * not have. Pass an empty {@code newTokens} to remove the entity entirely. Only CHANGED tokens are written:
     * an entity's tokens span a dozen or more partitions, and re-adding all of them cost one ZADD and one EXPIRE
     * per partition on every save (about 30 serial round trips for a profile edit that changed no name). Drift
     * from an earlier failed write is healed by the daily rebuild instead. Writes into a partition that is not
     * loaded yet are harmless: its load re-adds everything.
     */
    public boolean update(final String id, final Set<String> oldTokens, final Set<String> newTokens) {
        final Set<String> stale = new HashSet<>(oldTokens);
        stale.removeAll(newTokens);
        final Set<String> added = new HashSet<>(newTokens);
        added.removeAll(oldTokens);
        boolean ok = true;
        for (final Map.Entry<String, List<String>> part : byPartition(stale, id).entrySet()) {
            ok &= cache.removeSortedSetEntries(partitionKey(part.getKey()), part.getValue());
        }
        for (final Map.Entry<String, List<String>> part : byPartition(added, id).entrySet()) {
            final String partKey = partitionKey(part.getKey());
            ok &= cache.addSortedSetEntries(partKey, part.getValue());
            cache.expire(partKey, gcTtl);
        }
        return ok;
    }

    /**
     * Drops every partition, the registry and the reusable load (the next search of each partition reloads it
     * from the table: invalidation exists to pick up out-of-band edits, which a reused load would hide).
     */
    public boolean invalidate() {
        lastLoad.set(new SoftReference<>(null));
        for (final String partition : cache.getHash(partsKey()).keySet()) {
            cache.removeKey(partitionKey(partition));
        }
        return cache.removeKey(partsKey());
    }

    /**
     * Forgets the reusable load when {@code prefixes} cover this index: what another instance's (or this one's
     * whole-cache) clear asks of every instance, since a load reused after a clear would hide the out-of-band
     * edits the clear was for. Only the in-memory load: the cleared keys are already gone.
     */
    public void forgetLoadIfCleared(final Collection<String> prefixes) {
        if (prefixes.stream().anyMatch(key::startsWith)) {
            lastLoad.set(new SoftReference<>(null));
            coveringClears.incrementAndGet();
        }
    }

    /**
     * How many clears covering this index reached it since start: observable for the tests that pin both ways a
     * clear arrives (this instance's own, and another's by broadcast), whether or not a load was held.
     */
    public long coveringClears() {
        return coveringClears.get();
    }

    private String partsKey() {
        return key + PARTS_SUFFIX;
    }

    /**
     * Dedup and lock key of a cold load's background fill: its own, not the daily rebuild's, so a fill in progress
     * never makes a due rebuild skip (the rebuild only re-triggers on a later stale read). The two may overlap: the
     * fill only adds entries and stamps unmarked partitions, which a later rebuild reconciles.
     */
    private String fillKey() {
        return key + ":fill";
    }

    private String partitionKey(final String partition) {
        return key + ":" + partition;
    }

    private static String partitionOf(final String token) {
        return token.substring(0, PREFIX_LENGTH);
    }

    /** The partition's load stamp, when present, parseable and younger than {@code gcTtl}. */
    private Optional<Long> loadedAt(final String partition) {
        // Always from Valkey, never the near cache, even inside a Cached.YES search: the stamp decides whether the
        // partition's entries (always read from Valkey) can be trusted, so a heap copy outliving lost Valkey data
        // would trust an empty partition and answer "nobody matches".
        return validStamp(NearCacheContext.call(Cached.NO,
                () -> cache.getHashFields(partsKey(), List.of(partition)).get(partition)));
    }

    /** A raw registry stamp, when present, parseable and younger than {@code gcTtl}. */
    private Optional<Long> validStamp(final String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            final long stamp = Long.parseLong(raw.trim());
            return (clock.get() - stamp < gcTtl.toMillis()) ? Optional.of(stamp) : Optional.empty();
        } catch (final NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /** Groups an entity's storable tokens into entries by partition; tokens too short to partition are dropped. */
    private static Map<String, List<String>> byPartition(final Collection<String> tokens, final String id) {
        return tokens.stream()
                .filter(token -> token.length() >= PREFIX_LENGTH)
                .collect(Collectors.groupingBy(SearchIndex::partitionOf, TreeMap::new,
                        Collectors.mapping(token -> token + CacheKeys.SEARCH_SEPARATOR + id, Collectors.toList())));
    }

    /** Every entry of a full load, grouped by partition. */
    private static Map<String, List<String>> partitioned(final Map<String, Set<String>> all) {
        final Map<String, List<String>> parts = new TreeMap<>();
        for (final Map.Entry<String, Set<String>> entity : all.entrySet()) {
            for (final Map.Entry<String, List<String>> part : byPartition(entity.getValue(), entity.getKey())
                    .entrySet()) {
                parts.computeIfAbsent(part.getKey(), partition -> new ArrayList<>()).addAll(part.getValue());
            }
        }
        return parts;
    }

    /** {@code entries} in consecutive slices of at most {@link #WRITE_BATCH}. */
    private static List<List<String>> chunks(final List<String> entries) {
        final List<List<String>> chunks = new ArrayList<>();
        for (int from = 0; from < entries.size(); from += WRITE_BATCH) {
            chunks.add(entries.subList(from, Math.min(from + WRITE_BATCH, entries.size())));
        }
        return chunks;
    }

    private static List<String> idsFromEntries(final List<String> entries, final int limit) {
        final Set<String> ids = new LinkedHashSet<>();
        for (final String entry : entries) {
            final int sep = entry.lastIndexOf(CacheKeys.SEARCH_SEPARATOR);
            if (sep > 0) {
                ids.add(entry.substring(sep + 1));
            }
            if (ids.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(ids);
    }

    private List<String> rangeIds(final String partition, final String prefix, final int limit) {
        return idsFromEntries(cache.getSortedSetByPrefix(partitionKey(partition), prefix, RANGE_FETCH_LIMIT), limit);
    }

    /**
     * Unloaded partition: answer from a full load (always correct, even when cache writes fail), write this one
     * partition for the next search, and hand the same load to a background fill of every other unloaded
     * partition, so a cold index costs one table scan rather than one per partition searched.
     */
    private List<String> loadPartitionAndAnswer(final String partition, final String prefix, final int limit) {
        final Load recent = recentLoad();
        final Load load = (recent != null) ? recent : freshLoad();
        final List<String> loaded = load.parts().getOrDefault(partition, List.of());
        writePartition(partition, loaded, load.at());
        expireRegistry();
        if (load.scanned()) {
            // After this partition is written and marked, so the fill skips it rather than writing it again.
            new Revalidator(cache, softTtl, clock, ttlJitter).schedule(refreshing, fillKey(), fillKey(),
                    "search-index fill", () -> fillUnloaded(load.parts()));
        }
        // Write-through made after the load exists only in the partition key, so the answer reads both.
        final Set<String> entries = new TreeSet<>(cache.getSortedSetByPrefix(partitionKey(partition), prefix,
                RANGE_FETCH_LIMIT));
        loaded.stream().filter(entry -> entry.startsWith(prefix)).forEach(entries::add);
        return idsFromEntries(new ArrayList<>(entries), limit);
    }

    /** The last load while it is younger than {@link #reuseWindow}, else null. */
    private Load recentLoad() {
        final Load last = lastLoad.get().get();
        return (last != null && clock.get() - last.at() < reuseWindow.toMillis()) ? last.reused() : null;
    }

    /** A full load under {@link #loadLock}: a search that waited on another's scan reuses it. */
    private Load freshLoad() {
        loadLock.lock();
        try {
            final Load recent = recentLoad();
            if (recent != null) {
                return recent;
            }
            final Load load = new Load(clock.get(), partitioned(loader.get()), true);
            lastLoad.set(new SoftReference<>(load));
            return load;
        } finally {
            loadLock.unlock();
        }
    }

    /**
     * Background half of a cold load: writes each partition of {@code parts} not already marked loaded. The
     * registry is read once for the whole fill, not once per partition (several hundred of them).
     */
    private void fillUnloaded(final Map<String, List<String>> parts) {
        final long stamp = clock.get();
        final Map<String, String> stamps = cache.getHash(partsKey());
        for (final Map.Entry<String, List<String>> part : parts.entrySet()) {
            if (validStamp(stamps.get(part.getKey())).isEmpty()) {
                writePartition(part.getKey(), part.getValue(), stamp);
            }
        }
        expireRegistry();
    }

    /**
     * Adds a partition's entries in {@link #WRITE_BATCH} chunks and, only when every chunk succeeded, marks the
     * partition loaded. The key's expiry is pushed out BEFORE the mark, so the mark never outlives the entries.
     * The caller pushes out the registry's own expiry once ({@link #expireRegistry}) after all its writes.
     */
    private void writePartition(final String partition, final List<String> entries, final long stamp) {
        final String partKey = partitionKey(partition);
        for (final List<String> chunk : chunks(entries)) {
            if (!cache.addSortedSetEntries(partKey, chunk)) {
                log.warn("Search index partition '{}' not marked loaded: a write failed", partKey);
                return;
            }
        }
        cache.expire(partKey, gcTtl);
        cache.putHashField(partsKey(), partition, String.valueOf(stamp));
    }

    /**
     * Keeps the registry alive as long as the partitions it vouches for. Losing it early is safe (every partition
     * reads as unloaded and is answered from a scan), so it is refreshed once per load, not once per partition.
     */
    private void expireRegistry() {
        cache.expire(partsKey(), gcTtl);
    }

    private void maybeScheduleRebuild(final long loadedAt) {
        final Revalidator revalidator = new Revalidator(cache, softTtl, clock, ttlJitter);
        if (revalidator.isSoftStale(loadedAt)) {
            revalidator.schedule(refreshing, key, key, "search-index", this::rebuildIndex);
        }
    }

    /**
     * Daily self-heal. Snapshots each registered partition BEFORE the load (entries write-through adds meanwhile
     * are newer than the snapshot and so never removed), rewrites every partition the load produces, then removes
     * snapshot entries the load no longer produces. A registered partition the load no longer produces at all
     * (marked empty when a query nobody matched was searched, or whose last entity was deleted) is emptied and its
     * field DROPPED, not restamped: any two characters make a partition, so restamping would keep every made-up
     * prefix ever searched in the registry forever. Its next search loads it again (at most once per
     * {@link #reuseWindow}). Dropping, not keeping, the field also means its stale stamp never schedules this
     * rebuild again.
     */
    private void rebuildIndex() {
        final Map<String, List<String>> snapshot = new TreeMap<>();
        for (final String partition : cache.getHash(partsKey()).keySet()) {
            snapshot.put(partition, cache.getSortedSetByPrefix(partitionKey(partition), "", RECONCILE_FETCH_LIMIT));
        }
        final Map<String, List<String>> parts = partitioned(loader.get());
        final long stamp = clock.get();
        for (final Map.Entry<String, List<String>> part : parts.entrySet()) {
            writePartition(part.getKey(), part.getValue(), stamp);
            reconcile(part.getKey(), snapshot.getOrDefault(part.getKey(), List.of()), part.getValue());
        }
        for (final Map.Entry<String, List<String>> gone : snapshot.entrySet()) {
            if (!parts.containsKey(gone.getKey())) {
                reconcile(gone.getKey(), gone.getValue(), List.of());
                cache.removeHashField(partsKey(), gone.getKey());
            }
        }
        expireRegistry();
    }

    private void reconcile(final String partition, final List<String> before, final List<String> fresh) {
        final Set<String> keep = new HashSet<>(fresh);
        final List<String> stale = before.stream().filter(entry -> !keep.contains(entry)).toList();
        for (final List<String> chunk : chunks(stale)) {
            cache.removeSortedSetEntries(partitionKey(partition), chunk);
        }
    }
}
