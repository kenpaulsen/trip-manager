package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * A partition-keyed cache (one Valkey hash per partition, field = entity id, value = entity JSON) whose partitions
 * are all populated together by a single whole-table scan. Reading one partition is a single {@code HGETALL}.
 * Blocking since the virtual-threads port; background rebuilds run on spawned virtual threads.
 *
 * <p>Unlike {@link PartitionCache} -- which loads each partition on demand and therefore needs an efficient
 * per-partition database query -- this template is for data whose partition key is <em>not</em> the database key
 * (so a single partition cannot be queried directly). The whole table is scanned once on a cold cache (foreground,
 * lock-guarded, answered from the loaded snapshot so it works even when cache writes are discarded), and once per
 * {@link CacheKeys#INDEX_SOFT_TTL} in the background thereafter. A single {@code loaded} marker covers every
 * partition. Write-through ({@link #put}) keeps partitions current between scans; point lookups
 * ({@link #getOne}) fall back to a supplied loader (a database key lookup) on a miss.</p>
 *
 * <p><b>A rebuild is a compare-and-set against a snapshot taken BEFORE the scan</b> (2026-11; the same shape as
 * {@link PartitionCache}'s reload and {@code TripIndex.rebuildIndexes}). The scan is eventually consistent and
 * takes time, and a write-through or a {@link #removeOne} can land while it runs: the scan's copy of that row is
 * then OLDER than the cache's. So a rebuild writes a scanned row only when the cached field is still what the
 * snapshot saw, and removes a cached field the scan no longer returns only under the same condition. Before
 * this, every rebuild overwrote unconditionally (a revoked privilege came back for a day, and the next grant
 * read the stale holders and wrote the revoked person back into the table) and never removed anything (a
 * deleted content block a racing rebuild had put back was served until someone cleared the caches). The
 * snapshot needs to know which partitions exist, so each one is listed in a registry set next to the loaded
 * marker; a partition the registry has not seen yet is overwritten as before, once.</p>
 *
 * <p><b>A cache dropped during the scan is a cold load, not a mass removal.</b> {@link #invalidate()}, the
 * admin Clear caches and a namespace clear all leave every snapshotted field missing, which the per-field
 * compare alone would read as "each of these rows was removed while the scan ran". It would write none of
 * them and still mark the cache loaded: privileges would read as held by nobody for a day, and the next grant
 * would save a row holding only the new person. Every such drop takes the registry with it, so the snapshot
 * plants an epoch member in the registry and the merge trusts the snapshot only while that member is still
 * there.</p>
 *
 * @param <V> the cached entity type.
 */
@Slf4j
@Builder
public final class PartitionScanCache<V> {
    private final CacheClient cache;
    /** Prefix for the per-partition hash keys ({@code keyPrefix + partition}). */
    private final String keyPrefix;
    /** Key of the single "loaded at" marker covering all partitions. */
    private final String loadedKey;
    @Builder.Default
    private final Duration softTtl = CacheKeys.INDEX_SOFT_TTL;
    @Builder.Default
    private final boolean softRevalidate = true;
    @Builder.Default
    private final Duration gcTtl = CacheKeys.GC_TTL;
    /**
     * Full build: every live entity, from a STRONGLY CONSISTENT scan. A rebuild removes a cached row the scan
     * does not return, and the snapshot it compares against only protects a write that lands DURING the scan:
     * a row written just before it, which an eventually consistent scan may not see yet, would be removed and
     * the cache marked loaded without it (a new trip's creator would hold no role until the next rebuild).
     * These are small tables scanned once per soft TTL, so the doubled read cost is nothing.
     */
    private final Supplier<List<V>> loader;
    /** Entity -> its partition. */
    private final Function<V, String> partitioner;
    /** Entity -> its field id within the partition hash. */
    private final Function<V, String> fielder;
    private final Function<V, String> serializer;
    private final Function<String, V> deserializer;
    @Builder.Default
    private final Supplier<Long> clock = System::currentTimeMillis;
    /** Injectable jitter source in [0,1) for tests; production uses {@link Revalidator#randomJitter()}. */
    @Builder.Default
    private final Supplier<Double> ttlJitter = Revalidator::randomJitter;

    /** Suffix of the registry set (next to {@code loadedKey}) naming every partition hash that holds rows. */
    private static final String PARTITIONS_SUFFIX = ":parts";
    /**
     * Prefix of the registry's epoch member. A partition key starts with {@code keyPrefix}, which is a cache
     * key prefix and so never begins with this.
     */
    private static final String EPOCH_PREFIX = "epoch#";

    /** In-process single-flight for the (single) background rebuild; keyed by {@code loadedKey}. */
    private final ConcurrentHashMap<String, Boolean> refreshing = new ConcurrentHashMap<>();

    /** All entities in the given partition (unordered; caller applies display order). */
    public List<V> getPartition(final String partition) {
        return ensureAndQuery(
                all -> all.stream().filter(v -> partition.equals(partitioner.apply(v))).toList(),
                () -> deserializeValues(cache.getHash(keyPrefix + partition)));
    }

    /**
     * A single entity by (partition, field). Served from the partition hash; on a miss it consults {@code loadedKey}
     * (present == authoritative "not found") and otherwise falls back to {@code pointLoader} (a database key lookup),
     * caching the result.
     */
    public Optional<V> getOne(
            final String partition, final String field, final Supplier<Optional<V>> pointLoader) {
        final String json = cache.getHashFields(keyPrefix + partition, List.of(field)).get(field);
        if (json != null) {
            maybeRevalidateOnHit();
            return Optional.ofNullable(deserializer.apply(json));
        }
        if (cache.getValue(loadedKey).isPresent()) {
            return Optional.empty();
        }
        final Optional<V> loaded = pointLoader.get();
        loaded.ifPresent(this::put);
        return loaded;
    }

    /** Write-through of a single entity into its partition hash. */
    public boolean put(final V value) {
        final String key = keyPrefix + partitioner.apply(value);
        final String json = serializer.apply(value);
        if (json == null) {
            // Serializer regression (the DAO serializers answer null on failure, like the other cache
            // templates' contracts). Drop the possibly-stale cached entry surgically rather than NPE after
            // the database write already succeeded -- invalidate() is unsafe in local mode (see removeOne).
            log.warn("Serializer returned null for '{}'; dropping the cached entry instead", key);
            cache.removeHashField(key, fielder.apply(value));
            return false;
        }
        final boolean ok = cache.putHashField(key, fielder.apply(value), json);
        cache.expire(key, gcTtl);
        if (softRevalidate) {
            // So the next rebuild snapshots this partition too, even when this write is what created it.
            cache.addSetMembers(partitionsKey(), List.of(key));
        }
        return ok;
    }

    private String partitionsKey() {
        return loadedKey + PARTITIONS_SUFFIX;
    }

    /**
     * Whether the partitions are known to hold the WHOLE table, i.e. a scan populated them and left the loaded
     * marker behind. Without the marker the hashes hold only whatever write-throughs happened to land in them,
     * which is an incomplete answer for any caller that must see every row: with soft revalidate off (local
     * mode) nothing ever rebuilds them, so one save after a cache clear leaves a partition holding exactly that
     * one entity. Such a caller reads this and falls back to its own table scan.
     */
    public boolean isAuthoritative() {
        return cache.getValue(loadedKey).isPresent();
    }

    /** Drops every partition, the registry and the loaded marker (next read rebuilds). Admin/test path. */
    public boolean invalidate() {
        cache.clearNamespace(keyPrefix);
        cache.removeKey(partitionsKey());
        return cache.removeKey(loadedKey);
    }

    /**
     * Surgical removal of one entity from its partition hash — the delete-path counterpart of {@link #put}.
     *
     * <p>This exists because {@link #invalidate()} is NOT safe everywhere: in local mode the cache client is
     * the in-memory datastore itself (soft revalidate is off precisely because a rebuild would read an empty
     * loader and wipe it), so a delete that invalidates throws away every OTHER entity too. Removing exactly
     * the deleted field is correct in both modes, and in production the shared hash propagates it to every
     * instance at once.
     */
    public boolean removeOne(final String partition, final String field) {
        return cache.removeHashField(keyPrefix + partition, field);
    }

    private List<V> deserializeValues(final Map<String, String> hash) {
        final List<V> result = new ArrayList<>(hash.size());
        for (final String json : hash.values()) {
            final V value = deserializer.apply(json);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private List<V> ensureAndQuery(
            final Function<List<V>, List<V>> inMemoryAnswer, final Supplier<List<V>> cacheAnswer) {
        final Optional<String> loadedAt = cache.getValue(loadedKey);
        if (loadedAt.isEmpty() && softRevalidate) {
            return buildAndAnswer(inMemoryAnswer);
        }
        maybeScheduleRebuild(loadedAt.orElse(null));
        return cacheAnswer.get();
    }

    /**
     * A {@link #getOne} hit previously never revalidated, so an entity read only via point lookups stayed
     * stale until {@link CacheKeys#GC_TTL} (7 days). Consult the marker inline (heap-served for Cached.YES
     * readers) and schedule the shared rebuild when it is present and stale; an absent marker keeps the old
     * semantics -- the hash holds only write-through entries, and a foreground scan on a point read would
     * be a regression.
     */
    private void maybeRevalidateOnHit() {
        if (!softRevalidate) {
            return;
        }
        cache.getValue(loadedKey).ifPresent(this::maybeScheduleRebuild);
    }

    private List<V> buildAndAnswer(final Function<List<V>, List<V>> inMemoryAnswer) {
        final String lockKey = CacheKeys.refreshLockKey(loadedKey);
        final Snapshot before = snapshot();
        final List<V> all = loader.get();
        if (cache.tryAcquireLock(lockKey, CacheKeys.REFRESH_LOCK_TTL)) {
            try {
                populate(all, before);
            } catch (final RuntimeException ex) {
                logIfFailed(ex);
            } finally {
                cache.releaseLock(lockKey);
            }
        }
        return inMemoryAnswer.apply(all);
    }

    /** The background rebuild: snapshot, scan, then merge the scan in against the snapshot. */
    private boolean rebuild() {
        final Snapshot before = snapshot();
        return populate(loader.get(), before);
    }

    /**
     * What a rebuild compares against after its scan: every registered partition's hash as it stands now, and
     * the registry epoch they were read under.
     */
    private record Snapshot(String epoch, Map<String, Map<String, String>> partitions) {
    }

    private Snapshot snapshot() {
        final Map<String, Map<String, String>> partitions = new HashMap<>();
        String epoch = null;
        for (final String member : cache.getSetMembers(partitionsKey())) {
            if (member.startsWith(EPOCH_PREFIX)) {
                epoch = member;
            } else {
                partitions.put(member, cache.getHash(member));
            }
        }
        return new Snapshot((epoch == null) ? plantEpoch() : epoch, partitions);
    }

    /**
     * Starts a registry epoch. A set member rather than a key of its own, so that it goes exactly when the
     * registry goes, and so that two rebuilds planting at once both find their own member afterward (a
     * last-writer-wins key would make the loser see a drop that never happened).
     */
    private String plantEpoch() {
        final String epoch = EPOCH_PREFIX + UUID.randomUUID();
        cache.addSetMembers(partitionsKey(), List.of(epoch));
        cache.expire(partitionsKey(), gcTtl);
        return epoch;
    }

    private boolean populate(final List<V> all, final Snapshot snapshot) {
        final Map<String, Map<String, String>> scanned = new HashMap<>();
        for (final V v : all) {
            scanned.computeIfAbsent(keyPrefix + partitioner.apply(v), k -> new HashMap<>())
                    .put(fielder.apply(v), serializer.apply(v));
        }
        // The epoch is gone: the cache was dropped while the scan ran (see the class comment), and the scan
        // is merged as onto an empty cache. An empty baseline rather than none, so a write-through that has
        // landed since the drop is still not overwritten by the scan's older copy of that row.
        final boolean dropped = !cache.getSetMembers(partitionsKey()).contains(snapshot.epoch());
        final Map<String, Map<String, String>> before = dropped ? Map.of() : snapshot.partitions();
        // Partitions the scan no longer returns are visited too: their rows are what has to go.
        final List<String> keys = new ArrayList<>(scanned.keySet());
        before.keySet().stream().filter(key -> !scanned.containsKey(key)).forEach(keys::add);
        for (final String key : keys) {
            mergePartition(key, scanned.getOrDefault(key, Map.of()), dropped ? Map.of() : before.get(key));
        }
        if (!scanned.isEmpty()) {
            cache.addSetMembers(partitionsKey(), scanned.keySet());
            cache.expire(partitionsKey(), gcTtl);
        }
        return markLoaded();
    }

    /**
     * Brings one partition hash in line with the scan. {@code before} is the partition as the snapshot saw it
     * (empty when the cache was dropped during the scan), or null for a partition the registry did not know
     * yet, which is overwritten outright (there is nothing to compare against, and nothing is removed from it).
     */
    private void mergePartition(final String key, final Map<String, String> scanned,
            final Map<String, String> before) {
        if (before == null) {
            cache.putHashFields(key, scanned);
            cache.expire(key, gcTtl);
            return;
        }
        final Map<String, String> now = cache.getHash(key);
        final Map<String, String> unchanged = new HashMap<>();
        for (final Map.Entry<String, String> row : scanned.entrySet()) {
            if (java.util.Objects.equals(before.get(row.getKey()), now.get(row.getKey()))) {
                unchanged.put(row.getKey(), row.getValue());
            }
        }
        if (!unchanged.isEmpty()) {
            cache.putHashFields(key, unchanged);
        }
        int left = now.size();
        for (final Map.Entry<String, String> cached : now.entrySet()) {
            // Gone from the table, and nobody wrote it through while the scan ran: the cache drops it too.
            if (!scanned.containsKey(cached.getKey()) && cached.getValue().equals(before.get(cached.getKey()))) {
                cache.removeHashField(key, cached.getKey());
                left--;
            }
        }
        if (scanned.isEmpty() && left <= 0) {
            cache.removeSetMember(partitionsKey(), key);
        } else {
            cache.expire(key, gcTtl);
        }
    }

    private boolean markLoaded() {
        return cache.putValue(loadedKey, String.valueOf(clock.get()), gcTtl);
    }

    private void maybeScheduleRebuild(final String loadedAtRaw) {
        final Revalidator revalidator = new Revalidator(cache, softTtl, clock, ttlJitter);
        if (!softRevalidate || !revalidator.isSoftStale(loadedAtRaw)) {
            return;
        }
        revalidator.schedule(refreshing, loadedKey, loadedKey, "partition-scan", this::rebuild);
    }

    private void logIfFailed(final Throwable ex) {
        if (ex != null) {
            log.error("Partition-scan cache build failed for '{}'", keyPrefix, ex);
        }
    }
}
