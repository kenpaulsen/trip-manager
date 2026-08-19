package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.paulsens.trip.model.Privilege;
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
    private final com.fasterxml.jackson.databind.ObjectMapper privMapper =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    private ValkeyCacheClient client;

    @BeforeClass
    public void setup() {
        final CacheConfig config = CacheConfig.resolve();
        if (config.getMode() != CacheConfig.Mode.VALKEY) {
            throw new SkipException("No TRIP_VALKEY_URI configured; skipping Valkey integration test.");
        }
        client = new ValkeyCacheClient(config);
        client.clearNamespace(NS);
    }

    @AfterClass
    public void teardown() {
        if (client != null) {
            client.clearNamespace(NS);
            client.close();
        }
    }

    /** The background-lane connection works against real Valkey and shares the keyspace with foreground. */
    @Test
    public void backgroundLaneRoundTripsAgainstRealValkey() {
        final String key = NS + "lane-bg";
        org.paulsens.trip.util.CacheLane.runBackground(
                () -> client.putValue(key, "v", java.time.Duration.ofMinutes(5)));
        org.testng.Assert.assertEquals(client.getValue(key).orElse(null), "v");
    }

    /**
     * A real publish reaches a real subscriber, and the callback does NOT run on a Netty event loop.
     *
     * <p>The thread assertion is the point. Lettuce delivers listener callbacks on its event loop; the
     * {@code CacheClient} contract forbids running caller code there because the callback goes on to do a cursor
     * read, and every DAO read joins a future — joining from the loop that has to complete it deadlocks that loop.
     * A wrong hand-off would pass a naive "did it arrive" test and then hang the first time a nudge triggered real
     * work, so the test names the thread instead of trusting arrival.
     */
    @Test
    public void pubSubRoundTripDeliversOffTheEventLoop() throws Exception {
        final String channel = NS + "pubsub";
        final java.util.concurrent.CountDownLatch arrived = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<String> payload =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<String> thread =
                new java.util.concurrent.atomic.AtomicReference<>();

        try (java.io.Closeable ignored = asCloseable(client.subscribe(java.util.List.of(channel),
                (ch, msg) -> {
                    payload.set(msg);
                    thread.set(Thread.currentThread().getName());
                    arrived.countDown();
                }))) {
            // SUBSCRIBE is issued asynchronously; a publish that races it is legitimately dropped, so retry until
            // the subscription is live rather than sleeping a magic number and hoping.
            for (int i = 0; i < 50 && arrived.getCount() > 0; i++) {
                client.publish(channel, "{\"upTo\":" + i + "}");
                if (arrived.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
            assertTrue(arrived.getCount() == 0, "no nudge arrived within 5s");
            assertTrue(payload.get() != null && payload.get().contains("upTo"), "payload was " + payload.get());
            final String on = thread.get();
            assertTrue(on != null && !on.contains("lettuce") && !on.contains("nioEventLoop"),
                    "callback must not run on a Lettuce/Netty event loop, but ran on: " + on);
        }
    }

    /** Bridges the SPI's {@code AutoCloseable} handle into try-with-resources without a checked-exception clash. */
    private static java.io.Closeable asCloseable(final AutoCloseable handle) {
        return () -> {
            try {
                handle.close();
            } catch (final Exception ex) {
                throw new java.io.IOException(ex);
            }
        };
    }

    @Test
    public void valueRoundTrip() {
        assertTrue(client.putValue(NS + "v1", "hello", Duration.ofMinutes(5)));
        assertEquals(client.getValue(NS + "v1"), Optional.of("hello"));
        assertTrue(client.removeKey(NS + "v1"));
        assertEquals(client.getValue(NS + "v1"), Optional.empty());
    }

    @Test
    public void hashRoundTripIncludingHmget() {
        final String key = NS + "h1";
        assertTrue(client.putHashFields(key, Map.of("a", "1", "b", "2")));
        assertTrue(client.putHashField(key, "c", "3"));
        assertEquals(client.getHash(key), Map.of("a", "1", "b", "2", "c", "3"));
        // HMGET semantics: only present fields come back
        assertEquals(client.getHashFields(key, List.of("a", "nope", "c")), Map.of("a", "1", "c", "3"));
        assertTrue(client.removeHashField(key, "a"));
        assertEquals(client.getHash(key), Map.of("b", "2", "c", "3"));
    }

    @Test
    public void setRoundTrip() {
        final String key = NS + "s1";
        assertTrue(client.addSetMembers(key, List.of("x", "y")));
        assertEquals(client.getSetMembers(key), Set.of("x", "y"));
        assertTrue(client.removeSetMember(key, "x"));
        assertEquals(client.getSetMembers(key), Set.of("y"));
    }

    @Test
    public void sortedSetPrefixRoundTrip() {
        final String key = NS + "z1";
        assertTrue(client.addSortedSetEntries(key,
                List.of("paulsen|id1", "paulsen|id2", "peterson|id3", "adams|id4")));
        assertEquals(client.getSortedSetByPrefix(key, "paulsen", 10),
                List.of("paulsen|id1", "paulsen|id2"));
        assertEquals(client.getSortedSetByPrefix(key, "p", 10),
                List.of("paulsen|id1", "paulsen|id2", "peterson|id3"));
        // Limit respected, lexicographic order
        assertEquals(client.getSortedSetByPrefix(key, "p", 1), List.of("paulsen|id1"));
        assertEquals(client.getSortedSetByPrefix(key, "zzz", 10), List.of());
        assertTrue(client.removeSortedSetEntries(key, List.of("paulsen|id1")));
        assertEquals(client.getSortedSetByPrefix(key, "paulsen", 10), List.of("paulsen|id2"));
        // Empty collections are no-ops that still succeed
        assertTrue(client.addSortedSetEntries(key, List.of()));
        assertTrue(client.removeSortedSetEntries(key, List.of()));
    }

    @Test
    public void expireAppliesGcTtl() {
        final String key = NS + "e1";
        client.putHashField(key, "f", "v");
        assertTrue(client.expire(key, Duration.ofSeconds(100)));
    }

    @Test
    public void clearNamespaceOnlyRemovesPrefix() {
        client.putValue(NS + "gone", "x", Duration.ofMinutes(5));
        client.putValue(NS + "sub:gone2", "y", Duration.ofMinutes(5));
        client.putValue("ittest-keep", "z", Duration.ofMinutes(5));
        assertTrue(client.clearNamespace(NS));
        assertEquals(client.getValue(NS + "gone"), Optional.empty());
        assertEquals(client.getValue(NS + "sub:gone2"), Optional.empty());
        assertEquals(client.getValue("ittest-keep"), Optional.of("z"));
        client.removeKey("ittest-keep");
    }

    @Test
    public void scoredSortedSetRoundTrip() {
        final String key = NS + "z2";
        assertTrue(client.addScoredEntries(key, Map.of("t1", 100.0d, "t2", 200.0d, "t3", 300.0d)));
        // Ascending by score, inclusive range
        assertEquals(client.getRangeByScore(key, 150.0d, 300.0d, false, 0), List.of("t2", "t3"));
        // Descending
        assertEquals(client.getRangeByScore(key, 150.0d, 300.0d, true, 0), List.of("t3", "t2"));
        // Limit
        assertEquals(client.getRangeByScore(key, 0.0d, 1000.0d, false, 1), List.of("t1"));
        // Unbounded (infinity) bounds
        assertEquals(client.getRangeByScore(key, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 0),
                List.of("t1", "t2", "t3"));
        // Re-score moves a member; ZREM drops it
        assertTrue(client.addScoredEntries(key, Map.of("t1", 999.0d)));
        assertEquals(client.getRangeByScore(key, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 0),
                List.of("t2", "t3", "t1"));
        assertTrue(client.removeSortedSetEntries(key, List.of("t2")));
        assertEquals(client.getRangeByScore(key, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 0),
                List.of("t3", "t1"));
    }

    @Test
    public void tripIndexBuildsFromScanThenServesAndReconciles() {
        final long now = System.currentTimeMillis();
        final long day = 86_400_000L;
        // u1 on both trips; u2 only on the past trip.
        final List<TripIndex.Entry> live = new java.util.ArrayList<>(List.of(
                new TripIndex.Entry("future", now + 10 * day, Set.of("u1")),
                new TripIndex.Entry("past", now - 10 * day, Set.of("u1", "u2"))));
        final AtomicInteger scans = new AtomicInteger();
        final TripIndex index = TripIndex.builder()
                .cache(client)
                .softTtl(Duration.ofHours(24))
                .loader(() -> {
                    scans.incrementAndGet();
                    return List.copyOf(live);
                })
                .build();
        // Cold: first query builds from the "scan" and answers.
        assertEquals(index.activeTripIds(now, 0), List.of("future"));
        assertEquals(scans.get(), 1, "cold query builds once");
        assertEquals(index.inactiveTripIds(now, 0), List.of("past"));
        assertEquals(index.allTripIds(0), List.of("future", "past"));
        assertEquals(scans.get(), 1, "subsequent queries served from the built index, no re-scan");
        // Reverse index
        assertEquals(Set.copyOf(index.tripIdsForUser("u1", 0)), Set.of("future", "past"));
        assertEquals(index.tripIdsForUser("u2", 0), List.of("past"));
        // Write-through: drop u2 from "past"; reverse index reflects it without a rebuild.
        index.update(new TripIndex.Entry("past", now - 10 * day, Set.of("u1", "u2")),
                new TripIndex.Entry("past", now - 10 * day, Set.of("u1")), false);
        assertEquals(index.tripIdsForUser("u2", 0), List.of());
        assertEquals(index.tripIdsForUser("u1", 0).size(), 2);
        index.invalidate();
    }

    @Test
    public void partitionScanCacheBuildsServesAndFallsBackToPointRead() {
        final String tripId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
        final List<Privilege> live = new java.util.ArrayList<>(List.of(
                new Privilege("tripMgr" + tripId, "m", List.of()),
                new Privilege("tripView" + tripId, "v", List.of()),
                new Privilege("peopleAdmin", "pa", List.of())));
        final AtomicInteger scans = new AtomicInteger();
        final AtomicInteger pointLoads = new AtomicInteger();
        final PartitionScanCache<Privilege> cache = PartitionScanCache.<Privilege>builder()
                .cache(client)
                .keyPrefix(NS + "priv:")
                .loadedKey(NS + "priv_loaded")
                .softTtl(Duration.ofHours(24))
                .loader(() -> {
                    scans.incrementAndGet();
                    return List.copyOf(live);
                })
                .partitioner(p -> p.isGlobal() ? "__global__" : p.getTripId())
                .fielder(Privilege::getName)
                .serializer(this::privToJson)
                .deserializer(this::privFromJson)
                .build();
        final java.util.function.Supplier<Optional<Privilege>> failLoader = () -> {
            pointLoads.incrementAndGet();
            return Optional.empty();
        };

        // Cold: one scan builds every partition; the trip partition is one HGETALL.
        assertEquals(names(cache.getPartition(tripId)), Set.of("tripMgr", "tripView"));
        assertEquals(scans.get(), 1);
        assertEquals(names(cache.getPartition("__global__")), Set.of("peopleAdmin"));
        assertEquals(scans.get(), 1, "subsequent partition reads served from cache, no re-scan");

        // getOne: a hit needs no point load; a miss with the loaded marker set is authoritative (no point load).
        assertTrue(cache.getOne("__global__", "peopleAdmin", failLoader).isPresent());
        assertTrue(cache.getOne("__global__", "ghost", failLoader).isEmpty());
        assertEquals(pointLoads.get(), 0, "loaded marker answers 'not found' without a point read");

        // After invalidate (marker + partitions gone), getOne falls back to the point loader.
        cache.invalidate();
        assertTrue(cache.getOne(tripId, "ghost", failLoader).isEmpty());
        assertEquals(pointLoads.get(), 1, "with no marker, a miss falls back to the point read");

        // Write-through into an unloaded partition is readable via getOne.
        cache.put(new Privilege("tripFinView" + tripId, "fv", List.of()));
        assertTrue(cache.getOne(tripId, "tripFinView", failLoader).isPresent());
        cache.invalidate();
    }

    private static Set<String> names(final List<Privilege> privs) {
        return privs.stream().map(Privilege::getName).collect(java.util.stream.Collectors.toSet());
    }

    private String privToJson(final Privilege priv) {
        try {
            return privMapper.writeValueAsString(priv);
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private Privilege privFromJson(final String json) {
        try {
            return privMapper.readValue(json, Privilege.class);
        } catch (final Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Since the virtual-threads port there is no completion hand-off to assert: the call blocks the CALLING
     * thread and returns the value on it. What remains load-bearing is that a blocked call is interruptible
     * -- a cancelled request must be able to reclaim a thread parked on a lost cache command.
     */
    @Test
    public void blockingCallsRunOnTheCallingThreadAndStayInterruptible() throws Exception {
        final AtomicReference<String> thread = new AtomicReference<>();
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final Thread caller = Thread.ofVirtual().name("it-caller").start(() -> {
            client.putValue(NS + "onThread", "x", Duration.ofMinutes(1));
            thread.set(Thread.currentThread().getName());
            done.countDown();
        });
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        caller.join();
        assertEquals(thread.get(), "it-caller", "the value must come back on the calling thread");
    }

    @Test
    public void partitionCacheReadThroughAndWriteThrough() {
        final AtomicInteger loads = new AtomicInteger();
        final PartitionCache<String, String> cache = PartitionCache.<String, String>builder()
                .cache(client)
                .keyPrefix(NS + "pc:")
                .softTtl(Duration.ofMinutes(5))
                .idGetter(v -> v.substring(0, 1))
                .idFormatter(k -> k)
                .serializer(v -> v)
                .deserializer(v -> v)
                .order(Comparator.naturalOrder())
                .build();
        final var loader = (java.util.function.Supplier<List<String>>) () -> {
            loads.incrementAndGet();
            return List.of("a-fromDb", "b-fromDb");
        };
        // Miss -> loads from "db" and caches with the sentinel
        assertEquals(cache.getAll("p1", loader), List.of("a-fromDb", "b-fromDb"));
        assertEquals(loads.get(), 1);
        // Loaded -> served from cache
        assertEquals(cache.getAll("p1", loader), List.of("a-fromDb", "b-fromDb"));
        assertEquals(loads.get(), 1);
        // Write-through visible without reload; authoritative "not found" without a db call
        assertTrue(cache.put("p1", "c-written"));
        assertEquals(cache.getAll("p1", loader), List.of("a-fromDb", "b-fromDb", "c-written"));
        assertEquals(cache.getOne("p1", "z", loader), Optional.empty());
        assertEquals(loads.get(), 1);
        // A different partition loads independently; write-through into an UNLOADED partition is readable
        assertTrue(cache.put("p2", "x-written"));
        assertEquals(cache.getOne("p2", "x", loader), Optional.of("x-written"));
        assertFalse(loads.get() > 2, "getOne on a write-through hit must not force a load");
    }
}
