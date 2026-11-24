package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link SearchIndex}, the lex-ordered sorted sets behind people search, partitioned by a token's first two
 * characters, with one load stamp per partition in a {@code :parts} hash.
 *
 * <p>Tokens are stored as {@code token<SEP>id}, so the separator must never appear inside a token -- a name
 * containing it would split into a token boundary that was never intended and match the wrong prefix.
 */
public class SearchIndexTest {
    private static final String KEY = "test-search-index";
    private static final String PARTS = KEY + ":parts";
    /** Shorter than the one-minute soft TTL, so a test can step past it without triggering a rebuild. */
    private static final Duration REUSE = Duration.ofSeconds(10);

    private InMemoryCacheClient cache;
    private AtomicInteger loads;
    private AtomicLong clock;
    private Map<String, Set<String>> tokensById;

    @BeforeMethod
    public void setUp() {
        cache = new InMemoryCacheClient();
        loads = new AtomicInteger();
        clock = new AtomicLong(1_000_000L);
        tokensById = Map.of(
                "p1", Set.of("alice", "anderson", "alice@example.org"),
                "p2", Set.of("bob", "brown"),
                "p3", Set.of("albert", "adams"));
    }

    private SearchIndex index() {
        return index(cache);
    }

    private SearchIndex index(final CacheClient client) {
        return SearchIndex.builder()
                .cache(client)
                .key(KEY)
                .softTtl(Duration.ofMinutes(1))
                .gcTtl(Duration.ofDays(7))
                .reuseWindow(REUSE)
                .clock(clock::get)
                .ttlJitter(() -> 0.5) // pins the effective soft TTL to exactly softTtl (no test flake)
                .loader(this::countedLoad)
                .build();
    }

    /** Warms the index: one search loads its partition, then the background fill loads all the others. */
    private SearchIndex warm() throws InterruptedException {
        final SearchIndex index = index();
        index.searchIds("al", 10);
        awaitTrue(() -> cache.getHash(PARTS).keySet().containsAll(Set.of("al", "an", "bo", "br", "ad")),
                "the background fill must mark every partition the load produced");
        return index;
    }

    @Test
    public void normalizingLowercasesTrimsAndStripsTheSeparator() {
        Assert.assertEquals(SearchIndex.normalizeToken("  Alice  "), "alice");
        Assert.assertEquals(SearchIndex.normalizeToken("A" + CacheKeys.SEARCH_SEPARATOR + "B"), "a b",
                "The separator is replaced, never left to split a token");
        Assert.assertEquals(SearchIndex.normalizeToken(null), "");
    }

    @Test
    public void aColdIndexIsBuiltFromTheLoader() {
        final List<String> found = index().searchIds("ali", 10);

        Assert.assertEquals(loads.get(), 1);
        Assert.assertEquals(found, List.of("p1"));
    }

    @Test
    public void aPrefixMatchesEveryTokenThatStartsWithIt() throws Exception {
        final SearchIndex index = warm();

        Assert.assertEquals(Set.copyOf(index.searchIds("al", 10)), Set.of("p1", "p3"),
                "alice, alice@example.org and albert all start with 'al'");
        Assert.assertEquals(index.searchIds("bro", 10), List.of("p2"));
    }

    @Test
    public void anEmptyBlankOrOneCharacterPrefixMatchesNothingRatherThanEverything() {
        Assert.assertEquals(index().searchIds("", 10), List.of());
        Assert.assertEquals(index().searchIds("   ", 10), List.of());
        Assert.assertEquals(index().searchIds(null, 10), List.of());
        Assert.assertEquals(index().searchIds("a", 10), List.of(), "shorter than a partition: not searchable");
        Assert.assertEquals(loads.get(), 0, "a prefix that cannot be searched must not even load the index");
    }

    @Test
    public void searchIsCaseInsensitive() {
        Assert.assertEquals(index().searchIds("ALI", 10), List.of("p1"));
    }

    @Test
    public void aPrefixThatMatchesNothingIsEmpty() {
        Assert.assertEquals(index().searchIds("zzz", 10), List.of());
    }

