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
                .ttlJitter(() -> 0.5) // pins the effective soft TTL to exactly softTtl (no test flake)
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
                .ttlJitter(() -> 0.5) // pins the effective soft TTL to exactly softTtl (no test flake)
                .build();
    }

    @Test
    public void aGarbagePartitionMarkerCountsAsStaleAndTriggersARefresh() throws Exception {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final AtomicInteger loads = new AtomicInteger();
        final PartitionCache<String, String> cache = partition(client, clock);
        final Supplier<List<String>> loader = () -> {
            loads.incrementAndGet();
            return List.of("a-db");
        };
        cache.getAll("p1", loader);
        client.putHashField("tails:p1", CacheKeys.LOADED_AT, "garbage");

        cache.getAll("p1", loader);

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
        final Supplier<List<String>> loader = () -> {
            loads.incrementAndGet();
            return List.of("a-db");
        };
        cache.getAll("p1", loader);
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        Assert.assertTrue(client.tryAcquireLock(
                CacheKeys.refreshLockKey("tails:p1"), Duration.ofMinutes(5)));

        Assert.assertEquals(cache.getAll("p1", loader), List.of("a-db"));

        Thread.sleep(200);
        Assert.assertEquals(loads.get(), 1, "the lock loser must not reload");
    }

    /** A failed write-through drops the partition: serving a hash known to be wrong is the worst outcome. */
    @Test
    public void aFailedPartitionWriteThroughInvalidatesThePartition() {
        final InMemoryCacheClient real = new InMemoryCacheClient();
        final CacheClient failing = Mockito.mock(CacheClient.class, AdditionalAnswers.delegatesTo(real));
        Mockito.doReturn(false)
                .when(failing).putHashField(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString());
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final AtomicInteger loads = new AtomicInteger();
        final PartitionCache<String, String> cache = partition(failing, clock);
        final Supplier<List<String>> loader = () -> {
            loads.incrementAndGet();
            return List.of("a-db");
        };
        cache.getAll("p1", loader);
        final int afterWarm = loads.get();

        Assert.assertTrue(cache.put("p1", "b-new"),
                "the caller is not failed over a cache problem");

        cache.getAll("p1", loader);
        Assert.assertTrue(loads.get() > afterWarm,
                "the partition must reload from the database after a failed write-through");
    }

    @Test
    public void aGarbagePointEnvelopeCountsAsStale() throws Exception {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final AtomicInteger loads = new AtomicInteger();
        final PointCache<String> cache = point(client, clock);
        final Function<String, String> loader = id -> {
            loads.incrementAndGet();
            return "v-" + id;
        };
        Assert.assertEquals(cache.get("x", loader), Optional.of("v-x"));
        // A mangled envelope prefix decodes as legacy (the whole string is the value) and counts as stale.
        client.putValue("pt-tails:x", "99xx|v-x", Duration.ofMinutes(5));

        Assert.assertEquals(cache.get("x", loader), Optional.of("99xx|v-x"));

        final long deadline = System.currentTimeMillis() + 5_000;
        while (loads.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Assert.assertTrue(loads.get() >= 2, "a mangled envelope must trigger a background revalidate");
    }

    @Test
    public void aPointLockLoserServesTheCacheWithoutReloading() throws Exception {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final AtomicInteger loads = new AtomicInteger();
        final PointCache<String> cache = point(client, clock);
        final Function<String, String> loader = id -> {
            loads.incrementAndGet();
            return "v-" + id;
        };
        cache.get("y", loader);
        clock.addAndGet(Duration.ofMinutes(2).toMillis());
        Assert.assertTrue(client.tryAcquireLock(
                CacheKeys.refreshLockKey("pt-tails:y"), Duration.ofMinutes(5)));

        Assert.assertEquals(cache.get("y", loader), Optional.of("v-y"));

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
            return "v-z";
        });
        clock.addAndGet(Duration.ofMinutes(2).toMillis());

        cache.get("z", this::throwDbDown);

        clock.addAndGet(-Duration.ofMinutes(2).toMillis());
        Thread.sleep(200);
        Assert.assertEquals(cache.get("z", id -> {
            throw new AssertionError("cached value must still serve");
        }), Optional.of("v-z"));
    }

    private String throwDbDown(final String id) {
        throw new IllegalStateException("db down");
    }
}
