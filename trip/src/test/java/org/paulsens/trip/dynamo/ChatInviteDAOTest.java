package org.paulsens.trip.dynamo;

import java.time.Instant;
import java.util.List;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatInvite;
import org.testng.Assert;
import org.testng.annotations.Test;

/** {@code chat_invites} rows through the DAO facade against the in-memory fake. */
public class ChatInviteDAOTest {

    private static final Person.Id CREATOR = Person.Id.from("invite-creator");

    private ChatInvite invite(final ChatChannel.Id channelId, final String selector, final long expires) {
        return new ChatInvite(channelId, selector, "hash-of-" + selector, CREATOR,
                Instant.parse("2026-08-12T00:00:00Z"), expires, 0L);
    }

    @Test
    public void saveGetDeleteRoundTrip() {
        final ChatChannel.Id channel = ChatChannel.Id.forTrip("invite-dao-trip-1");
        final long expires = Instant.now().plusSeconds(3600).getEpochSecond();
        Assert.assertTrue(DAO.getInstance().saveChatInvite(invite(channel, "sel-1", expires)));

        final ChatInvite read = DAO.getInstance().getChatInvite(channel, "sel-1").orElseThrow();
        Assert.assertEquals(read.getChannelId(), channel);
        Assert.assertEquals(read.getSelector(), "sel-1");
        Assert.assertEquals(read.getValidatorHash(), "hash-of-sel-1");
        Assert.assertEquals(read.getCreatedBy(), CREATOR);
        Assert.assertEquals(read.getExpires(), expires);
        Assert.assertEquals(read.getUses(), 0L);

        Assert.assertTrue(DAO.getInstance().deleteChatInvite(channel, "sel-1"));
        Assert.assertTrue(DAO.getInstance().getChatInvite(channel, "sel-1").isEmpty());
    }

    @Test
    public void listReturnsOnlyThisChannelsInvites() {
        final ChatChannel.Id mine = ChatChannel.Id.forTrip("invite-dao-trip-2");
        final ChatChannel.Id other = ChatChannel.Id.forTrip("invite-dao-trip-3");
        final long expires = Instant.now().plusSeconds(3600).getEpochSecond();
        Assert.assertTrue(DAO.getInstance().saveChatInvite(invite(mine, "sel-a", expires)));
        Assert.assertTrue(DAO.getInstance().saveChatInvite(invite(mine, "sel-b", expires)));
        Assert.assertTrue(DAO.getInstance().saveChatInvite(invite(other, "sel-c", expires)));

        final List<ChatInvite> listed = DAO.getInstance().listChatInvites(mine);
        Assert.assertEquals(listed.size(), 2);
        Assert.assertTrue(listed.stream().allMatch(i -> i.getChannelId().equals(mine)));
    }

    @Test
    public void recordUseBumpsTheCounterBestEffort() {
        final ChatChannel.Id channel = ChatChannel.Id.forTrip("invite-dao-trip-4");
        final long expires = Instant.now().plusSeconds(3600).getEpochSecond();
        Assert.assertTrue(DAO.getInstance().saveChatInvite(invite(channel, "sel-use", expires)));

        DAO.getInstance().recordChatInviteUse(
                DAO.getInstance().getChatInvite(channel, "sel-use").orElseThrow());
        Assert.assertEquals(DAO.getInstance().getChatInvite(channel, "sel-use").orElseThrow().getUses(), 1L);
    }

    @Test
    public void expiryIsAnswerable() {
        final ChatChannel.Id channel = ChatChannel.Id.forTrip("invite-dao-trip-5");
        final Instant now = Instant.parse("2026-08-12T12:00:00Z");
        final ChatInvite live = invite(channel, "sel-live", now.plusSeconds(60).getEpochSecond());
        final ChatInvite dead = invite(channel, "sel-dead", now.minusSeconds(60).getEpochSecond());
        Assert.assertFalse(live.isExpired(now));
        Assert.assertTrue(dead.isExpired(now));
        // Boundary: TTL semantics are "expired at or after the stamp".
        Assert.assertTrue(invite(channel, "sel-edge", now.getEpochSecond()).isExpired(now));
    }

    @Test
    public void nullAndBlankKeysAreRefusedNotThrown() {
        final ChatChannel.Id channel = ChatChannel.Id.forTrip("invite-dao-trip-6");
        Assert.assertTrue(DAO.getInstance().getChatInvite(null, "x").isEmpty());
        Assert.assertTrue(DAO.getInstance().getChatInvite(channel, null).isEmpty());
        Assert.assertTrue(DAO.getInstance().getChatInvite(channel, "").isEmpty());
        Assert.assertEquals(DAO.getInstance().listChatInvites(null), List.of());
        Assert.assertFalse(DAO.getInstance().deleteChatInvite(null, "x"));
        Assert.assertFalse(DAO.getInstance().deleteChatInvite(channel, null));
        Assert.assertFalse(DAO.getInstance().saveChatInvite(null));
    }

    /** A store whose writes fail maps to {@code false} — never an exception into the redeem/revoke path. */
    @Test
    public void failedWritesMapToFalseNotThrow() {
        final Persistence failing = org.mockito.Mockito.mock(Persistence.class);
        org.mockito.Mockito.when(failing.putItem(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("write refused"));
        org.mockito.Mockito.when(failing.deleteItem(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("delete refused"));
        final ChatInviteDAO dao = new ChatInviteDAO(failing);
        final ChatChannel.Id channel = ChatChannel.Id.forTrip("invite-dao-failing");
        final long expires = Instant.now().plusSeconds(3600).getEpochSecond();

        Assert.assertFalse(dao.saveInvite(invite(channel, "sel-fail", expires)));
        Assert.assertFalse(dao.deleteInvite(channel, "sel-fail"));
        dao.recordUse(invite(channel, "sel-fail", expires)); // best effort: must not throw
    }

    @Test
    public void guestChannelReverseRowsRoundTrip() {
        final Person.Id guest = Person.Id.from("reverse-guest-" + System.nanoTime());
        final ChatChannel.Id first = ChatChannel.Id.forTrip("reverse-trip-1");
        final ChatChannel.Id second = ChatChannel.Id.forTrip("reverse-trip-2");
        Assert.assertEquals(DAO.getInstance().getGuestChatChannelIds(guest), List.of());

        Assert.assertTrue(DAO.getInstance().addGuestChatChannel(guest, first));
        Assert.assertTrue(DAO.getInstance().addGuestChatChannel(guest, second));
        // Idempotent: redeeming the same invite twice writes the same row.
        Assert.assertTrue(DAO.getInstance().addGuestChatChannel(guest, first));

        final List<ChatChannel.Id> channels = DAO.getInstance().getGuestChatChannelIds(guest);
        Assert.assertEquals(channels.size(), 2);
        Assert.assertTrue(channels.contains(first));
        Assert.assertTrue(channels.contains(second));

        // The synthetic person: partition must never leak into a real channel's member listing.
        Assert.assertTrue(DAO.getInstance().listChatMembers(first).stream()
                .noneMatch(m -> m.getPersonId().equals(guest)));
    }
}
