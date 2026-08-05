package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Prefix-search template: one lexicographic sorted set of {@code token|entityId} entries plus a sibling
 * {@code :loaded} marker (epoch millis of the last full build). Reads are ZRANGEBYLEX prefix queries; writers
 * diff-update their entity's entries so the index stays current without rebuilds.
 *
 * <p>The full build (a table scan) runs lazily: in the foreground on the first search after a cold cache
 * (answering that search straight from the loader results, so it works even when cache writes are discarded),
 * and in the background -- lock-guarded, with snapshot reconcile -- once the marker is older than
 * {@link CacheKeys#INDEX_SOFT_TTL}. This deliberately keeps a full scan as the rare rebuild path: the cache is
 * not durable, and self-healing from direct database edits is worth one background scan a day.</p>
 *
 * <p>Caller contract: resolve the returned ids to entities and drop any that no longer match the query --
 * entries can be stale between rebuilds (e.g. a person renamed by a direct database edit).</p>
 */
@Slf4j
@Builder
public final class SearchIndex {
    /** Upper bound of raw entries fetched per prefix query (ids then dedupe to fewer). */
    private static final int RANGE_FETCH_LIMIT = 500;
    /** Page size / ceiling when reading the whole index for reconcile. */
    private static final int RECONCILE_FETCH_LIMIT = 1_000_000;

    private final CacheClient cache;
    private final String key;
    @Builder.Default
    private final Duration softTtl = CacheKeys.INDEX_SOFT_TTL;
    @Builder.Default
    private final boolean softRevalidate = true;
    @Builder.Default
    private final Duration gcTtl = CacheKeys.GC_TTL;
    /** Full build: every live entity id mapped to its (already normalized) search tokens. */
    private final Supplier<CompletableFuture<Map<String, Set<String>>>> loader;
    @Builder.Default
    private final Supplier<Long> clock = System::currentTimeMillis;
    /** Injectable jitter source in [0,1) for tests; production uses {@link ThreadLocalRandom}. */
    @Builder.Default
    private final Supplier<Double> ttlJitter = SearchIndex::randomJitter;

    private final AtomicBoolean refreshing = new AtomicBoolean();

    /** Normalizes a raw field or query into a search token (lowercase, trimmed, separator stripped). */
    public static String normalizeToken(final String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replace(CacheKeys.SEARCH_SEPARATOR, ' ');
    }

    /**
     * Returns entity ids whose tokens start with {@code rawPrefix} (deduped, index order). May trigger a
     * foreground build on a cold index or a background rebuild on a soft-stale one.
     */
    public CompletableFuture<List<String>> searchIds(final String rawPrefix, final int limit) {
        final String prefix = normalizeToken(rawPrefix);
        if (prefix.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return cache.getValue(loadedKey())
                .thenCompose(loadedAt -> resolveSearch(prefix, limit, loadedAt.orElse(null)));
    }

    /**
     * Write-through diff: removes entries for tokens the entity no longer has, adds entries for its current
     * tokens (idempotent re-adds heal drift). Pass an empty {@code newTokens} to remove the entity entirely.
     */
    public CompletableFuture<Boolean> update(final String id, final Set<String> oldTokens,
            final Set<String> newTokens) {
        final Set<String> stale = new HashSet<>(oldTokens);
        stale.removeAll(newTokens);
        return cache.removeSortedSetEntries(key, toEntries(stale, id))
                .thenCompose(ignored -> cache.addSortedSetEntries(key, toEntries(newTokens, id)))
                .thenCompose(ok -> newTokens.isEmpty() ? CompletableFuture.completedFuture(ok)
                        : cache.expire(key, gcTtl).thenApply(ignored -> ok));
    }

    /** Drops the whole index (next search rebuilds). */
    public CompletableFuture<Boolean> invalidate() {
        return cache.removeKey(key).thenCompose(ignored -> cache.removeKey(loadedKey()));
    }

    private String loadedKey() {
        return key + CacheKeys.SEARCH_LOADED_SUFFIX;
    }

    private static List<String> toEntries(final Set<String> tokens, final String id) {
        return tokens.stream()
                .filter(token -> !token.isEmpty())
                .map(token -> token + CacheKeys.SEARCH_SEPARATOR + id)
                .toList();
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

    private CompletableFuture<List<String>> resolveSearch(
            final String prefix, final int limit, final String loadedAtRaw) {
        if (loadedAtRaw == null && softRevalidate) {
            return buildAndAnswer(prefix, limit);
        }
        if (softRevalidate && isSoftStale(loadedAtRaw)) {
            maybeScheduleRebuild();
        }
        return rangeIds(prefix, limit);
    }

    private CompletableFuture<List<String>> rangeIds(final String prefix, final int limit) {
        return cache.getSortedSetByPrefix(key, prefix, RANGE_FETCH_LIMIT)
                .thenApply(entries -> idsFromEntries(entries, limit));
    }

    /**
     * Cold-index foreground build. Whoever wins the lock scans and populates; the search is answered directly
     * from the loader results either way (losers just pay a redundant scan only if the winner has not finished
     * -- acceptable for the rare cold case, and both answers are correct).
     */
    private CompletableFuture<List<String>> buildAndAnswer(final String prefix, final int limit) {
        final String lockKey = CacheKeys.refreshLockKey(key);
        return loader.get().thenCompose(all -> cache.tryAcquireLock(lockKey, CacheKeys.REFRESH_LOCK_TTL)
                .thenCompose(acquired -> acquired ? populate(all).handle((ok, ex) -> {
                    logIfFailed(ex);
                    cache.releaseLock(lockKey);
                    return ok;
                }) : CompletableFuture.completedFuture(false))
                .thenApply(ignored -> answerFromLoaded(all, prefix, limit)));
    }

    private List<String> answerFromLoaded(final Map<String, Set<String>> all, final String prefix, final int limit) {
        final List<String> entries = new ArrayList<>();
        all.forEach((id, tokens) -> tokens.stream()
                .filter(token -> token.startsWith(prefix))
                .forEach(token -> entries.add(token + CacheKeys.SEARCH_SEPARATOR + id)));
        entries.sort(String::compareTo);
        return idsFromEntries(entries, limit);
    }

    private CompletableFuture<Boolean> populate(final Map<String, Set<String>> all) {
        final List<String> entries = new ArrayList<>();
        all.forEach((id, tokens) -> entries.addAll(toEntries(tokens, id)));
        return cache.addSortedSetEntries(key, entries)
                .thenCompose(ok -> ok ? markLoaded() : CompletableFuture.completedFuture(false));
    }

    private CompletableFuture<Boolean> markLoaded() {
        return cache.expire(key, gcTtl)
                .thenCompose(ignored -> cache.putValue(loadedKey(), String.valueOf(clock.get()), gcTtl));
    }

    private void maybeScheduleRebuild() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        if (!RefreshPermits.tryAcquire()) {
            // At the global refresh cap: skip entirely -- a later read re-triggers (see RefreshPermits).
            refreshing.set(false);
            return;
        }
        final String lockKey = CacheKeys.refreshLockKey(key);
        cache.tryAcquireLock(lockKey, CacheKeys.REFRESH_LOCK_TTL)
                .thenComposeAsync(this::runBackgroundRebuild, PersistenceExecutors.pool())
                .handle((ignored, ex) -> finishBackgroundRebuild(lockKey, ex));
    }

    private Void finishBackgroundRebuild(final String lockKey, final Throwable ex) {
        logIfFailed(ex);
        cache.releaseLock(lockKey);
        refreshing.set(false);
        RefreshPermits.release();
        return null;
    }

    private CompletableFuture<Boolean> runBackgroundRebuild(final Boolean acquired) {
        if (!Boolean.TRUE.equals(acquired)) {
            return CompletableFuture.completedFuture(false);
        }
        log.debug("Soft-revalidating search index '{}'", key);
        // Snapshot BEFORE the load: entries added concurrently by write-through are newer than the snapshot and
        // therefore never deleted by reconcile.
        return cache.getSortedSetByPrefix(key, "", RECONCILE_FETCH_LIMIT)
                .thenCompose(snapshot -> loader.get()
                        .thenCompose(all -> populate(all)
                                .thenCompose(ok -> reconcile(snapshot, all).thenApply(ignored -> ok))));
    }

    /** Removes snapshot entries the fresh load no longer produces (heals deletes and direct database edits). */
    private CompletableFuture<Boolean> reconcile(final List<String> snapshot, final Map<String, Set<String>> all) {
        final Set<String> fresh = new HashSet<>();
        all.forEach((id, tokens) -> fresh.addAll(toEntries(tokens, id)));
        final List<String> stale = snapshot.stream().filter(entry -> !fresh.contains(entry)).toList();
        return cache.removeSortedSetEntries(key, stale);
    }

    private boolean isSoftStale(final String loadedAtRaw) {
        if (loadedAtRaw == null || loadedAtRaw.isBlank()) {
            return true;
        }
        try {
            return clock.get() - Long.parseLong(loadedAtRaw.trim()) >= jitteredSoftTtlMillis();
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

    private void logIfFailed(final Throwable ex) {
        if (ex != null) {
            log.error("Search index build failed for '{}'", key, ex);
        }
    }
}
