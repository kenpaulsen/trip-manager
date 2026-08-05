package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link AdjacencyCache}, the per-source bindings template.
 *
 * <p>The behaviours pinned here are the self-healing ones: a legacy {@code LOADED} marker (pre-epoch format)
 * reads as stale so old cache entries revalidate themselves; an out-of-band delete is reconciled away by the
 * next refresh; and a failed write-through drops the loaded marker so the next read goes back to the database
 * rather than serving a set that is now known to be wrong.
 */
public class AdjacencyCacheTest {

    private static final String SRC = "1_alice";

    private InMemoryCacheClient cache;
    private AtomicInteger loads;
    private AtomicLong clock;
    private Map<String, List<String>> partition;

    @BeforeMethod
    public void setUp() {
        cache = new InMemoryCacheClient();
        loads = new AtomicInteger();
        clock = new AtomicLong(1_000_000L);
        partition = Map.of("2", List.of("t2", "t1"), "3", List.of());
    }

    private AdjacencyCache adjacency(final CacheClient client) {
        return AdjacencyCache.builder()
                .cache(client)
                .keyPrefix("adj-test:")
                .destTypeId("2")
                .destTypeId("3")
                .softTtl(Duration.ofMinutes(1))
                .clock(clock::get)
                .ttlJitter(() -> 0.5) // pins the effective soft TTL to exactly softTtl (no test flake)
                .build();
    }

    private Supplier<Map<String, List<String>>> loader() {
        return () -> {
            loads.incrementAndGet();
            return partition;
        };
    }

    @Test
    public void aColdSourceLoadsItsWholePartitionAndAnswersSorted() {
        final AdjacencyCache adj = adjacency(cache);

        Assert.assertEquals(adj.get(SRC, "2", loader()), List.of("t1", "t2"), "sorted for determinism");
        Assert.assertEquals(loads.get(), 1);

        Assert.assertEquals(adj.get(SRC, "3", loader()), List.of(),
                "the first load populated every direction");
        Assert.assertEquals(loads.get(), 1, "a warm source must not reload");
    }

    @Test
    public void writeThroughAddAndRemoveKeepTheSetCurrentBetweenLoads() {
        final AdjacencyCache adj = adjacency(cache);
        adj.get(SRC, "2", loader());

        Assert.assertTrue(adj.add(SRC, "2", "t9"));
        Assert.assertEquals(adj.get(SRC, "2", loader()), List.of("t1", "t2", "t9"));

        Assert.assertTrue(adj.remove(SRC, "2", "t1"));
        Assert.assertEquals(adj.get(SRC, "2", loader()), List.of("t2", "t9"));
        Assert.assertEquals(loads.get(), 1, "write-through must not force a reload");
    }

    @Test
    public void invalidateForcesTheNextReadBackToTheDatabase() {
        final AdjacencyCache adj = adjacency(cache);
        adj.get(SRC, "2", loader());

        Assert.assertTrue(adj.invalidate(SRC));
        adj.get(SRC, "2", loader());

        Assert.assertEquals(loads.get(), 2);
    }

    /** The pre-epoch {@code LOADED} marker format must read as stale, or old entries never revalidate. */
    @Test
    public void aLegacyLoadedMarkerCountsAsStale() throws Exception {
        final AdjacencyCache adj = adjacency(cache);
        adj.get(SRC, "2", loader());
        cache.putValue("adj-test:" + SRC + CacheKeys.BIND_LOADED_SUFFIX, CacheKeys.LOADED_VALUE,
                Duration.ofMinutes(5));
        partition = Map.of("2", List.of("t1", "t2", "fresh"), "3", List.of());

        adj.get(SRC, "2", loader());

        awaitTrue(() -> adj.get(SRC, "2", loader()).contains("fresh"),
                "a legacy marker must trigger a background revalidate");
    }

