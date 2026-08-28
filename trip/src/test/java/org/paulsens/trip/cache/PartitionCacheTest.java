package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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

    private static PartitionCache<String, String> partition(final AtomicLong clock) {
        return PartitionCache.<String, String>builder()
                .cache(new InMemoryCacheClient())
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
