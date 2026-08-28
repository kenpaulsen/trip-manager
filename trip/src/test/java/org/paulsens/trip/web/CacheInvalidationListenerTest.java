package org.paulsens.trip.web;

import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

/**
 * The startup subscription lifecycle. The suite runs in local mode, so the DAO's cache client is the
 * in-memory one -- whose pub/sub is real enough to prove the subscribe/close choreography.
 */
public class CacheInvalidationListenerTest {

    @Test
    public void subscribesOnInitAndClosesOnDestroy() {
        final CacheInvalidationListener listener = new CacheInvalidationListener();
        final CacheClient client = DAO.getInstance().getCacheClient();

        listener.contextInitialized(null);
        // The listener is now a live subscriber: a foreign-origin broadcast must be delivered to it
        // (and be harmless -- the in-memory client is not a NearCacheClient, so the handler no-ops).
        client.publish(CacheKeys.CACHE_INVAL_CHANNEL, "{\"origin\":\"other\",\"prefixes\":[\"t1:\"]}");

        listener.contextDestroyed(null);
        // Destroy is idempotent and safe to repeat (redeploys can call listeners in odd orders).
        listener.contextDestroyed(null);
    }

    /** A listener that never subscribed (failed startup) must still destroy cleanly. */
    @Test
    public void destroyWithoutInitIsANoop() {
        new CacheInvalidationListener().contextDestroyed(null);
    }

    /** A failed subscribe degrades to polling freshness -- startup must proceed as if nothing happened. */
    @Test
    public void aFailingSubscribeNeverAbortsStartup() {
        final CacheInvalidationListener listener = new CacheInvalidationListener() {
            @Override
            CacheClient client() {
                throw new IllegalStateException("cache unreachable (expected by the test)");
            }
        };
        listener.contextInitialized(null);
        listener.contextDestroyed(null);
    }

    /** A subscription that fails to close is logged, not thrown -- shutdown must not care. */
    @Test
    public void aFailingCloseIsSwallowed() {
        final CacheInvalidationListener listener = new CacheInvalidationListener() {
            @Override
            CacheClient client() {
                final CacheClient mock = org.mockito.Mockito.mock(CacheClient.class);
                org.mockito.Mockito.when(mock.subscribe(org.mockito.ArgumentMatchers.anyCollection(),
                                org.mockito.ArgumentMatchers.any()))
                        .thenReturn(CacheInvalidationListenerTest::throwOnClose);
                return mock;
            }
        };
        listener.contextInitialized(null);
        listener.contextDestroyed(null);
    }

    private static void throwOnClose() throws Exception {
        throw new Exception("close boom (expected by the test)");
    }

    @Test
    public void channelNameStaysOutsideTheClearableNamespaces() {
        assertEquals(CacheKeys.CACHE_INVAL_CHANNEL, "sys:v1:cache_inval");
        org.testng.Assert.assertFalse(CacheKeys.CACHE_INVAL_CHANNEL.startsWith(CacheKeys.FORMAT_VERSION),
                "the channel must survive clearAllCaches");
        org.testng.Assert.assertFalse(CacheKeys.CACHE_INVAL_CHANNEL.startsWith("chat:"),
                "the channel must stay out of the chat namespace");
    }
}
