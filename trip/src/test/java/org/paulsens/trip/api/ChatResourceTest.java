package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link ChatResource}, the largest resource on the API.
 *
 * <p>The bean owns the chat rules and is tested in {@code ChatCommandsTest}; what is pinned HERE is the mapping
 * layer -- which bean answer becomes which status code -- and the feed's ordering/authorization decisions. The
 * 429 mapping matters most: a client treats it as retryable and keeps the user's text, so mapping a rate limit
 * to any terminal status would make the UI throw away what somebody typed.
 */
public class ChatResourceTest extends ResourceTestSupport {

    private static final String TRIP_ID = "trip-chat";
    private static final String CHANNEL = "trip:" + TRIP_ID;
    private static final Person.Id ME = Person.Id.from("chat-me");

    private ChatCommands chat;
    private MockedStatic<ChatCommands> chatStatic;
    private ChatResource resource;

    @BeforeMethod
    public void bindChat() {
        chat = Mockito.mock(ChatCommands.class);
        chatStatic = Mockito.mockStatic(ChatCommands.class);
        chatStatic.when(ChatCommands::getChatCommands).thenReturn(chat);
        resource = resource(new ChatResource());
        signedInAs(ME);
    }

    @AfterMethod(alwaysRun = true)
    public void closeChatStatic() {
        if (chatStatic != null) {
            chatStatic.close();
            chatStatic = null;
        }
    }

    /** Collects the single response a suspended feed call resumes with. */
    private Response feed(final String channelId, final String since, final String before, final String order) {
        return resource.feed(channelId, since, before, order, 0, 200, null, null);
    }

    private static ChatMessage message(final String id) {
        return new ChatMessage(ChatMessage.Id.from(id), ChatChannel.Id.forTrip(TRIP_ID), ME, Instant.now(),
                null, "hello", null, null, null, null, null, null, null, null, null);
    }

    private static ChatPage pageOf(final ChatMessage... messages) {
        return new ChatPage(List.of(messages), Map.of(), null, 0L, 0L, false, false, Map.of(), Instant.now());
    }

    private ChatChannel channel() {
        final ChatChannel channel = Mockito.mock(ChatChannel.class);
        Mockito.when(chat.getChannel(TRIP_ID)).thenReturn(channel);
        return channel;
    }

    // --- the feed ---

    @Test
    public void aChannelIdWithoutTheTripPrefixIs400() {
        // A lenient parse would read a future "dm:a:b" as a trip named "dm:a:b" -- the wrong auth scope.
        assertError(feed("dm:a:b", null, null, null), 400, ChatErrors.BAD_CHANNEL);
        assertError(feed("trip:", null, null, null), 400, ChatErrors.BAD_CHANNEL);
        assertError(feed(null, null, null, null), 400, ChatErrors.BAD_CHANNEL);
    }

    /** A disabled chat must 403 even with no channel row, or a long-polling client never stops asking. */
    @Test
    public void aDisabledChatIs403BeforeAnyChannelLookup() {
        Mockito.when(chat.chatEnabledForTrip(TRIP_ID)).thenReturn(false);

        assertError(feed(CHANNEL, null, null, null), 403, ChatErrors.CHAT_DISABLED);
        Mockito.verify(chat, Mockito.never()).getChannel(ArgumentMatchers.anyString());
    }

    /** A GET must not create anything: a missing channel answers an EMPTY page to a member, 403 to others. */
    @Test
    public void aMissingChannelIsAnEmptyFeedForAMemberNotACreation() {
        Mockito.when(chat.chatEnabledForTrip(TRIP_ID)).thenReturn(true);
        Mockito.when(chat.getChannel(TRIP_ID)).thenReturn(null);
        Mockito.when(chat.canParticipate(TRIP_ID, ME)).thenReturn(true);

        final Response response = feed(CHANNEL, null, null, null);

        assertOk(response);
        Assert.assertTrue(((ChatPage) response.getEntity()).isEmpty());
    }

    @Test
    public void aMissingChannelIs403ForANonMember() {
        Mockito.when(chat.chatEnabledForTrip(TRIP_ID)).thenReturn(true);
        Mockito.when(chat.getChannel(TRIP_ID)).thenReturn(null);
        Mockito.when(chat.canParticipate(TRIP_ID, ME)).thenReturn(false);

        assertError(feed(CHANNEL, null, null, null), 403, ChatErrors.NOT_A_TRIP_MEMBER);
    }

    @Test
    public void aReadDenialIsForwardedWithItsOwnCode() {
        Mockito.when(chat.chatEnabledForTrip(TRIP_ID)).thenReturn(true);
        channel();
        Mockito.when(chat.readDenial(ArgumentMatchers.any(), ArgumentMatchers.eq(ME)))
                .thenReturn(ChatErrors.REMOVED_FROM_CHANNEL);

        assertError(feed(CHANNEL, null, null, null), 403, ChatErrors.REMOVED_FROM_CHANNEL);
    }

