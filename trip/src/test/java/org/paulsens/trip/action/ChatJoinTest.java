package org.paulsens.trip.action;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatInvite;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link ChatJoin}'s failure paths, which exist to keep a bookkeeping write from costing anybody their
 * message. The happy paths are covered where they matter, through {@code ChatCommands}, in
 * {@link ChatGuestAccessTest}; these are the ones a real run should never reach.
 */
public class ChatJoinTest {

    private static final Person.Id AUTHOR = Person.Id.from("chatjoin-test-author");

    private static ChatChannel channelWithId(final ChatChannel.Id id) {
        return new ChatChannel(id, "chatjoin-test-trip", ChatChannel.Kind.TRIP, "Test", null, null, null,
                Instant.parse("2026-09-01T00:00:00Z"), "admin", null, null);
    }

    private static Trip tripWithoutTheAuthor() {
        return Trip.builder().id("chatjoin-test-trip").title("ChatJoin test")
                .people(new ArrayList<>(List.of(Person.Id.from("somebody-else")))).build();
    }

    @Test
    public void aRefusedWriteIsLoggedRatherThanThrown() {
        DAO.getInstance();
        // A channel with no id is the one shape the store refuses, so this drives the "not written" branch.
        ChatJoin.byPosting(channelWithId(null), AUTHOR, tripWithoutTheAuthor(), Instant.now(),
                AuditActor.system());

        Assert.assertTrue(DAO.getInstance().getGuestChatChannelIds(AUTHOR, Cached.NO).isEmpty(),
                "a refused membership row must not leave a reverse row pointing at nothing");
    }

    @Test
    public void aThrowingWriteNeverEscapesToTheSender() {
        DAO.getInstance();
        // The message this follows is already durable: whatever goes wrong here, the sender must not hear
        // about it. A null channel is the bluntest way to make the body throw.
        ChatJoin.byPosting(null, AUTHOR, tripWithoutTheAuthor(), Instant.now(), AuditActor.system());
    }

    @Test
    public void aRosterMemberAndAnUnknownTripAreBothSkipped() {
        DAO.getInstance();
        final Person.Id onRoster = Person.Id.from("chatjoin-test-roster");
        final Trip trip = Trip.builder().id("chatjoin-test-trip").title("ChatJoin test")
                .people(new ArrayList<>(List.of(onRoster))).build();

        ChatJoin.byPosting(channelWithId(ChatChannel.Id.forTrip(trip.getId())), onRoster, trip,
                Instant.now(), AuditActor.system());
        ChatJoin.byPosting(channelWithId(ChatChannel.Id.forTrip("nope")), AUTHOR, null,
                Instant.now(), AuditActor.system());

        Assert.assertTrue(DAO.getInstance().getGuestChatChannelIds(onRoster, Cached.NO).isEmpty(),
                "a roster member is already in every list through the trip; a row would only be noise");
    }

    @Test
    public void aRedemptionThatCannotBeStoredReportsAnError() {
        DAO.getInstance();
        final ChatInvite invite = new ChatInvite(ChatChannel.Id.forTrip("chatjoin-test-trip"), "sel-x",
                "hash", Person.Id.from("minter"), Instant.now(),
                Instant.now().plusSeconds(3600).getEpochSecond(), 0L);

        Assert.assertEquals(ChatJoin.redeem(channelWithId(null), AUTHOR, null, false, invite,
                Instant.now(), AuditActor.system()), "error",
                "the redeem page must be told, not shown a chat the person was never recorded in");
        Assert.assertEquals(invite.getUses(), 0L, "a failed join counts no use");
    }
}
