package org.paulsens.trip.cache;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link TripIndex}, the sorted-set index that replaced full-table scans for trip listings.
 *
 * <p>The behaviour that matters is what happens on a cold or stale index: it must rebuild from the loader
 * rather than answer "no trips". An index that fails empty is indistinguishable from an account with no data,
 * which is how a listing silently loses rows.
 */
public class TripIndexTest {

    private InMemoryCacheClient cache;
    private AtomicInteger loads;
    private AtomicLong now;
    private List<TripIndex.Entry> entries;

    @BeforeMethod
    public void setUp() {
        cache = new InMemoryCacheClient();
        loads = new AtomicInteger();
        now = new AtomicLong(1_000_000L);
        entries = List.of(
                new TripIndex.Entry("past", 500_000L, Set.of("alice")),
                new TripIndex.Entry("future", 2_000_000L, Set.of("alice", "bob")),
                new TripIndex.Entry("further", 3_000_000L, Set.of("bob")));
    }

    private TripIndex index() {
        return TripIndex.builder()
                .cache(cache)
                .clock(now::get)
                .loader(() -> {
                    loads.incrementAndGet();
                    return CompletableFuture.completedFuture(entries);
                })
                .build();
    }

    /** A cold index rebuilds from the loader instead of answering "nothing". */
    @Test
    public void aColdIndexIsBuiltFromTheLoader() {
        final List<String> active = index().activeTripIds(1_000_000L, 50).join();

        Assert.assertEquals(loads.get(), 1, "the loader must run for a cold index");
        Assert.assertEquals(active, List.of("future", "further"), "soonest-ending first");
    }

    @Test
    public void aWarmIndexIsAnsweredFromTheCache() {
        final TripIndex index = index();
        index.activeTripIds(1_000_000L, 50).join();
        final int afterFirst = loads.get();

        index.activeTripIds(1_000_000L, 50).join();

        Assert.assertEquals(loads.get(), afterFirst, "a warm index must not reload");
    }

    @Test
    public void inactiveIsTheComplementOfActiveNewestFirst() {
        Assert.assertEquals(index().inactiveTripIds(1_000_000L, 50).join(), List.of("past"));
    }

    @Test
    public void allTripIdsSpanBothSidesOfTheCutoff() {
        final List<String> all = index().allTripIds(50).join();

        Assert.assertEquals(all.size(), 3);
        Assert.assertEquals(all.get(0), "further", "newest-ending first");
    }

    @Test
    public void theLimitIsApplied() {
        Assert.assertEquals(index().allTripIds(2).join().size(), 2);
        Assert.assertEquals(index().activeTripIds(1_000_000L, 1).join(), List.of("future"));
    }

    @Test
    public void tripsForAUserComeFromTheReverseIndex() {
        Assert.assertEquals(Set.copyOf(index().tripIdsForUser("alice", 50).join()),
                Set.of("past", "future"));
        Assert.assertEquals(index().tripIdsForUser("nobody", 50).join(), List.of());
    }

    @Test
    public void addingATripPutsItInBothTheDateAndPersonIndexes() {
        final TripIndex index = index();
        index.allTripIds(50).join(); // warm it

        final TripIndex.Entry added = new TripIndex.Entry("added", 4_000_000L, Set.of("carol"));
        Assert.assertTrue(index.update(null, added, false).join());

        Assert.assertTrue(index.allTripIds(50).join().contains("added"));
        Assert.assertEquals(index.tripIdsForUser("carol", 50).join(), List.of("added"));
    }

    /** Dropping somebody from a trip must remove the reverse-index entry, or their listing keeps the trip. */
    @Test
    public void droppingAMemberRemovesTheirReverseIndexEntry() {
        final TripIndex index = index();
        index.allTripIds(50).join();
        final TripIndex.Entry before = new TripIndex.Entry("future", 2_000_000L, Set.of("alice", "bob"));
        final TripIndex.Entry after = new TripIndex.Entry("future", 2_000_000L, Set.of("alice"));

        Assert.assertTrue(index.update(before, after, false).join());

        Assert.assertFalse(index.tripIdsForUser("bob", 50).join().contains("future"),
                "bob was dropped, so his listing must lose the trip");
        Assert.assertTrue(index.tripIdsForUser("alice", 50).join().contains("future"));
    }

    @Test
    public void removingATripTakesItOutOfEveryIndex() {
        final TripIndex index = index();
        index.allTripIds(50).join();
        final TripIndex.Entry existing = new TripIndex.Entry("future", 2_000_000L, Set.of("alice", "bob"));

        Assert.assertTrue(index.update(existing, existing, true).join());

        Assert.assertFalse(index.allTripIds(50).join().contains("future"));
        Assert.assertFalse(index.tripIdsForUser("alice", 50).join().contains("future"));
    }

    @Test
    public void anUpdateWithNothingToIdentifyIsANoop() {
        Assert.assertTrue(index().update(null, null, true).join());
    }

