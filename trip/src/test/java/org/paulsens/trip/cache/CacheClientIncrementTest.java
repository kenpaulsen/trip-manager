package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CacheClientIncrementTest {

    @Test
    public void incrementReturnsGrowingCount() {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        Assert.assertEquals(client.increment("k", 1, Duration.ofMinutes(5)), Optional.of(1L));
        Assert.assertEquals(client.increment("k", 1, Duration.ofMinutes(5)), Optional.of(2L));
        Assert.assertEquals(client.increment("k", 3, null), Optional.of(5L));
    }

    @Test
    public void noopReturnsEmpty() {
        final NoopCacheClient client = new NoopCacheClient();
        Assert.assertTrue(client.increment("k", 1, Duration.ofSeconds(10)).isEmpty());
    }

    @Test
    public void trimSortedSetKeepsNewestByScore() {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        client.addScoredEntries("z", Map.of(
                "a", 1.0,
                "b", 2.0,
                "c", 3.0,
                "d", 4.0));
        Assert.assertTrue(client.trimSortedSet("z", 2));
        final var remaining = client.getRangeByScore("z", Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                false, 100);
        Assert.assertEquals(remaining.size(), 2);
        Assert.assertTrue(remaining.contains("c"));
        Assert.assertTrue(remaining.contains("d"));
    }

    @Test
    public void windowIndexInKeyAvoidsTtlDependency() {
        // InMemory ignores TTL; rate-limit keys put the window in the key name so a new window is a new key.
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final String w10 = CacheKeys.chatRateLimitKey("ch", "p", "b", 10, 1000);
        final String w20 = CacheKeys.chatRateLimitKey("ch", "p", "b", 20, 1000);
        Assert.assertNotEquals(w10, w20);
        client.increment(w10, 5, Duration.ofSeconds(10));
        Assert.assertEquals(client.increment(w20, 1, Duration.ofSeconds(20)), Optional.of(1L));
        Assert.assertEquals(client.increment(w10, 1, Duration.ofSeconds(10)), Optional.of(6L));
    }
}
