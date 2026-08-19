package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class NearCacheClientTest {

    /** The remote-invalidation path: heap forgotten, delegate untouched (the initiator cleared Valkey). */
    @org.testng.annotations.Test
    public void dropLocalNamespaceForgetsHeapWithoutTouchingTheDelegate() {
        final CountingCacheClient delegate = new CountingCacheClient();
        final NearCacheClient near = new NearCacheClient(delegate, null);
        delegate.putValue("t1:drop:x", "v", null);
        NearCacheContext.call(Cached.YES, () -> near.getValue("t1:drop:x"));
        delegate.reads.set(0);

        near.dropLocalNamespace("t1:drop:");

        org.testng.Assert.assertEquals(delegate.getValue("t1:drop:x").orElse(null), "v",
                "the delegate's entry must survive a local drop");
        NearCacheContext.call(Cached.YES, () -> near.getValue("t1:drop:x"));
        org.testng.Assert.assertEquals(delegate.reads.get(), 2,
                "after the drop the next read must refetch from the delegate (plus this assert's own read)");
    }

    private static final String KEY = "t1:test:one";
    private static final String HASH_KEY = "t1:test:hash";
    private static final long START = 1_000_000L;

    // ------------------------------------------------------------------ serving and bypass rules

    @Test
    public void yesHitServesFromHeapWithoutTouchingTheDelegate() {
        final CountingCacheClient counting = new CountingCacheClient();
        final NearCacheClient near = near(counting, new AtomicLong(START));
        counting.putValue(KEY, "v1", null);
        counting.reads.set(0);

        assertEquals(yes(() -> near.getValue(KEY)), Optional.of("v1"));
        assertEquals(yes(() -> near.getValue(KEY)), Optional.of("v1"));
        assertEquals(counting.reads.get(), 1, "the second YES read must be a heap hit");
    }

    @Test
    public void noAndUnboundAlwaysForward() {
        final CountingCacheClient counting = new CountingCacheClient();
        final NearCacheClient near = near(counting, new AtomicLong(START));
        counting.putValue(KEY, "v1", null);
        counting.reads.set(0);

        assertEquals(NearCacheContext.call(Cached.NO, () -> near.getValue(KEY)), Optional.of("v1"));
        assertEquals(near.getValue(KEY), Optional.of("v1"));
        assertEquals(counting.reads.get(), 2, "NO and unbound reads must both reach the delegate");
    }

    @Test
    public void keysOutsideTheDataNamespaceAreNeverCached() {
        final CountingCacheClient counting = new CountingCacheClient();
        final NearCacheClient near = near(counting, new AtomicLong(START));
        counting.putValue("chat:x", "v1", null);
        counting.reads.set(0);

        assertEquals(yes(() -> near.getValue("chat:x")), Optional.of("v1"));
        assertEquals(yes(() -> near.getValue("chat:x")), Optional.of("v1"));
        assertEquals(counting.reads.get(), 2);
    }

    @Test
    public void aMissIsCachedTooAndHealsOnWrite() {
        final CountingCacheClient counting = new CountingCacheClient();
        final NearCacheClient near = near(counting, new AtomicLong(START));
        counting.reads.set(0);

        assertEquals(yes(() -> near.getValue(KEY)), Optional.empty());
        assertEquals(yes(() -> near.getValue(KEY)), Optional.empty());
        assertEquals(counting.reads.get(), 1, "an empty answer is still an answer");

        near.putValue(KEY, "born", null);
        assertEquals(yes(() -> near.getValue(KEY)), Optional.of("born"));
    }

    @Test
    public void hashReadsAreCachedPerFieldsTupleAndServedAsCopies() {
        final CountingCacheClient counting = new CountingCacheClient();
        final NearCacheClient near = near(counting, new AtomicLong(START));
        counting.putHashFields(HASH_KEY, Map.of("a", "1", "b", "2"));
        counting.reads.set(0);

        final Map<String, String> first = yes(() -> near.getHashFields(HASH_KEY, List.of("a")));
        assertEquals(first, Map.of("a", "1"));
        first.put("a", "corrupted");
        assertEquals(yes(() -> near.getHashFields(HASH_KEY, List.of("a"))), Map.of("a", "1"),
                "a caller mutating its result must not poison the cache");
        assertEquals(counting.reads.get(), 1);

        assertEquals(yes(() -> near.getHashFields(HASH_KEY, List.of("a", "b"))), Map.of("a", "1", "b", "2"));
        assertEquals(counting.reads.get(), 2, "a different fields tuple is its own cached op");

        final Map<String, String> whole = yes(() -> near.getHash(HASH_KEY));
        assertEquals(whole, Map.of("a", "1", "b", "2"));
        yes(() -> near.getHash(HASH_KEY)).clear();
        assertEquals(yes(() -> near.getHash(HASH_KEY)), Map.of("a", "1", "b", "2"));
        assertEquals(counting.reads.get(), 3);
    }

    // ------------------------------------------------------------------------ write invalidation

    @Test
    public void everyMutatingOperationInvalidatesItsKey() {
        final CountingCacheClient counting = new CountingCacheClient();
        final NearCacheClient near = near(counting, new AtomicLong(START));
        final List<Runnable> writes = List.of(
                () -> near.putValue(KEY, "w", null),
                () -> near.removeKey(KEY),
                () -> near.putHashField(KEY, "f", "w"),
                () -> near.putHashFields(KEY, Map.of("f", "w")),
                () -> near.removeHashField(KEY, "f"),
                () -> near.addSetMembers(KEY, List.of("m")),
                () -> near.removeSetMember(KEY, "m"),
                () -> near.addSortedSetEntries(KEY, List.of("z")),
                () -> near.removeSortedSetEntries(KEY, List.of("z")),
                () -> near.addScoredEntries(KEY, Map.of("z", 1.0)),
                () -> near.trimSortedSet(KEY, 1),
                () -> near.expire(KEY, Duration.ofMinutes(1)),
                () -> near.increment(KEY, 1, null));
        for (final Runnable write : writes) {
            // Reset through the near-cache: the in-memory store types each key by its first use, so a
            // hash write after a value write would type-clash; removeKey clears both store and heap.
            near.removeKey(KEY);
            yes(() -> near.getValue(KEY));
            counting.reads.set(0);
            write.run();
            yes(() -> near.getValue(KEY));
            assertEquals(counting.reads.get(), 1, "a mutation must drop the heap entry for its key");
        }
    }

    @Test
    public void clearNamespaceDropsMatchingEntries() {
        final CountingCacheClient counting = new CountingCacheClient();
        final NearCacheClient near = near(counting, new AtomicLong(START));
        counting.putValue(KEY, "v1", null);
        yes(() -> near.getValue(KEY));
        counting.reads.set(0);

        assertTrue(near.clearNamespace("t1:"));
        yes(() -> near.getValue(KEY));
        assertEquals(counting.reads.get(), 1, "clearNamespace must drop the heap copy too");
    }

    // ------------------------------------------------------- stale-while-revalidate health check

    @Test
    public void staleHitServesImmediatelyAndRefreshesInBackgroundOnce() {
        final CountingCacheClient counting = new CountingCacheClient();
        final AtomicLong clock = new AtomicLong(START);
        final NearCacheClient near = near(counting, clock);
        counting.putValue(KEY, "old", null);
        yes(() -> near.getValue(KEY));

        // The value changes BEHIND the near-cache (a direct delegate write models another host's edit).
        counting.putValue(KEY, "new", null);
        clock.addAndGet(near.effectiveCheckMillis() + 1);
        counting.reads.set(0);

        assertEquals(yes(() -> near.getValue(KEY)), Optional.of("old"),
                "the request that trips the health check still gets the cached value immediately");
        awaitTrue(() -> yes(() -> near.getValue(KEY)).equals(Optional.of("new")));

        // The CAS reset the timer before the check ran: the follow-up reads spawned no second refresh.
        assertEquals(counting.reads.get(), 1, "exactly one background refresh may hit the delegate");
    }

    @Test
    public void hardTtlExpiryReloadsInForeground() {
        final CountingCacheClient counting = new CountingCacheClient();
        final AtomicLong clock = new AtomicLong(START);
        final NearCacheClient near = near(counting, clock);
        counting.putValue(KEY, "old", null);
        yes(() -> near.getValue(KEY));
        counting.putValue(KEY, "new", null);

        clock.addAndGet(near.effectiveTtlMillis() + 1);
        counting.reads.set(0);
        assertEquals(yes(() -> near.getValue(KEY)), Optional.of("new"),
                "past the hard TTL the entry is a miss, not a stale serve");
        assertEquals(counting.reads.get(), 1);
    }

    // ----------------------------------------------------------------------------------- tuning

    @Test
    public void tuningFromTheReaderAppliesAndZeroTtlDisables() {
        final CountingCacheClient counting = new CountingCacheClient();
        final NearCacheClient near = new NearCacheClient(counting, () -> new long[] {120, 5},
                new AtomicLong(START)::get);
        near.resyncTuning();
        assertEquals(near.effectiveTtlMillis(), 120_000L);
        assertEquals(near.effectiveCheckMillis(), 5_000L);

        counting.putValue(KEY, "v1", null);
        yes(() -> near.getValue(KEY));

        final NearCacheClient disabled = new NearCacheClient(counting, () -> new long[] {0, 5},
                new AtomicLong(START)::get);
        counting.reads.set(0);
        disabled.resyncTuning();
        assertEquals(disabled.effectiveTtlMillis(), 0L);
        yes(() -> disabled.getValue(KEY));
        yes(() -> disabled.getValue(KEY));
        assertEquals(counting.reads.get(), 2, "ttl 0 must disable near-caching entirely");
    }

    @Test
    public void aFailingOrAbsentTuningReaderKeepsTheDefaults() {
        final CountingCacheClient counting = new CountingCacheClient();
        final NearCacheClient noReader = near(counting, new AtomicLong(START));
        noReader.resyncTuning();
        assertEquals(noReader.effectiveTtlMillis(), NearCacheClient.DEFAULT_TTL_SECONDS * 1000L);

        final NearCacheClient failing = new NearCacheClient(counting, NearCacheClientTest::boom,
                new AtomicLong(START)::get);
        failing.resyncTuning();
        assertEquals(failing.effectiveTtlMillis(), NearCacheClient.DEFAULT_TTL_SECONDS * 1000L);
        assertEquals(failing.effectiveCheckMillis(), NearCacheClient.DEFAULT_CHECK_SECONDS * 1000L);

        final NearCacheClient malformed = new NearCacheClient(counting, () -> new long[] {1},
                new AtomicLong(START)::get);
        malformed.resyncTuning();
        assertEquals(malformed.effectiveTtlMillis(), NearCacheClient.DEFAULT_TTL_SECONDS * 1000L);
    }

    @Test
    public void syspropsPinTuningAgainstTheSettingsTable() {
        System.setProperty(NearCacheClient.TTL_SYSPROP, "60");
        System.setProperty(NearCacheClient.CHECK_SYSPROP, "2");
        try {
            final CountingCacheClient counting = new CountingCacheClient();
            final NearCacheClient near = new NearCacheClient(counting, () -> new long[] {9999, 9999},
                    new AtomicLong(START)::get);
            assertEquals(near.effectiveTtlMillis(), 60_000L);
            assertEquals(near.effectiveCheckMillis(), 2_000L);
            near.resyncTuning();
            assertEquals(near.effectiveTtlMillis(), 60_000L, "a pinned ttl must ignore the settings table");
            assertEquals(near.effectiveCheckMillis(), 2_000L, "a pinned check must ignore the settings table");
        } finally {
            System.clearProperty(NearCacheClient.TTL_SYSPROP);
            System.clearProperty(NearCacheClient.CHECK_SYSPROP);
        }
    }

    // ------------------------------------------------------------------------ forwarded operations

    @Test
    public void nonPointHashOperationsAlwaysForward() {
        final CountingCacheClient counting = new CountingCacheClient();
        final NearCacheClient near = near(counting, new AtomicLong(START));
        counting.addScoredEntries(KEY + ":z", Map.of("a", 1.0, "b", 2.0));
        counting.addSetMembers(KEY + ":s", List.of("m"));

        assertEquals(yes(() -> near.getSortedSetByPrefix(KEY + ":z", "a", 10)), List.of("a"));
        assertEquals(yes(() -> near.getRangeByScore(KEY + ":z", 0, 3, false, 10)), List.of("a", "b"));
        assertEquals(yes(() -> near.getSetMembers(KEY + ":s")), java.util.Set.of("m"));
        assertTrue(near.tryAcquireLock(KEY + ":lock", Duration.ofSeconds(5)));
        assertTrue(near.releaseLock(KEY + ":lock"));
        assertTrue(near.publish("chan", "payload"));
        near.close();
    }

    // -------------------------------------------------------------------------------- helpers

    private static NearCacheClient near(final CacheClient delegate, final AtomicLong clock) {
        return new NearCacheClient(delegate, null, clock::get);
    }

    private static <T> T yes(final Supplier<T> read) {
        return NearCacheContext.call(Cached.YES, read);
    }

    private static long[] boom() {
        throw new IllegalStateException("tuning source down");
    }

    private static void awaitTrue(final Supplier<Boolean> condition) {
        final long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            sleepQuietly();
        }
        throw new AssertionError("condition not met within 5s");
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(10);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
