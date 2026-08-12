package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The guest marker is authorization state, so its survival across every copy method and a JSON round-trip is
 * load-bearing: a {@code with*} that dropped it would lock a guest out on their next mute, pref save or
 * read-cursor write, and it would fail SILENTLY (the row still saves fine).
 */
public class ChatMembershipTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final ChatChannel.Id CHANNEL = ChatChannel.Id.forTrip("membership-test-trip");
    private static final Person.Id ME = Person.Id.from("membership-test-person");

    @Test
    public void guestMarkerSurvivesEveryCopyMethod() {
        final Instant now = Instant.parse("2026-08-12T00:00:00Z");
        final ChatMembership guest = ChatMembership.guestJoining(CHANNEL, ME, now, "sel-abc");
        Assert.assertTrue(guest.isGuest());
        Assert.assertEquals(guest.getInvitedVia(), "sel-abc");

        Assert.assertTrue(guest.withState(ChatMembership.MemberState.LEFT).isGuest());
        Assert.assertTrue(guest.withLeft(now, "self").isGuest());
        Assert.assertTrue(guest.withRemoved(now, "spam", "admin").isGuest());
        Assert.assertTrue(guest.withLeft(now, "self").withRejoined(now, "admin").isGuest());
        Assert.assertTrue(guest.withMute(now.plusSeconds(60), "admin", "spam").isGuest());
        Assert.assertTrue(guest.withMute(now, "a", "b").withUnmuted().isGuest());
        Assert.assertTrue(guest.withAppearance(ChatAppearance.NONE).isGuest());
        Assert.assertTrue(guest.withNotify(ChatNotifyPref.defaults()).isGuest());
        Assert.assertTrue(guest.withLastRead(ChatMessage.Id.of(1_700_000_000_000L)).isGuest());
        Assert.assertTrue(guest.withRole(ChatMembership.MemberRole.MODERATOR).isGuest());

        Assert.assertEquals(guest.withRole(ChatMembership.MemberRole.MODERATOR).getInvitedVia(), "sel-abc");
    }

    @Test
    public void guestMarkerSurvivesJacksonRoundTrip() throws Exception {
        final ChatMembership guest = ChatMembership.guestJoining(
                CHANNEL, ME, Instant.parse("2026-08-12T00:00:00Z"), "sel-xyz");
        final ChatMembership revived =
                MAPPER.readValue(MAPPER.writeValueAsString(guest), ChatMembership.class);
        Assert.assertTrue(revived.isGuest());
        Assert.assertEquals(revived.getInvitedVia(), "sel-xyz");
    }

    @Test
    public void legacyRowsWithoutTheFieldAreNotGuests() throws Exception {
        // A pre-guest row's JSON has no `guest` key at all; it must deserialize as an ordinary member.
        final String legacy = "{\"channelId\":\"trip:membership-test-trip\","
                + "\"personId\":\"membership-test-person\",\"state\":\"JOINED\"}";
        final ChatMembership revived = MAPPER.readValue(legacy, ChatMembership.class);
        Assert.assertFalse(revived.isGuest());
        Assert.assertNull(revived.getInvitedVia());
    }

    @Test
    public void ordinaryJoiningRowsAreNotGuests() {
        final ChatMembership member = ChatMembership.joining(CHANNEL, ME, Instant.now());
        Assert.assertFalse(member.isGuest());
        Assert.assertNull(member.getInvitedVia());
    }
}