    /** A query in a partition nobody has is marked loaded too, or every such search would rescan the table. */
    @Test
    public void anEmptyPartitionIsMarkedLoadedSoItsNextSearchDoesNotRescan() {
        final SearchIndex index = index();
        index.searchIds("zz", 10);
        final int afterFirst = loads.get();

        Assert.assertEquals(index.searchIds("zzz", 10), List.of());

        Assert.assertEquals(loads.get(), afterFirst);
        Assert.assertTrue(cache.getHash(PARTS).containsKey("zz"));
    }

    @Test
    public void tokensShorterThanAPartitionAreNeverStored() throws Exception {
        tokensById = Map.of("p1", Set.of("a", "al"));
        index().searchIds("al", 10);

        Assert.assertEquals(cache.getHash(PARTS).keySet(), Set.of("al"));
        Assert.assertEquals(cache.getSortedSetByPrefix(KEY + ":al", "", 10), List.of("al|p1"));
    }

    @Test
    public void entriesLiveInTheirOwnPartitionKey() throws Exception {
        warm();

        Assert.assertEquals(cache.getSortedSetByPrefix(KEY + ":br", "", 10), List.of("brown|p2"));
        Assert.assertEquals(cache.getSortedSetByPrefix(KEY + ":bo", "", 10), List.of("bob|p2"));
    }

    @Test
    public void updatingAddsNewTokensAndRemovesStaleOnes() throws Exception {
        final SearchIndex index = warm();
        index.searchIds("bl", 10); // marks the (empty) "bl" partition loaded

        Assert.assertTrue(index.update("p2", Set.of("bob", "brown"), Set.of("bob", "black")));

        Assert.assertEquals(index.searchIds("bla", 10), List.of("p2"), "the new token is searchable");
        Assert.assertEquals(index.searchIds("bro", 10), List.of(),
                "the stale token must stop matching, or a renamed person is findable under the old name");
    }

    /** A save that changes no token writes nothing: re-adding every token cost a round trip per partition. */
    @Test
    public void anUpdateWritesOnlyTheTokensThatChanged() throws Exception {
        warm();
        final CacheClient watched = Mockito.mock(CacheClient.class, AdditionalAnswers.delegatesTo(cache));
        final SearchIndex index = index(watched);

        Assert.assertTrue(index.update("p2", Set.of("bob", "brown"), Set.of("bob", "brown")));
        Mockito.verify(watched, Mockito.never()).addSortedSetEntries(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyCollection());

        Assert.assertTrue(index.update("p2", Set.of("bob", "brown"), Set.of("bob", "brownie")));
        Mockito.verify(watched).addSortedSetEntries(KEY + ":br", List.of("brownie|p2"));
        Mockito.verify(watched, Mockito.never()).addSortedSetEntries(ArgumentMatchers.eq(KEY + ":bo"),
                ArgumentMatchers.anyCollection());
    }

    @Test
    public void removingEveryTokenTakesTheIdOutOfSearch() throws Exception {
        final SearchIndex index = warm();

        Assert.assertTrue(index.update("p3", Set.of("albert", "adams"), Set.of()));

        Assert.assertEquals(index.searchIds("alb", 10), List.of());
        Assert.assertEquals(index.searchIds("ada", 10), List.of());
    }

    @Test
    public void invalidateDropsEveryPartitionAndTheNextSearchReloads() throws Exception {
        final SearchIndex index = warm();
        final int afterWarm = loads.get();

        Assert.assertTrue(index.invalidate());

        Assert.assertTrue(cache.getHash(PARTS).isEmpty());
        Assert.assertTrue(cache.getSortedSetByPrefix(KEY + ":br", "", 10).isEmpty(),
                "the registry must name every partition, so none is left behind");
        Assert.assertEquals(index.searchIds("bro", 10), List.of("p2"));
        Assert.assertTrue(loads.get() > afterWarm);
    }

    @Test
    public void theLimitCapsTheResult() {
        Assert.assertEquals(index().searchIds("al", 1).size(), 1);
    }

    // --- partial loading (owner decision 2026-11-24: per-partition markers) ---

