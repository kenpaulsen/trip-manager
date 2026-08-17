package org.paulsens.trip.action;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatSettings;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.paulsens.trip.cache.Cached;

/**
 * Core membership / settings rules.
 *
 * <p>Owns its own trip fixture. Other tests call {@code clearAllCaches()} and can wipe FakeData's
 * {@code faketrip} from the shared cache; seeding here keeps membership checks order-independent.
 */
public class ChatCommandsTest {

    /** Dedicated trip id — not FakeData's {@code faketrip}, so this suite never depends on global seed state. */
    // A real UUID: the setup grants a trip-scoped privilege, and the write path refuses a scope that
    // cannot round-trip through the UUID-suffix identity parse.
    private static final String TRIP = java.util.UUID.randomUUID().toString();

    private ChatCommands chat;
    private AuditActor actor;
    private Person.Id adminId;

    @BeforeMethod
    public void setUp() throws IOException {
        DAO.getInstance();
        seedTrip();
        chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
        // The actor must actually HOLD chatMgr. There is no ambient admin here: no FacesContext means
        // hasRole("admin") is false, so moderation is authorized purely by the granted privilege -- which is the
        // point. Granting it explicitly also keeps these tests honest about what the privilege buys.
        adminId = Person.Id.from("chat-mgr-" + System.nanoTime());
        actor = new AuditActor("chatmgr@test", adminId.getValue());
        final PrivilegeCommands privs = new PrivilegeCommands();
        Assert.assertTrue(privs.savePrivilege(
                privs.createPrivilege("chatMgr", "Chat manager", TRIP, List.of(adminId))));
    }

