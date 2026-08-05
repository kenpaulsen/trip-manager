package org.paulsens.trip.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The pub/sub contract, at the level the fakes can prove it.
 *
 * <p>Cross-shard delivery — the property that made this design choose regular {@code PUBLISH} over sharded
 * {@code SPUBLISH} — can only be proven against a real multi-shard cluster, so it lives in
 * {@code ValkeyClusterPubSubIT}. What is checked here is everything that does not need a daemon: the nudge shape,
 * unsubscribe, per-channel isolation, and the no-op behaviour that keeps a cache outage from failing a request.
 */
public class CachePubSubTest {

    @Test
    public void aSubscriberReceivesWhatWasPublishedOnItsChannel() {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        final List<String> got = new CopyOnWriteArrayList<>();
        cache.subscribe(List.of("chat:trip:t1"), (channel, payload) -> got.add(channel + "|" + payload));

        Assert.assertTrue(cache.publish("chat:trip:t1", "{\"upTo\":123}"));

        Assert.assertEquals(got, List.of("chat:trip:t1|{\"upTo\":123}"));
    }

    @Test
    public void channelsAreIsolatedFromEachOther() {
        // Channel names must stay enumerable: ElastiCache Serverless has no PSUBSCRIBE, so nothing may rely on
        // pattern matching to fan a publish out across channels.
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        final List<String> t1 = new CopyOnWriteArrayList<>();
        cache.subscribe(List.of("chat:trip:t1"), (channel, payload) -> t1.add(payload));

        cache.publish("chat:trip:t2", "not for t1");
        cache.publish("chat:trip:t1", "for t1");

        Assert.assertEquals(t1, List.of("for t1"));
    }

    @Test
    public void closingTheHandleStopsDelivery() {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        final List<String> got = new CopyOnWriteArrayList<>();
        final AutoCloseable handle =
                cache.subscribe(List.of("chat:trip:t1"), (channel, payload) -> got.add(payload));

        cache.publish("chat:trip:t1", "first");
        try {
            handle.close();
        } catch (final Exception ex) {
            Assert.fail("closing a subscription must not throw: " + ex);
        }
        cache.publish("chat:trip:t1", "second");

        Assert.assertEquals(got, List.of("first"), "delivery must stop once the handle is closed");
    }

    @Test
    public void manySubscribersOnOneChannelAllGetIt() {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        final List<String> a = new CopyOnWriteArrayList<>();
        final List<String> b = new CopyOnWriteArrayList<>();
        cache.subscribe(List.of("chat:trip:t1"), (channel, payload) -> a.add(payload));
        cache.subscribe(List.of("chat:trip:t1"), (channel, payload) -> b.add(payload));

        cache.publish("chat:trip:t1", "nudge");

        Assert.assertEquals(a, List.of("nudge"));
        Assert.assertEquals(b, List.of("nudge"));
    }

    @Test
    public void publishingWithNoSubscriberIsHarmless() {
        // The common case in production: one task, nobody parked on the long-poll for that channel right now.
        Assert.assertTrue(new InMemoryCacheClient().publish("chat:trip:nobody", "nudge"));
    }

    @Test
    public void aThrowingSubscriberIsNotAllowedToBreakThePublisher() {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        cache.subscribe(List.of("chat:trip:t1"), (channel, payload) -> {
            throw new IllegalStateException("subscriber is broken");
        });
        // The in-memory client delivers inline, so a throwing listener surfaces here rather than being swallowed.
        // Documented deliberately: the Valkey path isolates listeners because it hands off per delivery, and this
        // asserts the fake does NOT silently pretend to.
        Assert.assertThrows(IllegalStateException.class, () -> cache.publish("chat:trip:t1", "nudge"));
    }

    @Test
    public void degradedModeNeitherDeliversNorFails() {
        // Cache off: a send must still succeed, so publish reports false rather than throwing, and subscribing
        // yields a handle that is safe to close. Real-time is what degrades; nothing is lost.
        final NoopCacheClient noop = new NoopCacheClient();
        Assert.assertFalse(noop.publish("chat:trip:t1", "nudge"));
        try {
            noop.subscribe(List.of("chat:trip:t1"), (channel, payload) -> Assert.fail("must not deliver")).close();
        } catch (final Exception ex) {
            Assert.fail("closing a no-op subscription must not throw: " + ex);
        }
    }

    @Test
    public void emptyOrNullSubscriptionsAreRejectedQuietly() {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        final List<String> got = new ArrayList<>();
        try {
            cache.subscribe(List.of(), (channel, payload) -> got.add(payload)).close();
            cache.subscribe(null, (channel, payload) -> got.add(payload)).close();
            cache.subscribe(List.of("chat:trip:t1"), null).close();
        } catch (final Exception ex) {
            Assert.fail("degenerate subscriptions must be no-ops, not exceptions: " + ex);
        }
        Assert.assertEquals(got, List.of());
    }

    @Test
    public void theChannelNameMatchesTheReservedShape() {
        // Reserved long before the feature existed, and kept OUTSIDE the versioned key segment because the payload
        // is a version-free nudge. Also outside FORMAT_VERSION so clearAllCaches() cannot wipe chat.
        Assert.assertEquals(CacheKeys.chatPubSubChannel("abc"), "chat:trip:abc");
        Assert.assertTrue(CacheKeys.chatPubSubChannel("abc").startsWith(CacheKeys.CHAT_CHANNEL_PREFIX));
        Assert.assertFalse(CacheKeys.chatPubSubChannel("abc").startsWith(CacheKeys.CHAT_FORMAT_VERSION));
        Assert.assertFalse(CacheKeys.chatPubSubChannel("abc").startsWith(CacheKeys.FORMAT_VERSION));
    }

    @Test
    public void mapBasedHelpersStillWorkAlongsideSubscriptions() {
        // Guards against the subscribers map colliding with the ordinary store in the fake.
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        cache.subscribe(List.of("chat:trip:t1"), (channel, payload) -> { });
        Assert.assertTrue(cache.putHashField("chat:trip:t1", "f", "v"));
        Assert.assertEquals(cache.getHash("chat:trip:t1"), Map.of("f", "v"));
    }
}