    /**
     * Only the searched partition is trusted when its field is present. A partition with entries but no field
     * (write-through landed before any load) is answered from the loader, so a partial index never hides anyone.
     */
    @Test
    public void anUnmarkedPartitionIsAnsweredFromTheLoaderEvenWhenItHoldsSomeEntries() {
        final SearchIndex index = index();
        index.update("p2", Set.of(), Set.of("bob", "brown")); // write-through into an unloaded partition
        tokensById = Map.of("p2", Set.of("bob", "brown"), "p4", Set.of("brownlee"));

        Assert.assertEquals(Set.copyOf(index.searchIds("bro", 10)), Set.of("p2", "p4"),
                "p4 is only in the loader; trusting the half-filled partition would hide them");
        Assert.assertEquals(loads.get(), 1);
    }

    /** A partition whose write failed is never marked: its next search reloads instead of trusting a gap. */
    @Test
    public void aFailedPartitionWriteLeavesThePartitionUnmarked() {
        final CacheClient failing = Mockito.mock(CacheClient.class, AdditionalAnswers.delegatesTo(cache));
        Mockito.doReturn(false).when(failing)
                .addSortedSetEntries(ArgumentMatchers.eq(KEY + ":br"), ArgumentMatchers.anyCollection());

        Assert.assertEquals(index(failing).searchIds("bro", 10), List.of("p2"),
                "the search itself is still answered, from the load");

        Assert.assertFalse(cache.getHash(PARTS).containsKey("br"));
        index(failing).searchIds("bro", 10);
        Assert.assertTrue(loads.get() >= 2, "an unmarked partition is reloaded, never trusted");
    }

    /** A stamp older than the GC TTL may outlive its entries' expiry: it counts as unloaded. */
    @Test
    public void aStampOlderThanTheGcTtlCountsAsUnloaded() throws Exception {
        final SearchIndex index = warm();
        final int afterWarm = loads.get();
        clock.addAndGet(Duration.ofDays(8).toMillis());

        index.searchIds("bro", 10);

        Assert.assertTrue(loads.get() > afterWarm);
    }

    /**
     * A partition key's expiry is pushed out BEFORE its stamp is written (checked on a cold load; the rebuild and
     * the fill share {@code writePartition}): that order is what keeps a stamp from outliving the entries it vouches
     * for. A write-through add also extends the expiry of each partition it adds to.
     */
    @Test
    public void thePartitionsExpiryIsPushedOutBeforeItsStampIsWritten() throws Exception {
        final CacheClient watched = Mockito.mock(CacheClient.class, AdditionalAnswers.delegatesTo(cache));
        final SearchIndex index = index(watched);

        index.searchIds("bro", 10);

        final InOrder cold = Mockito.inOrder(watched);
        cold.verify(watched).expire(KEY + ":br", Duration.ofDays(7));
        cold.verify(watched).putHashField(ArgumentMatchers.eq(PARTS), ArgumentMatchers.eq("br"),
                ArgumentMatchers.anyString());

        Assert.assertTrue(index.update("p7", Set.of(), Set.of("brook")));
        Mockito.verify(watched, Mockito.atLeast(2)).expire(KEY + ":br", Duration.ofDays(7));
    }

    /**
     * The stamp is read from Valkey even inside a near-cached ({@code Cached.YES}) search, as the admin People
     * search runs: after Valkey loses the partition and its stamp, a heap copy of the stamp must not make the
     * now-empty partition trusted (it would answer "nobody matches"). The key starts with the near cache's own
     * prefix, or the near cache would ignore it and the test would pass without the fix.
     */
    @Test
    public void theStampIsReadFromValkeyEvenInsideANearCachedSearch() throws Exception {
        final InMemoryCacheClient valkey = new InMemoryCacheClient();
        final String key = CacheKeys.FORMAT_VERSION + "idx:test-near";
        final SearchIndex index = SearchIndex.builder()
                .cache(new NearCacheClient(valkey, null))
                .key(key)
                .softTtl(Duration.ofMinutes(1))
                .reuseWindow(REUSE)
                .clock(clock::get)
                .ttlJitter(() -> 0.5)
                .loader(this::countedLoad)
                .build();
        Assert.assertEquals(nearCached(index, "bro"), List.of("p2"));
        awaitTrue(() -> valkey.getHash(key + ":parts").size() == 5, "the background fill marks every partition");
        Assert.assertEquals(nearCached(index, "bro"), List.of("p2"), "the stamp read is now warm in the heap");

        valkey.removeKey(key + ":br");
        valkey.removeKey(key + ":parts");
        clock.addAndGet(REUSE.toMillis() * 2);
        final int before = loads.get();

        Assert.assertEquals(nearCached(index, "bro"), List.of("p2"), "an unmarked partition is reloaded, not trusted");
        Assert.assertEquals(loads.get(), before + 1);
    }