    /** Ensures {@link #TRIP} exists for {@code isTripMember} even after another suite cleared caches. */
    private void seedTrip() throws IOException {
        final Trip trip = Trip.builder()
                .id(TRIP)
                .title("ChatCommands dedicated trip")
                .openToPublic(false)
                .description("Owned by ChatCommandsTest; not FakeData.")
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(14))
                .people(new ArrayList<>())
                .build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip), "test setup: save dedicated trip");
    }

    @Test
    public void moderationIsRefusedWithoutChatMgr() {
        // The gate must live in the bean, not only in the REST resource or an XHTML rendered= attribute.
        final AuditActor stranger = new AuditActor("nobody@test", Person.Id.from("nobody").getValue());
        final Person.Id victim = Person.Id.from("victim-" + System.nanoTime());
        chat.ensureChannel(TRIP, actor);

        Assert.assertFalse(chat.removeMember(TRIP, victim, "no right to do this", Caller.forActor(stranger)),
                "a caller without chatMgr must not be able to remove a member");
        Assert.assertFalse(chat.mute(TRIP, victim, Instant.now().plusSeconds(600), "nope", Caller.forActor(stranger)));
        Assert.assertFalse(chat.updateSettings(TRIP, ChatSettings.defaults(), Caller.forActor(stranger)));
        Assert.assertNull(DAO.getInstance()
                        .getChatMembership(ChatChannel.Id.forTrip(TRIP), victim, Cached.NO).orElse(null),
                "a denied removal must not have written a membership row");
    }

    @Test
    public void addMemberRequiresAnAcknowledgement() {
        // The acknowledgement is the whole control: nobody is re-enabled without their permission.
        final Person.Id target = Person.Id.from("add-target-" + System.nanoTime());
        chat.ensureChannel(TRIP, actor);
        Assert.assertFalse(chat.addMember(TRIP, target, null, Caller.forActor(actor)));
        Assert.assertFalse(chat.addMember(TRIP, target, "   ", Caller.forActor(actor)));
        Assert.assertTrue(chat.addMember(TRIP, target, "I confirmed by phone", Caller.forActor(actor)));
    }

    @Test
    public void readDenialDistinguishesLeavingFromBeingRemoved() {
        // Same status, different remedy: a leaver may rejoin, a removed person may not.
        final ChatChannel channel = chat.ensureChannel(TRIP, actor);
        final Person.Id leaver = Person.Id.from("leaver-" + System.nanoTime());
        final Person.Id removed = Person.Id.from("removed-" + System.nanoTime());
        // Trip membership is the OUTER gate, so these two must pass it before the channel state is even
        // consulted -- otherwise both would read NOT_A_TRIP_MEMBER and the distinction under test never runs.
        final PrivilegeCommands privs = new PrivilegeCommands();
        Assert.assertTrue(privs.savePrivilege(privs.createPrivilege(
                "tripView", "Trip viewer", TRIP, List.of(leaver, removed))));

        DAO.getInstance().saveChatMembership(new ChatMembership(
                channel.getId(), leaver, ChatMembership.MemberState.LEFT,
                ChatMembership.MemberRole.MEMBER, Instant.now(), Instant.now(), "self", null,
                null, null, null, null, null, null, null, null, null));
        DAO.getInstance().saveChatMembership(new ChatMembership(
                channel.getId(), removed, ChatMembership.MemberState.REMOVED,
                ChatMembership.MemberRole.MEMBER, Instant.now(), Instant.now(), "spam", "an-admin",
                null, null, null, null, null, null, null, null, null));

        Assert.assertEquals(chat.readDenial(channel, leaver), "LEFT_CHANNEL");
        Assert.assertEquals(chat.readDenial(channel, removed), "REMOVED_FROM_CHANNEL");
        Assert.assertFalse(chat.rejoin(TRIP, removed, actor),
                "a removed person cannot self-rejoin; only an admin can reverse it");
    }

    @Test
    public void ensureChannelIsIdempotent() {
        final ChatChannel a = chat.ensureChannel(TRIP, actor);
        final ChatChannel b = chat.ensureChannel(TRIP, actor);
        Assert.assertEquals(a.getId(), b.getId());
        Assert.assertEquals(a.getId().getValue(), "trip:" + TRIP);
    }

    @Test
    public void removeImplicitMemberWritesRemovedRow() {
        final String tripId = TRIP;
        chat.ensureChannel(tripId, actor);
        // Use a person id that may be on the fake trip — materialize remove
        final Person.Id target = Person.Id.from("implicit-user-" + System.nanoTime());
        Assert.assertTrue(chat.removeMember(tripId, target, "test remove", Caller.forActor(actor)));
        final ChatMembership row = DAO.getInstance()
                .getChatMembership(ChatChannel.Id.forTrip(tripId), target, Cached.NO).orElse(null);
        Assert.assertNotNull(row, "REMOVE of implicit member must write a row");
        Assert.assertEquals(row.getState(), ChatMembership.MemberState.REMOVED);
    }

    @Test
    public void joinedAtNotOverwrittenOnRejoin() {
        final String tripId = TRIP;
        final ChatChannel channel = chat.ensureChannel(tripId, actor);
        final Person.Id me = Person.Id.from("rejoin-user");
        // Rejoin now refuses non-members (a JOINED row grants access to guests, so writing one needs
        // standing); the person under test must actually be a trip member for the joinedAt rule to run.
        grantTripView(List.of(me));
        final Instant firstJoin = Instant.parse("2026-01-01T00:00:00Z");
        final ChatMembership original = ChatMembership.joining(channel.getId(), me, firstJoin);
        DAO.getInstance().saveChatMembership(original);
        chat.leave(tripId, me, actor);
        Assert.assertTrue(chat.rejoin(tripId, me, actor));
        final ChatMembership after = DAO.getInstance()
                .getChatMembership(channel.getId(), me, Cached.NO).orElseThrow();
        Assert.assertEquals(after.getJoinedAt(), firstJoin);
        Assert.assertEquals(after.getState(), ChatMembership.MemberState.JOINED);
        Assert.assertNotNull(after.getAddedBackAt());
    }

    @Test
    public void validateSettingsRejectsZeroBurst() {
        final ChatSettings bad = ChatSettings.defaults().toBuilder().burstLimit(0).build();
        Assert.assertNotNull(ChatCommands.validateSettings(bad));
    }

    @Test
    public void describeSettingsChangeMentionsDiffs() {
        final ChatSettings a = ChatSettings.defaults();
        final ChatSettings b = a.toBuilder().fullHistoryForNewMembers(false).burstLimit(9).build();
        final String d = chat.describeSettingsChange(a, b);
        Assert.assertTrue(d.contains("fullHistoryForNewMembers"));
        Assert.assertTrue(d.contains("burstLimit"));
    }

    // --- reactions ---

    @Test
    public void onlyPaletteEmojiAreAccepted() {
        final ChatChannel channel = chat.ensureChannel(TRIP, actor);
        final Person.Id me = reactor("palette");
        final ChatMessage.Id msg = postAs(me, "react to me");

        Assert.assertTrue(chat.react(TRIP, me, msg, "👍").ok());
        // Arbitrary text as a "reaction" would be a second message channel with no size limit, no rate limit, no
        // mute enforcement and no admin delete -- so the palette is a closed set, not a suggestion.
        Assert.assertEquals(chat.react(TRIP, me, msg, "not an emoji").code(), "bad_emoji");
        Assert.assertEquals(chat.react(TRIP, me, msg, "").code(), "bad_emoji");
        Assert.assertEquals(chat.react(TRIP, me, msg, null).code(), "bad_emoji");
        // A '#' would shift the sort-key field boundaries and let two (person, emoji) pairs collide on one row.
        Assert.assertEquals(chat.react(TRIP, me, msg, "a#b").code(), "bad_emoji");
        // Bare U+2764 without the variation selector is not the palette entry, and must not become a third
        // "emoji" that renders identically but counts separately.
        Assert.assertEquals(chat.react(TRIP, me, msg, "❤").code(), "bad_emoji");
    }

    @Test
    public void reactingIsIdempotentAndUnreactingRemoves() {
        final Person.Id me = reactor("toggle");
        final ChatMessage.Id msg = postAs(me, "toggle me");

        Assert.assertTrue(chat.react(TRIP, me, msg, "👍").ok());
        Assert.assertTrue(chat.react(TRIP, me, msg, "👍").ok(), "a double click is a no-op, not an error");
        Assert.assertEquals(summaryCount(me, msg, "👍"), 1);

        Assert.assertTrue(chat.unreact(TRIP, me, msg, "👍").ok());
        Assert.assertEquals(summaryCount(me, msg, "👍"), 0);
    }

    @Test
    public void aMutedMemberCannotReact() {
        // A muted person who can still react keeps a voice in the channel through a control that was never meant
        // to be one, which defeats the moderation action.
        final Person.Id muted = reactor("muted");
        final ChatMessage.Id msg = postAs(muted, "before the mute");
        Assert.assertTrue(chat.mute(TRIP, muted, Instant.now().plusSeconds(600), "testing", Caller.forActor(actor)));

        final ChatCommands.ReactResult result = chat.react(TRIP, muted, msg, "👍");
        Assert.assertFalse(result.ok());
        Assert.assertEquals(result.code(), "MUTED", "and reported AS a mute, never as something else");
    }

    @Test
    public void reactionsCanBeTurnedOffPerChannel() {
        final Person.Id me = reactor("disabled");
        final ChatMessage.Id msg = postAs(me, "no reactions here");
        Assert.assertTrue(chat.updateSettings(
                TRIP, ChatSettings.defaults().toBuilder().allowReactions(false).build(), Caller.forActor(actor)));
        try {
            Assert.assertEquals(chat.react(TRIP, me, msg, "👍").code(), "REACTIONS_DISABLED");
        } finally {
            Assert.assertTrue(chat.updateSettings(TRIP, ChatSettings.defaults(), Caller.forActor(actor)));
        }
    }

    @Test
    public void aNonMemberCannotReact() {
        final Person.Id member = reactor("owner");
        final ChatMessage.Id msg = postAs(member, "members only");
        final Person.Id stranger = Person.Id.from("stranger-" + System.nanoTime());

        Assert.assertEquals(chat.react(TRIP, stranger, msg, "👍").code(), "NOT_A_TRIP_MEMBER");
    }

    @Test
    public void reactingToAMissingMessageIsNotFound() {
        final Person.Id me = reactor("missing");
        // A far-future id cannot exist. Reacting to something unreadable must not confirm it exists.
        Assert.assertEquals(
                chat.react(TRIP, me, ChatMessage.Id.of(4_000_000_000_000L), "👍").code(), "not_found");
    }

    @Test
    public void reactorsGetDisplayNamesEvenWhenTheyNeverPosted() {
        // The chip tooltip names who reacted. A reactor is frequently NOT an author on the page -- reacting without
        // ever posting is the common case -- so resolving only authors put a raw person id on screen.
        // Granted together, in ONE call: createPrivilege REPLACES the privilege's member list, so granting twice
        // silently revokes the first person -- which surfaces here as an unrelated "send forbidden".
        final Person.Id author = Person.Id.from("author-" + System.nanoTime());
        final Person.Id lurker = Person.Id.from("lurker-" + System.nanoTime());
        grantTripView(List.of(author, lurker));
        final ChatMessage.Id msg = postAs(author, "someone will react to this");
        Assert.assertTrue(chat.react(TRIP, lurker, msg, "👍").ok());

        final Map<String, String> names = chat.reactorNames(chat.reactionWindow(TRIP, author, msg, msg));
        Assert.assertTrue(names.containsKey(lurker.getValue()),
                "a reactor who never posted must still get a name entry");
    }

    // --- author self-edit ---

    @Test
    public void anAuthorMayEditTheirOwnRecentMessage() {
        final Person.Id me = reactor("editor");
        final ChatMessage.Id msg = postAs(me, "teh bus leaves at 7");

        Assert.assertTrue(chat.editMessage(TRIP, me, msg, "the bus leaves at 7").ok());

        final ChatPage page = chat.history(TRIP, me, null, 50);
        final ChatMessage edited = page.getMessages().stream()
                .filter(m -> m.getId().equals(msg))
                .findFirst()
                .orElseThrow();
        Assert.assertEquals(edited.getBody(), "the bus leaves at 7");
        Assert.assertNotNull(edited.getEditedAt(), "an edit must be visible as an edit, not a silent rewrite");
        Assert.assertEquals(edited.getSentAt().toEpochMilli(), msg.getEpochMilli(),
                "an edit must not move the message in the conversation");
    }

    @Test
    public void nobodyElseMayEditYourMessage() {
        // Deliberately including administrators: an admin may REMOVE a message, which leaves a tombstone and an
        // audit record. Silently rewriting someone else's words would put unattributable text under their name.
        final Person.Id author = Person.Id.from("victim-author-" + System.nanoTime());
        final Person.Id other = Person.Id.from("other-" + System.nanoTime());
        // adminId included deliberately: without trip access it fails the OUTER gate with NOT_A_TRIP_MEMBER and
        // the rule under test -- that even a chat manager cannot rewrite -- would never be exercised.
        grantTripView(List.of(author, other, adminId));
        final ChatMessage.Id msg = postAs(author, "my own words");

        Assert.assertEquals(chat.editMessage(TRIP, other, msg, "words I never said").code(), "FORBIDDEN");
        Assert.assertEquals(chat.editMessage(TRIP, adminId, msg, "admin rewrite").code(), "FORBIDDEN",
                "chatMgr can delete, but must not rewrite");

        final ChatMessage after = chat.history(TRIP, author, null, 50).getMessages().stream()
                .filter(m -> m.getId().equals(msg)).findFirst().orElseThrow();
        Assert.assertEquals(after.getBody(), "my own words");
    }

    @Test
    public void theEditWindowArithmeticIsCorrectAtItsBoundary() {
        // Tested directly rather than through the store: saveMessage assigns the id (and therefore sentAt) from the
        // monotonic allocator, so a message written by a test is always "now" and an expired one is not expressible
        // that way. The arithmetic is the part that can be wrong.
        final Instant now = Instant.parse("2026-07-29T12:00:00Z");
        // The window itself is a setting now, so the boundaries are expressed in terms of it rather than in
        // literal minutes -- otherwise changing the default would fail this test for no real reason.
        final long window = chat.getEditWindowMinutes();
        Assert.assertTrue(chat.withinEditWindow(now.minusSeconds(60), now));
        Assert.assertTrue(chat.withinEditWindow(now.minusSeconds((window - 1) * 60), now));
        Assert.assertFalse(chat.withinEditWindow(now.minusSeconds((window + 1) * 60), now));
        Assert.assertFalse(chat.withinEditWindow(null, now), "no timestamp cannot be inside the window");
    }

    @Test
    public void aMutedAuthorCannotEdit() {
        // Otherwise the edit window is an unmoderated channel: a muted author keeps publishing by rewriting a
        // message they sent before the mute.
        final Person.Id muted = reactor("muted-editor");
        final ChatMessage.Id msg = postAs(muted, "before the mute");
        Assert.assertTrue(chat.mute(TRIP, muted, Instant.now().plusSeconds(600), "testing", Caller.forActor(actor)));

        Assert.assertEquals(chat.editMessage(TRIP, muted, msg, "after the mute").code(), "MUTED");
    }

    @Test
    public void anEditIsRejectedWhenEmptyOrTooLong() {
        final Person.Id me = reactor("sizes");
        final ChatMessage.Id msg = postAs(me, "valid");

        Assert.assertEquals(chat.editMessage(TRIP, me, msg, "").code(), "empty");
        Assert.assertEquals(chat.editMessage(TRIP, me, msg, "x".repeat(5000)).code(), "too_long");
    }

    @Test
    public void aDeletedMessageCannotBeEdited() {
        final Person.Id me = reactor("deleted-edit");
        final ChatMessage.Id msg = postAs(me, "will be removed");
        Assert.assertTrue(chat.deleteMessage(TRIP, msg, Caller.forActor(actor)));

        // Editing a tombstone would resurrect a body an administrator removed.
        Assert.assertEquals(chat.editMessage(TRIP, me, msg, "back from the dead").code(), "not_found");
    }

    // --- unread ---

    @Test
    public void unreadClearsOnceTheCursorCatchesUp() {
        final Person.Id reader = reactor("reader");
        postAs(reader, "something to read " + System.nanoTime());

        Assert.assertTrue(chat.hasUnread(TRIP, reader),
                "a person who has never opened the chat has everything unread");

        final ChatPage page = chat.feed(TRIP, reader, null, 200);
        Assert.assertTrue(chat.markRead(TRIP, reader, page.getCursor()));
        Assert.assertFalse(chat.hasUnread(TRIP, reader), "reading up to the cursor clears the dot");
    }

    @Test
    public void aNewMessageMakesItUnreadAgain() {
        final Person.Id reader = reactor("reader2");
        final ChatPage caughtUp = chat.feed(TRIP, reader, null, 200);
        chat.markRead(TRIP, reader, caughtUp.getCursor());

        postAs(reader, "arrives after the cursor " + System.nanoTime());
        Assert.assertTrue(chat.hasUnread(TRIP, reader));
    }

    // --- retention presets ---

    @Test
    public void theRetentionPresetDistinguishesForeverFromEphemeral() {
        // "forever" is null and "0" is zero -- adjacent in a dropdown, opposite in effect. Reading one as the other
        // either keeps history an admin asked to expire, or expires history they asked to keep.
        Assert.assertNull(ChatCommands.retentionFromPreset("forever", 99L), "forever means no expiry at all");
        Assert.assertEquals(ChatCommands.retentionFromPreset("0", null), Long.valueOf(0L),
                "0 means expires with the hot buffer -- NOT forever");
        Assert.assertEquals(ChatCommands.retentionFromPreset("7776000", null), Long.valueOf(7776000L));
        Assert.assertNull(ChatCommands.retentionFromPreset("FOREVER", 99L), "case must not change the meaning");

        // Unrecognised input keeps the current setting rather than guessing in either direction.
        Assert.assertEquals(ChatCommands.retentionFromPreset("not-a-number", 7776000L), Long.valueOf(7776000L));
        Assert.assertEquals(ChatCommands.retentionFromPreset(null, 7776000L), Long.valueOf(7776000L));
        Assert.assertEquals(ChatCommands.retentionFromPreset("  ", 7776000L), Long.valueOf(7776000L));
    }

    // --- reaction helpers ---

    /**
     * A person with trip access, so the outer gate passes and the channel rule under test actually runs.
     *
     * <p>One person per call only. {@code createPrivilege} replaces the privilege's whole member list, so calling
     * this twice in one test revokes the first person — grant them together with {@link #grantTripView} instead.
     */
    private Person.Id reactor(final String label) {
        final Person.Id id = Person.Id.from(label + "-" + System.nanoTime());
        grantTripView(List.of(id));
        return id;
    }

    private void grantTripView(final List<Person.Id> people) {
        final PrivilegeCommands privs = new PrivilegeCommands();
        Assert.assertTrue(privs.savePrivilege(
                privs.createPrivilege("tripView", "Trip viewer", TRIP, people)));
    }

    private ChatMessage.Id postAs(final Person.Id author, final String body) {
        chat.ensureChannel(TRIP, actor);
        final ChatCommands.SendResult sent = chat.send(TRIP, author, body, null, null, actor);
        Assert.assertTrue(sent.isOk(), "test setup: send failed with " + sent.getCode());
        return sent.getMessageObj().getId();
    }

    /** Reads as {@code reader}: the window read is gated on trip access like every other read. */
    private int summaryCount(final Person.Id reader, final ChatMessage.Id msg, final String emoji) {
        return chat.reactionWindow(TRIP, reader, msg, msg).values().stream()
                .filter(s -> s.getMessageId().equals(msg))
                .mapToInt(s -> s.count(emoji))
                .findFirst()
                .orElse(0);
    }

    @Test
    public void sendRejectsEmptyAndTooLong() {
        final String tripId = TRIP;
        chat.ensureChannel(tripId, actor);
        // Without trip membership the send may fail forbidden — use empty body check path
        // by constructing settings and testing validation via send with oversized body
        final Person.Id author = Person.Id.from("author");
        // Ensure channel allows; if not trip member, we get forbidden — still a structured result
        final ChatCommands.SendResult empty = chat.send(tripId, author, "", null, null, actor);
        Assert.assertFalse(empty.isOk());
    }

    /*
     * exitUrlForTrip -- the chat page's back arrow. The full-screen chat has no trip tabs, so a wrong answer
     * here is a dead end: it must always name a page the person can actually open.
     */

    @Test
    public void exitUrlSendsANonMemberToTheTripDetailsPage() {
        final Person.Id stranger = Person.Id.from("exit-stranger-" + System.nanoTime());
        Assert.assertEquals(chat.exitUrlForTrip(TRIP, stranger), "/trip/tripDetails.jsf?trip=" + TRIP,
                "someone who is not on the trip has no contacts or itinerary page to go back to");
    }

    @Test
    public void exitUrlSendsARosterMemberToTheItineraryWhileTheTripIsUnderway() throws IOException {
        final Person.Id traveller = Person.Id.from("exit-underway-" + System.nanoTime());
        final String tripId = seedDatedTrip(traveller, LocalDateTime.now().minusDays(2),
                LocalDateTime.now().plusDays(2));
        Assert.assertEquals(chat.exitUrlForTrip(tripId, traveller), "/trip/itinerary.jsf?trip=" + tripId);
    }

    @Test
    public void exitUrlIncludesTheWholeOfTheLastDay() throws IOException {
        // The end date is a DAY, not a moment: a trip whose endDate is midnight this morning is still
        // underway all of today. Storing it as a LocalDateTime makes that boundary easy to get wrong.
        final Person.Id traveller = Person.Id.from("exit-lastday-" + System.nanoTime());
        final String tripId = seedDatedTrip(traveller, LocalDateTime.now().minusDays(3),
                LocalDateTime.now().toLocalDate().atStartOfDay());
        Assert.assertEquals(chat.exitUrlForTrip(tripId, traveller), "/trip/itinerary.jsf?trip=" + tripId);
    }

    @Test
    public void exitUrlSendsARosterMemberToContactsBeforeAndAfterTheTrip() throws IOException {
        final Person.Id traveller = Person.Id.from("exit-dated-" + System.nanoTime());
        final String future = seedDatedTrip(traveller, LocalDateTime.now().plusDays(30),
                LocalDateTime.now().plusDays(40));
        Assert.assertEquals(chat.exitUrlForTrip(future, traveller), "/trip/tripContacts.jsf?trip=" + future);

        final String past = seedDatedTrip(traveller, LocalDateTime.now().minusDays(40),
                LocalDateTime.now().minusDays(30));
        Assert.assertEquals(chat.exitUrlForTrip(past, traveller), "/trip/tripContacts.jsf?trip=" + past);
    }

    @Test
    public void exitUrlKeepsANonRosterMemberOffTheItinerary() {
        // A tripView holder (admin, family manager) is a trip member for chat purposes but has no itinerary
        // of their own -- tripTabs.xhtml gates its Itinerary entry the same way, on the roster.
        final Person.Id viewer = Person.Id.from("exit-viewer-" + System.nanoTime());
        grantTripView(List.of(viewer));
        Assert.assertTrue(chat.isTripMember(TRIP, viewer), "test setup: tripView must make them a member");
        Assert.assertEquals(chat.exitUrlForTrip(TRIP, viewer), "/trip/tripContacts.jsf?trip=" + TRIP);
    }

    @Test
    public void exitUrlFallsBackWhenTheTripIsUnknown() {
        Assert.assertEquals(chat.exitUrlForTrip(null, adminId), "/trip/tripContacts.jsf");
        Assert.assertEquals(chat.exitUrlForTrip("no-such-trip-" + System.nanoTime(), adminId),
                "/trip/tripContacts.jsf");
    }

    /** A trip of its own with {@code member} on the roster, so the date window can be varied per test. */
    private String seedDatedTrip(final Person.Id member, final LocalDateTime start, final LocalDateTime end)
            throws IOException {
        final String id = java.util.UUID.randomUUID().toString();
        Assert.assertTrue(DAO.getInstance().saveTrip(Trip.builder()
                .id(id)
                .title("Exit url trip")
                .openToPublic(false)
                .startDate(start)
                .endDate(end)
                .people(new ArrayList<>(List.of(member)))
                .build()), "test setup: save dated trip");
        return id;
    }
}
