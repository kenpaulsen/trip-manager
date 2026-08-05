package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link NoopCacheClient}, the {@code trip.cache.mode=off} emergency fallback.
 *
 * <p>The contract worth pinning: every read is a MISS (so callers go to the database) while every write
 * reports SUCCESS (so callers never fail a request over a cache that is deliberately off). The deliberate
 * exceptions: {@code increment} answers empty so rate limiters detect "cache down" and fall back to their
 * in-JVM bucket, and {@code publish} answers false because nothing is listening.
 */
public class NoopCacheClientTest {

    private final NoopCacheClient noop = new NoopCacheClient();

    @Test
    public void everyReadIsAMiss() {
        Assert.assertEquals(noop.getValue("k"), Optional.empty());
        Assert.assertEquals(noop.getHash("k"), Map.of());
        Assert.assertEquals(noop.getHashFields("k", List.of("f")), Map.of());
        Assert.assertEquals(noop.getSortedSetByPrefix("k", "p", 10), List.of());
        Assert.assertEquals(noop.getRangeByScore("k", 0, 1, false, 10), List.of());
        Assert.assertEquals(noop.getSetMembers("k"), Set.of());
    }

    @Test
    public void everyWriteReportsSuccessSoCallersNeverFailOverADisabledCache() {
        Assert.assertTrue(noop.putValue("k", "v", Duration.ofMinutes(1)));
        Assert.assertTrue(noop.removeKey("k"));
        Assert.assertTrue(noop.putHashField("k", "f", "v"));
        Assert.assertTrue(noop.putHashFields("k", Map.of("f", "v")));
        Assert.assertTrue(noop.removeHashField("k", "f"));
        Assert.assertTrue(noop.addSetMembers("k", List.of("m")));
        Assert.assertTrue(noop.removeSetMember("k", "m"));
        Assert.assertTrue(noop.addSortedSetEntries("k", List.of("e")));
        Assert.assertTrue(noop.removeSortedSetEntries("k", List.of("e")));
        Assert.assertTrue(noop.addScoredEntries("k", Map.of("m", 1.0)));
        Assert.assertTrue(noop.expire("k", Duration.ofMinutes(1)));
        Assert.assertTrue(noop.trimSortedSet("k", 10));
        Assert.assertTrue(noop.clearNamespace("k"));
    }

    /** Rate limiters read empty as "cache down" and fall back to an in-JVM bucket -- not zero, not success. */
    @Test
    public void incrementAnswersEmptySoRateLimitersFallBackLocally() {
        Assert.assertEquals(noop.increment("k", 1, Duration.ofMinutes(1)), Optional.empty());
    }

    /** Nothing is listening: a "sent" nudge would be a lie, and a lost nudge costs latency, not messages. */
    @Test
    public void publishReportsFalse() {
        Assert.assertFalse(noop.publish("chan", "payload"));
    }

    @Test
    public void subscribeHandsBackAClosableNoop() throws Exception {
        try (AutoCloseable subscription = noop.subscribe(List.of("chan"), (c, p) -> { })) {
            Assert.assertNotNull(subscription);
        }
    }

    /** Locks grant freely -- nothing is cached, so exclusion protects nothing here. */
    @Test
    public void locksGrantAndReleaseFreely() {
        Assert.assertTrue(noop.tryAcquireLock("k", Duration.ofMinutes(1)));
        Assert.assertTrue(noop.releaseLock("k"));
    }

    /** The interface's own default close: an implementation with nothing to release need not override it. */
    @Test
    public void theInterfaceDefaultCloseIsANoop() {
        org.mockito.Mockito.mock(CacheClient.class, org.mockito.Mockito.CALLS_REAL_METHODS).close();
    }
}
