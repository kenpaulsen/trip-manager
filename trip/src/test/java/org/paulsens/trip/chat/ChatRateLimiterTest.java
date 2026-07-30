package org.paulsens.trip.chat;

import java.time.Instant;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.NoopCacheClient;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatSettings;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ChatRateLimiterTest {

    @BeforeMethod
    public void resetDegradedBuckets() {
        // The fallback map is static on purpose; clear it so counts do not leak between methods.
        ChatRateLimiter.clearFallbackBucketsForTest();
    }

    @Test
    public void allowsUpToBurstThenDenies() {
        final ChatRateLimiter limiter = new ChatRateLimiter(new InMemoryCacheClient());
        final ChatChannel channel = channel(ChatSettings.defaults().toBuilder()
                .burstLimit(5).burstWindowSeconds(10)
                .sustainedLimit(100).sustainedWindowSeconds(300)
                .build());
        final Person.Id me = Person.Id.from("p1");
        final Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            Assert.assertTrue(limiter.check(channel, me, now).isAllowed(), "hit " + i);
        }
        final ChatRateLimiter.Decision denied = limiter.check(channel, me, now);
        Assert.assertFalse(denied.isAllowed());
        Assert.assertEquals(denied.getReason(), "burst");
        Assert.assertNotNull(denied.userMessage());
    }

    @Test
    public void windowChangeStartsFreshKey() {
        final InMemoryCacheClient cache = new InMemoryCacheClient();
        final ChatRateLimiter limiter = new ChatRateLimiter(cache);
        final ChatChannel c10 = channel(ChatSettings.defaults().toBuilder()
                .burstLimit(2).burstWindowSeconds(10).build());
        final Person.Id me = Person.Id.from("p1");
        final Instant now = Instant.now();
        Assert.assertTrue(limiter.check(c10, me, now).isAllowed());
        Assert.assertTrue(limiter.check(c10, me, now).isAllowed());
        Assert.assertFalse(limiter.check(c10, me, now).isAllowed());

        // Wider window is a different key — not inheriting the exhausted counter
        final ChatChannel c20 = channel(ChatSettings.defaults().toBuilder()
                .burstLimit(2).burstWindowSeconds(20).build());
        Assert.assertTrue(limiter.check(c20, me, now).isAllowed());
    }

    @Test
    public void failOpenWithNoopStillHasCeiling() {
        final ChatRateLimiter limiter = new ChatRateLimiter(new NoopCacheClient());
        final ChatChannel channel = channel(ChatSettings.defaults());
        final Person.Id me = Person.Id.from("p1");
        final Instant now = Instant.now();
        // Local buckets still count
        boolean anyDenied = false;
        for (int i = 0; i < 300; i++) {
            if (!limiter.check(channel, me, now).isAllowed()) {
                anyDenied = true;
                break;
            }
        }
        // With defaults burst 5, local bucket should still deny after 5
        Assert.assertTrue(anyDenied);
    }

    /**
     * The regression that mattered most: the limiter used to keep hit counts, escalation tiers and pending mutes in
     * instance maps, while the bean that owns it was rebuilt on every REST request. Counting therefore restarted
     * each request and the auto-mute ladder could never advance. A second ECS task would have had the same effect.
     */
    @Test
    public void countingSurvivesANewLimiterInstance() {
        final InMemoryCacheClient shared = new InMemoryCacheClient();
        final ChatChannel channel = channel(ChatSettings.defaults().toBuilder()
                .burstLimit(2).burstWindowSeconds(10).build());
        final Person.Id me = Person.Id.from("across-instances");
        final Instant now = Instant.now();

        Assert.assertTrue(new ChatRateLimiter(shared).check(channel, me, now).isAllowed());
        Assert.assertTrue(new ChatRateLimiter(shared).check(channel, me, now).isAllowed());
        Assert.assertFalse(new ChatRateLimiter(shared).check(channel, me, now).isAllowed(),
                "a fresh instance sharing the cache must see the counts the previous one wrote");
    }

    @Test
    public void repeatedHitsEarnAnAutoMuteAndTheLadderEscalates() {
        final InMemoryCacheClient shared = new InMemoryCacheClient();
        final ChatRateLimiter limiter = new ChatRateLimiter(shared);
        final ChatChannel channel = channel(ChatSettings.defaults().toBuilder()
                .burstLimit(1).burstWindowSeconds(10).sustainedLimit(1000).build());
        final Person.Id me = Person.Id.from("offender");
        final Instant now = Instant.now();

        Assert.assertTrue(limiter.check(channel, me, now).isAllowed());
        // Each further send is a limit hit; the third crosses AUTO_MUTE_TRIGGER_COUNT.
        Assert.assertNull(limiter.check(channel, me, now).getAutoMuteUntil());
        Assert.assertNull(limiter.check(channel, me, now).getAutoMuteUntil());
        final ChatRateLimiter.Decision muted = limiter.check(channel, me, now);
        Assert.assertNotNull(muted.getAutoMuteUntil(), "third hit in the window must earn a mute");

        // Tier 1 is 5 minutes; the caller is the one that persists it, which is why it rides the Decision.
        Assert.assertEquals(muted.getAutoMuteUntil(), now.plusSeconds(5 * 60));

        // Three more hits escalate to tier 2 (30 min) rather than repeating the base penalty.
        limiter.check(channel, me, now);
        limiter.check(channel, me, now);
        final ChatRateLimiter.Decision again = limiter.check(channel, me, now);
        Assert.assertNotNull(again.getAutoMuteUntil());
        Assert.assertEquals(again.getAutoMuteUntil(), now.plusSeconds(30 * 60));
    }

    @Test
    public void slowModeDeniesTheSecondMessageInTheWindow() {
        final ChatRateLimiter limiter = new ChatRateLimiter(new InMemoryCacheClient());
        final ChatChannel channel = channel(ChatSettings.defaults().toBuilder()
                .slowModeSeconds(30).build());
        final Person.Id me = Person.Id.from("slow-user");
        final Instant now = Instant.now();

        Assert.assertTrue(limiter.check(channel, me, now).isAllowed());
        final ChatRateLimiter.Decision denied = limiter.check(channel, me, now);
        Assert.assertFalse(denied.isAllowed());
        // Reported as slow mode, never as a rate limit or a mute -- the three read differently to a user.
        Assert.assertEquals(denied.getReason(), "slow_mode");
        Assert.assertTrue(denied.userMessage().contains("Slow mode"));
    }

    @Test
    public void aDeniedSendWithNoAutoMuteCarriesNoMute() {
        final ChatRateLimiter limiter = new ChatRateLimiter(new InMemoryCacheClient());
        final ChatChannel channel = channel(ChatSettings.defaults().toBuilder()
                .burstLimit(1).burstWindowSeconds(10).build());
        final Person.Id me = Person.Id.from("mild-offender");
        final Instant now = Instant.now();
        limiter.check(channel, me, now);
        final ChatRateLimiter.Decision denied = limiter.check(channel, me, now);
        Assert.assertFalse(denied.isAllowed());
        Assert.assertNull(denied.getAutoMuteUntil(), "one hit must not mute anyone");
    }

    private static ChatChannel channel(final ChatSettings settings) {
        return new ChatChannel(
                ChatChannel.Id.forTrip("t1"), "t1", ChatChannel.Kind.TRIP, "c",
                null, null, settings, Instant.EPOCH, "a", null, null);
    }
}
