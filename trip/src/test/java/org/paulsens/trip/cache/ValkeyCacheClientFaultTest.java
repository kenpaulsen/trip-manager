package org.paulsens.trip.cache;

import com.github.fppt.jedismock.RedisServer;
import com.github.fppt.jedismock.operations.server.MockExecutor;
import com.github.fppt.jedismock.server.Response;
import com.github.fppt.jedismock.server.ServiceOptions;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * {@link ValkeyCacheClient} when the server MISBEHAVES, via jedis-mock's command interceptor.
 *
 * <p>Every case here pins the same contract from a different angle: a cache error must degrade (read = miss,
 * write = false, feature = off) and never propagate to a caller. These are the paths the 2026-07-26 Valkey
 * outage went through -- the app survived precisely because errors mapped to fallbacks.
 */
public class ValkeyCacheClientFaultTest {

    /** Flipped per-test to make the named command fail while everything else proceeds normally. */
    private static final AtomicBoolean FAIL_EXPIRE = new AtomicBoolean();
    private static final AtomicBoolean FAIL_SCAN = new AtomicBoolean();
    private static final AtomicBoolean FAIL_GET = new AtomicBoolean();

    private RedisServer server;
    private ValkeyCacheClient client;

    @BeforeClass
    public void start() throws Exception {
        server = RedisServer.newRedisServer()
                .setOptions(ServiceOptions.withInterceptor((state, name, params) -> {
                    // NOTE: PING cannot be failed here -- Lettuce's own connection handshake pings, so an
                    // erroring PING prevents connect() entirely and the startup log-only path stays untestable.
                    if (FAIL_EXPIRE.get() && "expire".equalsIgnoreCase(name)) {
                        return Response.error("ERR expire disabled by test");
                    }
                    if (FAIL_SCAN.get() && "scan".equalsIgnoreCase(name)) {
                        return Response.error("ERR scan disabled by test");
                    }
                    if (FAIL_GET.get() && "get".equalsIgnoreCase(name)) {
                        return Response.error("ERR get disabled by test");
                    }
                    return MockExecutor.proceed(state, name, params);
                }))
                .start();
        System.setProperty("trip.valkey.uri", "redis://" + server.getHost() + ":" + server.getBindPort());
        client = new ValkeyCacheClient(CacheConfig.resolve());
    }

    @AfterClass(alwaysRun = true)
    public void stop() throws Exception {
        System.clearProperty("trip.valkey.uri");
        FAIL_EXPIRE.set(false);
        FAIL_SCAN.set(false);
        FAIL_GET.set(false);
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    /** An erroring read is a MISS, never an exception -- the caller falls back to DynamoDB. */
    @Test
    public void anErroringReadDegradesToAMiss() {
        client.putValue("guarded", "v", null);
        FAIL_GET.set(true);
        try {
            Assert.assertTrue(client.getValue("guarded").isEmpty());
        } finally {
            FAIL_GET.set(false);
        }
        Assert.assertEquals(client.getValue("guarded").orElse(null), "v", "recovery is immediate");
    }

    /**
     * The counter TTL is fire-and-forget: when EXPIRE fails the increment itself must still answer -- a missing
     * TTL costs memory hygiene, not correctness, and must never fail the rate-limit check that asked.
     */
    @Test
    public void aFailedCounterTtlDoesNotFailTheIncrement() {
        FAIL_EXPIRE.set(true);
        try {
            Assert.assertEquals(client.increment("fault-counter", 5, Duration.ofSeconds(30)).orElse(-1L),
                    5L);
        } finally {
            FAIL_EXPIRE.set(false);
        }
    }

    @Test
    public void aFailedScanMakesClearNamespaceReportFalse() {
        FAIL_SCAN.set(true);
        try {
            Assert.assertFalse(client.clearNamespace("fault-ns:"));
        } finally {
            FAIL_SCAN.set(false);
        }
    }

    /** After close, a subscribe attempt degrades to a no-op handle -- long-polls ride their timeout instead. */
    @Test
    public void subscribeOnAClosedClientDegradesToANoop() throws Exception {
        final RedisServer own = RedisServer.newRedisServer().start();
        System.setProperty("trip.valkey.uri", "redis://" + own.getHost() + ":" + own.getBindPort());
        final ValkeyCacheClient closed = new ValkeyCacheClient(CacheConfig.resolve());
        System.setProperty("trip.valkey.uri", "redis://" + server.getHost() + ":" + server.getBindPort());
        closed.close();
        own.stop();

        try (AutoCloseable handle = closed.subscribe(List.of("chan"), (c, p) -> { })) {
            Assert.assertNotNull(handle, "a cache problem degrades the feature, never fails the caller");
        }
    }

    // --- input-shape short-circuits (no server round trip at all) ---

    @Test
    public void emptyCollectionsShortCircuitToSuccess() {
        Assert.assertTrue(client.addSortedSetEntries("z", List.of()));
        Assert.assertTrue(client.removeSortedSetEntries("z", List.of()));
        Assert.assertTrue(client.addScoredEntries("z", java.util.Map.of()));
        Assert.assertTrue(client.trimSortedSet("z", 0));
    }

    @Test
    public void subscribeWithNothingToSubscribeToIsANoop() throws Exception {
        try (AutoCloseable none = client.subscribe(List.of(), (c, p) -> { })) {
            Assert.assertNotNull(none);
        }
        try (AutoCloseable nullChannels = client.subscribe(null, (c, p) -> { })) {
            Assert.assertNotNull(nullChannels);
        }
        try (AutoCloseable nullConsumer = client.subscribe(Set.of("chan"), null)) {
            Assert.assertNotNull(nullConsumer);
        }
    }

    @Test
    public void aNullLockTtlStillAcquiresWithTheDefaultExpiry() {
        Assert.assertTrue(client.tryAcquireLock("fault-lock", null));
        Assert.assertFalse(client.tryAcquireLock("fault-lock", Duration.ofSeconds(5)));
    }

    @Test
    public void aZeroTtlWriteUsesPlainSet() {
        Assert.assertTrue(client.putValue("zero-ttl", "v", Duration.ZERO));
        Assert.assertEquals(client.getValue("zero-ttl").orElse(null), "v");
    }
}