    /** Invalidating drops the index so the next read rebuilds -- for rows written behind the cache's back. */
    @Test
    public void invalidateForcesTheNextReadToReload() {
        final TripIndex index = index();
        index.allTripIds(50).join();
        final int afterFirst = loads.get();

        Assert.assertTrue(index.invalidate().join());
        index.allTripIds(50).join();

        Assert.assertTrue(loads.get() > afterFirst, "an invalidated index must rebuild");
    }

    @Test
    public void aTripThatEndsExactlyOnTheCutoffCountsAsActive() {
        entries = List.of(new TripIndex.Entry("edge", 1_000_000L, Set.of("alice")));

        Assert.assertEquals(index().activeTripIds(1_000_000L, 50).join(), List.of("edge"));
        Assert.assertEquals(index().inactiveTripIds(1_000_000L, 50).join(), List.of());
    }

    // --- soft-stale background rebuild ---

    /**
     * A soft-stale index rebuilds in the background and reconciles BOTH structures: a trip deleted behind the
     * cache's back leaves the date index, and its members' reverse-index entries go with it.
     */
    @Test
    public void aSoftStaleRebuildReconcilesBothIndexes() throws Exception {
        final TripIndex index = index();
        index.allTripIds(50).join(); // warm
        entries = List.of( // "past" deleted behind the cache's back
                new TripIndex.Entry("future", 2_000_000L, Set.of("alice", "bob")),
                new TripIndex.Entry("further", 3_000_000L, Set.of("bob")));
        now.addAndGet(java.time.Duration.ofHours(25).toMillis());

        Assert.assertTrue(index.allTripIds(50).join().contains("past"),
                "the read that noticed staleness still answers from the (old) index");

        now.addAndGet(-java.time.Duration.ofHours(25).toMillis());
        awaitTrue(() -> !index.allTripIds(50).join().contains("past"),
                "the deleted trip must leave the date index");
        Assert.assertFalse(index.tripIdsForUser("alice", 50).join().contains("past"),
                "the reverse index must be reconciled too, or alice's listing keeps a deleted trip");
    }

    @Test
    public void anUnparseableLoadedMarkerCountsAsStale() throws Exception {
        final TripIndex index = index();
        index.allTripIds(50).join();
        final int afterWarm = loads.get();
        cache.putValue(CacheKeys.TRIPS_BY_DATE + CacheKeys.SEARCH_LOADED_SUFFIX, "garbage",
                java.time.Duration.ofMinutes(5)).join();

        index.allTripIds(50).join();

        awaitTrue(() -> loads.get() > afterWarm, "a garbage marker must trigger a rebuild");
    }

    /** Losing the refresh lock means another node is rebuilding: serve the index, do nothing. */
    @Test
    public void losingTheRefreshLockSkipsTheRebuild() throws Exception {
        final TripIndex index = index();
        index.allTripIds(50).join();
        final int afterWarm = loads.get();
        now.addAndGet(java.time.Duration.ofHours(25).toMillis());
        Assert.assertTrue(cache.tryAcquireLock(
                CacheKeys.refreshLockKey(CacheKeys.TRIPS_BY_DATE), java.time.Duration.ofMinutes(5)).join());

        Assert.assertEquals(index.allTripIds(50).join().size(), 3);

        Thread.sleep(200);
        Assert.assertEquals(loads.get(), afterWarm, "the lock loser must not rebuild");
    }

    /** A loader failure during the background rebuild is logged and swallowed, never thrown at a reader. */
    @Test
    public void aFailingBackgroundRebuildIsSwallowed() throws Exception {
        final TripIndex index = index();
        index.allTripIds(50).join();
        entries = null; // the next load blows up inside populate
        now.addAndGet(java.time.Duration.ofHours(25).toMillis());

        Assert.assertEquals(index.allTripIds(50).join().size(), 3);

        now.addAndGet(-java.time.Duration.ofHours(25).toMillis());
        Thread.sleep(200);
        Assert.assertEquals(index.allTripIds(50).join().size(), 3,
                "a failed rebuild must leave the index serving");
    }

    /** A cold-build lock loser still answers -- from its own loader snapshot -- and must not mark loaded. */
    @Test
    public void aColdBuildLockLoserStillAnswersFromItsSnapshot() {
        Assert.assertTrue(cache.tryAcquireLock(
                CacheKeys.refreshLockKey(CacheKeys.TRIPS_BY_DATE), java.time.Duration.ofMinutes(5)).join());

        Assert.assertEquals(index().allTripIds(50).join().size(), 3);

        Assert.assertTrue(cache.getValue(CacheKeys.TRIPS_BY_DATE + CacheKeys.SEARCH_LOADED_SUFFIX)
                .join().isEmpty(), "the loser must not mark loaded: it did not populate");
    }

    private static void awaitTrue(final java.util.function.BooleanSupplier condition, final String message)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        Assert.fail(message);
    }
}
