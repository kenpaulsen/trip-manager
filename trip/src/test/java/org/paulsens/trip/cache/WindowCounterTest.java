package org.paulsens.trip.cache;

import java.time.Duration;
import java.util.Optional;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@link WindowCounter}: the shared cache counts; when it cannot, a per-server fallback does, bounded or not. */
public class WindowCounterTest {

    @Test
    public void theCacheCountsWithATwoWindowTtl() {
        final CacheClient cache = Mockito.mock(CacheClient.class);
        Mockito.when(cache.increment("k", 1, Duration.ofSeconds(120))).thenReturn(Optional.of(7L));
        Assert.assertEquals(new WindowCounter(0).count(cache, "k", 60), 7L);
        Mockito.when(cache.increment("z", 1, Duration.ofSeconds(1))).thenReturn(Optional.of(1L));
        Assert.assertEquals(new WindowCounter(0).count(cache, "z", 0), 1L, "a TTL is never under a second");
    }

    @Test
    public void withoutTheCacheTheFallbackCountsAndForgets() {
        final CacheClient down = Mockito.mock(CacheClient.class);
        Mockito.when(down.increment(ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        final WindowCounter counter = new WindowCounter(0);
        Assert.assertEquals(counter.count(down, "a", 60), 1L);
        Assert.assertEquals(counter.count(down, "a", 60), 2L);
        counter.forget("a");
        Assert.assertEquals(counter.count(down, "a", 60), 1L, "a spent counter starts over");
        counter.clearForTest();
        Assert.assertEquals(counter.count(down, "a", 60), 1L);
    }

    @Test
    public void aBoundedFallbackForgetsEverythingAtItsSize() {
        final CacheClient down = Mockito.mock(CacheClient.class);
        Mockito.when(down.increment(ArgumentMatchers.anyString(), ArgumentMatchers.anyLong(), ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        final WindowCounter counter = new WindowCounter(2);
        Assert.assertEquals(counter.count(down, "a", 60), 1L);
        Assert.assertEquals(counter.count(down, "a", 60), 2L);
        Assert.assertEquals(counter.count(down, "b", 60), 1L);
        Assert.assertEquals(counter.count(down, "a", 60), 1L, "at two keys the map was cleared, erring toward serving");
        Assert.assertEquals(new WindowCounter(-5).count(down, "x", 60), 1L, "a negative bound is no bound");
    }
}
