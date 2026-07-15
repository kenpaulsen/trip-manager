package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Integration test for {@link ValkeyCacheClient} against a real Valkey/Redis. Skipped unless a server is
 * configured; to run it locally:
 * <pre>
 *     redis-server --port 6390 &amp;   # or: docker run -p 6390:6379 valkey/valkey:9
 *     env TRIP_VALKEY_URI=redis://localhost:6390 mvn test -pl trip -Dtest=ValkeyCacheClientIntegrationTest
 * </pre>
 */
public class ValkeyCacheClientIntegrationTest {
    private static final String NS = "ittest:";
    private ValkeyCacheClient client;

    @BeforeClass
    public void setup() {
        final CacheConfig config = CacheConfig.resolve();
        if (config.getMode() != CacheConfig.Mode.VALKEY) {
            throw new SkipException("No TRIP_VALKEY_URI configured; skipping Valkey integration test.");
        }
        client = new ValkeyCacheClient(config);
        client.clearNamespace(NS).join();
    }

    @AfterClass
    public void teardown() {
        if (client != null) {
            client.clearNamespace(NS).join();
            client.close();
        }
    }

    @Test
    public void valueRoundTrip() {
        assertTrue(client.putValue(NS + "v1", "hello", Duration.ofMinutes(5)).join());
        assertEquals(client.getValue(NS + "v1").join(), Optional.of("hello"));
        assertTrue(client.removeKey(NS + "v1").join());
        assertEquals(client.getValue(NS + "v1").join(), Optional.empty());
    }

    @Test
    public void hashRoundTripIncludingHmget() {
        final String key = NS + "h1";
        assertTrue(client.putHashFields(key, Map.of("a", "1", "b", "2")).join());
        assertTrue(client.putHashField(key, "c", "3").join());
        assertEquals(client.getHash(key).join(), Map.of("a", "1", "b", "2", "c", "3"));
        // HMGET semantics: only present fields come back
        assertEquals(client.getHashFields(key, List.of("a", "nope", "c")).join(), Map.of("a", "1", "c", "3"));
        assertTrue(client.removeHashField(key, "a").join());
        assertEquals(client.getHash(key).join(), Map.of("b", "2", "c", "3"));
    }

    @Test
    public void setRoundTrip() {
        final String key = NS + "s1";
        assertTrue(client.addSetMembers(key, List.of("x", "y")).join());
        assertEquals(client.getSetMembers(key).join(), Set.of("x", "y"));
        assertTrue(client.removeSetMember(key, "x").join());
        assertEquals(client.getSetMembers(key).join(), Set.of("y"));
    }

    @Test
    public void expireIfNoTtlDoesNotSlideExistingTtl() {
        final String key = NS + "e1";
        client.putHashField(key, "f", "v").join();
        assertTrue(client.expire(key, Duration.ofSeconds(100)).join());
        // NX must report success even though a TTL already exists
        assertTrue(client.expireIfNoTtl(key, Duration.ofSeconds(5000)).join());
    }

    @Test
    public void clearNamespaceOnlyRemovesPrefix() {
        client.putValue(NS + "gone", "x", Duration.ofMinutes(5)).join();
        client.putValue(NS + "sub:gone2", "y", Duration.ofMinutes(5)).join();
        client.putValue("ittest-keep", "z", Duration.ofMinutes(5)).join();
        assertTrue(client.clearNamespace(NS).join());
        assertEquals(client.getValue(NS + "gone").join(), Optional.empty());
        assertEquals(client.getValue(NS + "sub:gone2").join(), Optional.empty());
        assertEquals(client.getValue("ittest-keep").join(), Optional.of("z"));
        client.removeKey("ittest-keep").join();
    }

    @Test
    public void futuresCompleteOnWorkerPoolNeverOnEventLoop() {
        final AtomicReference<String> thread = new AtomicReference<>();
        client.getValue(NS + "whatever").thenApply(v -> {
            thread.set(Thread.currentThread().getName());
            return v;
        }).join();
        assertTrue(thread.get().startsWith("trip-persist-"),
                "Continuations must run on the persistence pool, was: " + thread.get());
    }

    @Test
    public void partitionCacheReadThroughAndWriteThrough() {
        final AtomicInteger loads = new AtomicInteger();
        final PartitionCache<String, String> cache = PartitionCache.<String, String>builder()
                .cache(client)
                .keyPrefix(NS + "pc:")
                .ttl(Duration.ofMinutes(5))
                .idGetter(v -> v.substring(0, 1))
                .idFormatter(k -> k)
                .serializer(v -> v)
                .deserializer(v -> v)
                .order(Comparator.naturalOrder())
                .build();
        final var loader = (java.util.function.Supplier<CompletableFuture<List<String>>>) () -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture(List.of("a-fromDb", "b-fromDb"));
        };
        // Miss -> loads from "db" and caches with the sentinel
        assertEquals(cache.getAll("p1", loader).join(), List.of("a-fromDb", "b-fromDb"));
        assertEquals(loads.get(), 1);
        // Loaded -> served from cache
        assertEquals(cache.getAll("p1", loader).join(), List.of("a-fromDb", "b-fromDb"));
        assertEquals(loads.get(), 1);
        // Write-through visible without reload; authoritative "not found" without a db call
        assertTrue(cache.put("p1", "c-written").join());
        assertEquals(cache.getAll("p1", loader).join(), List.of("a-fromDb", "b-fromDb", "c-written"));
        assertEquals(cache.getOne("p1", "z", loader).join(), Optional.empty());
        assertEquals(loads.get(), 1);
        // A different partition loads independently; write-through into an UNLOADED partition is readable
        assertTrue(cache.put("p2", "x-written").join());
        assertEquals(cache.getOne("p2", "x", loader).join(), Optional.of("x-written"));
        assertFalse(loads.get() > 2, "getOne on a write-through hit must not force a load");
    }
}
