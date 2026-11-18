package org.paulsens.trip.api;

import java.time.Instant;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.NoopCacheClient;
import org.paulsens.trip.config.KnownSettings;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link PublicRateLimiter}: the per-address fixed window on the endpoints that answer anyone, what an address
 * is counted as, and the fail-open fallback when the cache cannot count.
 */
public class PublicRateLimiterTest {

    /** A fixed instant 10 s into a 60 s window, so "seconds until it turns over" is exact. */
    private static final Instant NOW = Instant.ofEpochSecond(1_800_000_010L - (1_800_000_010L % 60) + 10);

    @BeforeMethod
    public void resetDegradedBuckets() {
        // The fallback map is static on purpose; clear it so counts do not leak between methods.
        PublicRateLimiter.clearFallbackBucketsForTest();
    }

    @Test
    public void allowsUpToTheLimitThenAsksTheClientToWaitOutTheWindow() {
        final PublicRateLimiter limiter = new PublicRateLimiter(new InMemoryCacheClient(), 3, 60);

        for (int i = 0; i < 3; i++) {
            Assert.assertTrue(limiter.check("203.0.113.7", NOW).allowed(), "request " + i);
        }
        final PublicRateLimiter.Decision denied = limiter.check("203.0.113.7", NOW);

        Assert.assertFalse(denied.allowed());
        Assert.assertEquals(denied.retryAfterSeconds(), 50, "ten seconds into a minute: fifty to go");
        Assert.assertTrue(limiter.check("203.0.113.7", NOW.plusSeconds(50)).allowed(), "the next window is fresh");
    }

    @Test
    public void eachAddressHasItsOwnAllowance() {
        final PublicRateLimiter limiter = new PublicRateLimiter(new InMemoryCacheClient(), 1, 60);

        Assert.assertTrue(limiter.check("203.0.113.7", NOW).allowed());
        Assert.assertFalse(limiter.check("203.0.113.7", NOW).allowed());
        Assert.assertTrue(limiter.check("203.0.113.8", NOW).allowed(), "a neighbor is not punished");
    }

    /** One subscriber holds a whole IPv6 /64: counting single addresses would hand a script 2^64 allowances. */
    @Test
    public void anIpv6ClientIsCountedByItsSlash64() {
        final PublicRateLimiter limiter = new PublicRateLimiter(new InMemoryCacheClient(), 1, 60);

        Assert.assertTrue(limiter.check("2001:db8:aa:bb:1::1", NOW).allowed());
        Assert.assertFalse(limiter.check("2001:db8:aa:bb:ffff:ffff:ffff:ffff", NOW).allowed(), "the same /64");
        Assert.assertTrue(limiter.check("2001:db8:aa:bc::1", NOW).allowed(), "the next /64 is somebody else");
    }

    @Test
    public void theClientKeyFoldsAddressesAndNeverLooksAHostUp() {
        Assert.assertEquals(PublicRateLimiter.clientKey("203.0.113.7"), "203.0.113.7");
        Assert.assertEquals(PublicRateLimiter.clientKey(" 203.0.113.7 "), "203.0.113.7");
        Assert.assertEquals(PublicRateLimiter.clientKey("2001:db8:aa:bb:1::1"), "20010db800aa00bb/64");
        Assert.assertEquals(PublicRateLimiter.clientKey("2001:DB8:AA:BB::2"), "20010db800aa00bb/64");
        Assert.assertEquals(PublicRateLimiter.clientKey("fe80::1%en0"), "fe80000000000000/64", "a zone id is dropped");
        Assert.assertEquals(PublicRateLimiter.clientKey("::ffff:203.0.113.7"), "203.0.113.7",
                "an IPv4 address in IPv6 clothing is that IPv4 address");
        Assert.assertEquals(PublicRateLimiter.clientKey(null), "unknown");
        Assert.assertEquals(PublicRateLimiter.clientKey("  "), "unknown");
        Assert.assertEquals(PublicRateLimiter.clientKey("example.com"), "unknown", "a name is never resolved");
        Assert.assertEquals(PublicRateLimiter.clientKey("not:an:address:zz"), "unknown");
        Assert.assertEquals(PublicRateLimiter.clientKey(":::::"), "unknown", "address-shaped garbage");
    }

    /** Whatever cannot be read as an address is counted TOGETHER, so garbage is no way around the limit. */
    @Test
    public void unreadableAddressesShareOneAllowance() {
        final PublicRateLimiter limiter = new PublicRateLimiter(new InMemoryCacheClient(), 1, 60);

        Assert.assertTrue(limiter.check(null, NOW).allowed());
        Assert.assertFalse(limiter.check("garbage", NOW).allowed());
    }

    @Test
    public void aChangedWindowStartsAFreshCount() {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        final PublicRateLimiter minute = new PublicRateLimiter(cache, 1, 60);
        Assert.assertTrue(minute.check("203.0.113.7", NOW).allowed());
        Assert.assertFalse(minute.check("203.0.113.7", NOW).allowed());

        Assert.assertTrue(new PublicRateLimiter(cache, 1, 120).check("203.0.113.7", NOW).allowed(),
                "the window length is part of the key");
    }

    /** With no cache to count in, requests are served, and a per-server fallback still holds a ceiling. */
    @Test
    public void withoutACacheItFailsOpenButKeepsACeiling() {
        final PublicRateLimiter limiter = new PublicRateLimiter(new NoopCacheClient(), 2, 60);

        Assert.assertTrue(limiter.check("203.0.113.7", NOW).allowed());
        Assert.assertTrue(limiter.check("203.0.113.7", NOW).allowed());
        Assert.assertFalse(limiter.check("203.0.113.7", NOW).allowed(), "the local ceiling");
        Assert.assertTrue(limiter.check("203.0.113.8", NOW).allowed());
    }

    /** The fallback's keys are caller-chosen (addresses), so it forgets everything rather than grow without end. */
    @Test
    public void theFallbackIsBounded() {
        final PublicRateLimiter limiter = new PublicRateLimiter(new NoopCacheClient(), 1, 60);
        Assert.assertTrue(limiter.check("198.51.100.1", NOW).allowed());
        Assert.assertFalse(limiter.check("198.51.100.1", NOW).allowed());

        for (int i = 0; i < PublicRateLimiter.FALLBACK_MAX_KEYS; i++) {
            limiter.check("10." + (i >> 16 & 0xff) + "." + (i >> 8 & 0xff) + "." + (i & 0xff), NOW);
        }

        Assert.assertTrue(limiter.check("198.51.100.1", NOW).allowed(), "forgotten: bounded errs toward serving");
    }

    /** The no-argument form reads the two settings on each check; the shipped defaults are generous. */
    @Test
    public void theApplicationLimiterReadsItsSettings() {
        Assert.assertEquals(KnownSettings.DISCOVER_RATE_LIMIT.intDefault(), 120);
        Assert.assertEquals(KnownSettings.DISCOVER_RATE_WINDOW_SECONDS.intDefault(), 60);
        final PublicRateLimiter limiter = new PublicRateLimiter();

        Assert.assertTrue(limiter.check("192.0.2.200", NOW).allowed());
        Assert.assertEquals(limiter.check("192.0.2.200", NOW).retryAfterSeconds(), 0);
    }
}
