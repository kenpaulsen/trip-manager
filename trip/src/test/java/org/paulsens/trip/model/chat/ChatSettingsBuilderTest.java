package org.paulsens.trip.model.chat;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Pins the defaults onto the <em>builder</em>, not just the constructor.
 *
 * <p>A Lombok builder over {@code int}/{@code boolean} fields initializes them to {@code 0}/{@code false} and
 * autoboxes those into the constructor, so the constructor's null-defaulting branch never runs. That produced a
 * settings object whose every numeric limit was zero — three independent outages from one omission — and it was
 * invisible because {@code toBuilder()} (which copies an existing object) behaved correctly.
 */
public class ChatSettingsBuilderTest {

    @Test
    public void builderMatchesDefaultsExactly() {
        Assert.assertEquals(ChatSettings.builder().build(), ChatSettings.defaults(),
                "builder() and defaults() must agree, or which one a caller happens to use changes behaviour");
    }

    @Test
    public void builderDoesNotZeroTheLimitsThatWouldBreakTheChannel() {
        final ChatSettings s = ChatSettings.builder().build();
        // Each of these at 0/false is a distinct failure, so assert them individually rather than as a blob.
        Assert.assertEquals(s.getMaxMessageChars(), ChatSettings.DEFAULT_MAX_MESSAGE_CHARS,
                "0 would reject every message");
        Assert.assertEquals(s.getBurstLimit(), ChatSettings.DEFAULT_BURST_LIMIT,
                "0 would mute the channel through a control not labelled 'mute'");
        Assert.assertEquals(s.getSustainedLimit(), ChatSettings.DEFAULT_SUSTAINED_LIMIT);
        Assert.assertTrue(s.isFullHistoryForNewMembers(),
                "false is the fail-closed privacy mode; it must never be reached by omission");
        Assert.assertEquals(s.getBufferMinutes(), ChatSettings.DEFAULT_BUFFER_MINUTES,
                "0 would leave no hot buffer at all");
        Assert.assertEquals(s.getBufferMaxMessages(), ChatSettings.DEFAULT_BUFFER_MAX_MESSAGES);
        Assert.assertEquals(s.getArchiveAfterTripEndDays(), ChatSettings.DEFAULT_ARCHIVE_AFTER_TRIP_END_DAYS,
                "0 would freeze the channel the moment the trip ends");
        Assert.assertTrue(s.isAllowReactions());
        Assert.assertTrue(s.isAllowEdit());
    }

    @Test
    public void v1DefaultsThatAreDeliberatelyZeroOrFalse() {
        final ChatSettings s = ChatSettings.builder().build();
        Assert.assertNull(s.getRetentionSeconds(), "null = keep forever");
        Assert.assertNull(s.getRetentionDaysAfterTripEnd(), "null = never auto-delete");
        Assert.assertEquals(s.getSlowModeSeconds(), 0, "slow mode off");
        Assert.assertTrue(s.isAllowMedia(), "media landed (P4): on unless an admin turns it off");
        Assert.assertEquals(s.getMaxAttachmentsPerMessage(), ChatSettings.DEFAULT_MAX_ATTACHMENTS_PER_MESSAGE);
        Assert.assertEquals(s.getMaxAttachmentBytes(), ChatSettings.DEFAULT_MAX_ATTACHMENT_BYTES);
        Assert.assertEquals(s.getPostPolicy(), ChatSettings.PostPolicy.ALL_MEMBERS);
    }

    @Test
    public void explicitZeroIsStillHonoured() {
        // The defaults must not become a floor: an admin setting slow mode or retention to a real 0 means it.
        final ChatSettings s = ChatSettings.builder().retentionSeconds(0L).burstLimit(1).build();
        Assert.assertEquals(s.getRetentionSeconds(), Long.valueOf(0L));
        Assert.assertEquals(s.getBurstLimit(), 1);
        Assert.assertEquals(s.getMaxMessageChars(), ChatSettings.DEFAULT_MAX_MESSAGE_CHARS,
                "untouched fields keep their defaults");
    }

    @Test
    public void toBuilderPreservesEverythingItDidNotChange() {
        final ChatSettings original = ChatSettings.defaults().toBuilder()
                .maxMessageChars(500)
                .fullHistoryForNewMembers(false)
                .build();
        final ChatSettings tweaked = original.toBuilder().slowModeSeconds(5).build();

        Assert.assertEquals(tweaked.getMaxMessageChars(), 500);
        Assert.assertFalse(tweaked.isFullHistoryForNewMembers(),
                "a round trip must not silently re-enable full history");
        Assert.assertEquals(tweaked.getSlowModeSeconds(), 5);
    }
}
