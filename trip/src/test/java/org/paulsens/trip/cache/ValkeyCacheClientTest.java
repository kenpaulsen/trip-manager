package org.paulsens.trip.cache;

import com.github.fppt.jedismock.RedisServer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * {@link ValkeyCacheClient} against a real RESP server, in-process.
 *
 * <p>jedis-mock rather than Testcontainers on purpose: a container would make Docker a hard requirement for
 * {@code mvn test}, and this needs none. It speaks enough of the protocol for the whole command surface this
 * client uses, including the parts most likely to be got wrong: {@code ZRANGEBYLEX}, {@code ZREMRANGEBYRANK},
 * SCAN-based namespace clearing and pub/sub.
 *
 * <p>What this cannot cover is topology: the cluster branch, and the reader-port failure behind the 2026-07-26
 * outage, still belong to the webtest suite's real-Valkey mode.
 */
public class ValkeyCacheClientTest {

    private RedisServer server;
    private ValkeyCacheClient client;

    @BeforeClass
    public void start() throws Exception {
        server = RedisServer.newRedisServer().start();
        System.setProperty("trip.cache.mode", "valkey");
        System.setProperty("trip.valkey.uri", "redis://" + server.getHost() + ":" + server.getBindPort());
        client = new ValkeyCacheClient(CacheConfig.resolve());
    }

    @AfterClass(alwaysRun = true)
    public void stop() throws Exception {
        System.clearProperty("trip.cache.mode");
        System.clearProperty("trip.valkey.uri");
        if (server != null) {
            server.stop();
        }
    }

    private static <T> T join(final CompletableFuture<T> future) {
        return future.orTimeout(10, TimeUnit.SECONDS).join();
    }

    @Test
    public void stringsRoundTrip() {
        Assert.assertTrue(join(client.putValue("k1", "v1", null)));
        Assert.assertEquals(join(client.getValue("k1")).orElse(null), "v1");
        Assert.assertTrue(join(client.removeKey("k1")));
        Assert.assertTrue(join(client.getValue("k1")).isEmpty());
    }

    @Test
    public void stringsHonourTtl() {
        Assert.assertTrue(join(client.putValue("k2", "v2", Duration.ofSeconds(30))));
        Assert.assertEquals(join(client.getValue("k2")).orElse(null), "v2");
        Assert.assertTrue(join(client.expire("k2", Duration.ofSeconds(60))));
    }

    @Test
    public void hashesRoundTrip() {
        Assert.assertTrue(join(client.putHashField("h1", "f1", "a")));
        Assert.assertTrue(join(client.putHashFields("h1", Map.of("f2", "b", "f3", "c"))));
        Assert.assertEquals(join(client.getHash("h1")), Map.of("f1", "a", "f2", "b", "f3", "c"));
        Assert.assertEquals(join(client.getHashFields("h1", List.of("f1", "f3"))), Map.of("f1", "a", "f3", "c"));
        Assert.assertTrue(join(client.removeHashField("h1", "f1")));
    }

    @Test
    public void setsRoundTrip() {
        Assert.assertTrue(join(client.addSetMembers("s1", List.of("m1", "m2"))));
        Assert.assertEquals(join(client.getSetMembers("s1")), Set.of("m1", "m2"));
        Assert.assertTrue(join(client.removeSetMember("s1", "m1")));
        Assert.assertEquals(join(client.getSetMembers("s1")), Set.of("m2"));
    }

    @Test
    public void lexSortedSetsRoundTrip() {
        Assert.assertTrue(join(client.addSortedSetEntries("z1", List.of("apple", "apricot", "banana"))));
        Assert.assertEquals(join(client.getSortedSetByPrefix("z1", "ap", 10)), List.of("apple", "apricot"));
        Assert.assertTrue(join(client.removeSortedSetEntries("z1", List.of("apple"))));
    }

    @Test
    public void scoredSetsAndTrimming() {
        Assert.assertTrue(join(client.addScoredEntries("z2", Map.of("a", 1.0, "b", 2.0, "c", 3.0))));
        Assert.assertTrue(join(client.trimSortedSet("z2", 2)));
    }

    @Test
    public void countersIncrement() {
        Assert.assertEquals(join(client.increment("c1", 5, Duration.ofSeconds(30))).orElse(-1L), 5L);
        Assert.assertEquals(join(client.increment("c1", 3, Duration.ofSeconds(30))).orElse(-1L), 8L);
    }

    @Test
    public void locksAreExclusive() {
        Assert.assertTrue(join(client.tryAcquireLock("lock1", Duration.ofSeconds(30))));
        Assert.assertFalse(join(client.tryAcquireLock("lock1", Duration.ofSeconds(30))),
                "A second acquire of a held lock must be refused -- this is what NoopCacheClient gets wrong.");
        Assert.assertTrue(join(client.releaseLock("lock1")));
        Assert.assertTrue(join(client.tryAcquireLock("lock1", Duration.ofSeconds(30))));
    }

    @Test
    public void namespaceClearScansAndDeletes() {
        join(client.putValue("ns:a", "1", null));
        join(client.putValue("ns:b", "2", null));
        join(client.putValue("other:c", "3", null));
        Assert.assertTrue(join(client.clearNamespace("ns:")));
        Assert.assertTrue(join(client.getValue("ns:a")).isEmpty());
        Assert.assertEquals(join(client.getValue("other:c")).orElse(null), "3");
    }

    @Test
    public void scoredRangesQueryBothDirectionsAndHonourInfinities() {
        join(client.addScoredEntries("z3", Map.of("old", 100.0, "mid", 200.0, "new", 300.0)));

        Assert.assertEquals(join(client.getRangeByScore("z3", 150.0, 250.0, false, 10)), List.of("mid"));
        Assert.assertEquals(join(client.getRangeByScore(
                        "z3", Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 10)),
                List.of("old", "mid", "new"), "infinities map to unbounded, not to a numeric literal");
        Assert.assertEquals(join(client.getRangeByScore(
                        "z3", Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, true, 10)),
                List.of("new", "mid", "old"), "reverse order for newest-first listings");
        Assert.assertEquals(join(client.getRangeByScore(
                "z3", Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, true, 2)).size(), 2);
        Assert.assertEquals(join(client.getRangeByScore(
                "z3", Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 0)).size(), 3,
                "a non-positive limit means unlimited");
    }

    /** A subscriber that throws must be contained by the hand-off, never bounce back into Lettuce/Netty. */
    @Test
    public void aThrowingSubscriberIsContained() throws Exception {
        final CountDownLatch delivered = new CountDownLatch(1);
        try (AutoCloseable ignored = client.subscribe(List.of("chan2"), (channel, payload) -> {
            delivered.countDown();
            throw new IllegalStateException("listener blew up");
        })) {
            Thread.sleep(300);
            join(client.publish("chan2", "boom"));
            Assert.assertTrue(delivered.await(10, TimeUnit.SECONDS));
            Thread.sleep(100); // the throw happens after countDown; give it time to be (not) propagated
        }
        Assert.assertTrue(join(client.putValue("still-alive", "yes", null)),
                "the client must still serve after a listener failure");
    }

    @Test
    public void pubSubDeliversToSubscriber() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> got = new AtomicReference<>();
        try (AutoCloseable ignored = client.subscribe(List.of("chan1"), (channel, payload) -> {
            got.set(payload);
            latch.countDown();
        })) {
            Thread.sleep(300);
            join(client.publish("chan1", "hello"));
            Assert.assertTrue(latch.await(10, TimeUnit.SECONDS), "No pub/sub message arrived");
            Assert.assertEquals(got.get(), "hello");
        }
    }
}
