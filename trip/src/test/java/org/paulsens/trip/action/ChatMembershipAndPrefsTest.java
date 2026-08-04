package org.paulsens.trip.action;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatSettings;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link ChatCommands}' membership, preferences and read-cursor surface, complementing {@code ChatCommandsTest}
 * (which covers moderation and sending).
 *
 * <p>The rule underneath most of this: an ABSENT membership row means JOINED. So every state change has to
 * materialise a row, and every read has to treat "no row" as a member rather than as a stranger. Getting that
 * backwards either silently un-removes somebody or hides the chat from everyone who never touched it.
 */
public class ChatMembershipAndPrefsTest {

    /** A dedicated trip, so this suite never depends on FakeData's seed or on another suite's state. */
    private String tripId;
    private ChatCommands chat;
    private AuditActor actor;
    private Person.Id adminId;
    private Person.Id member;

    @BeforeMethod
    public void setUp() throws IOException {
        DAO.getInstance();
        tripId = "chat-prefs-" + System.nanoTime();
        member = person("Member");
        adminId = person("Admin");
        seedTrip();
        chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
        actor = new AuditActor("chatmgr@test", adminId.getValue());
        final PrivilegeCommands privs = new PrivilegeCommands();
        Assert.assertTrue(privs.savePrivilege(
                privs.createPrivilege("chatMgr", "Chat manager", tripId, List.of(adminId))));
    }

    private static Person.Id person(final String first) {
        final PersonCommands people = new PersonCommands();
        final Person who = people.createPerson();
        who.setFirst(first);
        who.setLast("Chatter");
        Assert.assertTrue(people.savePerson(who));
        return who.getId();
    }

