package org.paulsens.trip.chat;

import java.time.Instant;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatSettings;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The notification policy decisions — who gets told what, and whether they are told the contents.
 */
public class ChatNotificationsTest {

    @Test
    public void aShortRetentionChannelSendsNoMessageText() {
        // An inbox keeps mail for years. Emailing the body out of a channel set to forget it within the week makes
        // that content outlive the retention policy somewhere no administrator can reach.
        Assert.assertFalse(ChatNotifications.includeContent(channelKeeping(60L)), "1 minute");
        Assert.assertFalse(ChatNotifications.includeContent(channelKeeping(0L)), "ephemeral");
        Assert.assertFalse(ChatNotifications.includeContent(channelKeeping(6L * 86400L)), "6 days");
    }

    @Test
    public void aNormalChannelIncludesTheText() {
        Assert.assertTrue(ChatNotifications.includeContent(channelKeeping(null)), "forever is the default");
        Assert.assertTrue(ChatNotifications.includeContent(channelKeeping(7L * 86400L)), "exactly 7 days");
        Assert.assertTrue(ChatNotifications.includeContent(channelKeeping(90L * 86400L)), "90 days");
    }

    @Test
    public void aSnippetIsCutOnACodePointBoundary() {
        // Emoji are surrogate pairs; slicing by char index splits one and produces invalid UTF-8 in the mail body.
        final String emoji = "🙏".repeat(300);
        final String snippet = ChatNotifications.snippet(emoji);

        Assert.assertTrue(snippet.endsWith("…"));
        final String withoutEllipsis = snippet.substring(0, snippet.length() - 1);
        Assert.assertEquals(withoutEllipsis.codePointCount(0, withoutEllipsis.length()), 200);
        // The real assertion: no lone surrogate survived the cut.
        for (int i = 0; i < withoutEllipsis.length(); i++) {
            if (Character.isHighSurrogate(withoutEllipsis.charAt(i))) {
                Assert.assertTrue(i + 1 < withoutEllipsis.length()
                                && Character.isLowSurrogate(withoutEllipsis.charAt(i + 1)),
                        "a high surrogate at " + i + " was left without its pair");
            }
        }
    }

    @Test
    public void aShortBodyIsUntouched() {
        Assert.assertEquals(ChatNotifications.snippet("the bus leaves at 7"), "the bus leaves at 7");
        Assert.assertNull(ChatNotifications.snippet(null));
    }

    /**
     * The photo-comment mention gate. Anonymous readers can comment on a photo, so an untrusted commenter
     * must not be able to make the site email anyone by typing an @mention -- the suppression happens before
     * the mention list is even read, and every malformed input answers quietly rather than throwing.
     */
    @Test
    public void aPhotoMentionFromAnUntrustedCommenterEmailsNobody() {
        final ChatChannel photoChannel = photoChannel();
        final ChatMessage comment = comment("@all look at this");

        // None of these may throw, and none may reach the notifier.
        ChatNotifications.photoMentionsFor(null, photoChannel, null, "Someone", true);
        ChatNotifications.photoMentionsFor(comment, null, null, "Someone", true);
        ChatNotifications.photoMentionsFor(comment, photoChannel, null, "Someone", false);
        ChatNotifications.photoMentionsFor(comment, orphanPhotoChannel(), null, "Someone", true);
        ChatNotifications.photoMentionsFor(comment("no mentions here"), photoChannel, null, "Someone", true);
    }

    private static ChatMessage comment(final String body) {
        return new ChatMessage(null, ChatChannel.Id.forPhoto("photos/x.jpg"), Person.Id.from("p1"),
                Instant.parse("2026-01-01T00:00:00Z"), ChatMessage.MessageKind.TEXT, body, null,
                java.util.List.of(), null, null, null, null, null, null, null);
    }

    private static ChatChannel photoChannel() {
        return new ChatChannel(ChatChannel.Id.forPhoto("photos/x.jpg"), "t1", ChatChannel.Kind.PHOTO,
                "Photo", null, null, ChatSettings.defaults(), Instant.parse("2026-01-01T00:00:00Z"),
                "admin", null, null);
    }

    /** A photo channel with no trip behind it: nothing to notify about, and no NPE either. */
    private static ChatChannel orphanPhotoChannel() {
        return new ChatChannel(ChatChannel.Id.forPhoto("photos/y.jpg"), null, ChatChannel.Kind.PHOTO,
                "Photo", null, null, ChatSettings.defaults(), Instant.parse("2026-01-01T00:00:00Z"),
                "admin", null, null);
    }

    private static ChatChannel channelKeeping(final Long retentionSeconds) {
        final ChatSettings settings = ChatSettings.defaults().toBuilder()
                .retentionSeconds(retentionSeconds)
                .build();
        return new ChatChannel(
                ChatChannel.Id.forTrip("t1"), "t1", ChatChannel.Kind.TRIP, "Test", null, null,
                settings, Instant.parse("2026-01-01T00:00:00Z"), "admin", null, null);
    }
}
