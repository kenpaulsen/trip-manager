package org.paulsens.trip.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatSettings;
import org.paulsens.trip.model.chat.ChatVisibility;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ChatVisibilityTest {

    private static final Instant T0 = Instant.parse("2026-06-01T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-01T13:00:00Z");
    private static final Instant NOW = Instant.parse("2026-06-01T14:00:00Z");

    @Test
    public void foreverKeepsMessagesVisible() {
        final ChatChannel channel = channel(ChatSettings.defaults());
        final ChatMessage msg = message(T0);
        Assert.assertTrue(ChatVisibility.isVisible(msg, null, channel, null, NOW));
        Assert.assertNull(ChatVisibility.effectiveExpiry(channel, null, T0));
    }

    @Test
    public void retentionSecondsExpiresOnRead() {
        final ChatSettings settings = ChatSettings.defaults().toBuilder().retentionSeconds(3600L).build();
        final ChatChannel channel = channel(settings);
        final ChatMessage msg = message(T0); // 2 hours before NOW
        Assert.assertFalse(ChatVisibility.isVisible(msg, null, channel, null, NOW));
        final Instant expiry = ChatVisibility.effectiveExpiry(channel, null, T0);
        Assert.assertEquals(expiry, T0.plusSeconds(3600));
    }

    @Test
    public void ephemeralUsesBufferMinutesNotSentAt() {
        final ChatSettings settings = ChatSettings.defaults().toBuilder()
                .retentionSeconds(0L)
                .bufferMinutes(60)
                .build();
        final ChatChannel channel = channel(settings);
        // sent 30 min ago — still inside buffer window
        final Instant sent = NOW.minusSeconds(30 * 60);
        Assert.assertTrue(ChatVisibility.isVisible(message(sent), null, channel, null, NOW));
        // sent 2 hours ago — past buffer
        Assert.assertFalse(ChatVisibility.isVisible(message(NOW.minusSeconds(2 * 3600)), null, channel, null, NOW));
        final Instant expiry = ChatVisibility.effectiveExpiry(channel, null, sent);
        Assert.assertEquals(expiry, sent.plusSeconds(60 * 60));
    }

    @Test
    public void tombstoneStillVisibleWhenNotExpired() {
        final ChatChannel channel = channel(ChatSettings.defaults());
        final ChatMessage msg = message(T0).withDeleted("admin");
        Assert.assertTrue(msg.isDeleted());
        Assert.assertTrue(ChatVisibility.isVisible(msg, null, channel, null, NOW));
    }

    @Test
    public void fullHistoryOffRequiresMembershipRow() {
        final ChatSettings settings = ChatSettings.defaults().toBuilder()
                .fullHistoryForNewMembers(false)
                .build();
        final ChatChannel channel = channel(settings);
        final ChatMessage msg = message(T0);
        // Implicit member (null row) — fail closed
        Assert.assertFalse(ChatVisibility.isVisible(msg, null, channel, null, NOW));
        // Member joined after message
        final ChatMembership late = membership(T1);
        Assert.assertFalse(ChatVisibility.isVisible(msg, late, channel, null, NOW));
        // Member joined before message
        final ChatMembership early = membership(T0.minusSeconds(60));
        Assert.assertTrue(ChatVisibility.isVisible(msg, early, channel, null, NOW));
    }

    @Test
    public void tripEndRetentionBeatsMessageAnchor() {
        final ChatSettings settings = ChatSettings.defaults().toBuilder()
                .retentionSeconds(60L) // would expire quickly
                .retentionDaysAfterTripEnd(10)
                .build();
        final ChatChannel channel = channel(settings);
        final Trip trip = Trip.builder()
                .id("t1")
                .title("Trip")
                .startDate(LocalDateTime.of(2026, 5, 1, 0, 0))
                .endDate(LocalDateTime.of(2026, 5, 10, 0, 0))
                .build();
        final Instant expiry = ChatVisibility.effectiveExpiry(channel, trip, T0);
        Assert.assertEquals(expiry, LocalDateTime.of(2026, 5, 20, 0, 0).toInstant(ZoneOffset.UTC));
        // Moving the trip end re-dates expiry with no write
        final Trip postponed = Trip.builder()
                .id("t1")
                .title("Trip")
                .startDate(LocalDateTime.of(2026, 5, 1, 0, 0))
                .endDate(LocalDateTime.of(2026, 6, 1, 0, 0))
                .build();
        final Instant newExpiry = ChatVisibility.effectiveExpiry(channel, postponed, T0);
        Assert.assertEquals(newExpiry, LocalDateTime.of(2026, 6, 11, 0, 0).toInstant(ZoneOffset.UTC));
    }

    @Test
    public void expiresAtEpochSecondsIsSecondsNotMillis() {
        final ChatSettings settings = ChatSettings.defaults().toBuilder().retentionSeconds(100L).build();
        final ChatChannel channel = channel(settings);
        final Long epoch = ChatVisibility.expiresAtEpochSeconds(channel, null, Instant.ofEpochSecond(1_700_000_000L));
        Assert.assertNotNull(epoch);
        Assert.assertTrue(epoch < 10_000_000_000L, "must be epoch seconds, not millis: " + epoch);
    }

    private static ChatChannel channel(final ChatSettings settings) {
        return new ChatChannel(
                ChatChannel.Id.forTrip("trip-1"), "trip-1", ChatChannel.Kind.TRIP,
                "Chat", null, null, settings, Instant.parse("2026-01-01T00:00:00Z"), "admin",
                null, null);
    }

    private static ChatMessage message(final Instant sentAt) {
        final ChatMessage.Id id = ChatMessage.Id.of(sentAt.toEpochMilli());
        return new ChatMessage(
                id, ChatChannel.Id.forTrip("trip-1"), Person.Id.from("p1"), sentAt,
                ChatMessage.MessageKind.TEXT, "hello", null, null, null,
                null, null, null, null, null, null);
    }

    private static ChatMembership membership(final Instant joinedAt) {
        return ChatMembership.joining(
                ChatChannel.Id.forTrip("trip-1"), Person.Id.from("p1"), joinedAt);
    }
}
