package org.paulsens.trip.cache;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * {@link CacheConfig} resolution.
 *
 * <p>System properties win over environment variables, so every case here pins the sysprops it needs and
 * restores them after -- the suite must never leak a cache mode into other tests. Cases that require an
 * ABSENT setting guard on the environment, because a developer's exported {@code TRIP_VALKEY_URI} would
 * otherwise flip the expected default (see {@code reference_test_prod_isolation}: an exported URI must never
 * silently point tests at live Valkey).
 */
public class CacheConfigTest {

    private static final String MODE = "trip.cache.mode";
    private static final String URI = "trip.valkey.uri";
    private static final String PROTOCOL = "trip.valkey.protocol";

    @AfterMethod(alwaysRun = true)
    public void restore() {
        System.clearProperty(MODE);
        System.clearProperty(URI);
        System.clearProperty(PROTOCOL);
    }

    @Test
    public void anExplicitModeWinsRegardlessOfCase() {
        System.setProperty(MODE, "off");
        Assert.assertEquals(CacheConfig.resolve().getMode(), CacheConfig.Mode.OFF);

        System.setProperty(MODE, " MEMORY ");
        Assert.assertEquals(CacheConfig.resolve().getMode(), CacheConfig.Mode.MEMORY);
    }

    /** A typo'd mode must degrade to per-JVM memory (safe), never throw during startup. */
    @Test
    public void anUnknownModeFallsBackToMemory() {
        System.setProperty(MODE, "redis-ish");

        Assert.assertEquals(CacheConfig.resolve().getMode(), CacheConfig.Mode.MEMORY);
    }

    @Test
    public void aConfiguredUriDefaultsTheModeToValkey() {
        System.setProperty(URI, "redis://localhost:6379");

        final CacheConfig config = CacheConfig.resolve();

        Assert.assertEquals(config.getMode(), CacheConfig.Mode.VALKEY);
        Assert.assertEquals(config.getValkeyUri(), "redis://localhost:6379");
    }

    /**
     * Mode says valkey but no URI anywhere: fall back to memory rather than boot a client with nowhere to go.
     * Guarded: an exported TRIP_VALKEY_URI in the environment legitimately satisfies the URI requirement.
     */
    @Test
    public void valkeyModeWithoutAUriFallsBackToMemory() {
        if (System.getenv("TRIP_VALKEY_URI") != null) {
            return; // the environment supplies a URI; the fallback branch is unreachable here by design
        }
        System.setProperty(MODE, "valkey");

        final CacheConfig config = CacheConfig.resolve();

        Assert.assertEquals(config.getMode(), CacheConfig.Mode.MEMORY);
        Assert.assertNull(config.getValkeyUri());
        Assert.assertFalse(config.isCluster());
    }

    /** ElastiCache Serverless speaks cluster protocol behind TLS, so rediss:// defaults to cluster. */
    @Test
    public void aTlsUriDefaultsToClusterAndAPlainUriToStandalone() {
        System.setProperty(URI, "rediss://prod.example:6379");
        Assert.assertTrue(CacheConfig.resolve().isCluster());

        System.setProperty(URI, "redis://localhost:6379");
        if (System.getenv("TRIP_VALKEY_PROTOCOL") == null) {
            Assert.assertFalse(CacheConfig.resolve().isCluster());
        }
    }

    @Test
    public void anExplicitProtocolOverridesTheUriDefault() {
        System.setProperty(URI, "rediss://prod.example:6379");
        System.setProperty(PROTOCOL, "standalone");
        Assert.assertFalse(CacheConfig.resolve().isCluster());

        System.setProperty(URI, "redis://localhost:6379");
        System.setProperty(PROTOCOL, "Cluster");
        Assert.assertTrue(CacheConfig.resolve().isCluster(), "protocol comparison is case-insensitive");
    }
}