    private static List<String> nearCached(final SearchIndex index, final String prefix) {
        return NearCacheContext.call(Cached.YES, () -> index.searchIds(prefix, 10));
    }

    private Map<String, Set<String>> countedLoad() {
        loads.incrementAndGet();
        return tokensById;
    }

    /** A mangled stamp reads as unloaded (reload) rather than fresh (serve a possibly empty partition forever). */
    @Test
    public void anUnparseableStampCountsAsUnloaded() throws Exception {
        final SearchIndex index = warm();
        final int afterWarm = loads.get();
        cache.putHashField(PARTS, "al", "garbage");
        clock.addAndGet(REUSE.toMillis() * 2);

        Assert.assertEquals(index.searchIds("ali", 10), List.of("p1"));

        Assert.assertTrue(loads.get() > afterWarm, "a garbage stamp must trigger a load");
    }

    /** No ZADD carries more than WRITE_BATCH entries, however large a partition grows. */
    @Test
    public void aLargePartitionIsWrittenInBatches() {
        final Map<String, Set<String>> many = new HashMap<>();
        final int count = SearchIndex.WRITE_BATCH * 2 + 7;
        for (int i = 0; i < count; i++) {
            many.put("id" + i, Set.of("smith" + i));
        }
        tokensById = many;
        final List<Integer> sizes = new ArrayList<>();
        final CacheClient counting = Mockito.mock(CacheClient.class, AdditionalAnswers.delegatesTo(cache));
        Mockito.doAnswer(call -> recordedAdd(sizes, call.getArgument(0), call.getArgument(1)))
                .when(counting).addSortedSetEntries(ArgumentMatchers.eq(KEY + ":sm"), ArgumentMatchers.anyCollection());

        Assert.assertEquals(index(counting).searchIds("smith1", 5).size(), 5);

        Assert.assertEquals(sizes, List.of(SearchIndex.WRITE_BATCH, SearchIndex.WRITE_BATCH, 7));
        Assert.assertTrue(cache.getHash(PARTS).containsKey("sm"));
    }

    /** Records one ZADD's size, then performs it on the real in-memory cache. */
    private boolean recordedAdd(final List<Integer> sizes, final String key, final Collection<String> entries) {
        sizes.add(entries.size());
        return cache.addSortedSetEntries(key, entries);
    }

    // --- soft-stale background rebuild ---

    /**
     * A partition marked empty (a query nobody matched) loses its field in the daily rebuild instead of being
     * restamped: any two characters make a partition, so restamping would keep every made-up prefix forever. Its
     * next search loads and marks it again.
     */
    @Test
    public void theRebuildDropsTheFieldOfAnEmptyPartition() throws Exception {
        final SearchIndex index = warm();
        index.searchIds("zz", 10); // marks "zz" loaded and empty
        Assert.assertTrue(cache.getHash(PARTS).containsKey("zz"));
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        index.searchIds("ali", 10); // soft-stale: schedules the rebuild

        awaitTrue(() -> !cache.getHash(PARTS).containsKey("zz"), "the empty partition's field must be dropped");
        Assert.assertTrue(cache.getHash(PARTS).containsKey("al"), "a produced partition keeps its field");
        final int afterRebuild = loads.get();
        Assert.assertEquals(index.searchIds("zzz", 10), List.of());
        Assert.assertEquals(loads.get(), afterRebuild + 1, "its next search loads once");
        Assert.assertTrue(cache.getHash(PARTS).containsKey("zz"), "and marks it again");
    }

