package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.Singular;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-through/write-through template for the bindings adjacency shape: one shared-cache set per
 * {@code (source, destination-type)} direction plus a per-source loaded marker (epoch millis for soft revalidate).
 * Blocking since the virtual-threads port; background refreshes run on spawned virtual threads.
 *
 * <p>Soft revalidate is read-triggered (idle sources can stay soft-stale until next access). Full loads take a
 * <em>pre-load</em> snapshot of known dest-type sets, run the loader, then overlay and reconcile-delete members
 * present in the snapshot but absent from the loader (new write-through members are not in the pre-load snapshot
 * and are kept). Write-through does not refresh the marker epoch. {@link CacheKeys#GC_TTL} is hygiene only.
 * Duplicate refreshes are safe by design.</p>
 */
@Slf4j
@Builder
public class AdjacencyCache {
    private final CacheClient cache;
    /** Full cache-key prefix, e.g. {@code t1:bind:} (source key and direction are appended). */
    private final String keyPrefix;
    /**
     * Destination-type ids used for pre-load snapshots (e.g. all {@code BindingType} numeric ids). Required so
     * snapshots can run before {@code loader.get()}.
     */
    @Singular("destTypeId")
    private final List<String> destTypeIds;
    @Builder.Default
    private final Duration softTtl = CacheKeys.SOFT_TTL;
    @Builder.Default
    private final Duration gcTtl = CacheKeys.GC_TTL;
    @Builder.Default
    private final boolean softRevalidate = true;
    @Builder.Default
    private final Supplier<Long> clock = System::currentTimeMillis;
    /** Injectable jitter source in [0,1) for tests; production uses {@link Revalidator#randomJitter()}. */
    @Builder.Default
    private final Supplier<Double> ttlJitter = Revalidator::randomJitter;

    private final ConcurrentHashMap<String, Boolean> refreshing = new ConcurrentHashMap<>();

    /**
     * Returns the bound ids for one direction, loading (and caching) every direction of the source's partition on
     * a cache miss. The result is sorted for deterministic ordering.
     *
     * @param sourceKey  The source's encoded key ({@code {typeId}_{id}}).
     * @param destTypeId The destination type's encoded id.
     * @param loader     Runs the database query for the whole partition: destination-type id -&gt; bound ids.
     *                   Prefer including empty lists for known dest types so cache sets can be cleared.
     */
    public List<String> get(final String sourceKey, final String destTypeId,
            final Supplier<Map<String, List<String>>> loader) {
        final Optional<String> marker = cache.getValue(markerKey(sourceKey));
        if (marker.isEmpty()) {
            return loadAndMerge(sourceKey, destTypeId, loader);
        }
        maybeScheduleRefresh(sourceKey, marker.get(), loader);
        return members(sourceKey, destTypeId);
    }

    /** Mirrors one saved binding row into its direction's set (call only after the database write succeeded). */
    public boolean add(final String sourceKey, final String destTypeId, final String destId) {
        final String key = setKey(sourceKey, destTypeId);
        afterWriteThrough(sourceKey, key, cache.addSetMembers(key, List.of(destId)));
        return true;
    }

    /** Mirrors one removed binding row (call only after the database delete succeeded). */
    public boolean remove(final String sourceKey, final String destTypeId, final String destId) {
        final String key = setKey(sourceKey, destTypeId);
        afterWriteThrough(sourceKey, key, cache.removeSetMember(key, destId));
        return true;
    }

    /** Forces the next read of this source to reload from the database. */
    public boolean invalidate(final String sourceKey) {
        return cache.removeKey(markerKey(sourceKey));
    }

    private void afterWriteThrough(final String sourceKey, final String setKey, final boolean ok) {
        if (!ok) {
            recover(sourceKey, setKey);
            return;
        }
        cache.expire(setKey, gcTtl);
    }

    private void maybeScheduleRefresh(final String sourceKey, final String loadedAtRaw,
            final Supplier<Map<String, List<String>>> loader) {
        // The legacy marker value "1" parses to epoch 1 ms -- ancient, therefore stale, as before.
        final Revalidator revalidator = new Revalidator(cache, softTtl, clock, ttlJitter);
        if (!softRevalidate || !revalidator.isSoftStale(loadedAtRaw)) {
            return;
        }
        // Duplicate refreshes after lock TTL are safe by design; a lost lock belongs to another instance.
        revalidator.schedule(refreshing, sourceKey, keyPrefix + sourceKey, "binding",
                () -> loadAndMerge(sourceKey, "", loader));
    }

    private List<String> loadAndMerge(final String sourceKey, final String destTypeId,
            final Supplier<Map<String, List<String>>> loader) {
        // Pre-load snapshot (before Dynamo) so write-through during the query is not SREM'd.
        final Map<String, Set<String>> preByDest = snapshotDestTypes(sourceKey);
        final Map<String, List<String>> byDestType = loader.get();
        return mergeBindingPartition(sourceKey, destTypeId, byDestType, preByDest);
    }

    private Map<String, Set<String>> snapshotDestTypes(final String sourceKey) {
        final List<String> destIds = destTypeIds == null ? List.of() : destTypeIds;
        final Map<String, Set<String>> preByDest = new HashMap<>();
        for (final String dest : destIds) {
            preByDest.put(dest, new HashSet<>(cache.getSetMembers(setKey(sourceKey, dest))));
        }
        return preByDest;
    }

    private List<String> mergeBindingPartition(
            final String sourceKey,
            final String destTypeId,
            final Map<String, List<String>> byDestType,
            final Map<String, Set<String>> preByDest) {
        final Set<String> destKeys = new HashSet<>();
        destKeys.addAll(byDestType.keySet());
        destKeys.addAll(preByDest.keySet());
        boolean allOk = true;
        for (final String dest : destKeys) {
            final List<String> loaderIds = byDestType.getOrDefault(dest, List.of());
            allOk &= reconcileBindingSet(sourceKey, dest, loaderIds, preByDest.getOrDefault(dest, Set.of()));
        }
        final boolean marked = afterBindingWrites(sourceKey, allOk);
        if (marked) {
            return members(sourceKey, destTypeId);
        }
        return sorted(byDestType.getOrDefault(destTypeId, List.of()));
    }

    private boolean reconcileBindingSet(
            final String sourceKey,
            final String dest,
            final List<String> loaderIds,
            final Set<String> preMembers) {
        final String key = setKey(sourceKey, dest);
        final Set<String> loaderSet = new HashSet<>(loaderIds);
        boolean ok = true;
        if (!loaderIds.isEmpty()) {
            ok &= cache.addSetMembers(key, loaderIds);
        }
        for (final String member : preMembers) {
            if (!loaderSet.contains(member)) {
                // Snapshot member not in loader → out-of-band delete. Write-through during load is not in pre.
                ok &= cache.removeSetMember(key, member);
            }
        }
        ok &= cache.expire(key, gcTtl);
        return ok;
    }

    private boolean afterBindingWrites(final String sourceKey, final boolean allOk) {
        if (allOk) {
            return cache.putValue(markerKey(sourceKey), String.valueOf(clock.get()), gcTtl);
        }
        cache.removeKey(markerKey(sourceKey));
        return false;
    }

    private List<String> members(final String sourceKey, final String destTypeId) {
        if (destTypeId == null || destTypeId.isEmpty()) {
            return List.of();
        }
        return sorted(cache.getSetMembers(setKey(sourceKey, destTypeId)));
    }

    private List<String> sorted(final Collection<String> ids) {
        final List<String> result = new ArrayList<>(ids);
        result.sort(null);
        return result;
    }

    private void recover(final String sourceKey, final String setKey) {
        log.error("Cache write-through failed for '{}'; dropping the loaded marker as a precaution.", setKey);
        cache.removeKey(markerKey(sourceKey));
    }

    private String setKey(final String sourceKey, final String destTypeId) {
        return keyPrefix + sourceKey + ":" + destTypeId;
    }

    private String markerKey(final String sourceKey) {
        return keyPrefix + sourceKey + CacheKeys.BIND_LOADED_SUFFIX;
    }
}
