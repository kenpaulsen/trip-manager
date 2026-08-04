package org.paulsens.trip.chat;

import java.time.Instant;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatSettings;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link ChatRateLimiter}'s remaining edges: the pinned-tier test constructor, the null guards, and the
 * escalation ladder's promise that a mangled setting falls back to the default rather than to no mute at all.
 */
public class ChatRateLimiterTailsTest {

    @BeforeMethod
    public void resetDegradedBuckets() {
        ChatRateLimiter.clearFallbackBucketsForTest();
    }

    private static ChatChannel channel(final ChatSettings settings) {
        return new ChatChannel(ChatChannel.Id.forTrip("rl-tails"), "rl-tails", ChatChannel.Kind.TRIP,
                "Test", null, null, settings, Instant.now(), "admin", null, null);
    }

    @Test
    public void nullInputsAreDeniedNotNulled() {
        final ChatRateLimiter limiter = new ChatRateLimiter(new InMemoryCacheClient());

        Assert.assertFalse(limiter.check(null, Person.Id.from("p"), Instant.now()).isAllowed());
        Assert.assertFalse(limiter.check(channel(ChatSettings.defaults()), null, Instant.now()).isAllowed());
        Assert.assertFalse(limiter.check(channel(ChatSettings.defaults()), Person.Id.from("p"), null)
                .isAllowed());
    }

    /** The pinned-tier constructor exists so a test never depends on the config table; prove it pins. */
    @Test
    public void thePinnedTierConstructorEnforcesItsGlobalLimit() {
        final ChatRateLimiter limiter = new ChatRateLimiter(new InMemoryCacheClient(), 2, 60);
        final ChatChannel roomy = channel(ChatSettings.defaults().toBuilder()
                .burstLimit(100).burstWindowSeconds(10)
                .sustainedLimit(100).sustainedWindowSeconds(300).build());
        final Person.Id me = Person.Id.from("pinned");
        final Instant now = Instant.now();

        Assert.assertTrue(limiter.check(roomy, me, now).isAllowed());
        Assert.assertTrue(limiter.check(roomy, me, now).isAllowed());
        final ChatRateLimiter.Decision denied = limiter.check(roomy, me, now);

        Assert.assertFalse(denied.isAllowed());
        Assert.assertEquals(denied.getReason(), "global", "the pinned site-wide tier must be what denies");
    }

    @Test
    public void aChannelWithNoSettingsUsesTheDefaults() {
        final ChatRateLimiter limiter = new ChatRateLimiter(new InMemoryCacheClient());

        Assert.assertTrue(limiter.check(channel(null), Person.Id.from("p"), Instant.now()).isAllowed());
    }

    /**
     * A mangled ladder setting must fall back to the DECLARED default ladder, never to "no mute": an
     * unparseable setting silently disabling moderation is the failure this parse guards against.
     */
    @Test
    public void aMangledLadderSettingStillProducesAMute() {
        final ConfigCommands badLadder = new ConfigCommands() {
            @Override
            public int getInt(final String name, final int defaultValue) {
                if (name.contains("autoMute.trigger")) {
                    return 1; // first offence escalates, so the test reaches the ladder immediately
                }
                return defaultValue;
            }

            @Override
            public String getString(final String name, final String defaultValue) {
                return name.contains("ladder") ? "5, junk, 45" : defaultValue;
            }
        };
        final ChatRateLimiter limiter = new ChatRateLimiter(new InMemoryCacheClient(), badLadder);
        final ChatChannel tight = channel(ChatSettings.defaults().toBuilder()
                .burstLimit(1).burstWindowSeconds(10)
                .sustainedLimit(100).sustainedWindowSeconds(300).build());
        final Person.Id me = Person.Id.from("ladder");
        final Instant now = Instant.now();
        Assert.assertTrue(limiter.check(tight, me, now).isAllowed());

        final ChatRateLimiter.Decision denied = limiter.check(tight, me, now);

        Assert.assertFalse(denied.isAllowed());
        Assert.assertNotNull(denied.getAutoMuteUntil(),
                "the default ladder must apply when the configured one cannot parse");
        Assert.assertTrue(denied.getAutoMuteUntil().isAfter(now));
    }
}
