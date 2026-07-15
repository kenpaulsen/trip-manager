package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-through/write-through template for the bindings adjacency shape: one shared-cache set per
 * {@code (source, destination-type)} direction -- mirroring exactly the rows of the {@code bindings} table's
 * {@code id1} partition -- plus a per-source "loaded" marker entry.
 *
 * <p>The marker plays the role of {@link CacheKeys#LOADED_SENTINEL}: only when it is present may an absent/empty
 * set be read as "no bindings". Member sets deliberately carry a much longer TTL than the marker
 * ({@link CacheKeys#BIND_SET_TTL} vs {@link CacheKeys#BIND_MARKER_TTL}); a set expiring while its marker lives
 * would silently read as authoritatively empty. Loads refresh every touched set's TTL, keeping that ordering.</p>
 */
@Slf4j
@Builder
public class AdjacencyCache {
    private final CacheClient cache;
    /** Full cache-key prefix, e.g. {@code t1:bind:} (source key and direction are appended). */
    private final String keyPrefix;
    @Builder.Default
    private final Duration setTtl = CacheKeys.BIND_SET_TTL;
    @Builder.Default
    private final Duration markerTtl = CacheKeys.BIND_MARKER_TTL;

    /**
     * Returns the bound ids for one direction, loading (and caching) every direction of the source's partition on
     * a cache miss. The result is sorted for deterministic ordering.
     *
     * @param sourceKey  The source's encoded key ({@code {typeId}_{id}}).
     * @param destTypeId The destination type's encoded id.
     * @param loader     Runs the database query for the whole partition: destination-type id -&gt; bound ids.
     */
    public CompletableFuture<List<String>> get(final String sourceKey, final String destTypeId,
            final Supplier<CompletableFuture<Map<String, List<String>>>> loader) {
        return cache.getValue(markerKey(sourceKey)).thenCompose(marker -> marker.isPresent()
                ? members(sourceKey, destTypeId)
                : loadAndMerge(sourceKey, destTypeId, loader));
    }

    /** Mirrors one saved binding row into its direction's set (call only after the database write succeeded). */
    public CompletableFuture<Boolean> add(final String sourceKey, final String destTypeId, final String destId) {
        final String key = setKey(sourceKey, destTypeId);
        return cache.addSetMembers(key, List.of(destId))
                .thenCompose(ok -> ok ? cache.expireIfNoTtl(key, setTtl) : recover(sourceKey, key))
                .thenApply(ignored -> true);
    }

    /** Mirrors one removed binding row (call only after the database delete succeeded). */
    public CompletableFuture<Boolean> remove(final String sourceKey, final String destTypeId, final String destId) {
        return cache.removeSetMember(setKey(sourceKey, destTypeId), destId)
                .thenCompose(ok -> ok ? CompletableFuture.completedFuture(true)
                        : recover(sourceKey, setKey(sourceKey, destTypeId)))
                .thenApply(ignored -> true);
    }

    /** Forces the next read of this source to reload from the database. */
    public CompletableFuture<Boolean> invalidate(final String sourceKey) {
        return cache.removeKey(markerKey(sourceKey));
    }

    private CompletableFuture<List<String>> loadAndMerge(final String sourceKey, final String destTypeId,
            final Supplier<CompletableFuture<Map<String, List<String>>>> loader) {
        return loader.get().thenCompose(byDestType -> {
            final List<CompletableFuture<Boolean>> writes = new ArrayList<>();
            byDestType.forEach((dest, ids) -> {
                if (!ids.isEmpty()) {
                    final String key = setKey(sourceKey, dest);
                    writes.add(cache.addSetMembers(key, ids)
                            // Loads refresh the sets' TTLs so member sets always outlive the marker written below.
                            .thenCompose(ok -> ok ? cache.expire(key, setTtl)
                                    : CompletableFuture.completedFuture(false)));
                }
            });
            return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> writes.stream().allMatch(CompletableFuture::join))
                    // Only mark "loaded" if every set write stuck; a marker over missing sets would read as
                    // authoritatively empty. On failure, drop the marker and serve the loader's own answer.
                    .thenCompose(allOk -> allOk
                            ? cache.putValue(markerKey(sourceKey), CacheKeys.LOADED_VALUE, markerTtl)
                            : cache.removeKey(markerKey(sourceKey)).thenApply(ignored -> false))
                    .thenCompose(marked -> marked
                            ? members(sourceKey, destTypeId)
                            : CompletableFuture.completedFuture(
                                    sorted(byDestType.getOrDefault(destTypeId, List.of()))));
        });
    }

    private CompletableFuture<List<String>> members(final String sourceKey, final String destTypeId) {
        return cache.getSetMembers(setKey(sourceKey, destTypeId)).thenApply(this::sorted);
    }

    private List<String> sorted(final Collection<String> ids) {
        final List<String> result = new ArrayList<>(ids);
        result.sort(null);
        return result;
    }

    private CompletableFuture<Boolean> recover(final String sourceKey, final String setKey) {
        // The cache write failed. Dropping the marker forces the next read to reload this source's partition from
        // the database, which re-merges the authoritative rows over whatever state the set is in.
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
