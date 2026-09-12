package org.paulsens.trip.media;

import java.util.concurrent.atomic.AtomicLong;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The sliding window: N in, the N+1th out, and the oldest stamp leaving the window lets one more in. */
public class UploadRateLimiterTest {

    @Test
    public void theWindowSlides() {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final UploadRateLimiter limiter = new UploadRateLimiter(2, 10, clock::get);

        Assert.assertTrue(limiter.allow("a"));
        Assert.assertEquals(limiter.retryAfterSeconds("a"), 0, "not limited yet");
        clock.addAndGet(4_000L);
        Assert.assertTrue(limiter.allow("a"));
        Assert.assertFalse(limiter.allow("a"), "the third within the window is refused");
        Assert.assertEquals(limiter.retryAfterSeconds("a"), 6, "the first stamp leaves the window in 6 s");
        Assert.assertTrue(limiter.allow("b"), "keys are independent");

        clock.addAndGet(6_000L);
        Assert.assertTrue(limiter.allow("a"), "the first stamp has left the window");
        Assert.assertFalse(limiter.allow("a"));
        Assert.assertTrue(limiter.retryAfterSeconds("a") >= 1, "never answers 0 while limited");
        Assert.assertTrue(limiter.allow(null), "a null key is a key");
    }

    @Test
    public void theChatAllowanceIsOneSharedInstance() {
        Assert.assertSame(UploadRateLimiter.chatPhotos(), UploadRateLimiter.chatPhotos());
    }
}
