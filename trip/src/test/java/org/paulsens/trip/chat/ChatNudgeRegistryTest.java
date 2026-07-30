package org.paulsens.trip.chat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.NoopCacheClient;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The wake path, provable without a servlet container because a waiter is just a callback.
 */
public class ChatNudgeRegistryTest {

    private static final String CHANNEL = "trip:t1";

    @Test
    public void aParkedReaderIsWokenByANudgeOnItsChannel() throws Exception {
        final ChatNudgeRegistry registry = new ChatNudgeRegistry(new InMemoryCacheClient());
        final AtomicLong woken = new AtomicLong(-1);
        try (AutoCloseable handle = registry.park(CHANNEL, woken::set)) {
            registry.nudge(CHANNEL, 1769558400123L);
            Assert.assertEquals(woken.get(), 1769558400123L, "the watermark should reach the waiter");
        }
    }

    @Test
    public void aNudgeOnAnotherChannelDoesNotWakeThisReader() throws Exception {
        final ChatNudgeRegistry registry = new ChatNudgeRegistry(new InMemoryCacheClient());
        final AtomicLong woken = new AtomicLong(-1);
        try (AutoCloseable handle = registry.park(CHANNEL, woken::set)) {
            registry.nudge("trip:other", 999L);
            Assert.assertEquals(woken.get(), -1L, "channels must stay isolated");
        }
    }

    @Test
    public void everyReaderOnAChannelIsWoken() throws Exception {
        final ChatNudgeRegistry registry = new ChatNudgeRegistry(new InMemoryCacheClient());
        final List<Long> woken = new CopyOnWriteArrayList<>();
        try (AutoCloseable a = registry.park(CHANNEL, woken::add);
             AutoCloseable b = registry.park(CHANNEL, woken::add)) {
            Assert.assertEquals(registry.parkedCount(CHANNEL), 2);
            registry.nudge(CHANNEL, 5L);
            Assert.assertEquals(woken, List.of(5L, 5L));
        }
    }

    @Test
    public void closingTheHandleUnparksAndTheLastOneOutClosesTheSubscription() throws Exception {
        final ChatNudgeRegistry registry = new ChatNudgeRegistry(new InMemoryCacheClient());
        final AtomicLong woken = new AtomicLong(-1);
        final AutoCloseable first = registry.park(CHANNEL, woken::set);
        final AutoCloseable second = registry.park(CHANNEL, ignored -> { });
        Assert.assertTrue(registry.isSubscribed(CHANNEL));

        second.close();
        Assert.assertTrue(registry.isSubscribed(CHANNEL), "one reader remains, so the subscription stays");
        Assert.assertEquals(registry.parkedCount(CHANNEL), 1);

        first.close();
        // An idle deployment should hold no subscriptions: the last reader out turns the lights off.
        Assert.assertFalse(registry.isSubscribed(CHANNEL));
        Assert.assertEquals(registry.parkedCount(CHANNEL), 0);

        registry.nudge(CHANNEL, 7L);
        Assert.assertEquals(woken.get(), -1L, "an unparked waiter must never be woken");
    }

    @Test
    public void closingTwiceIsHarmless() throws Exception {
        final ChatNudgeRegistry registry = new ChatNudgeRegistry(new InMemoryCacheClient());
        final AutoCloseable handle = registry.park(CHANNEL, ignored -> { });
        handle.close();
        handle.close();
        Assert.assertFalse(registry.isSubscribed(CHANNEL));
    }

    @Test
    public void oneBrokenWaiterDoesNotDenyTheNudgeToOthers() throws Exception {
        final ChatNudgeRegistry registry = new ChatNudgeRegistry(new InMemoryCacheClient());
        final AtomicLong good = new AtomicLong(-1);
        try (AutoCloseable bad = registry.park(CHANNEL, upTo -> {
                 throw new IllegalStateException("waiter is broken");
             });
             AutoCloseable ok = registry.park(CHANNEL, good::set)) {
            registry.nudge(CHANNEL, 11L);
            Assert.assertEquals(good.get(), 11L, "a throwing waiter must be contained, not fatal");
        }
    }

    @Test
    public void aWaiterMayCloseItsOwnHandleFromInsideWake() throws Exception {
        // This is what the long-poll does: the nudge resumes the request, which unparks it. Walking the waiter list
        // without a snapshot would mutate it mid-iteration.
        final ChatNudgeRegistry registry = new ChatNudgeRegistry(new InMemoryCacheClient());
        final AtomicLong woken = new AtomicLong(-1);
        final AutoCloseable[] holder = new AutoCloseable[1];
        holder[0] = registry.park(CHANNEL, upTo -> {
            woken.set(upTo);
            closeQuietly(holder[0]);
        });
        registry.nudge(CHANNEL, 42L);
        Assert.assertEquals(woken.get(), 42L);
        Assert.assertFalse(registry.isSubscribed(CHANNEL), "the waiter unparked itself");
    }

    @Test
    public void withTheCacheDownParkingAndNudgingStillSucceedQuietly() throws Exception {
        // Real-time is what degrades. Nothing here may throw, because the send that triggers a nudge has already
        // been acknowledged and the reader's cursor read is the real source of truth.
        final ChatNudgeRegistry registry = new ChatNudgeRegistry(new NoopCacheClient());
        final AtomicLong woken = new AtomicLong(-1);
        try (AutoCloseable handle = registry.park(CHANNEL, woken::set)) {
            registry.nudge(CHANNEL, 3L);
            Assert.assertEquals(woken.get(), -1L, "nothing is delivered when the cache is off");
        }
    }

    @Test
    public void degenerateParksAreNoOps() throws Exception {
        final ChatNudgeRegistry registry = new ChatNudgeRegistry(new InMemoryCacheClient());
        registry.park(null, ignored -> { }).close();
        registry.park(CHANNEL, null).close();
        registry.nudge(null, 1L);
        Assert.assertFalse(registry.isSubscribed(CHANNEL));
    }

    private static void closeQuietly(final AutoCloseable handle) {
        try {
            handle.close();
        } catch (final Exception ex) {
            Assert.fail("close must not throw: " + ex);
        }
    }
}