    /**
     * order=newest asks for the LATEST page; an ordinary poll reads forward. Inferring intent from a blank
     * `before` once served the oldest messages in the channel as though they were the newest.
     */
    @Test
    public void orderNewestTakesTheHistoryPathAndAPlainPollTakesTheFeedPath() {
        Mockito.when(chat.chatEnabledForTrip(TRIP_ID)).thenReturn(true);
        channel();
        Mockito.when(chat.readDenial(ArgumentMatchers.any(), ArgumentMatchers.eq(ME))).thenReturn(null);
        Mockito.when(chat.history(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt())).thenReturn(pageOf(message("m1")));
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt())).thenReturn(pageOf(message("m2")));

        assertOk(feed(CHANNEL, null, null, "newest"));
        Mockito.verify(chat).history(TRIP_ID, ME, null, 50);

        assertOk(feed(CHANNEL, "m1", null, null));
        Mockito.verify(chat).feed(TRIP_ID, ME, ChatMessage.Id.from("m1"), 200);
    }

    @Test
    public void aVanishedSessionResumesWith401RatherThanEscaping() {
        anonymous();

        assertError(feed(CHANNEL, null, null, null), 401, ChatErrors.NOT_AUTHENTICATED);
    }

    @Test
    public void aBeanFailureResumesWith500RatherThanHanging() {
        Mockito.when(chat.chatEnabledForTrip(TRIP_ID)).thenThrow(new IllegalStateException("boom"));

        assertError(feed(CHANNEL, null, null, null), 500, ChatErrors.INTERNAL);
    }

    // --- send ---

    private Response send(final String body) {
        return resource.send(CHANNEL, CSRF_OK, ApiMediaTypes.CHAT_V1,
                body == null ? null : Map.of("body", body));
    }

    @Test
    public void sendRequiresItsOwnCsrfHeaderAndAChatContentType() {
        assertError(resource.send(CHANNEL, null, ApiMediaTypes.CHAT_V1, Map.of()), 403, ChatErrors.CSRF);

        final Response wrongType = resource.send(CHANNEL, CSRF_OK, "text/plain", Map.of());
        assertError(wrongType, 415, ChatErrors.UNSUPPORTED_MEDIA_TYPE);
        Mockito.verifyNoInteractions(chat);
    }

    @Test
    public void aSentMessageComesBackAsTheStoredObject() {
        final ChatMessage stored = message("m9");
        Mockito.when(chat.send(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.eq("hi"),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(AuditActor.class),
                ArgumentMatchers.anyList()))
                .thenReturn(ChatCommands.SendResult.ok(stored));

        final Response response = send("hi");

        assertOk(response);
        Assert.assertSame(response.getEntity(), stored);
    }

    /** The 429 contract: retryable, with Retry-After, so the client keeps the user's text. */
    @Test
    public void aRateLimitedSendIs429WithRetryAfterNotATerminalError() {
        Mockito.when(chat.send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(AuditActor.class),
                ArgumentMatchers.anyList()))
                .thenReturn(ChatCommands.SendResult.fail("rate_limit", "Slow down"));

        final Response response = send("spam");

        Assert.assertEquals(response.getStatus(), 429);
        Assert.assertNotNull(response.getHeaderString("Retry-After"));
    }

    @Test
    public void sendFailuresMapToTheStatusTheClientActsOn() {
        Mockito.when(chat.send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(AuditActor.class),
                ArgumentMatchers.anyList()))
                .thenReturn(ChatCommands.SendResult.fail("muted", "You are muted"))
                .thenReturn(ChatCommands.SendResult.fail("too_long", "Too long"))
                .thenReturn(ChatCommands.SendResult.fail("store", "Store failed"));

        assertError(send("a"), 403, ChatErrors.MUTED);
        assertError(send("b"), 400, ChatErrors.MESSAGE_TOO_LONG);
        assertError(send("c"), 500, ChatErrors.STORE_FAILED);
    }

    // --- delete and edit ---

    @Test
    public void anAuthorDeletesTheirOwnMessage() {
        Mockito.when(chat.canDelete(TRIP_ID, ChatMessage.Id.from("m1"), ME)).thenReturn(true);
        Mockito.when(chat.deleteMessage(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ChatMessage.Id.from("m1")),
                ArgumentMatchers.any(Caller.class))).thenReturn(true);

        assertOk(resource.delete(CHANNEL, "m1", CSRF_OK));
    }

    @Test
    public void aStrangerCannotDeleteSomebodyElsesMessage() {
        Mockito.when(chat.canDelete(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(false);
        Mockito.when(chat.canAdminister(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.any(Caller.class)))
                .thenReturn(false);

        assertError(resource.delete(CHANNEL, "m1", CSRF_OK), 403, ChatErrors.FORBIDDEN);
        Mockito.verify(chat, Mockito.never()).deleteMessage(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(Caller.class));
    }

    @Test
    public void deletingAMessageThatIsGoneIs404() {
        Mockito.when(chat.canDelete(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(true);
        Mockito.when(chat.deleteMessage(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(Caller.class))).thenReturn(false);

        assertError(resource.delete(CHANNEL, "m1", CSRF_OK), 404, ChatErrors.NOT_FOUND);
    }

    @Test
    public void editOutcomesMapToTheirStatuses() {
        Mockito.when(chat.editMessage(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(ChatCommands.ReactResult.success())
                .thenReturn(ChatCommands.ReactResult.fail("EDIT_WINDOW_CLOSED", "Too late"))
                .thenReturn(ChatCommands.ReactResult.fail("not_found", "Gone"))
                .thenReturn(ChatCommands.ReactResult.fail("empty", "Empty"));

        assertOk(resource.edit(CHANNEL, "m1", CSRF_OK, Map.of("body", "fixed")));
        assertError(resource.edit(CHANNEL, "m1", CSRF_OK, Map.of("body", "late")),
                403, ChatErrors.EDIT_WINDOW_CLOSED);
        assertError(resource.edit(CHANNEL, "m1", CSRF_OK, Map.of("body", "gone")), 404, ChatErrors.NOT_FOUND);
        assertError(resource.edit(CHANNEL, "m1", CSRF_OK, Map.of("body", "")), 400, ChatErrors.MESSAGE_EMPTY);
    }

    // --- read cursor, reactions, membership ---

    @Test
    public void markReadNeedsACursor() {
        assertError(resource.markRead(CHANNEL, CSRF_OK, Map.of()), 400, ChatErrors.BAD_CHANNEL);
    }

    @Test
    public void markReadRecordsTheCursor() {
        Mockito.when(chat.markRead(TRIP_ID, ME, ChatMessage.Id.from("m5"))).thenReturn(true);

        assertOk(resource.markRead(CHANNEL, CSRF_OK, Map.of("cursor", "m5")));
    }

    @Test
    public void reactionOutcomesMapToTheirStatuses() {
        Mockito.when(chat.react(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.eq("🙂")))
                .thenReturn(ChatCommands.ReactResult.success())
                .thenReturn(ChatCommands.ReactResult.fail("bad_emoji", "Not in palette"));
        Mockito.when(chat.reactionsVersion(TRIP_ID)).thenReturn(7L);

        final Response ok = resource.react(CHANNEL, "m1", "🙂", CSRF_OK);
        assertOk(ok);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) ok.getEntity();
        Assert.assertEquals(body.get("reactionsVersion"), 7L);

        assertError(resource.react(CHANNEL, "m1", "🙂", CSRF_OK), 400, ChatErrors.BAD_EMOJI);
    }

    @Test
    public void unreactGoesThroughTheSameMapping() {
        Mockito.when(chat.unreact(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.eq("🙂"))).thenReturn(ChatCommands.ReactResult.success());
        Mockito.when(chat.reactionsVersion(TRIP_ID)).thenReturn(8L);

        assertOk(resource.unreact(CHANNEL, "m1", "🙂", CSRF_OK));
    }

    @Test
    public void theReactionWindowNeedsBothEnds() {
        assertError(resource.reactions(CHANNEL, null, "m9"), 400, ChatErrors.BAD_CHANNEL);
        assertError(resource.reactions(CHANNEL, "m1", " "), 400, ChatErrors.BAD_CHANNEL);
    }

    @Test
    public void aMissingChannelAnswersAnEmptyReactionWindow() {
        Mockito.when(chat.getChannel(TRIP_ID)).thenReturn(null);

        final Response response = resource.reactions(CHANNEL, "m1", "m9");

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("reactionsVersion"), 0L);
    }

    @Test
    public void joinReportsWhetherRejoiningIsAllowed() {
        Mockito.when(chat.rejoin(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME),
                ArgumentMatchers.any(AuditActor.class))).thenReturn(true).thenReturn(false);

        assertOk(resource.join(CHANNEL, CSRF_OK));
        assertError(resource.join(CHANNEL, CSRF_OK), 403, ChatErrors.REMOVED_FROM_CHANNEL);
    }

    @Test
    public void leaveReportsItsOutcome() {
        Mockito.when(chat.leave(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME),
                ArgumentMatchers.any(AuditActor.class))).thenReturn(true);

        assertOk(resource.leave(CHANNEL, CSRF_OK));
    }

    /** An export is a bulk disclosure and is audited with its size, unlike scrolling. */
    @Test
    public void exportIsAdminOnlyAndAudited() {
        Mockito.when(chat.canAdminister(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.any(Caller.class)))
                .thenReturn(false);
        assertError(resource.export(CHANNEL, 500), 403, ChatErrors.FORBIDDEN);

        Mockito.when(chat.canAdminister(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.any(Caller.class)))
                .thenReturn(true);
        Mockito.when(chat.history(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt())).thenReturn(pageOf(message("m1"), message("m2")));

        assertOk(resource.export(CHANNEL, 500));
        Mockito.verify(chat).auditExport(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(2),
                ArgumentMatchers.any(AuditActor.class));
    }

    @Test
    public void theRosterIsForMembersOnly() {
        Mockito.when(chat.canParticipate(TRIP_ID, ME)).thenReturn(false);
        assertError(resource.roster(CHANNEL), 403, ChatErrors.NOT_A_TRIP_MEMBER);

        Mockito.when(chat.canParticipate(TRIP_ID, ME)).thenReturn(true);
        Mockito.when(chat.roster(TRIP_ID)).thenReturn(List.of());
        assertOk(resource.roster(CHANNEL));
    }

    @Test
    public void prefsReadAndWriteTheCallersOwnSettings() {
        Mockito.when(chat.mentionEmailForTrip(TRIP_ID, ME)).thenReturn(true);
        Mockito.when(chat.dailyDigestForTrip(TRIP_ID, ME)).thenReturn(false);

        assertOk(resource.prefs(CHANNEL));

        Mockito.when(chat.setEmailPrefs(TRIP_ID, ME, true, true)).thenReturn(true);
        // Only dailyDigest submitted; mentionEmail falls back to the stored value rather than a default.
        assertOk(resource.savePrefs(CHANNEL, CSRF_OK, Map.of("dailyDigest", true)));
        Mockito.verify(chat).setEmailPrefs(TRIP_ID, ME, true, true);
    }

    // --- moderation ---

    @Test
    public void muteNeedsAPositiveDuration() {
        assertError(resource.mute(CHANNEL, "target-1", CSRF_OK, Map.of()), 400, ChatErrors.FORBIDDEN);
        assertError(resource.mute(CHANNEL, "target-1", CSRF_OK, Map.of("minutes", -5)), 400, ChatErrors.FORBIDDEN);
        Mockito.verify(chat, Mockito.never()).mute(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(Caller.class));
    }

    /** Moderation passes caller() down because the bean's own role check reads FacesContext and finds nothing. */
    @Test
    public void moderationActionsCarryTheSessionDerivedCaller() {
        Mockito.when(chat.mute(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(Person.Id.from("target-1")),
                ArgumentMatchers.any(Instant.class), ArgumentMatchers.any(),
                ArgumentMatchers.any(Caller.class))).thenReturn(true);
        Mockito.when(chat.unmute(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(Person.Id.from("target-1")),
                ArgumentMatchers.any(Caller.class))).thenReturn(true);
        Mockito.when(chat.removeMember(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(Person.Id.from("target-1")),
                ArgumentMatchers.any(), ArgumentMatchers.any(Caller.class))).thenReturn(true);

        assertOk(resource.mute(CHANNEL, "target-1", CSRF_OK, Map.of("minutes", 10)));
        assertOk(resource.unmute(CHANNEL, "target-1", CSRF_OK));
        assertOk(resource.removeMember(CHANNEL, "target-1", "spam", CSRF_OK));
    }

    @Test
    public void aRefusedModerationActionIs403() {
        Mockito.when(chat.unmute(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(Caller.class))).thenReturn(false);

        assertError(resource.unmute(CHANNEL, "target-1", CSRF_OK), 403, ChatErrors.FORBIDDEN);
    }

    /** Nobody is put back into a chat without their permission: the acknowledgement travels to the bean. */
    @Test
    public void addMemberForwardsTheAcknowledgement() {
        Mockito.when(chat.addMember(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(Person.Id.from("target-1")),
                ArgumentMatchers.eq("they asked me to"), ArgumentMatchers.any(Caller.class))).thenReturn(true);

        assertOk(resource.addMember(CHANNEL, "target-1", CSRF_OK, Map.of("acknowledgement", "they asked me to")));

        assertError(resource.addMember(CHANNEL, "target-1", CSRF_OK, Map.of()), 403, ChatErrors.FORBIDDEN);
    }

    @Test
    public void theEmojiPaletteComesFromTheServer() {
        Mockito.when(chat.getEmojiPalette()).thenReturn(List.of("🙂", "🎉"));

        assertOk(resource.emoji(CHANNEL));
    }

    @Test
    public void theProducedTypeIsTheChatMediaType() {
        Assert.assertEquals(new ChatResource().versionedType(), ApiMediaTypes.CHAT_V1);
    }
}
