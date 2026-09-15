package org.paulsens.trip.action;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link ChatRoster}: turning membership rows into something an administrator can read and act on.
 *
 * <p>What is actually being pinned here is that the id never reaches the screen when a name can be had, that a
 * timestamp travels as BOTH text and epoch millis (the browser re-renders it in the viewer's zone, and the
 * server-rendered text has to stand on its own until it does), and that the picker's idea of "in this chat"
 * matches the mention autocomplete's — a moderator offered somebody they cannot act on is worse than a
 * moderator who has to search.
 */
public class ChatRosterTest {

    private String tripId;
    private ChatChannel.Id channelId;
    private Person.Id member;
    /** Unique per run: {@link PersonCommands#savePerson} refuses an address another person already holds. */
    private String memberEmail;

    @BeforeMethod
    public void setUp() throws IOException {
        DAO.getInstance();
        tripId = UUID.randomUUID().toString();
        channelId = ChatChannel.Id.forTrip(tripId);
        memberEmail = uniqueEmail("kevin");
        member = person("Kevin", "Paulsen", memberEmail);
        final Trip trip = Trip.builder()
                .id(tripId)
                .title("Roster trip")
                .openToPublic(false)
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(7))
                .people(new ArrayList<>(List.of(member)))
                .build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip), "test setup: save the trip");
    }

    private static String uniqueEmail(final String who) {
        return who + "-" + UUID.randomUUID() + "@example.com";
    }

    private static Person.Id person(final String first, final String last, final String email) {
        final PersonCommands people = new PersonCommands();
        final Person who = people.createPerson();
        who.setFirst(first);
        who.setLast(last);
        who.setEmail(email);
        Assert.assertTrue(people.savePerson(who), "test setup: save " + first);
        return who.getId();
    }

    private ChatMembership.MemberState stateOf(final ChatRoster.RosterRow row) {
        return row.state();
    }

    // --- the roster line ---

    @Test
    public void aLineNamesThePersonAndTheirEmailRatherThanTheStoredId() {
        final Instant joined = Instant.parse("2026-07-04T18:30:00Z");
        Assert.assertTrue(DAO.getInstance()
                .saveChatMembership(ChatMembership.joining(channelId, member, joined)));

        final List<ChatRoster.RosterRow> rows = ChatRoster.view(
                DAO.getInstance().listChatMembers(channelId, Cached.NO));

        Assert.assertEquals(rows.size(), 1);
        final ChatRoster.RosterRow row = rows.get(0);
        Assert.assertEquals(row.name(), "Paulsen, Kevin", "last name first: it is what a roster is scanned by");
        Assert.assertEquals(row.email(), memberEmail);
        Assert.assertEquals(row.personId(), member.getValue(), "the id is still carried, for the commands");
        Assert.assertEquals(stateOf(row), ChatMembership.MemberState.JOINED);
        Assert.assertFalse(row.guest());
        Assert.assertFalse(row.muted());
    }

    /**
     * The zone conversion happens in the browser, so the row has to carry the instant itself. The text form is
     * stamped UTC because an unlabelled time that has NOT been converted reads as a local one.
     */
    @Test
    public void aTimestampTravelsAsBothUtcTextAndEpochMillis() {
        final Instant joined = Instant.parse("2026-07-04T18:30:00Z");
        Assert.assertTrue(DAO.getInstance()
                .saveChatMembership(ChatMembership.joining(channelId, member, joined)));

        final ChatRoster.RosterRow row = ChatRoster.view(
                DAO.getInstance().listChatMembers(channelId, Cached.NO)).get(0);

        Assert.assertEquals(row.joinedUtc(), "2026-07-04 18:30 UTC");
        Assert.assertEquals(row.joinedMillis(), joined.toEpochMilli());
    }

    /** No mute, no expiry: the cell must be empty rather than showing the epoch or the word "null". */
    @Test
    public void anAbsentTimestampIsBlankAndWeighsZero() {
        Assert.assertTrue(DAO.getInstance()
                .saveChatMembership(ChatMembership.joining(channelId, member, null)));

        final ChatRoster.RosterRow row = ChatRoster.view(
                DAO.getInstance().listChatMembers(channelId, Cached.NO)).get(0);

        Assert.assertEquals(row.joinedUtc(), "");
        Assert.assertEquals(row.joinedMillis(), 0L);
        Assert.assertEquals(row.mutedUntilUtc(), "");
        Assert.assertEquals(row.mutedUntilMillis(), 0L);
    }

    /** The flag the page hangs the "muted" badge and its Unmute button on. */
    @Test
    public void aLiveMuteIsFlaggedAndAnExpiredOneIsNot() {
        final Instant until = Instant.now().plusSeconds(600);
        Assert.assertTrue(DAO.getInstance().saveChatMembership(
                ChatMembership.joining(channelId, member, Instant.now()).withMute(until, "admin", "spam")));
        Assert.assertTrue(ChatRoster.view(DAO.getInstance().listChatMembers(channelId, Cached.NO)).get(0).muted());

        Assert.assertTrue(DAO.getInstance().saveChatMembership(
                ChatMembership.joining(channelId, member, Instant.now())
                        .withMute(Instant.now().minusSeconds(60), "admin", "spam")));
        final ChatRoster.RosterRow row = ChatRoster.view(
                DAO.getInstance().listChatMembers(channelId, Cached.NO)).get(0);

        Assert.assertFalse(row.muted(), "a mute that has run out is over; the button must not offer to lift it");
        Assert.assertEquals(row.mutedUntilMillis() > 0L, true, "the expiry is still shown for the record");
    }

    @Test
    public void aGuestRowIsMarkedAsOne() {
        final Person.Id guest = person("Gail", "Guest", uniqueEmail("gail"));
        Assert.assertTrue(DAO.getInstance().saveChatMembership(
                ChatMembership.guestJoining(channelId, guest, Instant.now(), "sel123")));

        final ChatRoster.RosterRow row = ChatRoster.view(
                DAO.getInstance().listChatMembers(channelId, Cached.NO)).get(0);

        Assert.assertTrue(row.guest());
    }

    // --- naming, for the rows that have no name to give ---

    @Test
    public void aPersonWithHalfANameIsShownWithTheHalfTheyHave() {
        final Person.Id id = Person.Id.from(UUID.randomUUID().toString());
        final Person onlyLast = new Person();
        onlyLast.setLast("Solo");
        Assert.assertEquals(ChatRoster.displayName(onlyLast, id), "Solo");

        final Person onlyFirst = new Person();
        onlyFirst.setFirst("Cher");
        Assert.assertEquals(ChatRoster.displayName(onlyFirst, id), "Cher");
    }

    /**
     * A nameless row is still somebody on the roster who may need muting, so the cell falls back to the id
     * rather than going blank — an empty Person column reads as a rendering fault and leaves nothing to act on.
     */
    @Test
    public void aNamelessPersonFallsBackToTheirIdAndAMissingOneToo() {
        final Person.Id id = Person.Id.from(UUID.randomUUID().toString());

        Assert.assertEquals(ChatRoster.displayName(new Person(), id), id.getValue());
        Assert.assertEquals(ChatRoster.displayName(null, id), id.getValue());
        Assert.assertEquals(ChatRoster.displayName(null, null), "");
    }

    // --- the moderation picker ---

    /**
     * "In this chat" is the trip's roster UNION anyone holding an explicit joined row: a guest admitted by an
     * invite link never appears on the trip, and a moderator has to be able to reach them.
     */
    @Test
    public void thePickerOffersTripMembersAndJoinedGuestsUnderNamesNotIds() {
        final String guestEmail = uniqueEmail("aaron");
        final Person.Id guest = person("Aaron", "Adams", guestEmail);
        Assert.assertTrue(DAO.getInstance().saveChatMembership(
                ChatMembership.guestJoining(channelId, guest, Instant.now(), "sel123")));

        final List<ChatRoster.PersonChoice> choices = ChatRoster.choices(tripId);

        Assert.assertEquals(choices.size(), 2, "the trip member and the guest: " + choices);
        Assert.assertEquals(choices.get(0).label(), "Adams, Aaron (" + guestEmail + ")",
                "sorted by label, so the list reads like a phone book");
        Assert.assertEquals(choices.get(0).id(), guest.getValue(), "the command still gets the id");
        Assert.assertEquals(choices.get(1).label(), "Paulsen, Kevin (" + memberEmail + ")");
    }

    /** A removed member is no longer in the chat, so muting or removing them again is not on offer. */
    @Test
    public void somebodyWhoLeftIsNotOffered() {
        final Person.Id guest = person("Gail", "Guest", uniqueEmail("gail"));
        Assert.assertTrue(DAO.getInstance().saveChatMembership(
                ChatMembership.guestJoining(channelId, guest, Instant.now(), "sel123")
                        .withRemoved(Instant.now(), "spam", "admin")));

        Assert.assertEquals(ChatRoster.choices(tripId).size(), 1, "only the trip's own member is left");
    }

    @Test
    public void anUnknownTripOffersNobodyRatherThanFailing() {
        Assert.assertTrue(ChatRoster.choices(UUID.randomUUID().toString()).isEmpty());
    }
}
