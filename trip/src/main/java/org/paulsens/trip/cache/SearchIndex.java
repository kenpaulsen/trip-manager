package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Prefix-search template: one lexicographic sorted set of {@code token|entityId} entries plus a sibling
 * {@code :loaded} marker (epoch millis of the last full build). Reads are ZRANGEBYLEX prefix queries; writers
 * diff-update their entity's entries so the index stays current without rebuilds. Blocking since the
 * virtual-threads port; background rebuilds run on spawned virtual threads.
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
    private final Supplier<Map<String, Set<String>>> loader;
    @Builder.Default
    private final Supplier<Long> clock = System::currentTimeMillis;
    /** Injectable jitter source in [0,1) for tests; production uses {@link Revalidator#randomJitter()}. */
    @Builder.Default
    private final Supplier<Double> ttlJitter = Revalidator::randomJitter;

    /** In-process single-flight for the (single) background rebuild; keyed by the index key. */
    private final ConcurrentHashMap<String, Boolean> refreshing = new ConcurrentHashMap<>();

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
    public List<String> searchIds(final String rawPrefix, final int limit) {
        final String prefix = normalizeToken(rawPrefix);
        if (prefix.isEmpty()) {
            return List.of();
        }
        final String loadedAt = cache.getValue(loadedKey()).orElse(null);
        if (loadedAt == null && softRevalidate) {
            return buildAndAnswer(prefix, limit);
        }
        maybeScheduleRebuild(loadedAt);
        return rangeIds(prefix, limit);
    }

    /**
     * Write-through diff: removes entries for tokens the entity no longer has, adds entries for its current
     * tokens (idempotent re-adds heal drift). Pass an empty {@code newTokens} to remove the entity entirely.
     */
    public boolean update(final String id, final Set<String> oldTokens, final Set<String> newTokens) {
        final Set<String> stale = new HashSet<>(oldTokens);
        stale.removeAll(newTokens);
        cache.removeSortedSetEntries(key, toEntries(stale, id));
        final boolean ok = cache.addSortedSetEntries(key, toEntries(newTokens, id));
        if (!newTokens.isEmpty()) {
            cache.expire(key, gcTtl);
        }
        return ok;
    }

    /** Drops the whole index (next search rebuilds). */
    public boolean invalidate() {
        cache.removeKey(key);
        return cache.removeKey(loadedKey());
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

    private List<String> rangeIds(final String prefix, final int limit) {
        return idsFromEntries(cache.getSortedSetByPrefix(key, prefix, RANGE_FETCH_LIMIT), limit);
    }

    /**
     * Cold-index foreground build. Whoever wins the lock scans and populates; the search is answered directly
     * from the loader results either way (losers just pay a redundant scan only if the winner has not finished
     * -- acceptable for the rare cold case, and both answers are correct).
     */
    private List<String> buildAndAnswer(final String prefix, final int limit) {
        final String lockKey = CacheKeys.refreshLockKey(key);
        final Map<String, Set<String>> all = loader.get();
        if (cache.tryAcquireLock(lockKey, CacheKeys.REFRESH_LOCK_TTL)) {
            try {
                populate(all);
            } catch (final RuntimeException ex) {
                logIfFailed(ex);
            } finally {
                cache.releaseLock(lockKey);
            }
        }
        return answerFromLoaded(all, prefix, limit);
    }

    private List<String> answerFromLoaded(final Map<String, Set<String>> all, final String prefix, final int limit) {
        final List<String> entries = new ArrayList<>();
        all.forEach((id, tokens) -> tokens.stream()
                .filter(token -> token.startsWith(prefix))
                .forEach(token -> entries.add(token + CacheKeys.SEARCH_SEPARATOR + id)));
        entries.sort(String::compareTo);
        return idsFromEntries(entries, limit);
    }

    private boolean populate(final Map<String, Set<String>> all) {
        final List<String> entries = new ArrayList<>();
        all.forEach((id, tokens) -> entries.addAll(toEntries(tokens, id)));
        return cache.addSortedSetEntries(key, entries) && markLoaded();
    }

    private boolean markLoaded() {
        cache.expire(key, gcTtl);
        return cache.putValue(loadedKey(), String.valueOf(clock.get()), gcTtl);
    }

    private void maybeScheduleRebuild(final String loadedAtRaw) {
        final Revalidator revalidator = new Revalidator(cache, softTtl, clock, ttlJitter);
        if (!softRevalidate || !revalidator.isSoftStale(loadedAtRaw)) {
            return;
        }
        revalidator.schedule(refreshing, key, key, "search-index", this::rebuildIndex);
    }

    private void rebuildIndex() {
        // Snapshot BEFORE the load: entries added concurrently by write-through are newer than the
        // snapshot and therefore never deleted by reconcile.
        final List<String> snapshot = cache.getSortedSetByPrefix(key, "", RECONCILE_FETCH_LIMIT);
        final Map<String, Set<String>> all = loader.get();
        populate(all);
        reconcile(snapshot, all);
    }

    /** Removes snapshot entries the fresh load no longer produces (heals deletes and direct database edits). */
    private boolean reconcile(final List<String> snapshot, final Map<String, Set<String>> all) {
        final Set<String> fresh = new HashSet<>();
        all.forEach((id, tokens) -> fresh.addAll(toEntries(tokens, id)));
        final List<String> stale = snapshot.stream().filter(entry -> !fresh.contains(entry)).toList();
        return cache.removeSortedSetEntries(key, stale);
    }

    private void logIfFailed(final Throwable ex) {
        if (ex != null) {
            log.error("Search index build failed for '{}'", key, ex);
        }
    }
}
