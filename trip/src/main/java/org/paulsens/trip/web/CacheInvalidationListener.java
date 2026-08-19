package org.paulsens.trip.web;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheInvalidation;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.dynamo.DAO;

/**
 * Subscribes this instance to {@link CacheKeys#CACHE_INVAL_CHANNEL} so an invalidation initiated anywhere
 * (another instance's admin button, the REST endpoint, a migration-script hook) drops this JVM's near-cache
 * heap copies too. Declared in the live {@code web.xml} AFTER {@code TripBootstrapListener}, whose
 * being-first is load-bearing (LocalMode resolution).
 *
 * <p>Failure posture: a failed subscribe degrades freshness to the soft-TTL polling backstop and must never
 * abort startup. In local/memory/off cache modes {@code subscribe} is a no-op, which is correct for a
 * single JVM. Netty-loop safety is handled below us -- {@code ValkeyCacheClient} hands every subscription
 * callback off to a fresh virtual thread.</p>
 */
@Slf4j
public class CacheInvalidationListener implements ServletContextListener {

    private AutoCloseable subscription;

    @Override
    public void contextInitialized(final ServletContextEvent sce) {
        try {
            final CacheClient client = client();
            subscription = client.subscribe(
                    List.of(CacheKeys.CACHE_INVAL_CHANNEL), CacheInvalidation.handlerFor(client));
            log.info("Subscribed to cache-invalidation channel {}", CacheKeys.CACHE_INVAL_CHANNEL);
        } catch (final RuntimeException ex) {
            log.warn("Cache-invalidation subscribe failed; freshness falls back to the soft-TTL poll", ex);
        }
    }

    /** Seam for tests -- the failure paths need a client that can be made to misbehave. */
    CacheClient client() {
        return DAO.getInstance().getCacheClient();
    }

    @Override
    public void contextDestroyed(final ServletContextEvent sce) {
        if (subscription == null) {
            return;
        }
        try {
            subscription.close();
        } catch (final Exception ex) {
            log.debug("Error closing the cache-invalidation subscription", ex);
        }
    }
}
