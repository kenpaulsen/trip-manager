package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class PartitionCacheTest {
    @Test
    public void missLoadsSyncThenHitServesCache() {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final PartitionCache<String, String> cache = partition(clock);
        final Supplier<List<String>> loader = () -> {
            loads.incrementAndGet();
            return List.of("a-db", "b-db");
        };

        assertEquals(cache.getAll("p1", loader), List.of("a-db", "b-db"));
        assertEquals(loads.get(), 1);
        assertEquals(cache.getAll("p1", loader), List.of("a-db", "b-db"));
        assertEquals(loads.get(), 1);
        assertEquals(cache.getOne("p1", "z", loader), Optional.empty());
        assertEquals(loads.get(), 1);
    }

    @Test
    public void writeThroughVisibleWithoutReload() {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final PartitionCache<String, String> cache = partition(clock);
        final Supplier<List<String>> loader = () -> {
            loads.incrementAndGet();
            return List.of("a-db");
        };

        cache.getAll("p1", loader);
        assertTrue(cache.put("p1", "c-written"));
        assertEquals(cache.getAll("p1", loader), List.of("a-db", "c-written"));
        assertEquals(loads.get(), 1);
    }

    @Test
    public void softStaleReturnsImmediatelyAndReloadsOnceInBackground() throws Exception {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final CountDownLatch secondLoad = new CountDownLatch(1);
        final PartitionCache<String, String> cache = partition(clock);
        final Supplier<List<String>> loader = () -> {
            final int n = loads.incrementAndGet();
            if (n == 1) {
                return List.of("v1");
            }
            secondLoad.countDown();
            return List.of("v2");
        };

        assertEquals(cache.getAll("p1", loader), List.of("v1"));
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        assertEquals(cache.getAll("p1", loader), List.of("v1"));
        assertTrue(secondLoad.await(3, TimeUnit.SECONDS), "background revalidate should run");

        // Rewind the clock before polling. The loop below reads with the SAME call that triggers a background
        // revalidate when an entry is stale, so with the clock left 2 minutes ahead the test races itself: if
        // the reload has not landed by the first poll, that poll sees a stale entry and fires ANOTHER load,
        // and the "exactly 2 loads" assertion fails. It only showed up under a loaded machine, which is the
        // worst way to find out. Reads are fresh now, so polling observes the reload instead of provoking one.
        clock.addAndGet(-Duration.ofMinutes(2).toMillis());

        List<String> latest = List.of("v1");
        for (int i = 0; i < 100; i++) {
            latest = cache.getAll("p1", loader);
            if (latest.equals(List.of("v2"))) {
                break;
            }
            Thread.sleep(20);
        }
        assertEquals(latest, List.of("v2"));
        assertEquals(loads.get(), 2);
    }

    @Test
    public void concurrentSoftStaleTriggersSingleLoad() throws Exception {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final CountDownLatch enteredLoad = new CountDownLatch(1);
        final CountDownLatch releaseLoad = new CountDownLatch(1);
        final PartitionCache<String, String> cache = partition(clock);
        final Supplier<List<String>> loader = () -> {
            final int n = loads.incrementAndGet();
            if (n == 1) {
                return List.of("v1");
            }
            enteredLoad.countDown();
            try {
                assertTrue(releaseLoad.await(3, TimeUnit.SECONDS));
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return List.of("v2");
        };

        cache.getAll("p1", loader);
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        final int threads = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    cache.getAll("p1", loader);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertTrue(enteredLoad.await(3, TimeUnit.SECONDS));
        releaseLoad.countDown();
        Thread.sleep(100);
        assertEquals(loads.get(), 2);
    }

    @Test
    public void writeThroughDoesNotResetSoftAge() throws Exception {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final CountDownLatch secondLoad = new CountDownLatch(1);
        final PartitionCache<String, String> cache = partition(clock);
        final Supplier<List<String>> loader = () -> {
            final int n = loads.incrementAndGet();
            if (n >= 2) {
                secondLoad.countDown();
            }
            return List.of("v1");
        };

        cache.getAll("p1", loader);
        clock.addAndGet(Duration.ofSeconds(30).toMillis());
        cache.put("p1", "v1b"); // must NOT freshen LOADED_AT
        clock.addAndGet(Duration.ofSeconds(40).toMillis()); // 70s from load > 1 min softTtl

        cache.getAll("p1", loader);
        assertTrue(secondLoad.await(3, TimeUnit.SECONDS), "soft revalidate should still run after write-through");
        assertTrue(loads.get() >= 2);
    }

    @Test
    public void revalidateRemovesDeletedRowStillEqualToSnapshot() throws Exception {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final AtomicReference<List<String>> db = new AtomicReference<>(List.of("a-db", "b-db"));
        final AtomicInteger loads = new AtomicInteger();
        final PartitionCache<String, String> cache = partition(clock);
        final Supplier<List<String>> loader = () -> {
            loads.incrementAndGet();
            return db.get();
        };

        assertEquals(cache.getAll("p1", loader), List.of("a-db", "b-db"));
        db.set(List.of("a-db")); // out-of-band delete of b
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        cache.getAll("p1", loader); // schedules refresh
        List<String> latest = List.of("a-db", "b-db");
        for (int i = 0; i < 100; i++) {
            latest = cache.getAll("p1", loader);
            if (latest.equals(List.of("a-db"))) {
                break;
            }
            Thread.sleep(20);
        }
        assertEquals(latest, List.of("a-db"));
        assertEquals(cache.getOne("p1", "b", loader), Optional.empty());
        assertTrue(loads.get() >= 2);
    }

    @Test
    public void revalidateKeepsWriteThroughAbsentFromStaleLoader() throws Exception {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final CountDownLatch enteredSecondLoad = new CountDownLatch(1);
        final CountDownLatch releaseSecondLoad = new CountDownLatch(1);
        final AtomicInteger loads = new AtomicInteger();
        final PartitionCache<String, String> cache = partition(clock);
        final Supplier<List<String>> loader = () -> {
            final int n = loads.incrementAndGet();
            if (n == 1) {
                return List.of("a-db");
            }
            enteredSecondLoad.countDown();
            try {
                assertTrue(releaseSecondLoad.await(3, TimeUnit.SECONDS));
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            // Stale loader: does not include concurrent write-through "c-written"
            return List.of("a-db");
        };

        cache.getAll("p1", loader);
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        cache.getAll("p1", loader); // start background refresh
        assertTrue(enteredSecondLoad.await(3, TimeUnit.SECONDS));
        assertTrue(cache.put("p1", "c-written"));
        releaseSecondLoad.countDown();
        Thread.sleep(150);

        assertTrue(cache.getAll("p1", loader).contains("c-written"),
                "write-through during refresh must not be reconcile-deleted");
        assertEquals(cache.getOne("p1", "c", loader), Optional.of("c-written"));
    }

    /**
     * The audit's case: a save's own Cached.NO read is the stale hit that starts the reload, and the reload's
     * eventually consistent query reads the row BEFORE the save commits. The unconditional overlay then put the
     * pre-save value back over the save's write-through (and a removal came back the same way).
     */
    @Test
    public void revalidateKeepsAWriteThatLandedOnAnExistingRowDuringTheReload() throws Exception {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final CountDownLatch enteredSecondLoad = new CountDownLatch(1);
        final CountDownLatch releaseSecondLoad = new CountDownLatch(1);
        final AtomicInteger loads = new AtomicInteger();
        final PartitionCache<String, String> cache = partition(clock);
        final Supplier<List<String>> loader =
                () -> tableBeforeTheWrites(loads, enteredSecondLoad, releaseSecondLoad);

        cache.getAll("p1", loader);
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        cache.getAll("p1", loader); // start background refresh
        assertTrue(enteredSecondLoad.await(3, TimeUnit.SECONDS));
        assertTrue(cache.put("p1", "a-edited"));
        assertTrue(cache.remove("p1", "b"));
        releaseSecondLoad.countDown();
        // The reload stamps a fresh LOADED_AT when it lands; rewind so the polls below are not stale reads.
        clock.addAndGet(-Duration.ofMinutes(2).toMillis());
        for (int i = 0; i < 100 && loads.get() < 2; i++) {
            Thread.sleep(20);
        }
        Thread.sleep(150);

        assertEquals(cache.getOne("p1", "a", loader), Optional.of("a-edited"), "the edit survives the reload");
        assertEquals(cache.getOne("p1", "b", loader), Optional.empty(), "the removal survives the reload");
        assertEquals(cache.getOne("p1", "c", loader), Optional.of("c-db"), "an untouched row is still refreshed");
    }

    /**
     * A key that goes away while the reload reads (a failed write-through's recover, an invalidate, the admin
     * Clear caches) leaves every field missing. The per-field compare read that as "each row was removed during
     * the load", put none of them back and still marked the partition loaded: an authoritative empty list until
     * the soft TTL. A dropped key is a cold load.
     */
    @Test
    public void aKeyDroppedDuringTheReloadIsLoadedInFull() throws Exception {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final CountDownLatch enteredSecondLoad = new CountDownLatch(1);
        final CountDownLatch releaseSecondLoad = new CountDownLatch(1);
        final AtomicInteger loads = new AtomicInteger();
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final PartitionCache<String, String> cache = partition(clock, client);
        final Supplier<List<String>> loader =
                () -> tableBeforeTheWrites(loads, enteredSecondLoad, releaseSecondLoad);

        cache.getAll("p1", loader);
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        cache.getAll("p1", loader); // start background refresh
        assertTrue(enteredSecondLoad.await(3, TimeUnit.SECONDS));
        assertTrue(cache.invalidate("p1"));
        releaseSecondLoad.countDown();
        clock.addAndGet(-Duration.ofMinutes(2).toMillis());
        awaitLoaded(client, "t:p1");

        assertEquals(cache.getAll("p1", loader), List.of("a-db", "b-db", "c-db"), "every loader row is cached");
        assertEquals(loads.get(), 2, "and the list came from the cache the reload filled");
    }

    /** The other half of a cold load: what was written through AFTER the drop is newer than the loader's read. */
    @Test
    public void aWriteThatLandsAfterTheDropSurvivesTheReload() throws Exception {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final CountDownLatch enteredSecondLoad = new CountDownLatch(1);
        final CountDownLatch releaseSecondLoad = new CountDownLatch(1);
        final AtomicInteger loads = new AtomicInteger();
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final PartitionCache<String, String> cache = partition(clock, client);
        final Supplier<List<String>> loader =
                () -> tableBeforeTheWrites(loads, enteredSecondLoad, releaseSecondLoad);

        cache.getAll("p1", loader);
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        cache.getAll("p1", loader); // start background refresh
        assertTrue(enteredSecondLoad.await(3, TimeUnit.SECONDS));
        assertTrue(client.removeKey("t:p1"));
        assertTrue(cache.put("p1", "c-written"));
        releaseSecondLoad.countDown();
        clock.addAndGet(-Duration.ofMinutes(2).toMillis());
        awaitLoaded(client, "t:p1");

        assertEquals(cache.getAll("p1", loader), List.of("a-db", "b-db", "c-written"));
        assertEquals(loads.get(), 2);
    }

    /**
     * A COLD load (no sentinel) over a row that was only written through: the drop of the whole key during the
     * load must not read as "that row was removed", or the partition is marked loaded without it.
     */
    @Test
    public void aKeyDroppedDuringAColdLoadIsLoadedInFull() throws Exception {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final CountDownLatch enteredLoad = new CountDownLatch(1);
        final CountDownLatch releaseLoad = new CountDownLatch(1);
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final PartitionCache<String, String> cache = partition(clock, client);
        assertTrue(cache.put("p1", "a-written")); // write-through into a hash nobody has loaded
        final Supplier<List<String>> loader = () -> blockedTable(enteredLoad, releaseLoad);

        final Thread reader = Thread.ofVirtual().start(() -> cache.getAll("p1", loader));
        assertTrue(enteredLoad.await(3, TimeUnit.SECONDS));
        assertTrue(client.removeKey("t:p1")); // a failed write-through's recover, an invalidate, Clear caches
        releaseLoad.countDown();
        reader.join(TimeUnit.SECONDS.toMillis(3));

        assertEquals(cache.getAll("p1", ArrayList::new), List.of("a-db", "b-db", "c-db"),
                "every loader row is cached, the written-through one included");
    }

    /** A drop, then ANOTHER cold load's mark: the first load's own mark is gone, so it is still a drop. */
    @Test
    public void aDropFollowedByAnotherLoadsMarkIsStillADrop() throws Exception {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final CountDownLatch enteredLoad = new CountDownLatch(1);
        final CountDownLatch releaseLoad = new CountDownLatch(1);
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final PartitionCache<String, String> cache = partition(clock, client);
        assertTrue(cache.put("p1", "a-written"));
        final Supplier<List<String>> loader = () -> blockedTable(enteredLoad, releaseLoad);

        final Thread reader = Thread.ofVirtual().start(() -> cache.getAll("p1", loader));
        assertTrue(enteredLoad.await(3, TimeUnit.SECONDS));
        assertTrue(client.removeKey("t:p1"));
        assertTrue(client.putHashFields("t:p1", Map.of(PartitionCache.LOAD_MARK + "second-load", "1")));
        releaseLoad.countDown();
        reader.join(TimeUnit.SECONDS.toMillis(3));

        assertEquals(cache.getAll("p1", ArrayList::new), List.of("a-db", "b-db", "c-db"),
                "every loader row is cached: the drop was seen despite the other load's mark");
    }

    /**
     * Two overlapping cold loads and a removal between them: the second load's mark is not a drop, so the first
     * load still trusts its snapshot and does not put back the row removed while it loaded.
     */
    @Test
    public void aSecondColdLoadDoesNotBringBackARowRemovedDuringTheFirst() throws Exception {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final CountDownLatch enteredLoad = new CountDownLatch(1);
        final CountDownLatch releaseLoad = new CountDownLatch(1);
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final PartitionCache<String, String> cache = partition(clock, client);
        assertTrue(cache.put("p1", "a-written"));
        assertTrue(cache.put("p1", "c-written"));
        final Supplier<List<String>> loader = () -> blockedTable(enteredLoad, releaseLoad);

        final Thread reader = Thread.ofVirtual().start(() -> cache.getAll("p1", loader));
        assertTrue(enteredLoad.await(3, TimeUnit.SECONDS));
        assertTrue(cache.remove("p1", "c"));       // deleted while the (stale) load still returns it
        assertTrue(client.putHashFields("t:p1", Map.of(PartitionCache.LOAD_MARK + "second-load", "1")));
        releaseLoad.countDown();
        reader.join(TimeUnit.SECONDS.toMillis(3));

        assertEquals(cache.getAll("p1", ArrayList::new), List.of("a-db", "b-db"),
                "the removed row stays removed");
    }

    /**
     * The drop lands BETWEEN the cold load's first look and its mark: the mark write then recreates the key holding
     * only the mark. The snapshot is read after the mark, so it already shows the drop, and every loader row is
     * cached rather than skipped as "removed during the load".
     */
    @Test
    public void aKeyDroppedJustBeforeTheMarkIsLoadedInFull() {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final CacheClient dropsBeforeTheMark = Mockito.mock(CacheClient.class, AdditionalAnswers.delegatesTo(client));
        Mockito.doAnswer(call -> dropThenWrite(client, call)).when(dropsBeforeTheMark).putHashFields(
                ArgumentMatchers.eq("t:p1"), ArgumentMatchers.argThat(PartitionCacheTest::isTheMarkWrite));
        final PartitionCache<String, String> cache = partition(clock, dropsBeforeTheMark);
        assertTrue(cache.put("p1", "a-written")); // a written-through row in a hash nobody has loaded

        cache.getAll("p1", () -> List.of("a-db", "b-db", "c-db"));

        assertEquals(cache.getAll("p1", ArrayList::new), List.of("a-db", "b-db", "c-db"),
                "every loader row is cached, the one dropped before the mark included");
    }

    private static boolean isTheMarkWrite(final Map<String, String> fields) {
        return fields != null && fields.keySet().stream().anyMatch(field -> field.startsWith(PartitionCache.LOAD_MARK));
    }

    /** The drop a failed write-through's recover makes, just before the mark write goes through. */
    private static boolean dropThenWrite(final InMemoryCacheClient real, final InvocationOnMock call) {
        real.removeKey("t:p1");
        return real.putHashFields(call.getArgument(0), call.getArgument(1));
    }

    private static List<String> blockedTable(final CountDownLatch entered, final CountDownLatch release) {
        entered.countDown();
        awaitRelease(release);
        return List.of("a-db", "b-db", "c-db");
    }

    /**
     * What the table held when a reload read it, before the test's writes. The SECOND load (the background
     * reload) waits between its read and its return, which is where a test acts "during the reload".
     */
    private static List<String> tableBeforeTheWrites(final AtomicInteger loads, final CountDownLatch entered,
            final CountDownLatch release) {
        if (loads.incrementAndGet() == 2) {
            entered.countDown();
            awaitRelease(release);
        }
        return List.of("a-db", "b-db", "c-db");
    }

    private static void awaitRelease(final CountDownLatch release) {
        try {
            assertTrue(release.await(3, TimeUnit.SECONDS));
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits for a reload to land: the sentinel is the last thing it leaves missing after a drop. */
    private static void awaitLoaded(final CacheClient client, final String key) throws InterruptedException {
        for (int i = 0; i < 250 && !client.getHash(key).containsKey(CacheKeys.LOADED_SENTINEL); i++) {
            Thread.sleep(20);
        }
        assertTrue(client.getHash(key).containsKey(CacheKeys.LOADED_SENTINEL), "the reload never landed");
    }

    private static PartitionCache<String, String> partition(final AtomicLong clock) {
        return partition(clock, new InMemoryCacheClient());
    }

    private static PartitionCache<String, String> partition(final AtomicLong clock, final CacheClient client) {
        return PartitionCache.<String, String>builder()
                .cache(client)
                .keyPrefix("t:")
                .softTtl(Duration.ofMinutes(1))
                .idGetter(v -> v.substring(0, 1))
                .idFormatter(k -> k)
                .serializer(v -> v)
                .deserializer(v -> v)
                .order(Comparator.naturalOrder())
                .clock(clock::get)
                .ttlJitter(() -> 0.5) // pins the effective soft TTL to exactly softTtl (no test flake)
                .build();
    }
}
