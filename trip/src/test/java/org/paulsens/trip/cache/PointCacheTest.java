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

    private static PointCache<String> point(final AtomicLong clock) {
        return PointCache.<String>builder()
                .cache(new InMemoryCacheClient())
                .keyPrefix("te:")
                .softTtl(Duration.ofMinutes(1))
                .serializer(v -> v)
                .deserializer(v -> v)
                .clock(clock::get)
                .build();
    }
}
