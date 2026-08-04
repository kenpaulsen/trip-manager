package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link PartitionCache} and {@link PointCache} failure tails: mangled staleness markers, lock losers, and the
 * write-through recovery that drops a partition rather than serving a hash known to be wrong.
 */
public class CacheTemplateTailsTest {

    private static PartitionCache<String, String> partition(final CacheClient client, final AtomicLong clock) {
        return PartitionCache.<String, String>builder()
                .cache(client)
                .keyPrefix("tails:")
                .softTtl(Duration.ofMinutes(1))
                .idGetter(v -> v.substring(0, 1))
                .idFormatter(k -> k)
                .serializer(v -> v)
                .deserializer(v -> v)
                .order(Comparator.naturalOrder())
                .clock(clock::get)
                .build();
    }

    private static PointCache<String> point(final CacheClient client, final AtomicLong clock) {
        return PointCache.<String>builder()
                .cache(client)
                .keyPrefix("pt-tails:")
                .softTtl(Duration.ofMinutes(1))
                .serializer(v -> v)
                .deserializer(v -> v)
                .clock(clock::get)
                .build();
    }

    @Test
    public void aGarbagePartitionMarkerCountsAsStaleAndTriggersARefresh() throws Exception {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final AtomicInteger loads = new AtomicInteger();
        final PartitionCache<String, String> cache = partition(client, clock);
        final Supplier<CompletableFuture<List<String>>> loader = () -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(List.of("a-db"));
        };
        cache.getAll("p1", loader).join();
        client.putHashField("tails:p1", CacheKeys.LOADED_AT, "garbage").join();

        cache.getAll("p1", loader).join();

        final long deadline = System.currentTimeMillis() + 5_000;
        while (loads.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Assert.assertTrue(loads.get() >= 2, "a mangled marker must read as stale, not as fresh forever");
    }

    /** Losing the refresh lock means another node is refreshing: serve the cache and schedule nothing. */
    @Test
    public void aPartitionLockLoserServesTheCacheWithoutReloading() throws Exception {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final AtomicInteger loads = new AtomicInteger();
        final PartitionCache<String, String> cache = partition(client, clock);
        final Supplier<CompletableFuture<List<String>>> loader = () -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(List.of("a-db"));
        };
        cache.getAll("p1", loader).join();
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        Assert.assertTrue(client.tryAcquireLock(
                CacheKeys.refreshLockKey("tails:p1"), Duration.ofMinutes(5)).join());

        Assert.assertEquals(cache.getAll("p1", loader).join(), List.of("a-db"));

        Thread.sleep(200);
        Assert.assertEquals(loads.get(), 1, "the lock loser must not reload");
    }

    /** A failed write-through drops the partition: serving a hash known to be wrong is the worst outcome. */
    @Test
    public void aFailedPartitionWriteThroughInvalidatesThePartition() {
        final InMemoryCacheClient real = new InMemoryCacheClient();
        final CacheClient failing = Mockito.mock(CacheClient.class, AdditionalAnswers.delegatesTo(real));
        Mockito.doReturn(CompletableFuture.completedFuture(false))
                .when(failing).putHashField(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString());
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final AtomicInteger loads = new AtomicInteger();
        final PartitionCache<String, String> cache = partition(failing, clock);
        final Supplier<CompletableFuture<List<String>>> loader = () -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(List.of("a-db"));
        };
        cache.getAll("p1", loader).join();
        final int afterWarm = loads.get();

        Assert.assertTrue(cache.put("p1", "b-new").join(),
                "the caller is not failed over a cache problem");

        cache.getAll("p1", loader).join();
        Assert.assertTrue(loads.get() > afterWarm,
                "the partition must reload from the database after a failed write-through");
    }

    @Test
    public void aGarbagePointMarkerCountsAsStale() throws Exception {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final AtomicInteger loads = new AtomicInteger();
        final PointCache<String> cache = point(client, clock);
        final Function<String, CompletableFuture<String>> loader = id -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture("v-" + id);
        };
        Assert.assertEquals(cache.get("x", loader).join(), Optional.of("v-x"));
        client.putValue(CacheKeys.pointAtKey("pt-tails:x"), "garbage", Duration.ofMinutes(5)).join();

        Assert.assertEquals(cache.get("x", loader).join(), Optional.of("v-x"));

        final long deadline = System.currentTimeMillis() + 5_000;
        while (loads.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Assert.assertTrue(loads.get() >= 2, "a mangled marker must trigger a background revalidate");
    }

    @Test
    public void aPointLockLoserServesTheCacheWithoutReloading() throws Exception {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final AtomicInteger loads = new AtomicInteger();
        final PointCache<String> cache = point(client, clock);
        final Function<String, CompletableFuture<String>> loader = id -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture("v-" + id);
        };
        cache.get("y", loader).join();
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        Assert.assertTrue(client.tryAcquireLock(
                CacheKeys.refreshLockKey("pt-tails:y"), Duration.ofMinutes(5)).join());

        Assert.assertEquals(cache.get("y", loader).join(), Optional.of("v-y"));

        Thread.sleep(200);
        Assert.assertEquals(loads.get(), 1, "the lock loser must not reload");
    }

    /** A loader failure during a point revalidate is logged and swallowed; the cached value keeps serving. */
    @Test
    public void aFailingPointRevalidateIsSwallowed() throws Exception {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final AtomicInteger loads = new AtomicInteger();
        final PointCache<String> cache = point(client, clock);
        cache.get("z", id -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture("v-z");
        }).join();
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        cache.get("z", id -> CompletableFuture.failedFuture(new IllegalStateException("db down"))).join();

        clock.addAndGet(-Duration.ofMinutes(2).toMillis());
        Thread.sleep(200);
        Assert.assertEquals(cache.get("z", id -> {
            throw new AssertionError("cached value must still serve");
        }).join(), Optional.of("v-z"));
    }
}
