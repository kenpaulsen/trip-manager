package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * Trip index: replaces the whole-table trip scan with two Valkey structures rebuilt from the trips themselves.
 * <ul>
 *   <li><b>Date index</b> ({@link CacheKeys#TRIPS_BY_DATE}): sorted set, member = tripId, score = endDate epoch
 *       millis. Serves active (score &ge; cutoff), inactive (most recent below cutoff, capped), and "all" queries.</li>
 *   <li><b>Reverse index</b> ({@link CacheKeys#TRIPS_BY_PERSON}): lexicographic sorted set of {@code userId|tripId}
 *       entries. Serves per-user trip lists via a prefix range on {@code userId|}.</li>
 * </ul>
 *
 * <p>Both are derived from {@code trip.people} (the source of truth) so there is no separate store to back-fill:
 * write-through {@link #update} keeps them current, and a rare cold-cache scan rebuilds them (foreground on first
 * access, answered directly from the loaded snapshot so it works even when cache writes are discarded; background
 * with snapshot reconcile once older than {@link CacheKeys#INDEX_SOFT_TTL}). Mirrors {@link SearchIndex}.</p>
 *
 * <p>Callers resolve the returned ids to trips and apply display ordering; ids can be briefly stale between a direct
 * database edit and the next rebuild.</p>
 */
@Slf4j
@Builder
public final class TripIndex {
    private static final int RECONCILE_FETCH_LIMIT = 1_000_000;
    private static final double NEG_INF = Double.NEGATIVE_INFINITY;
    private static final double POS_INF = Double.POSITIVE_INFINITY;

    private final CacheClient cache;
    @Builder.Default
    private final Duration softTtl = CacheKeys.INDEX_SOFT_TTL;
    @Builder.Default
    private final boolean softRevalidate = true;
    @Builder.Default
    private final Duration gcTtl = CacheKeys.GC_TTL;
    /** Full build: every live trip's id, endDate epoch millis, and member userIds. */
    private final Supplier<CompletableFuture<List<Entry>>> loader;
    @Builder.Default
    private final Supplier<Long> clock = System::currentTimeMillis;

    private final AtomicBoolean refreshing = new AtomicBoolean();

    /** One trip's contribution to the index. {@code userIds} are the member ids as plain strings. */
    public record Entry(String tripId, long endEpochMillis, Set<String> userIds) {
    }

    /** Trip ids ending at or after {@code cutoffMillis} (active), soonest-ending first. */
    public CompletableFuture<List<String>> activeTripIds(final long cutoffMillis, final int limit) {
        return ensureAndQuery(
                entries -> byScore(entries, cutoffMillis, POS_INF, false, limit),
                () -> cache.getRangeByScore(CacheKeys.TRIPS_BY_DATE, cutoffMillis, POS_INF, false, limit));
    }

    /** Most-recent trip ids ending before {@code cutoffMillis} (inactive), newest first, capped at {@code limit}. */
    public CompletableFuture<List<String>> inactiveTripIds(final long cutoffMillis, final int limit) {
        return ensureAndQuery(
                entries -> byScore(entries, NEG_INF, cutoffMillis - 1, true, limit),
                () -> cache.getRangeByScore(CacheKeys.TRIPS_BY_DATE, NEG_INF, cutoffMillis - 1, true, limit));
    }

    /** All trip ids, newest-ending first, capped at {@code limit} (non-positive = no cap). */
    public CompletableFuture<List<String>> allTripIds(final int limit) {
        return ensureAndQuery(
                entries -> byScore(entries, NEG_INF, POS_INF, true, limit),
                () -> cache.getRangeByScore(CacheKeys.TRIPS_BY_DATE, NEG_INF, POS_INF, true, limit));
    }

    /** Trip ids the given user is a member of. */
    public CompletableFuture<List<String>> tripIdsForUser(final String userId, final int limit) {
        final String prefix = userId + CacheKeys.SEARCH_SEPARATOR;
        return ensureAndQuery(
                entries -> entries.stream()
                        .filter(e -> e.userIds().contains(userId))
                        .map(Entry::tripId)
                        .limit(limit > 0 ? limit : Long.MAX_VALUE)
                        .toList(),
                () -> cache.getSortedSetByPrefix(CacheKeys.TRIPS_BY_PERSON, prefix, limit)
                        .thenApply(TripIndex::stripPrefixes));
    }

    /**
     * Write-through: reflects one trip's change. {@code old} may be null (new trip); when {@code updated} is null or
     * marked removed, the trip is dropped from both indexes.
     */
    public CompletableFuture<Boolean> update(final Entry old, final Entry updated, final boolean removed) {
        final Set<String> oldUsers = (old == null) ? Set.of() : old.userIds();
        if (removed || updated == null) {
            final String tripId = (updated != null) ? updated.tripId() : (old != null ? old.tripId() : null);
            if (tripId == null) {
                return CompletableFuture.completedFuture(true);
            }
            return cache.removeSortedSetEntries(CacheKeys.TRIPS_BY_DATE, List.of(tripId))
                    .thenCompose(ignored -> cache.removeSortedSetEntries(
                            CacheKeys.TRIPS_BY_PERSON, personEntries(oldUsers, tripId)));
        }
        final String tripId = updated.tripId();
        final Set<String> addedUsers = new HashSet<>(updated.userIds());
        addedUsers.removeAll(oldUsers);
        final Set<String> removedUsers = new HashSet<>(oldUsers);
        removedUsers.removeAll(updated.userIds());
        return cache.addScoredEntries(CacheKeys.TRIPS_BY_DATE, Map.of(tripId, (double) updated.endEpochMillis()))
                .thenCompose(ignored -> cache.removeSortedSetEntries(
                        CacheKeys.TRIPS_BY_PERSON, personEntries(removedUsers, tripId)))
                .thenCompose(ignored -> cache.addSortedSetEntries(
                        CacheKeys.TRIPS_BY_PERSON, personEntries(addedUsers, tripId)))
                .thenCompose(ok -> cache.expire(CacheKeys.TRIPS_BY_DATE, gcTtl).thenApply(ignored -> ok));
    }

    /** Drops both indexes (next query rebuilds). */
    public CompletableFuture<Boolean> invalidate() {
        return cache.removeKey(CacheKeys.TRIPS_BY_DATE)
                .thenCompose(ignored -> cache.removeKey(CacheKeys.TRIPS_BY_PERSON))
                .thenCompose(ignored -> cache.removeKey(loadedKey()));
    }

    private static String loadedKey() {
        return CacheKeys.TRIPS_BY_DATE + CacheKeys.SEARCH_LOADED_SUFFIX;
    }

    private static List<String> personEntries(final Set<String> users, final String tripId) {
        return users.stream().map(u -> u + CacheKeys.SEARCH_SEPARATOR + tripId).toList();
    }

    private static List<String> stripPrefixes(final List<String> entries) {
        final List<String> ids = new ArrayList<>(entries.size());
        for (final String entry : entries) {
            final int sep = entry.indexOf(CacheKeys.SEARCH_SEPARATOR);
            if (sep >= 0) {
                ids.add(entry.substring(sep + 1));
            }
        }
        return ids;
    }

    private static List<String> byScore(
            final List<Entry> entries, final double min, final double max, final boolean reverse, final int limit) {
        final Comparator<Entry> byEnd = Comparator.comparingLong(Entry::endEpochMillis);
        return entries.stream()
                .filter(e -> e.endEpochMillis() >= min && e.endEpochMillis() <= max)
                .sorted(reverse ? byEnd.reversed() : byEnd)
                .map(Entry::tripId)
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .toList();
    }

    private CompletableFuture<List<String>> ensureAndQuery(
            final Function<List<Entry>, List<String>> inMemoryAnswer,
            final Supplier<CompletableFuture<List<String>>> cacheAnswer) {
        return cache.getValue(loadedKey()).thenCompose(loadedAt -> {
            if (loadedAt.isEmpty() && softRevalidate) {
                return buildAndAnswer(inMemoryAnswer);
            }
            if (softRevalidate && isSoftStale(loadedAt.orElse(null))) {
                maybeScheduleRebuild();
            }
            return cacheAnswer.get();
        });
    }

    private CompletableFuture<List<String>> buildAndAnswer(final Function<List<Entry>, List<String>> inMemoryAnswer) {
        final String lockKey = CacheKeys.refreshLockKey(CacheKeys.TRIPS_BY_DATE);
        return loader.get().thenCompose(entries -> cache.tryAcquireLock(lockKey, CacheKeys.REFRESH_LOCK_TTL)
                .thenCompose(acquired -> acquired ? populate(entries).handle((ok, ex) -> {
                    logIfFailed(ex);
                    cache.releaseLock(lockKey);
                    return ok;
                }) : CompletableFuture.completedFuture(false))
                .thenApply(ignored -> inMemoryAnswer.apply(entries)));
    }

    private CompletableFuture<Boolean> populate(final List<Entry> entries) {
        final Map<String, Double> dateScores = new HashMap<>();
        final List<String> personEntries = new ArrayList<>();
        for (final Entry e : entries) {
            dateScores.put(e.tripId(), (double) e.endEpochMillis());
            personEntries.addAll(personEntries(e.userIds(), e.tripId()));
        }
        return cache.addScoredEntries(CacheKeys.TRIPS_BY_DATE, dateScores)
                .thenCompose(ignored -> cache.addSortedSetEntries(CacheKeys.TRIPS_BY_PERSON, personEntries))
                .thenCompose(ok -> ok ? markLoaded() : CompletableFuture.completedFuture(false));
    }

    private CompletableFuture<Boolean> markLoaded() {
        return cache.expire(CacheKeys.TRIPS_BY_DATE, gcTtl)
                .thenCompose(ignored -> cache.expire(CacheKeys.TRIPS_BY_PERSON, gcTtl))
                .thenCompose(ignored -> cache.putValue(loadedKey(), String.valueOf(clock.get()), gcTtl));
    }

    private void maybeScheduleRebuild() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        final String lockKey = CacheKeys.refreshLockKey(CacheKeys.TRIPS_BY_DATE);
        cache.tryAcquireLock(lockKey, CacheKeys.REFRESH_LOCK_TTL)
                .thenComposeAsync(this::runBackgroundRebuild, PersistenceExecutors.pool())
                .handle((ignored, ex) -> {
                    logIfFailed(ex);
                    cache.releaseLock(lockKey);
                    refreshing.set(false);
                    return null;
                });
    }

    private CompletableFuture<Boolean> runBackgroundRebuild(final Boolean acquired) {
        if (!Boolean.TRUE.equals(acquired)) {
            return CompletableFuture.completedFuture(false);
        }
        log.debug("Soft-revalidating trip index");
        // Snapshot BEFORE the load so concurrent write-through additions are never reconciled away.
        return cache.getRangeByScore(CacheKeys.TRIPS_BY_DATE, NEG_INF, POS_INF, false, RECONCILE_FETCH_LIMIT)
                .thenCompose(dateSnapshot -> cache.getSortedSetByPrefix(
                                CacheKeys.TRIPS_BY_PERSON, "", RECONCILE_FETCH_LIMIT)
                        .thenCompose(personSnapshot -> loader.get().thenCompose(entries -> populate(entries)
                                .thenCompose(ok -> reconcile(dateSnapshot, personSnapshot, entries)
                                        .thenApply(ignored -> ok)))));
    }

    private CompletableFuture<Boolean> reconcile(
            final List<String> dateSnapshot, final List<String> personSnapshot, final List<Entry> entries) {
        final Set<String> freshTripIds = new HashSet<>();
        final Set<String> freshPersonEntries = new HashSet<>();
        for (final Entry e : entries) {
            freshTripIds.add(e.tripId());
            freshPersonEntries.addAll(personEntries(e.userIds(), e.tripId()));
        }
        final List<String> staleTrips = dateSnapshot.stream().filter(id -> !freshTripIds.contains(id)).toList();
        final List<String> stalePersons = personSnapshot.stream()
                .filter(entry -> !freshPersonEntries.contains(entry)).toList();
        return cache.removeSortedSetEntries(CacheKeys.TRIPS_BY_DATE, staleTrips)
                .thenCompose(ignored -> cache.removeSortedSetEntries(CacheKeys.TRIPS_BY_PERSON, stalePersons));
    }

    private boolean isSoftStale(final String loadedAtRaw) {
        if (loadedAtRaw == null || loadedAtRaw.isBlank()) {
            return true;
        }
        try {
            return clock.get() - Long.parseLong(loadedAtRaw.trim()) >= softTtl.toMillis();
        } catch (final NumberFormatException ex) {
            return true;
        }
    }

    private void logIfFailed(final Throwable ex) {
        if (ex != null) {
            log.error("Trip index build failed", ex);
        }
    }
}
