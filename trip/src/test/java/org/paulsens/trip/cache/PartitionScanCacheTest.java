package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link PartitionScanCache}, the whole-table-scan template for data whose partition key is not the database
 * key.
 *
 * <p>The load-bearing behaviours: a cold cache answers from the loader snapshot (correct even when cache
 * writes are discarded), a present {@code loaded} marker makes a point-lookup miss an authoritative "not
 * found" (no database fallback), and a soft-stale marker triggers a background rescan without blocking the
 * read that noticed it.
 */
public class PartitionScanCacheTest {

    /** Values are {@code partition:field}; the serialized form is the value itself. */
    private static final String A1 = "pa:one";
    private static final String A2 = "pa:two";
    private static final String B1 = "pb:one";

    private InMemoryCacheClient cache;
    private AtomicInteger loads;
    private AtomicLong clock;
    private List<String> table;

    @BeforeMethod
    public void setUp() {
        cache = new InMemoryCacheClient();
        loads = new AtomicInteger();
        clock = new AtomicLong(1_000_000L);
        table = List.of(A1, A2, B1);
    }

    private PartitionScanCache<String> scanCache() {
        return PartitionScanCache.<String>builder()
                .cache(cache)
                .keyPrefix("scan-test:")
                .loadedKey("scan-test-loaded")
                .softTtl(Duration.ofMinutes(1))
                .clock(clock::get)
                .loader(() -> {
                    loads.incrementAndGet();
                    return CompletableFuture.completedFuture(table);
                })
                .partitioner(v -> v.substring(0, v.indexOf(':')))
                .fielder(v -> v.substring(v.indexOf(':') + 1))
                .serializer(v -> v)
                .deserializer(json -> "BAD".equals(json) ? null : json)
                .build();
    }

    @Test
    public void aColdCacheScansAndAnswersFromTheSnapshot() {
        final List<String> pa = scanCache().getPartition("pa").join();

        Assert.assertEquals(loads.get(), 1);
        Assert.assertEquals(Set.copyOf(pa), Set.of(A1, A2), "answered from the loader snapshot");
    }

    @Test
    public void aWarmCacheAnswersWithoutRescanning() {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa").join();
        final int afterFirst = loads.get();

        Assert.assertEquals(Set.copyOf(scan.getPartition("pa").join()), Set.of(A1, A2));
        Assert.assertEquals(scan.getPartition("pb").join(), List.of(B1),
                "one scan populated every partition, not just the one asked for");
        Assert.assertEquals(loads.get(), afterFirst);
    }

    @Test
    public void aValueTheDeserializerRejectsIsSkippedNotReturnedAsNull() {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa").join(); // warm
        cache.putHashField("scan-test:pa", "junk", "BAD").join();

        Assert.assertEquals(Set.copyOf(scan.getPartition("pa").join()), Set.of(A1, A2),
                "an undeserializable row must vanish from the list, not appear as a null element");
    }

    // --- getOne ---

    @Test
    public void aPointLookupIsServedFromTheWarmPartitionHash() {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa").join();

        final Optional<String> found = scan.getOne("pa", "one",
                () -> { throw new AssertionError("a warm hit must not consult the point loader"); }).join();

        Assert.assertEquals(found, Optional.of(A1));
    }

    /** The marker makes a miss authoritative: the whole table is cached, so absent means absent. */
    @Test
    public void aMissWithTheMarkerPresentIsAuthoritativeNotFound() {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa").join();

        final Optional<String> found = scan.getOne("pa", "no-such-field",
                () -> { throw new AssertionError("marker present: the database must not be consulted"); }).join();

        Assert.assertEquals(found, Optional.empty());
    }

    @Test
    public void aColdMissFallsBackToThePointLoaderAndCachesTheResult() {
        final PartitionScanCache<String> scan = scanCache();

        final Optional<String> found = scan.getOne("pa", "one",
                () -> CompletableFuture.completedFuture(Optional.of(A1))).join();

        Assert.assertEquals(found, Optional.of(A1));
        // The point result was cached: a repeat lookup is a hash hit, no loader involved.
        Assert.assertEquals(scan.getOne("pa", "one",
                () -> { throw new AssertionError("cached by the first lookup"); }).join(), Optional.of(A1));
    }