    /** An out-of-band database delete is healed: the refresh SREMs members the loader no longer returns. */
    @Test
    public void aSoftStaleRefreshReconcilesAwayAnOutOfBandDelete() throws Exception {
        final AdjacencyCache adj = adjacency(cache);
        adj.get(SRC, "2", loader());
        partition = Map.of("2", List.of("t2"), "3", List.of()); // t1 deleted behind the cache's back
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        Assert.assertEquals(adj.get(SRC, "2", loader()), List.of("t1", "t2"),
                "the read that noticed staleness still answers from the cache");

        clock.addAndGet(-Duration.ofMinutes(2).toMillis());
        awaitTrue(() -> adj.get(SRC, "2", loader()).equals(List.of("t2")),
                "the refresh must reconcile the deleted member away");
    }

    @Test
    public void anUnparseableMarkerCountsAsStale() throws Exception {
        final AdjacencyCache adj = adjacency(cache);
        adj.get(SRC, "2", loader());
        cache.putValue("adj-test:" + SRC + CacheKeys.BIND_LOADED_SUFFIX, "garbage",
                Duration.ofMinutes(5));
        final int afterWarm = loads.get();

        adj.get(SRC, "2", loader());

        awaitTrue(() -> loads.get() > afterWarm, "a garbage marker must trigger a revalidate");
    }

    @Test
    public void aFreshMarkerSchedulesNothing() throws Exception {
        final AdjacencyCache adj = adjacency(cache);
        adj.get(SRC, "2", loader());
        clock.addAndGet(Duration.ofSeconds(30).toMillis()); // half the soft TTL

        adj.get(SRC, "2", loader());

        Thread.sleep(100);
        Assert.assertEquals(loads.get(), 1);
    }

    /** Losing the refresh lock means another node is refreshing: serve the cache, schedule nothing. */
    @Test
    public void losingTheRefreshLockSkipsTheRefresh() throws Exception {
        final AdjacencyCache adj = adjacency(cache);
        adj.get(SRC, "2", loader());
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        Assert.assertTrue(cache.tryAcquireLock(
                CacheKeys.refreshLockKey("adj-test:" + SRC), Duration.ofMinutes(5)));

        Assert.assertEquals(adj.get(SRC, "2", loader()), List.of("t1", "t2"));

        Thread.sleep(200);
        Assert.assertEquals(loads.get(), 1, "the lock loser must not reload");
    }

    /**
     * A failed write-through drops the loaded marker. The set is now known to be wrong (the database write
     * succeeded, the cache write did not), so the next read must go back to the database.
     */
    @Test
    public void aFailedWriteThroughDropsTheMarkerSoTheNextReadReloads() {
        final InMemoryCacheClient real = cache;
        final CacheClient failing = Mockito.spy(real);
        Mockito.doReturn(false)
                .when(failing).addSetMembers(Mockito.contains(":2"), Mockito.anyCollection());
        final AdjacencyCache adj = adjacency(failing);
        adj.get(SRC, "3", loader()); // cold load; the ":2" writes fail, the marker write is refused

        Assert.assertTrue(adj.add(SRC, "2", "t9"), "the caller is not failed for a cache problem");

        Assert.assertTrue(real.getValue("adj-test:" + SRC + CacheKeys.BIND_LOADED_SUFFIX).isEmpty(),
                "the marker must be gone so the next read reloads");
        adj.get(SRC, "3", loader());
        Assert.assertEquals(loads.get(), 2, "the next read must go back to the database");
    }

    /** When populate cannot mark the source loaded, the answer still comes from the loader result. */
    @Test
    public void aColdLoadWithFailingCacheWritesStillAnswersFromTheLoader() {
        final CacheClient failing = Mockito.spy(cache);
        Mockito.doReturn(false)
                .when(failing).addSetMembers(Mockito.anyString(), Mockito.anyCollection());

        Assert.assertEquals(adjacency(failing).get(SRC, "2", loader()), List.of("t1", "t2"),
                "discarded cache writes must not change the answer");
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
