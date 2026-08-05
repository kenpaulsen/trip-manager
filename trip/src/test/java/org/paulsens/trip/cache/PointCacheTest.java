package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class PointCacheTest {
    @Test
    public void missLoadsAndHitServes() throws Exception {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final PointCache<String> cache = point(clock);
        final Function<String, CompletableFuture<String>> loader = id -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture("val-" + id);
        };

        assertEquals(cache.get("e1", loader).join(), Optional.of("val-e1"));
        assertEquals(loads.get(), 1);
        assertEquals(cache.get("e1", loader).join(), Optional.of("val-e1"));
        Thread.sleep(50);
        assertEquals(loads.get(), 1);
    }

    @Test
    public void softStaleHitReturnsImmediatelyAndReloadsInBackground() throws Exception {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final CountDownLatch secondLoad = new CountDownLatch(1);
        final PointCache<String> cache = point(clock);
        final Function<String, CompletableFuture<String>> loader = id -> {
            final int n = loads.incrementAndGet();
            if (n == 1) {
                return CompletableFuture.completedFuture("v1");
            }
            secondLoad.countDown();
            return CompletableFuture.completedFuture("v2");
        };

        assertEquals(cache.get("e1", loader).join(), Optional.of("v1"));
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        assertEquals(cache.get("e1", loader).join(), Optional.of("v1"));
        assertTrue(secondLoad.await(3, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(cache.get("e1", loader).join(), Optional.of("v2"));
        Thread.sleep(50);
        assertEquals(loads.get(), 2);
    }

    @Test
    public void backgroundRevalidateRemovesDeletedEntity() throws Exception {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final CountDownLatch secondLoad = new CountDownLatch(1);
        final PointCache<String> cache = point(clock);
        final Function<String, CompletableFuture<String>> loader = id -> {
            final int n = loads.incrementAndGet();
            if (n == 1) {
                return CompletableFuture.completedFuture("v1");
            }
            secondLoad.countDown();
            return CompletableFuture.completedFuture(null);
        };

        assertEquals(cache.get("e1", loader).join(), Optional.of("v1"));
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        assertEquals(cache.get("e1", loader).join(), Optional.of("v1"));
        assertTrue(secondLoad.await(3, TimeUnit.SECONDS));
        Thread.sleep(100);
        // Miss path reloads again after remove
        assertEquals(cache.get("e1", loader).join(), Optional.empty());
        assertTrue(loads.get() >= 3);
    }

    /**
     * The 2026-08-04 incident regression test: when the cache is too unhealthy to accept a SET, the old
     * code UNLINKed the (healthy, merely stale) entry -- during a brown-out that evicted good data and
     * turned a slow cache into a DynamoDB miss-stampede. A failed put must leave the entry alone.
     */
    @Test
    public void aFailedPutMustNotEvictTheExistingEntry() throws Exception {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final FailingPutClient client = new FailingPutClient();
        final PointCache<String> cache = point(clock, client);
        final CountDownLatch refreshTried = new CountDownLatch(1);
        final Function<String, CompletableFuture<String>> loader = id -> {
            refreshTried.countDown();
            return CompletableFuture.completedFuture("fresh");
        };

        assertEquals(cache.get("e1", id -> CompletableFuture.completedFuture("v1")).join(), Optional.of("v1"));
        client.failPuts = true;
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        assertEquals(cache.get("e1", loader).join(), Optional.of("v1"));
        assertTrue(refreshTried.await(3, TimeUnit.SECONDS), "the background refresh must have run");
        Thread.sleep(100);
        assertEquals(client.dataKeyRemovals.get(), 0,
                "a failed refresh put must never remove the entry it could not replace");
        assertEquals(cache.get("e1", loader).join(), Optional.of("v1"),
                "the stale-but-present entry must still serve");
    }

    /** With every refresh permit taken, a soft-stale read serves the hit and schedules NOTHING. */
    @Test
    public void atTheGlobalRefreshCapAStaleReadSkipsTheRefresh() throws Exception {
        final AtomicInteger loads = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final PointCache<String> cache = point(clock);
        final Function<String, CompletableFuture<String>> loader = id -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture("v" + loads.get());
        };

        assertEquals(cache.get("e1", loader).join(), Optional.of("v1"));
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        final int drained = drainAllPermits();
        try {
            assertEquals(cache.get("e1", loader).join(), Optional.of("v1"));
            Thread.sleep(150);
            assertEquals(loads.get(), 1, "no refresh may run while the cap is exhausted");
        } finally {
            releasePermits(drained);
        }
        // With permits back, the next stale read refreshes normally.
        assertEquals(cache.get("e1", loader).join(), Optional.of("v1"));
        awaitLoads(loads, 2);
    }

    /** Jitter bounds: 0.0 makes the effective TTL 0.9x (stale sooner), 1.0 makes it 1.1x (stale later). */
    @Test
    public void ttlJitterWidensAndNarrowsTheStalenessLine() throws Exception {
        final AtomicInteger earlyLoads = new AtomicInteger();
        final AtomicInteger lateLoads = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final PointCache<String> early = pointWithJitter(clock, 0.0, "early:");
        final PointCache<String> late = pointWithJitter(clock, 1.0, "late:");

        assertEquals(early.get("e1", countingLoader(earlyLoads)).join(), Optional.of("v1"));
        assertEquals(late.get("e1", countingLoader(lateLoads)).join(), Optional.of("v1"));
        clock.addAndGet(Duration.ofSeconds(57).toMillis()); // 0.95x of the 60s softTtl

        assertEquals(early.get("e1", countingLoader(earlyLoads)).join(), Optional.of("v1"));
        assertEquals(late.get("e1", countingLoader(lateLoads)).join(), Optional.of("v1"));
        awaitLoads(earlyLoads, 2); // 57s >= 54s effective: stale, refresh runs
        Thread.sleep(100);
        assertEquals(lateLoads.get(), 1, "57s < 66s effective TTL: still fresh, no refresh");
    }

    private static Function<String, CompletableFuture<String>> countingLoader(final AtomicInteger loads) {
        return id -> countLoad(loads);
    }

    private static CompletableFuture<String> countLoad(final AtomicInteger loads) {
        loads.incrementAndGet();
        return CompletableFuture.completedFuture("v1");
    }

    private static void awaitLoads(final AtomicInteger loads, final int expected) throws InterruptedException {
        for (int i = 0; i < 40 && loads.get() < expected; i++) {
            Thread.sleep(50);
        }
        assertEquals(loads.get(), expected);
    }

    private static int drainAllPermits() {
        int drained = 0;
        while (RefreshPermits.tryAcquire()) {
            drained++;
        }
        return drained;
    }

    private static void releasePermits(final int count) {
        for (int i = 0; i < count; i++) {
            RefreshPermits.release();
        }
    }

    private static PointCache<String> point(final AtomicLong clock) {
        return point(clock, new InMemoryCacheClient());
    }

    private static PointCache<String> point(final AtomicLong clock, final CacheClient client) {
        return PointCache.<String>builder()
                .cache(client)
                .keyPrefix("te:")
                .softTtl(Duration.ofMinutes(1))
                .serializer(v -> v)
                .deserializer(v -> v)
                .clock(clock::get)
                .ttlJitter(() -> 0.5) // pins the effective soft TTL to exactly softTtl (no test flake)
                .build();
    }

    private static PointCache<String> pointWithJitter(
            final AtomicLong clock, final double jitter, final String prefix) {
        return PointCache.<String>builder()
                .cache(new InMemoryCacheClient())
                .keyPrefix(prefix)
                .softTtl(Duration.ofMinutes(1))
                .serializer(v -> v)
                .deserializer(v -> v)
                .clock(clock::get)
                .ttlJitter(() -> jitter)
                .build();
    }

    /** Delegates to the in-memory client but can refuse data-key SETs and counts data-key removals. */
    private static final class FailingPutClient extends ForwardingCacheClient {
        volatile boolean failPuts;
        final AtomicInteger dataKeyRemovals = new AtomicInteger();

        FailingPutClient() {
            super(new InMemoryCacheClient());
        }

        @Override
        public CompletableFuture<Boolean> putValue(final String key, final String value, final Duration ttl) {
            if (failPuts) {
                return CompletableFuture.completedFuture(false);
            }
            return super.putValue(key, value, ttl);
        }

        @Override
        public CompletableFuture<Boolean> removeKey(final String key) {
            if (!key.contains("refresh:") && !key.endsWith(CacheKeys.POINT_AT_SUFFIX)) {
                dataKeyRemovals.incrementAndGet();
            }
            return super.removeKey(key);
        }
    }
}
