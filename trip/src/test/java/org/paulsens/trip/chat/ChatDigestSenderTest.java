package org.paulsens.trip.chat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.MailCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatNotifyPref;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatSettings;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;

/**
 * {@link ChatDigestSender}: who gets a daily digest, and what stops tomorrow repeating today.
 *
 * <p>Two rules here were each written in response to a real failure, and both are about NOT sending.
 *
 * <p>The send is AWAITED. Fire-and-forget reported success the moment the request reached the SDK, so an SES
 * rejection counted as a sent digest -- and the watermark advanced anyway, so the retry found nothing left and
 * the day's summary was lost outright rather than retried.
 *
 * <p>Tombstones are not news. Deleted messages stay visible in the app on purpose, but "1 new message" that
 * turns out to be "Message removed" is mail nobody wanted.
 */
public class ChatDigestSenderTest {

    private static final Person.Id MEMBER = Person.Id.from("digest-member");

    private DAO dao;
    private CacheClient cache;
    private MailCommands mail;
    private ConfigCommands config;
    private ChatDigestSender sender;

    @BeforeMethod
    public void setUp() {
        dao = Mockito.mock(DAO.class);
        cache = Mockito.mock(CacheClient.class);
        mail = Mockito.mock(MailCommands.class);
        config = Mockito.mock(ConfigCommands.class);
        sender = new ChatDigestSender(dao, cache, mail, config);

        Mockito.when(cache.getValue(ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        Mockito.when(cache.putValue(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any())).thenReturn(true);
    }

    private static ChatChannel channel(final Long retentionSeconds) {
        final ChatSettings settings = ChatSettings.defaults().toBuilder()
                .retentionSeconds(retentionSeconds).build();
        return new ChatChannel(ChatChannel.Id.forTrip("t1"), "t1", ChatChannel.Kind.TRIP, "Test",
                null, null, settings, Instant.now(), "admin", null, null);
    }

    private static ChatMessage message(final String id, final Instant deletedAt) {
        return new ChatMessage(ChatMessage.Id.from(id), ChatChannel.Id.forTrip("t1"),
                Person.Id.from("author"), Instant.now(), null, "hello", null, null, null, null,
                deletedAt, null, null, null, null);
    }

    private static ChatPage pageOf(final ChatMessage... messages) {
        return new ChatPage(List.of(messages), Map.of(), null, 0L, 0L, false, false, Map.of(), Instant.now());
    }

    private static Trip trip() {
        return Trip.builder().id("t1").title("Rome 2027")
                .startDate(LocalDateTime.now().minusDays(5))
                .endDate(LocalDateTime.now().plusDays(5))
                .people(List.of(MEMBER)).build();
    }

    private ChatDigestSender.Candidate candidate(final ChatPage page) {
        final ChatMembership member = new ChatMembership(
                ChatChannel.Id.forTrip("t1"), MEMBER, ChatMembership.MemberState.JOINED, null,
                Instant.now(), null, null, null, null, null, null, null,
                ChatNotifyPref.defaults().withEmail(false, true), null, null, null, null);
        return new ChatDigestSender.Candidate(channel(null), trip(), MEMBER, member, page);
    }

    private void personIsReachable() {
        final Person person = new Person();
        person.setId(MEMBER);
        person.setEmail("member@example.org");
        Mockito.when(dao.getPerson(MEMBER)).thenReturn(Optional.of(person));
        Mockito.when(mail.formatEmail(ArgumentMatchers.any())).thenReturn("Member <member@example.org>");
    }

    private void mailAnswers(final SendEmailResponse response) {
        Mockito.when(mail.send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(AuditActor.class)))
                .thenReturn(response);
    }

    // --- what counts as news ---

    @Test
    public void tombstonesAreNotNews() {
        Assert.assertTrue(ChatDigestSender.newsIn(pageOf(message("1", Instant.now()))).isEmpty(),
                "a digest of nothing but removals is mail nobody wanted");
        Assert.assertEquals(ChatDigestSender.newsIn(
                pageOf(message("1", Instant.now()), message("2", null))).size(), 1);
        Assert.assertTrue(ChatDigestSender.newsIn(pageOf()).isEmpty());
    }

    /** An ephemeral channel is excluded: a digest would outlive the messages it summarises. */
    @Test
    public void anEphemeralChannelIsNotDigested() {
        Assert.assertFalse(ChatDigestSender.digestAllowed(channel(0L)));
        Assert.assertTrue(ChatDigestSender.digestAllowed(channel(null)), "keep-forever is digestible");
        Assert.assertTrue(ChatDigestSender.digestAllowed(channel(86_400L)));
    }

    // --- sending ---

    @Test
    public void aConfirmedSendAdvancesTheWatermark() {
        personIsReachable();
        mailAnswers(SendEmailResponse.builder().messageId("ses-1").build());

        Assert.assertTrue(sender.send(candidate(pageOf(message("100", null)))));

        // Only after a confirmed send: the watermark is what stops tomorrow repeating today.
        Mockito.verify(cache).putValue(ArgumentMatchers.contains("digest"), ArgumentMatchers.anyString(),
                ArgumentMatchers.any());
    }

    /**
     * The regression this exists for: a rejected send must NOT advance the watermark.
     *
     * <p>Otherwise the retry finds nothing left to send and the day's summary is lost outright rather than
     * retried. MailCommands maps a failed send to a null response.
     */
    @Test
    public void aRejectedSendLeavesTheWatermarkAloneSoTheRetryStillHasSomethingToSend() {
        personIsReachable();
        mailAnswers(null);

        Assert.assertFalse(sender.send(candidate(pageOf(message("100", null)))));

        Mockito.verify(cache, Mockito.never()).putValue(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    /** No usable address is not a retryable failure: report done so the run can finish. */
    @Test
    public void aRecipientWithNoAddressIsTreatedAsDoneNotFailed() {
        Mockito.when(dao.getPerson(MEMBER)).thenReturn(Optional.empty());

        Assert.assertTrue(sender.send(candidate(pageOf(message("100", null)))));

        Mockito.verify(mail, Mockito.never()).send(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(AuditActor.class));
        Mockito.verify(cache, Mockito.never()).putValue(ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.any());
    }

    @Test
    public void aFailureInsideDeliveryIsReportedNotThrown() {
        Mockito.when(dao.getPerson(MEMBER)).thenThrow(new IllegalStateException("store is down"));

        Assert.assertFalse(sender.send(candidate(pageOf(message("100", null)))),
                "one bad recipient must not abort the whole scheduled run");
    }

    /** The digest is attributed to System: nobody asked for this send, the scheduler did. */
    @Test
    public void theDigestIsSentAsSystem() {
        personIsReachable();
        mailAnswers(SendEmailResponse.builder().messageId("ses-1").build());

        sender.send(candidate(pageOf(message("100", null))));

        Mockito.verify(mail).send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.contains("Rome 2027"), ArgumentMatchers.any(),
                ArgumentMatchers.argThat((AuditActor actor) ->
                        actor != null && AuditActor.SYSTEM_ID.equals(actor.id())));
    }

    // --- candidate selection ---

    @Test
    public void nobodyIsACandidateWhenThereAreNoTrips() {
        Mockito.when(dao.getActiveTrips(ArgumentMatchers.any()))
                .thenReturn(List.of());

        Assert.assertTrue(sender.candidates(Instant.now()).isEmpty());
    }

    /**
     * Trips that have ENDED still have open chats.
     *
     * <p>A chat stays writable until archiveAfterTripEndDays (90 by default), so asking only for currently
     * active trips stopped every digest the day a trip ended -- exactly when people are still talking about it.
     * The lookback is therefore long enough to cover any archive setting, and isArchived decides per channel.
     */
    @Test
    public void theTripLookbackReachesWellPastTheEndOfATrip() {
        Mockito.when(dao.getActiveTrips(ArgumentMatchers.any()))
                .thenReturn(List.of());

        sender.candidates(Instant.now());

        final org.mockito.ArgumentCaptor<LocalDateTime> cutoff =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        Mockito.verify(dao).getActiveTrips(cutoff.capture());
        Assert.assertTrue(cutoff.getValue().isBefore(LocalDateTime.now().minusDays(300)),
                "the cutoff must reach past any archiveAfterTripEndDays setting");
    }

    @Test
    public void theProgressFieldIdentifiesAChannelAndPerson() {
        final ChatDigestSender.Candidate candidate = candidate(pageOf());

        Assert.assertEquals(candidate.progressField(),
                ChatChannel.Id.forTrip("t1").getValue() + "|" + MEMBER.getValue());
    }

    @Test
    public void theDefaultConstructorWiresItselfUp() {
        Assert.assertNotNull(new ChatDigestSender());
    }

    // --- the full candidate pipeline (trip -> channel -> member -> floor -> page) ---

    private ChatMembership membership(final ChatMembership.MemberState state, final boolean digest) {
        return new ChatMembership(
                ChatChannel.Id.forTrip("t1"), MEMBER, state, null,
                Instant.now(), null, null, null, null, null, null, null,
                ChatNotifyPref.defaults().withEmail(false, digest), null, null, null, null);
    }

    private void tripPipeline(final Trip trip, final ChatChannel chan, final ChatMembership member) {
        Mockito.when(dao.getActiveTrips(ArgumentMatchers.any()))
                .thenReturn(List.of(trip));
        Mockito.when(dao.getChatChannel(ArgumentMatchers.any()))
                .thenReturn(Optional.ofNullable(chan));
        Mockito.when(dao.listChatMembers(ArgumentMatchers.any()))
                .thenReturn(List.of(member));
        Mockito.when(dao.getChatCursor(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        Mockito.when(dao.getChatMessagesSince(ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(pageOf(message("100", null)));
    }

    @Test
    public void anOptedInMemberWithNewsIsACandidate() {
        personIsReachable();
        tripPipeline(trip(), channel(null), membership(ChatMembership.MemberState.JOINED, true));

        final List<ChatDigestSender.Candidate> found = sender.candidates(Instant.now());

        Assert.assertEquals(found.size(), 1);
        Assert.assertEquals(found.get(0).personId(), MEMBER);
        Assert.assertEquals(found.get(0).trip().getId(), "t1");
    }

    /** The digest is a positive opt-in: the default preference must never produce a candidate. */
    @Test
    public void aMemberWhoNeverOptedInIsNotACandidate() {
        personIsReachable();
        tripPipeline(trip(), channel(null), membership(ChatMembership.MemberState.JOINED, false));

        Assert.assertTrue(sender.candidates(Instant.now()).isEmpty());
    }

    @Test
    public void aMemberWhoLeftIsNotACandidateWhateverTheirPreferenceSays() {
        personIsReachable();
        tripPipeline(trip(), channel(null), membership(ChatMembership.MemberState.LEFT, true));

        Assert.assertTrue(sender.candidates(Instant.now()).isEmpty());
    }

    /** An unusable address opts them out -- otherwise the retry protocol re-attempts them daily, forever. */
    @Test
    public void aMemberWithNoUsableAddressIsNotACandidate() {
        final Person noAddress = new Person();
        noAddress.setId(MEMBER);
        noAddress.setEmail("just a name, not an address");
        Mockito.when(dao.getPerson(MEMBER))
                .thenReturn(Optional.of(noAddress));
        tripPipeline(trip(), channel(null), membership(ChatMembership.MemberState.JOINED, true));

        Assert.assertTrue(sender.candidates(Instant.now()).isEmpty());
    }

    @Test
    public void aTripWithChatDisabledContributesNothing() {
        final Trip disabled = trip();
        disabled.setChatEnabled(false);
        tripPipeline(disabled, channel(null), membership(ChatMembership.MemberState.JOINED, true));

        Assert.assertTrue(sender.candidates(Instant.now()).isEmpty());
        Mockito.verify(dao, Mockito.never()).getChatChannel(ArgumentMatchers.any());
    }

    @Test
    public void aTripWhoseChannelWasNeverCreatedContributesNothing() {
        tripPipeline(trip(), null, membership(ChatMembership.MemberState.JOINED, true));

        Assert.assertTrue(sender.candidates(Instant.now()).isEmpty());
    }

    @Test
    public void anEphemeralChannelContributesNothing() {
        personIsReachable();
        tripPipeline(trip(), channel(0L), membership(ChatMembership.MemberState.JOINED, true));

        Assert.assertTrue(sender.candidates(Instant.now()).isEmpty());
    }

    /** A closed chat is read-only and finished; a daily summary of it would be mail nobody can act on. */
    @Test
    public void anArchivedChannelContributesNothing() {
        personIsReachable();
        final Trip longOver = Trip.builder().id("t1").title("Rome 2018")
                .startDate(LocalDateTime.now().minusDays(400))
                .endDate(LocalDateTime.now().minusDays(395))
                .people(List.of(MEMBER)).build();
        tripPipeline(longOver, channel(null), membership(ChatMembership.MemberState.JOINED, true));

        Assert.assertTrue(sender.candidates(Instant.now()).isEmpty());
        Mockito.verify(dao, Mockito.never()).listChatMembers(ArgumentMatchers.any());
    }

    @Test
    public void aMemberWithOnlyTombstonesToReadIsNotACandidate() {
        personIsReachable();
        tripPipeline(trip(), channel(null), membership(ChatMembership.MemberState.JOINED, true));
        Mockito.when(dao.getChatMessagesSince(ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.anyInt(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                        ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(pageOf(message("100", Instant.now())));

        Assert.assertTrue(sender.candidates(Instant.now()).isEmpty());
    }

    // --- the digest floor ---

    /**
     * The floor is the LATER of the read cursor and the send watermark. Cursor alone repeats yesterday's digest
     * to anyone who never opened the chat; watermark alone re-summarises messages read in the app since.
     */
    @Test
    public void theFloorIsTheLaterOfCursorAndWatermark() {
        personIsReachable();
        final Instant now = Instant.now();
        final ChatMessage.Id cursor = ChatMessage.Id.of(now.minusSeconds(3_600).toEpochMilli());
        final ChatMessage.Id watermark = ChatMessage.Id.of(now.minusSeconds(60).toEpochMilli());
        tripPipeline(trip(), channel(null), membership(ChatMembership.MemberState.JOINED, true));
        Mockito.when(dao.getChatCursor(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(Optional.of(cursor));
        Mockito.when(cache.getValue(ArgumentMatchers.contains("digest")))
                .thenReturn(Optional.of(watermark.getValue()));

        sender.candidates(now);

        final org.mockito.ArgumentCaptor<ChatMessage.Id> floor =
                org.mockito.ArgumentCaptor.forClass(ChatMessage.Id.class);
        Mockito.verify(dao).getChatMessagesSince(ArgumentMatchers.any(), floor.capture(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
        Assert.assertEquals(floor.getValue(), watermark, "the later floor (the watermark here) must win");
    }

    /**
     * With no cursor and no watermark at all -- a new member, a quiet week, a cache flush -- the floor is the
     * 7-day lookback, never null: a null floor would digest the channel's OPENING conversation as new.
     */
    @Test
    public void aMissingFloorFallsBackToTheLookbackNeverNull() {
        personIsReachable();
        final Instant now = Instant.now();
        tripPipeline(trip(), channel(null), membership(ChatMembership.MemberState.JOINED, true));

        sender.candidates(now);

        final org.mockito.ArgumentCaptor<ChatMessage.Id> floor =
                org.mockito.ArgumentCaptor.forClass(ChatMessage.Id.class);
        Mockito.verify(dao).getChatMessagesSince(ArgumentMatchers.any(), floor.capture(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
        Assert.assertNotNull(floor.getValue(), "a null floor digests the trip's opening conversation");
        Assert.assertEquals(floor.getValue(), ChatMessage.Id.of(now.minus(Duration.ofDays(7)).toEpochMilli()));
    }
}