    @Test
    public void aColdMissWhereTheDatabaseAlsoMissesStaysEmpty() {
        Assert.assertEquals(scanCache().getOne("pa", "one",
                () -> CompletableFuture.completedFuture(Optional.empty())).join(), Optional.empty());
    }

    // --- write-through, invalidate ---

    @Test
    public void putWritesThroughToThePartitionHash() {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa").join();

        Assert.assertTrue(scan.put("pa:three").join());

        Assert.assertTrue(scan.getPartition("pa").join().contains("pa:three"));
    }

    @Test
    public void invalidateForcesTheNextReadToRescan() {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa").join();
        final int afterFirst = loads.get();

        Assert.assertTrue(scan.invalidate().join());
        scan.getPartition("pa").join();

        Assert.assertTrue(loads.get() > afterFirst);
    }

    // --- soft-stale background rebuild ---

    @Test
    public void aSoftStaleMarkerTriggersABackgroundRescanWithoutBlockingTheRead() throws Exception {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa").join();
        table = List.of(A1, A2, B1, "pa:new");
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        // The stale read answers from the (old) cache; the rescan happens behind it.
        Assert.assertEquals(Set.copyOf(scan.getPartition("pa").join()), Set.of(A1, A2));

        // Rewind before polling so the poll reads do not themselves look stale and race the assert.
        clock.addAndGet(-Duration.ofMinutes(2).toMillis());
        awaitTrue(() -> scan.getPartition("pa").join().contains("pa:new"),
                "the background rescan never landed");
    }

    /** A mangled marker reads as stale (rebuild) rather than as fresh (serve stale data forever). */
    @Test
    public void anUnparseableMarkerCountsAsStale() throws Exception {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa").join();
        final int afterFirst = loads.get();
        cache.putValue("scan-test-loaded", "not-a-number", Duration.ofMinutes(5)).join();

        scan.getPartition("pa").join();

        awaitTrue(() -> loads.get() > afterFirst, "a garbage marker must trigger a rescan");
    }

    /** Losing the refresh lock means another node is already scanning: serve the cache and do nothing. */
    @Test
    public void losingTheRefreshLockSkipsTheRebuildButStillAnswers() throws Exception {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa").join();
        final int afterWarm = loads.get();
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        Assert.assertTrue(cache.tryAcquireLock(
                CacheKeys.refreshLockKey("scan-test-loaded"), Duration.ofMinutes(5)).join());

        Assert.assertEquals(Set.copyOf(scan.getPartition("pa").join()), Set.of(A1, A2));

        // The loader may be consulted at most once more (the schedule path); the populate must not run.
        Thread.sleep(200);
        Assert.assertEquals(loads.get(), afterWarm, "the lock loser must not scan");
    }

    /** On a cold build the lock loser still answers correctly -- from its own loader snapshot. */
    @Test
    public void aColdBuildLockLoserStillAnswersFromItsSnapshot() {
        Assert.assertTrue(cache.tryAcquireLock(
                CacheKeys.refreshLockKey("scan-test-loaded"), Duration.ofMinutes(5)).join());

        Assert.assertEquals(Set.copyOf(scanCache().getPartition("pa").join()), Set.of(A1, A2));

        Assert.assertTrue(cache.getValue("scan-test-loaded").join().isEmpty(),
                "the loser must not mark loaded: it did not populate");
    }

    /** A loader failure during the background rescan is logged and swallowed, never thrown at a reader. */
    @Test
    public void aFailingBackgroundRescanIsSwallowed() throws Exception {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa").join();
        table = null; // makes the next loader call blow up inside populate
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        Assert.assertEquals(Set.copyOf(scan.getPartition("pa").join()), Set.of(A1, A2),
                "the read that noticed staleness must still answer");

        clock.addAndGet(-Duration.ofMinutes(2).toMillis());
        Thread.sleep(200);
        Assert.assertEquals(Set.copyOf(scan.getPartition("pa").join()), Set.of(A1, A2),
                "a failed rescan must leave the cached data untouched");
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
