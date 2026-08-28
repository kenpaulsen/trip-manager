package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
                .ttlJitter(() -> 0.5) // pins the effective soft TTL to exactly softTtl (no test flake)
                .loader(() -> {
                    loads.incrementAndGet();
                    return table;
                })
                .partitioner(v -> v.substring(0, v.indexOf(':')))
                .fielder(v -> v.substring(v.indexOf(':') + 1))
                .serializer(v -> v)
                .deserializer(json -> "BAD".equals(json) ? null : json)
                .build();
    }

    @Test
    public void aColdCacheScansAndAnswersFromTheSnapshot() {
        final List<String> pa = scanCache().getPartition("pa");

        Assert.assertEquals(loads.get(), 1);
        Assert.assertEquals(Set.copyOf(pa), Set.of(A1, A2), "answered from the loader snapshot");
    }

    @Test
    public void aWarmCacheAnswersWithoutRescanning() {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa");
        final int afterFirst = loads.get();

        Assert.assertEquals(Set.copyOf(scan.getPartition("pa")), Set.of(A1, A2));
        Assert.assertEquals(scan.getPartition("pb"), List.of(B1),
                "one scan populated every partition, not just the one asked for");
        Assert.assertEquals(loads.get(), afterFirst);
    }

    @Test
    public void aValueTheDeserializerRejectsIsSkippedNotReturnedAsNull() {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa"); // warm
        cache.putHashField("scan-test:pa", "junk", "BAD");

        Assert.assertEquals(Set.copyOf(scan.getPartition("pa")), Set.of(A1, A2),
                "an undeserializable row must vanish from the list, not appear as a null element");
    }

    // --- getOne ---

    @Test
    public void aPointLookupIsServedFromTheWarmPartitionHash() {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa");

        final Optional<String> found = scan.getOne("pa", "one",
                () -> {
                    throw new AssertionError("a warm hit must not consult the point loader");
                });

        Assert.assertEquals(found, Optional.of(A1));
    }

    /** The marker makes a miss authoritative: the whole table is cached, so absent means absent. */
    @Test
    public void aMissWithTheMarkerPresentIsAuthoritativeNotFound() {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa");

        final Optional<String> found = scan.getOne("pa", "no-such-field",
                () -> {
                    throw new AssertionError("marker present: the database must not be consulted");
                });

        Assert.assertEquals(found, Optional.empty());
    }

    @Test
    public void aColdMissFallsBackToThePointLoaderAndCachesTheResult() {
        final PartitionScanCache<String> scan = scanCache();

        final Optional<String> found = scan.getOne("pa", "one",
                () -> Optional.of(A1));

        Assert.assertEquals(found, Optional.of(A1));
        // The point result was cached: a repeat lookup is a hash hit, no loader involved.
        Assert.assertEquals(scan.getOne("pa", "one",
                () -> {
                    throw new AssertionError("cached by the first lookup");
                }), Optional.of(A1));
    }

    @Test
    public void aColdMissWhereTheDatabaseAlsoMissesStaysEmpty() {
        Assert.assertEquals(scanCache().getOne("pa", "one",
                () -> Optional.empty()), Optional.empty());
    }

    // --- write-through, invalidate ---

    @Test
    public void putWritesThroughToThePartitionHash() {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa");

        Assert.assertTrue(scan.put("pa:three"));

        Assert.assertTrue(scan.getPartition("pa").contains("pa:three"));
    }

    @Test
    public void invalidateForcesTheNextReadToRescan() {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa");
        final int afterFirst = loads.get();

        Assert.assertTrue(scan.invalidate());
        scan.getPartition("pa");

        Assert.assertTrue(loads.get() > afterFirst);
    }

    // --- soft-stale background rebuild ---

    @Test
    public void aSoftStaleMarkerTriggersABackgroundRescanWithoutBlockingTheRead() throws Exception {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa");
        table = List.of(A1, A2, B1, "pa:new");
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        // The stale read answers from the (old) cache; the rescan happens behind it.
        Assert.assertEquals(Set.copyOf(scan.getPartition("pa")), Set.of(A1, A2));

        // Rewind before polling so the poll reads do not themselves look stale and race the assert.
        clock.addAndGet(-Duration.ofMinutes(2).toMillis());
        awaitTrue(() -> scan.getPartition("pa").contains("pa:new"),
                "the background rescan never landed");
    }

    /** A point-lookup hit on a stale cache schedules the same background rescan a partition read would. */
    @Test
    public void aSoftStaleMarkerTriggersARescanOnAGetOneHit() throws Exception {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa");
        final int afterWarm = loads.get();
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        Assert.assertEquals(scan.getOne("pa", "one", Optional::empty), Optional.of(A1));

        awaitTrue(() -> loads.get() > afterWarm, "a stale getOne hit must trigger a background rescan");
    }

    @Test
    public void aFreshMarkerSchedulesNothingOnAGetOneHit() throws Exception {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa");
        final int afterWarm = loads.get();

        Assert.assertEquals(scan.getOne("pa", "one", Optional::empty), Optional.of(A1));

        Thread.sleep(200);
        Assert.assertEquals(loads.get(), afterWarm, "a fresh getOne hit must not scan");
    }

    /** Without the loaded marker a hash holds only write-through entries; a hit must stay schedule-free. */
    @Test
    public void anUnloadedWriteThroughHitSchedulesNothing() throws Exception {
        final PartitionScanCache<String> scan = scanCache();
        Assert.assertTrue(scan.put(A1)); // write-through only: no scan ever ran, no marker exists

        Assert.assertEquals(scan.getOne("pa", "one", Optional::empty), Optional.of(A1));

        Thread.sleep(200);
        Assert.assertEquals(loads.get(), 0, "an unloaded hit must not trigger a scan");
    }

    /** A mangled marker reads as stale (rebuild) rather than as fresh (serve stale data forever). */
    @Test
    public void anUnparseableMarkerCountsAsStale() throws Exception {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa");
        final int afterFirst = loads.get();
        cache.putValue("scan-test-loaded", "not-a-number", Duration.ofMinutes(5));

        scan.getPartition("pa");

        awaitTrue(() -> loads.get() > afterFirst, "a garbage marker must trigger a rescan");
    }

    /** Losing the refresh lock means another node is already scanning: serve the cache and do nothing. */
    @Test
    public void losingTheRefreshLockSkipsTheRebuildButStillAnswers() throws Exception {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa");
        final int afterWarm = loads.get();
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        Assert.assertTrue(cache.tryAcquireLock(
                CacheKeys.refreshLockKey("scan-test-loaded"), Duration.ofMinutes(5)));

        Assert.assertEquals(Set.copyOf(scan.getPartition("pa")), Set.of(A1, A2));

        // The loader may be consulted at most once more (the schedule path); the populate must not run.
        Thread.sleep(200);
        Assert.assertEquals(loads.get(), afterWarm, "the lock loser must not scan");
    }

    /** On a cold build the lock loser still answers correctly -- from its own loader snapshot. */
    @Test
    public void aColdBuildLockLoserStillAnswersFromItsSnapshot() {
        Assert.assertTrue(cache.tryAcquireLock(
                CacheKeys.refreshLockKey("scan-test-loaded"), Duration.ofMinutes(5)));

        Assert.assertEquals(Set.copyOf(scanCache().getPartition("pa")), Set.of(A1, A2));

        Assert.assertTrue(cache.getValue("scan-test-loaded").isEmpty(),
                "the loser must not mark loaded: it did not populate");
    }

    /** A loader failure during the background rescan is logged and swallowed, never thrown at a reader. */
    @Test
    public void aFailingBackgroundRescanIsSwallowed() throws Exception {
        final PartitionScanCache<String> scan = scanCache();
        scan.getPartition("pa");
        table = null; // makes the next loader call blow up inside populate
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        Assert.assertEquals(Set.copyOf(scan.getPartition("pa")), Set.of(A1, A2),
                "the read that noticed staleness must still answer");

        clock.addAndGet(-Duration.ofMinutes(2).toMillis());
        Thread.sleep(200);
        Assert.assertEquals(Set.copyOf(scan.getPartition("pa")), Set.of(A1, A2),
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