    /** A burst of never-seen prefixes inside the reuse window costs one table scan, not one each. */
    @Test
    public void manyEmptyPrefixesInsideTheReuseWindowCostOneLoad() throws Exception {
        final SearchIndex index = warm();
        final int afterWarm = loads.get();

        for (final String junk : List.of("qq", "qx", "zq", "x1", "9z")) {
            Assert.assertEquals(index.searchIds(junk, 10), List.of());
        }
        Assert.assertEquals(loads.get(), afterWarm, "the warm load is reused");

        clock.addAndGet(REUSE.toMillis() * 2);
        index.searchIds("jj", 10);
        Assert.assertEquals(loads.get(), afterWarm + 1, "after the window, one new load");
    }

    /** Concurrent cold searches wait for one scan instead of each running their own. */
    @Test
    public void concurrentColdSearchesShareOneLoad() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        final SearchIndex index = SearchIndex.builder()
                .cache(cache)
                .key(KEY)
                .softTtl(Duration.ofMinutes(1))
                .reuseWindow(REUSE)
                .clock(clock::get)
                .ttlJitter(() -> 0.5)
                .loader(() -> blockedLoad(release))
                .build();
        final List<Thread> searchers = new ArrayList<>();
        for (final String prefix : List.of("al", "bo", "br", "ad", "an")) {
            searchers.add(Thread.ofVirtual().start(() -> index.searchIds(prefix, 10)));
        }
        Thread.sleep(200);
        release.countDown();
        for (final Thread searcher : searchers) {
            searcher.join(5_000);
        }

        Assert.assertEquals(loads.get(), 1, "five cold partitions, one scan");
    }

    private Map<String, Set<String>> blockedLoad(final CountDownLatch release) {
        loads.incrementAndGet();
        try {
            release.await();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return tokensById;
    }

    /** A token written through AFTER the reused load is still found: the answer reads the partition key too. */
    @Test
    public void aReusedLoadStillFindsAWriteMadeAfterIt() throws Exception {
        final SearchIndex index = warm();
        Assert.assertTrue(index.update("p9", Set.of(), Set.of("zebra")));

        Assert.assertEquals(index.searchIds("zeb", 10), List.of("p9"), "\"ze\" was unmarked, the load had no zebra");
    }

    /**
     * A soft-stale index rebuilds in the background and RECONCILES: entries the fresh load no longer produces
     * are removed. This is the self-heal for direct database edits -- without it, a renamed person stays
     * findable under the old name until the whole key expires.
     */
    @Test
    public void aSoftStaleRebuildReconcilesAwayEntriesTheLoaderNoLongerProduces() throws Exception {
        final SearchIndex index = warm();
        tokensById = Map.of(
                "p1", Set.of("alicia", "anderson"), // renamed behind the cache's back
                "p2", Set.of("bob", "brown"),
                "p3", Set.of("albert", "adams"));
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        Assert.assertEquals(index.searchIds("alice", 10), List.of("p1"),
                "the read that noticed staleness still answers from the (old) index");

        awaitTrue(() -> index.searchIds("alice", 10).isEmpty(),
                "the old name must stop matching after the rebuild reconciles");
        Assert.assertEquals(index.searchIds("alici", 10), List.of("p1"), "the new name matches");
    }

    /** Losing the refresh lock means another node is rebuilding: serve the index, do nothing. */
    @Test
    public void losingTheRefreshLockSkipsTheRebuild() throws Exception {
        final SearchIndex index = warm();
        final int afterWarm = loads.get();
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        Assert.assertTrue(cache.tryAcquireLock(CacheKeys.refreshLockKey(KEY), Duration.ofMinutes(5)));

        Assert.assertEquals(index.searchIds("ali", 10), List.of("p1"));

        Thread.sleep(200);
        Assert.assertEquals(loads.get(), afterWarm, "the lock loser must not rebuild");
    }

    /** A loader failure during the background rebuild is logged and swallowed, never thrown at a reader. */
    @Test
    public void aFailingBackgroundRebuildIsSwallowed() throws Exception {
        final SearchIndex index = warm();
        tokensById = null; // the next load blows up inside the rebuild
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        Assert.assertEquals(index.searchIds("ali", 10), List.of("p1"));

        Thread.sleep(200);
        Assert.assertEquals(index.searchIds("ali", 10), List.of("p1"),
                "a failed rebuild must leave the index serving");
    }

    private static void awaitTrue(final BooleanSupplier condition, final String message)
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
