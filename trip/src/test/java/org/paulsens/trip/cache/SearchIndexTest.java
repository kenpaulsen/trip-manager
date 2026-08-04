package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link SearchIndex}, the lex-ordered sorted set behind people search.
 *
 * <p>Tokens are stored as {@code token<SEP>id}, so the separator must never appear inside a token -- a name
 * containing it would split into a token boundary that was never intended and match the wrong prefix.
 */
public class SearchIndexTest {

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
        return SearchIndex.builder()
                .cache(cache)
                .key("test-search-index")
                .softTtl(Duration.ofMinutes(1))
                .clock(clock::get)
                .loader(() -> {
                    loads.incrementAndGet();
                    return CompletableFuture.completedFuture(tokensById);
                })
                .build();
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
        final List<String> found = index().searchIds("ali", 10).join();

        Assert.assertEquals(loads.get(), 1);
        Assert.assertEquals(found, List.of("p1"));
    }

    @Test
    public void aPrefixMatchesEveryTokenThatStartsWithIt() {
        Assert.assertEquals(Set.copyOf(index().searchIds("a", 10).join()), Set.of("p1", "p3"),
                "alice, anderson and albert/adams all start with 'a'");
        Assert.assertEquals(index().searchIds("bro", 10).join(), List.of("p2"));
    }

    @Test
    public void anEmptyOrBlankPrefixMatchesNothingRatherThanEverything() {
        Assert.assertEquals(index().searchIds("", 10).join(), List.of());
        Assert.assertEquals(index().searchIds("   ", 10).join(), List.of());
        Assert.assertEquals(index().searchIds(null, 10).join(), List.of());
        Assert.assertEquals(loads.get(), 0, "a prefix that cannot match must not even build the index");
    }

    @Test
    public void searchIsCaseInsensitive() {
        Assert.assertEquals(index().searchIds("ALI", 10).join(), List.of("p1"));
    }

    @Test
    public void aPrefixThatMatchesNothingIsEmpty() {
        Assert.assertEquals(index().searchIds("zzz", 10).join(), List.of());
    }

    @Test
    public void updatingAddsNewTokensAndRemovesStaleOnes() {
        final SearchIndex index = index();
        index.searchIds("a", 10).join(); // warm

        Assert.assertTrue(index.update("p2", Set.of("bob", "brown"), Set.of("bob", "black")).join());

        Assert.assertEquals(index.searchIds("bla", 10).join(), List.of("p2"), "the new token is searchable");
        Assert.assertEquals(index.searchIds("bro", 10).join(), List.of(),
                "the stale token must stop matching, or a renamed person is findable under the old name");
    }

    @Test
    public void removingEveryTokenTakesTheIdOutOfSearch() {
        final SearchIndex index = index();
        index.searchIds("a", 10).join();

        Assert.assertTrue(index.update("p3", Set.of("albert", "adams"), Set.of()).join());

        Assert.assertEquals(index.searchIds("alb", 10).join(), List.of());
    }

    @Test
    public void invalidateForcesTheNextSearchToRebuild() {
        final SearchIndex index = index();
        index.searchIds("a", 10).join();
        final int afterFirst = loads.get();

        Assert.assertTrue(index.invalidate().join());
        index.searchIds("a", 10).join();

        Assert.assertTrue(loads.get() > afterFirst);
    }

    @Test
    public void theLimitCapsTheResult() {
        Assert.assertEquals(index().searchIds("a", 1).join().size(), 1);
    }

    // --- soft-stale background rebuild ---

    /**
     * A soft-stale index rebuilds in the background and RECONCILES: entries the fresh load no longer produces
     * are removed. This is the self-heal for direct database edits -- without it, a renamed person stays
     * findable under the old name until the whole key expires.
     */
    @Test
    public void aSoftStaleRebuildReconcilesAwayEntriesTheLoaderNoLongerProduces() throws Exception {
        final SearchIndex index = index();
        index.searchIds("ali", 10).join(); // warm
        tokensById = Map.of(
                "p1", Set.of("alicia", "anderson"), // renamed behind the cache's back
                "p2", Set.of("bob", "brown"),
                "p3", Set.of("albert", "adams"));
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        Assert.assertEquals(index.searchIds("alice", 10).join(), List.of("p1"),
                "the read that noticed staleness still answers from the (old) index");

        clock.addAndGet(-Duration.ofMinutes(2).toMillis());
        awaitTrue(() -> index.searchIds("alice", 10).join().isEmpty(),
                "the old name must stop matching after the rebuild reconciles");
        Assert.assertEquals(index.searchIds("alici", 10).join(), List.of("p1"), "the new name matches");
    }

    /** A mangled marker reads as stale (rebuild) rather than fresh (serve stale data forever). */
    @Test
    public void anUnparseableLoadedMarkerCountsAsStale() throws Exception {
        final SearchIndex index = index();
        index.searchIds("ali", 10).join();
        final int afterWarm = loads.get();
        cache.putValue("test-search-index" + CacheKeys.SEARCH_LOADED_SUFFIX, "garbage",
                Duration.ofMinutes(5)).join();

        index.searchIds("ali", 10).join();

        awaitTrue(() -> loads.get() > afterWarm, "a garbage marker must trigger a rebuild");
    }

    /** Losing the refresh lock means another node is rebuilding: serve the index, do nothing. */
    @Test
    public void losingTheRefreshLockSkipsTheRebuild() throws Exception {
        final SearchIndex index = index();
        index.searchIds("ali", 10).join();
        final int afterWarm = loads.get();
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        Assert.assertTrue(cache.tryAcquireLock(
                CacheKeys.refreshLockKey("test-search-index"), Duration.ofMinutes(5)).join());

        Assert.assertEquals(index.searchIds("ali", 10).join(), List.of("p1"));

        Thread.sleep(200);
        Assert.assertEquals(loads.get(), afterWarm, "the lock loser must not rebuild");
    }

    /** A loader failure during the background rebuild is logged and swallowed, never thrown at a reader. */
    @Test
    public void aFailingBackgroundRebuildIsSwallowed() throws Exception {
        final SearchIndex index = index();
        index.searchIds("ali", 10).join();
        tokensById = null; // the next load blows up inside populate
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        Assert.assertEquals(index.searchIds("ali", 10).join(), List.of("p1"));

        clock.addAndGet(-Duration.ofMinutes(2).toMillis());
        Thread.sleep(200);
        Assert.assertEquals(index.searchIds("ali", 10).join(), List.of("p1"),
                "a failed rebuild must leave the index serving");
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
