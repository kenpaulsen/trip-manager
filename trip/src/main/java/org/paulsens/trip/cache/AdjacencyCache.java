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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.Singular;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-through/write-through template for the bindings adjacency shape: one shared-cache set per
 * {@code (source, destination-type)} direction plus a per-source loaded marker (epoch millis for soft revalidate).
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
    /** Injectable jitter source in [0,1) for tests; production uses {@link ThreadLocalRandom}. */
    @Builder.Default
    private final Supplier<Double> ttlJitter = AdjacencyCache::randomJitter;

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
    public CompletableFuture<List<String>> get(final String sourceKey, final String destTypeId,
            final Supplier<CompletableFuture<Map<String, List<String>>>> loader) {
        return cache.getValue(markerKey(sourceKey))
                .thenCompose(marker -> serveBindingsOrLoad(sourceKey, destTypeId, marker, loader));
    }

    /** Mirrors one saved binding row into its direction's set (call only after the database write succeeded). */
    public CompletableFuture<Boolean> add(final String sourceKey, final String destTypeId, final String destId) {
        final String key = setKey(sourceKey, destTypeId);
        return cache.addSetMembers(key, List.of(destId))
                .thenCompose(ok -> afterWriteThrough(sourceKey, key, ok))
                .thenApply(ignored -> true);
    }

    /** Mirrors one removed binding row (call only after the database delete succeeded). */
    public CompletableFuture<Boolean> remove(final String sourceKey, final String destTypeId, final String destId) {
        final String key = setKey(sourceKey, destTypeId);
        return cache.removeSetMember(key, destId)
                .thenCompose(ok -> afterWriteThrough(sourceKey, key, ok))
                .thenApply(ignored -> true);
    }

    /** Forces the next read of this source to reload from the database. */
    public CompletableFuture<Boolean> invalidate(final String sourceKey) {
        return cache.removeKey(markerKey(sourceKey));
    }

    private CompletableFuture<List<String>> serveBindingsOrLoad(
            final String sourceKey,
            final String destTypeId,
            final Optional<String> marker,
            final Supplier<CompletableFuture<Map<String, List<String>>>> loader) {
        if (marker.isEmpty()) {
            return loadAndMerge(sourceKey, destTypeId, loader);
        }
        maybeScheduleRefresh(sourceKey, marker.get(), loader);
        return members(sourceKey, destTypeId);
    }

    private CompletableFuture<Boolean> afterWriteThrough(
            final String sourceKey, final String setKey, final boolean ok) {
        if (!ok) {
            return recover(sourceKey, setKey);
        }
        return cache.expire(setKey, gcTtl).thenApply(ignored -> true);
    }

    private void maybeScheduleRefresh(final String sourceKey, final String loadedAtRaw,
            final Supplier<CompletableFuture<Map<String, List<String>>>> loader) {
        if (!softRevalidate || !isSoftStale(loadedAtRaw)) {
            return;
        }
        if (refreshing.putIfAbsent(sourceKey, Boolean.TRUE) != null) {
            return;
        }
        if (!RefreshPermits.tryAcquire()) {
            // At the global refresh cap: skip entirely -- a later read re-triggers (see RefreshPermits).
            refreshing.remove(sourceKey);
            return;
        }
        final String lockKey = CacheKeys.refreshLockKey(keyPrefix + sourceKey);
        // Duplicate refreshes after lock TTL are safe by design.
        cache.tryAcquireLock(lockKey, CacheKeys.REFRESH_LOCK_TTL)
                .thenComposeAsync(acquired -> runBackgroundRefresh(sourceKey, lockKey, acquired, loader),
                        PersistenceExecutors.pool());
    }

    private CompletableFuture<Void> runBackgroundRefresh(
            final String sourceKey,
            final String lockKey,
            final Boolean acquired,
            final Supplier<CompletableFuture<Map<String, List<String>>>> loader) {
        if (!Boolean.TRUE.equals(acquired)) {
            refreshing.remove(sourceKey);
            RefreshPermits.release();
            return CompletableFuture.completedFuture(null);
        }
        log.debug("Soft-revalidating bindings for '{}'", sourceKey);
        return loadAndMerge(sourceKey, "", loader)
                .handle((ignored, ex) -> finishBackgroundRefresh(sourceKey, lockKey, ex));
    }

    private Void finishBackgroundRefresh(final String sourceKey, final String lockKey, final Throwable ex) {
        if (ex != null) {
            log.error("Background binding revalidate failed for '{}'", sourceKey, ex);
        }
        cache.releaseLock(lockKey);
        refreshing.remove(sourceKey);
        RefreshPermits.release();
        return null;
    }

    private boolean isSoftStale(final String loadedAtRaw) {
        if (loadedAtRaw == null || loadedAtRaw.isBlank()) {
            return true;
        }
        if (CacheKeys.LOADED_VALUE.equals(loadedAtRaw.trim())) {
            return true;
        }
        try {
            final long loadedAt = Long.parseLong(loadedAtRaw.trim());
            return clock.get() - loadedAt >= jitteredSoftTtlMillis();
        } catch (final NumberFormatException ex) {
            return true;
        }
    }

    /** ±10% per check -- see {@code PointCache#jitteredSoftTtlMillis} for why the herd must be broken. */
    private long jitteredSoftTtlMillis() {
        return (long) (softTtl.toMillis() * (0.9 + 0.2 * ttlJitter.get()));
    }

    private static double randomJitter() {
        return ThreadLocalRandom.current().nextDouble();
    }

    private CompletableFuture<List<String>> loadAndMerge(final String sourceKey, final String destTypeId,
            final Supplier<CompletableFuture<Map<String, List<String>>>> loader) {
        // Pre-load snapshot (before Dynamo) so write-through during the query is not SREM'd.
        return snapshotDestTypes(sourceKey)
                .thenCompose(preByDest -> loader.get()
                        .thenCompose(byDestType -> mergeBindingPartition(
                                sourceKey, destTypeId, byDestType, preByDest)));
    }

    private CompletableFuture<Map<String, Set<String>>> snapshotDestTypes(final String sourceKey) {
        final List<String> destIds = destTypeIds == null ? List.of() : destTypeIds;
        if (destIds.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        final Map<String, Set<String>> preByDest = new HashMap<>();
        final List<CompletableFuture<Void>> snaps = new ArrayList<>();
        for (final String dest : destIds) {
            snaps.add(cache.getSetMembers(setKey(sourceKey, dest))
                    .thenAccept(members -> preByDest.put(dest, new HashSet<>(members))));
        }
        return CompletableFuture.allOf(snaps.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> preByDest);
    }

    private CompletableFuture<List<String>> mergeBindingPartition(
            final String sourceKey,
            final String destTypeId,
            final Map<String, List<String>> byDestType,
            final Map<String, Set<String>> preByDest) {
        final List<CompletableFuture<Boolean>> writes = new ArrayList<>();
        final Set<String> destKeys = new HashSet<>();
        destKeys.addAll(byDestType.keySet());
        destKeys.addAll(preByDest.keySet());
        for (final String dest : destKeys) {
            final List<String> loaderIds = byDestType.getOrDefault(dest, List.of());
            queueBindingSetReconcile(sourceKey, dest, loaderIds,
                    preByDest.getOrDefault(dest, Set.of()), writes);
        }
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> allWritesOk(writes))
                .thenCompose(allOk -> afterBindingWrites(sourceKey, allOk))
                .thenCompose(marked -> afterMarkerWritten(sourceKey, destTypeId, byDestType, marked));
    }

    private void queueBindingSetReconcile(
            final String sourceKey,
            final String dest,
            final List<String> loaderIds,
            final Set<String> preMembers,
            final List<CompletableFuture<Boolean>> writes) {
        final String key = setKey(sourceKey, dest);
        final Set<String> loaderSet = new HashSet<>(loaderIds);
        if (!loaderIds.isEmpty()) {
            writes.add(cache.addSetMembers(key, loaderIds));
        }
        for (final String member : preMembers) {
            if (!loaderSet.contains(member)) {
                // Snapshot member not in loader → out-of-band delete. Write-through during load is not in pre.
                writes.add(cache.removeSetMember(key, member));
            }
        }
        writes.add(cache.expire(key, gcTtl));
    }

    private boolean allWritesOk(final List<CompletableFuture<Boolean>> writes) {
        return writes.isEmpty() || writes.stream().allMatch(CompletableFuture::join);
    }

    private CompletableFuture<Boolean> afterBindingWrites(final String sourceKey, final boolean allOk) {
        if (allOk) {
            return cache.putValue(markerKey(sourceKey), String.valueOf(clock.get()), gcTtl);
        }
        return cache.removeKey(markerKey(sourceKey)).thenApply(ignored -> false);
    }

    private CompletableFuture<List<String>> afterMarkerWritten(
            final String sourceKey,
            final String destTypeId,
            final Map<String, List<String>> byDestType,
            final boolean marked) {
        if (marked) {
            return members(sourceKey, destTypeId);
        }
        return CompletableFuture.completedFuture(sorted(byDestType.getOrDefault(destTypeId, List.of())));
    }

    private CompletableFuture<List<String>> members(final String sourceKey, final String destTypeId) {
        if (destTypeId == null || destTypeId.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return cache.getSetMembers(setKey(sourceKey, destTypeId)).thenApply(this::sorted);
    }

    private List<String> sorted(final Collection<String> ids) {
        final List<String> result = new ArrayList<>(ids);
        result.sort(null);
        return result;
    }

    private CompletableFuture<Boolean> recover(final String sourceKey, final String setKey) {
        log.error("Cache write-through failed for '{}'; dropping the loaded marker as a precaution.", setKey);
        return cache.removeKey(markerKey(sourceKey)).thenApply(ignored -> true);
    }

    private String setKey(final String sourceKey, final String destTypeId) {
        return keyPrefix + sourceKey + ":" + destTypeId;
    }

    private String markerKey(final String sourceKey) {
        return keyPrefix + sourceKey + CacheKeys.BIND_LOADED_SUFFIX;
    }
}