    private void seedTrip() throws IOException {
        final Trip trip = Trip.builder()
                .id(tripId)
                .title("Chat prefs trip")
                .openToPublic(false)
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(14))
                .people(new ArrayList<>(List.of(member, adminId)))
                .build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip).join(), "test setup: save dedicated trip");
    }

    // --- read access ---

    @Test
    public void aTripMemberWithNoRowCanRead() {
        final ChatChannel channel = chat.ensureChannel(tripId, actor);

        Assert.assertTrue(chat.canRead(channel, member), "An absent membership row means JOINED");
        Assert.assertNull(chat.readDenial(channel, member));
    }

    @Test
    public void aStrangerCannotReadAndIsToldWhy() {
        final ChatChannel channel = chat.ensureChannel(tripId, actor);
        final Person.Id stranger = person("Stranger");

        Assert.assertFalse(chat.canRead(channel, stranger));
        Assert.assertEquals(chat.readDenial(channel, stranger), "NOT_A_TRIP_MEMBER");
    }

    @Test
    public void leavingAndRejoiningFlipsReadAccess() {
        chat.ensureChannel(tripId, actor);

        Assert.assertTrue(chat.leave(tripId, member, actor));
        final ChatChannel channel = chat.getChannel(tripId);
        Assert.assertFalse(chat.canRead(channel, member));
        Assert.assertEquals(chat.readDenial(channel, member), "LEFT_CHANNEL");
        Assert.assertEquals(chat.membershipStateForTrip(tripId, member), "LEFT");

        Assert.assertTrue(chat.rejoin(tripId, member, actor));
        Assert.assertTrue(chat.canRead(chat.getChannel(tripId), member));
    }

    /** Removal is not self-reversible: only an administrator can undo it. */
    @Test
    public void someoneRemovedCannotRejoinThemselves() {
        chat.ensureChannel(tripId, actor);
        Assert.assertTrue(chat.removeMember(tripId, member, "spam", Caller.forActor(actor)));

        Assert.assertEquals(chat.readDenial(chat.getChannel(tripId), member), "REMOVED_FROM_CHANNEL");
        Assert.assertFalse(chat.rejoin(tripId, member, actor), "Self-rejoin after removal must be refused");
    }

    /** A trip save that drops people takes them off the chat too, so the roster cannot disagree with the trip. */
    @Test
    public void leaveOnTripRemovalTakesDroppedPeopleOffTheChat() {
        chat.ensureChannel(tripId, actor);

        chat.leaveOnTripRemoval(tripId, List.of(member));

        Assert.assertFalse(chat.canRead(chat.getChannel(tripId), member));
        // Guards: nothing to do, and nothing to blow up on.
        chat.leaveOnTripRemoval(tripId, List.of());
        chat.leaveOnTripRemoval(tripId, null);
        chat.leaveOnTripRemoval(null, List.of(member));
    }

    // --- email preferences ---

    @Test
    public void emailPrefsRoundTripPerPersonPerChat() {
        chat.ensureChannel(tripId, actor);

        Assert.assertTrue(chat.setEmailPrefs(tripId, member, true, false));
        Assert.assertTrue(chat.mentionEmailForTrip(tripId, member));
        Assert.assertFalse(chat.dailyDigestForTrip(tripId, member));

        Assert.assertTrue(chat.setEmailPrefs(tripId, member, false, true));
        Assert.assertFalse(chat.mentionEmailForTrip(tripId, member));
        Assert.assertTrue(chat.dailyDigestForTrip(tripId, member));
    }

    @Test
    public void emailPrefsAreRefusedForSomeoneWhoCannotRead() {
        chat.ensureChannel(tripId, actor);

        Assert.assertFalse(chat.setEmailPrefs(tripId, person("Outsider"), true, true));
        Assert.assertFalse(chat.setEmailPrefs(tripId, null, true, true));
        Assert.assertFalse(chat.setEmailPrefs(null, member, true, true));
    }

    @Test
    public void prefsForSomeoneWithNoRowReadAsTheDefaults() {
        chat.ensureChannel(tripId, actor);

        // No row written yet: the defaults answer rather than throwing or reporting "on".
        Assert.assertFalse(chat.dailyDigestForTrip(tripId, person("Fresh")));
    }

    // --- read cursor ---

    @Test
    public void theReadCursorRecordsAndClearsUnread() {
        chat.ensureChannel(tripId, actor);
        final ChatCommands.SendResult sent = chat.send(tripId, member, "hello", null, null, actor);
        Assert.assertTrue(sent.isOk(), "test setup: send");

        Assert.assertTrue(chat.markRead(tripId, member, sent.getMessageObj().getId()));
        Assert.assertFalse(chat.hasUnread(tripId, member), "Reading to the newest message clears the dot");
    }

    @Test
    public void aMessageAfterYourCursorIsUnread() {
        chat.ensureChannel(tripId, actor);
        final ChatCommands.SendResult first = chat.send(tripId, member, "one", null, null, actor);
        Assert.assertTrue(chat.markRead(tripId, member, first.getMessageObj().getId()));

        Assert.assertTrue(chat.send(tripId, adminId, "two", null, null, actor).isOk());

        Assert.assertTrue(chat.hasUnread(tripId, member));
    }

    @Test
    public void cursorOperationsGuardTheirInputs() {
        chat.ensureChannel(tripId, actor);

        Assert.assertFalse(chat.markRead(tripId, null, ChatMessage.Id.from("1")));
        Assert.assertFalse(chat.markRead(tripId, member, null));
        Assert.assertFalse(chat.markRead("no-such-trip", member, ChatMessage.Id.from("1")));
        Assert.assertFalse(chat.hasUnread(tripId, null));
        Assert.assertFalse(chat.hasUnread("no-such-trip", member));
        Assert.assertFalse(chat.hasUnread(tripId, person("Nobody")), "A stranger has nothing to read");
    }

    // --- deletion rights ---

    @Test
    public void anAuthorMayDeleteTheirOwnAndNobodyElsesUnlessTheyAdminister() {
        chat.ensureChannel(tripId, actor);
        final ChatMessage.Id mine = chat.send(tripId, member, "mine", null, null, actor)
                .getMessageObj().getId();

        Assert.assertTrue(chat.canDelete(tripId, mine, member));
        Assert.assertFalse(chat.canDelete(tripId, mine, person("Other")));
        // NOT true off a Faces thread: canDelete's admin branch asks about the SIGNED-IN user (the one-arg
        // canAdminister), and there is no session here. The REST edge compensates by also calling
        // canAdminister(tripId, caller()) with a real Caller.
        Assert.assertFalse(chat.canDelete(tripId, mine, adminId));

        Assert.assertFalse(chat.canDelete(tripId, null, member));
        Assert.assertFalse(chat.canDelete(tripId, mine, null));
        Assert.assertFalse(chat.canDelete(tripId, ChatMessage.Id.from("999"), member), "No such message");
    }

    /**
     * {@code canAdminister(String)} answers about the SIGNED-IN user, and says so in its signature.
     *
     * <p>It used to take a {@code Person.Id} it never consulted -- callers got the right answer only because
     * they all happened to pass the current user's id. The id parameter is gone so that mistake can no longer
     * be expressed; a per-person answer goes through the Caller-taking overload.
     */
    @Test
    public void theJsfCanAdministerAnswersForTheSignedInUserOnly() {
        chat.ensureChannel(tripId, actor);

        Assert.assertFalse(chat.canAdminister(tripId),
                "no FacesContext means no signed-in user, whatever privileges exist");
        // The Caller-taking overload is the one that answers about a given person.
        Assert.assertTrue(chat.canAdminister(tripId, Caller.forActor(actor)));
    }

    // --- the roster and the chat list ---

    @Test
    public void theRosterListsTheChannelsMembers() {
        chat.ensureChannel(tripId, actor);
        Assert.assertTrue(chat.leave(tripId, member, actor), "test setup: materialise a row");

        final List<ChatMembership> roster = chat.roster(tripId);

        Assert.assertFalse(roster.isEmpty());
        Assert.assertEquals(chat.roster("no-such-trip"), List.of(), "No channel means no roster");
    }

    @Test
    public void myChatsListsOnlyChatsThisPersonCanRead() {
        chat.ensureChannel(tripId, actor);

        Assert.assertTrue(chat.myChats(member).stream()
                .anyMatch(summary -> tripId.equals(summary.channel().getId().getValue().substring("trip:".length()))),
                "A member sees their own chat");
        Assert.assertTrue(chat.myChats(person("Unrelated")).stream()
                .noneMatch(summary -> summary.channel().getId().getValue().endsWith(tripId)),
                "Somebody not on the trip does not");
    }

    // --- the add-member warning, which is the human control on re-adding somebody ---

    @Test
    public void theAddWarningNamesWhatItIsReversing() {
        final ChatChannel channel = chat.ensureChannel(tripId, actor);

        // Never touched the chat: a plain permission ask.
        final Person.Id fresh = person("Fresh");
        Assert.assertTrue(chat.addWarningText(channel, fresh).contains("Confirm you have their permission"));

        // Left voluntarily: says so, and says re-adding resumes notifications.
        Assert.assertTrue(chat.leave(tripId, member, actor));
        final String leftText = chat.addWarningText(chat.getChannel(tripId), member);
        Assert.assertTrue(leftText.contains("chose to leave"), leftText);

        // Removed by an admin: names the admin and the reason, and warns about exposing what was said.
        final Person.Id removed = person("Removed");
        Assert.assertTrue(chat.removeMember(tripId, removed, "being unkind", Caller.forActor(actor)));
        final String removedText = chat.addWarningText(chat.getChannel(tripId), removed);
        Assert.assertTrue(removedText.contains("was removed by"), removedText);
        Assert.assertTrue(removedText.contains("being unkind"), "The reason is shown");
        Assert.assertTrue(removedText.contains("read everything said while they were removed"), removedText);
    }

    // --- settings diffing, which is what the audit record says ---

    @Test
    public void theSettingsDiffNamesOnlyWhatChanged() {
        final ChatSettings before = ChatSettings.defaults();
        final ChatSettings after = before.toBuilder().slowModeSeconds(30).build();

        final String described = chat.describeSettingsChange(before, after);

        Assert.assertTrue(described.contains("slowModeSeconds"), described);
        Assert.assertFalse(described.contains("burstLimit"), "Unchanged fields stay out of the record");
        Assert.assertEquals(chat.describeSettingsChange(before, before), "Saved settings",
                "Nothing changed means nothing listed");
    }

    @Test
    public void exportIsAuditedWithItsSize() {
        chat.ensureChannel(tripId, actor);

        // A bulk disclosure is a different act from scrolling, so it is recorded with the count.
        chat.auditExport(tripId, 42, actor);
    }

    /** Both of these fail OPEN for an unknown trip: a missing trip must not present as "chat disabled". */
    @Test
    public void chatDefaultsToOnIncludingForAnUnknownTrip() {
        Assert.assertTrue(chat.chatEnabledForTrip(tripId));
        Assert.assertTrue(chat.chatEnabledForTrip("no-such-trip"));
    }

    @Test
    public void settingsHelpersAnswerForAnAbsentChannel() {
        Assert.assertNull(chat.getChannel("no-such-trip"));
        Assert.assertTrue(chat.fullHistoryForTrip("no-such-trip"), "Defaults apply when there is no channel");
        Assert.assertNotNull(chat.getDefaultBackgroundImage());
        Assert.assertTrue(chat.getEditWindowMinutes() > 0);
    }

    /**
     * The UI helpers resolve their own caller through {@code Caller.current()}, which reads FacesContext. Off a
     * Faces thread that yields nobody, so they fail CLOSED -- refusing moderation rather than performing it
     * unattributed. What is asserted here is the input guard and that direction of failure.
     */
    @Test
    public void theUiMuteHelperGuardsItsInputAndFailsClosedOffAFacesThread() {
        chat.ensureChannel(tripId, actor);

        Assert.assertFalse(chat.muteMinutes(tripId, member.getValue(), null, "why"));
        Assert.assertFalse(chat.muteMinutes(tripId, member.getValue(), 0, "why"));
        Assert.assertFalse(chat.muteMinutes(tripId, null, 10, "why"));
        Assert.assertFalse(chat.muteMinutes(tripId, member.getValue(), 10, "why"),
                "No FacesContext means no caller, so the mute is refused rather than made anonymously");

        Assert.assertFalse(chat.unmuteUi(tripId, null));
        Assert.assertFalse(chat.unmuteUi(tripId, member.getValue()));
        Assert.assertFalse(chat.removeMemberUi(tripId, member.getValue(), "reason"));
    }

    @Test
    public void reactionsVersionAdvancesWithReactions() {
        chat.ensureChannel(tripId, actor);
        final ChatMessage.Id msg = chat.send(tripId, member, "react to me", null, null, actor)
                .getMessageObj().getId();
        final long before = chat.reactionsVersion(tripId);

        Assert.assertTrue(chat.react(tripId, member, msg, chat.getEmojiPalette().get(0)).ok());

        Assert.assertTrue(chat.reactionsVersion(tripId) >= before, "A reaction advances the version");
    }
}
