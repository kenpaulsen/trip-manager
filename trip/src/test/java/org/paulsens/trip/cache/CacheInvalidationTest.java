package org.paulsens.trip.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * The invalidation broadcast contract: the initiator has already cleared Valkey and its own heap, so an
 * event is an instruction for OTHER instances to drop near-cache heap entries -- nothing more.
 */
public class CacheInvalidationTest {

    @Test
    public void broadcastCarriesOriginAndPrefixes() throws Exception {
        final InMemoryCacheClient client = new InMemoryCacheClient();
        final List<String> payloads = new ArrayList<>();
        try (AutoCloseable ignored =
                client.subscribe(List.of(CacheKeys.CACHE_INVAL_CHANNEL), (ch, p) -> payloads.add(p))) {
            CacheInvalidation.broadcast(client, List.of("t1:person:", "t1:email"));
        }
        assertEquals(payloads.size(), 1);
        assertTrue(payloads.get(0).contains(CacheInvalidation.ORIGIN), "the event must be origin-stamped");
        assertTrue(payloads.get(0).contains("t1:person:"), "the event must carry the cleared prefixes");
    }

    @Test
    public void aForeignEventDropsHeapAndAnOwnEventDoesNot() {
        final CountingCacheClient delegate = new CountingCacheClient();
        final NearCacheClient near = new NearCacheClient(delegate, null);
        delegate.putValue("t1:person:p1", "{\"j\":1}", null);
        warm(near);
        delegate.reads.set(0);
        warm(near);
        assertEquals(delegate.reads.get(), 0, "a warm heap entry serves without the delegate");

        CacheInvalidation.handle(near,
                "{\"origin\":\"" + CacheInvalidation.ORIGIN + "\",\"prefixes\":[\"t1:person:\"]}");
        warm(near);
        assertEquals(delegate.reads.get(), 0,
                "an own-origin event must not drop heap -- the initiator already handled itself");

        CacheInvalidation.handle(near, "{\"origin\":\"another-jvm\",\"prefixes\":[\"t1:person:\"]}");
        warm(near);
        assertEquals(delegate.reads.get(), 1, "a foreign event drops the heap copy; the next read refetches");
    }

    /** A garbled or incomplete event is dropped quietly -- the soft-TTL poll is the loss backstop. */
    @Test
    public void malformedPayloadsAreSwallowed() {
        final CountingCacheClient delegate = new CountingCacheClient();
        final NearCacheClient near = new NearCacheClient(delegate, null);
        CacheInvalidation.handle(near, "not json at all");
        CacheInvalidation.handle(near, "{\"origin\":\"x\"}");
        CacheInvalidation.handle(near, "{}");
        CacheInvalidation.handle(new InMemoryCacheClient(), "{\"origin\":\"x\",\"prefixes\":[\"t1:\"]}");
    }

    /** A broadcast failure is logged and swallowed -- an invalidation must never fail its initiator. */
    @Test
    public void aFailingPublishNeverThrows() {
        final CacheClient failing = new ForwardingCacheClient(new InMemoryCacheClient()) {
            @Override
            public boolean publish(final String channel, final String payload) {
                throw new IllegalStateException("publish boom (expected by the test)");
            }
        };
        CacheInvalidation.broadcast(failing, List.of("t1:"));
    }

    /** The subscribe-facing handler is the same logic, bound to a client. */
    @Test
    public void handlerForDelegatesToHandle() {
        final CountingCacheClient delegate = new CountingCacheClient();
        final NearCacheClient near = new NearCacheClient(delegate, null);
        delegate.putValue("t1:person:p1", "{\"j\":1}", null);
        warm(near);
        delegate.reads.set(0);

        CacheInvalidation.handlerFor(near).accept(CacheKeys.CACHE_INVAL_CHANNEL,
                "{\"origin\":\"another-jvm\",\"prefixes\":[\"t1:person:\"]}");

        warm(near);
        assertEquals(delegate.reads.get(), 1, "the handler must drop the heap entry like handle() does");
    }

    private static Optional<String> warm(final NearCacheClient near) {
        return NearCacheContext.call(Cached.YES, () -> near.getValue("t1:person:p1"));
    }
}
