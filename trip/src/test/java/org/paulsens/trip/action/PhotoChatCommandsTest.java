package org.paulsens.trip.action;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatNotification;
import org.paulsens.trip.chat.ChatNotifications;
import org.paulsens.trip.chat.ChatNotifier;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.media.PhotoFixtures;
import org.paulsens.trip.model.MediaItem;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatMentions;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.PhotoChatMeta;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The photo-thread rules: who may read (comments follow the photo), who may post (any signed-in user), the
 * parent resolution both eager and lazy, the roll-up ordering, moderation, the delete cascade, and the
 * mention policies (sender-trust email gate, masked typeahead labels).
 */
public class PhotoChatCommandsTest {

    /** A real UUID: privilege scopes must round-trip the UUID-suffix identity parse. */
    private final String tripId = java.util.UUID.randomUUID().toString();

    private final Person.Id member = Person.Id.from("pc-member-" + System.nanoTime());
    private final Person.Id stranger = Person.Id.from("pc-stranger-" + System.nanoTime());

    private PhotoChatCommands photoChat;

    @BeforeMethod
    public void setUp() throws IOException {
        DAO.getInstance();
        final Trip trip = Trip.builder()
                .id(tripId)
                .title("PhotoChat test trip")
                .openToPublic(false)
                .description("owned by PhotoChatCommandsTest")
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(7))
                .people(new ArrayList<>(List.of(member)))
                .build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        photoChat = new PhotoChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
    }

    @AfterMethod(alwaysRun = true)
    public void resetNotifier() {
        ChatNotifications.setNotifier(null);
    }

    // --- key parsing ---

    @Test
    public void onlyChatShapedKeysParse() {
        Assert.assertEquals(PhotoChatCommands.tripIdOfKey("chat/t1/20260809-x.jpg"), "t1");
        Assert.assertNull(PhotoChatCommands.tripIdOfKey("profilePics/p.jpg"));
        Assert.assertNull(PhotoChatCommands.tripIdOfKey("chat//x.jpg"));
        Assert.assertNull(PhotoChatCommands.tripIdOfKey("chat/t1/"));
        Assert.assertNull(PhotoChatCommands.tripIdOfKey("chat/t1/a#b.jpg"), "'#' is the reaction delimiter");
        Assert.assertNull(PhotoChatCommands.tripIdOfKey(null));
    }

    // --- read authorization: comments follow the photo ---

    @Test
    public void aVisiblePhotoIsReadableByAbsolutelyAnyone() {
        final String key = seedPhoto(false);
        Assert.assertNull(photoChat.readDenialFor(key, anonymous()), "anonymous may read");
        Assert.assertNull(photoChat.readDenialFor(key, callerFor(stranger)), "so may a non-member");
        Assert.assertNull(photoChat.readDenialFor(key, callerFor(member)));
    }

    @Test
    public void aHiddenPhotoIsMemberOnlyAndLooksGoneToEveryoneElse() {
        final String key = seedPhoto(true);
        Assert.assertEquals(photoChat.readDenialFor(key, anonymous()), "NOT_FOUND",
                "hidden must be indistinguishable from gone");
        Assert.assertEquals(photoChat.readDenialFor(key, callerFor(stranger)), "NOT_FOUND");
        Assert.assertNull(photoChat.readDenialFor(key, callerFor(member)), "a trip member still sees it");
        final Person.Id moderator = grant(PrivilegeCommands.MEDIA_ADMIN, null);
        Assert.assertNull(photoChat.readDenialFor(key, callerFor(moderator)), "so does mediaAdmin");
    }

    @Test
    public void aPhotoWithNoMediaRowDoesNotExistHere() {
        Assert.assertEquals(photoChat.readDenialFor("chat/" + tripId + "/never-recorded.jpg",
                callerFor(member)), "NOT_FOUND");
        Assert.assertEquals(photoChat.readDenialFor("not-a-chat-key", callerFor(member)), "NOT_FOUND");
    }

    // --- comments ---

    @Test
    public void anyoneSignedInMayCommentAndTheBadgeCountFollows() {
        final String key = seedPhoto(false);
        final ChatCommands.SendResult result =
                photoChat.comment(key, stranger, "lovely shot", null, callerFor(stranger));
        Assert.assertTrue(result.isOk(), String.valueOf(result.getMessage()));

        final Map<String, PhotoChatMeta> meta = photoChat.batchMeta(List.of(key), anonymous());
        Assert.assertEquals(meta.get(key).getCommentCount(), 1, "the write invalidated pmeta");

        final ChatPage thread = photoChat.thread(key, null, 50);
        Assert.assertEquals(thread.getMessages().size(), 1);
        Assert.assertEquals(thread.getMessages().get(0).getBody(), "lovely shot");
    }

    @Test
    public void commentValidationRefusesEmptyAndOversized() {
        final String key = seedPhoto(false);
        Assert.assertEquals(photoChat.comment(key, member, "   ", null, callerFor(member)).getCode(), "empty");
        Assert.assertEquals(photoChat.comment(key, member, "x".repeat(1001), null,
                callerFor(member)).getCode(), "too_long");
        Assert.assertEquals(photoChat.comment("chat/" + tripId + "/none.jpg", member, "hi", null,
                callerFor(member)).getCode(), "not_found");
    }

    @Test
    public void theMasterSwitchRefusesEverything() {
        final ConfigCommands off = Mockito.mock(ConfigCommands.class);
        Mockito.when(off.getBoolean(KnownSettings.CHAT_PHOTO_COMMENTS_ENABLED)).thenReturn(false);
        final PhotoChatCommands disabled =
                new PhotoChatCommands(new ChatRateLimiter(new InMemoryCacheClient()), off);
        final String key = seedPhoto(false);
        Assert.assertEquals(disabled.comment(key, member, "hi", null, callerFor(member)).getCode(),
                "disabled");
        Assert.assertEquals(disabled.react(key, member, "👍", true, callerFor(member)).code(), "disabled");
    }

    @Test
    public void aRateLimitedCommentIsA429NeverAMute() {
        final ChatRateLimiter limiter = Mockito.mock(ChatRateLimiter.class);
        Mockito.when(limiter.check(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(ChatRateLimiter.Decision.deny("burst", 7, 5, 10,
                        Instant.now().plusSeconds(300)));
        final PhotoChatCommands limited = new PhotoChatCommands(limiter);
        final String key = seedPhoto(false);

        final ChatCommands.SendResult result = limited.comment(key, member, "hi", null, callerFor(member));
        Assert.assertFalse(result.isOk());
        Assert.assertNotNull(result.getDecision(), "the 429 mapping needs the decision's Retry-After");
        // Photo posting has no membership row, so even an auto-mute-tier denial stays a plain rate limit.
        Assert.assertTrue(DAO.getInstance().getChatMembership(
                ChatChannel.Id.forPhoto(key), member).isEmpty(), "no mute row materialised");
    }

    @Test
    public void aDuplicateClientMessageIdCollapsesOntoOneComment() {
        final String key = seedPhoto(false);
        final String cmid = "photo-cmid-" + System.nanoTime();
        final ChatMessage first = photoChat.comment(key, member, "once", cmid,
                callerFor(member)).getMessageObj();
        final ChatMessage second = photoChat.comment(key, member, "once", cmid,
                callerFor(member)).getMessageObj();
        Assert.assertEquals(second.getId(), first.getId());
    }

    // --- reactions and the roll-up ---

    @Test
    public void reactingRollsUpToTheCarryingMessageFoundEagerly() {
        final ChatMessage carrier = sendCarrierThroughTripChat();
        final String key = carrier.getAttachments().get(0).getS3Key();

        Assert.assertTrue(photoChat.react(key, stranger, "👍", true, callerFor(stranger)).ok());

        final ChatChannel photo = photoChat.photoChannelForRead(key);
        Assert.assertEquals(photo.getParentMsgId(), carrier.getId(),
                "the send-path hook created the channel with its parent already known");
        Assert.assertEquals(DAO.getInstance().getChatReactionSummaries(
                        ChatChannel.Id.forTrip(tripId), List.of(carrier)).get(carrier.getId()).count("👍"), 1,
                "the photo reaction reached the message's chip");

        Assert.assertTrue(photoChat.react(key, stranger, "👍", false, callerFor(stranger)).ok());
        Assert.assertEquals(photoChat.rootSummary(key).totalCount(), 0, "unreact removes for real");
    }

    @Test
    public void aLegacyPhotoResolvesItsParentByScanningOnce() {
        final ChatMessage carrier = sendCarrierThroughTripChat();
        final String key = carrier.getAttachments().get(0).getS3Key();
        // Simulate "uploaded before this feature": drop the eagerly created channel.
        DAO.getInstance().purgeChatChannel(ChatChannel.Id.forPhoto(key));

        Assert.assertTrue(photoChat.react(key, member, "🎉", true, callerFor(member)).ok());

        Assert.assertEquals(photoChat.photoChannelForRead(key).getParentMsgId(), carrier.getId(),
                "the lazy scan found the carrying message and persisted it");
    }

    @Test
    public void aPhotoNobodyEverPostedResolvesToNoParentAndStillWorks() {
        final String key = seedPhoto(false);
        Assert.assertTrue(photoChat.react(key, member, "👍", true, callerFor(member)).ok());
        final ChatChannel photo = photoChat.photoChannelForRead(key);
        Assert.assertNull(photo.getParentMsgId(), "no carrier ⇒ nothing to roll up into; stored as null");
        Assert.assertEquals(photoChat.rootSummary(key).count("👍"), 1);
        Assert.assertFalse(photo.getSettings().isAllowMedia(),
                "the stored settings survived the reserved-media fingerprint");
        Assert.assertNull(photo.getSettings().getRetentionSeconds(), "photo comments never expire");
    }

    @Test
    public void reactionValidation() {
        final String key = seedPhoto(false);
        Assert.assertEquals(photoChat.react(key, member, "💣", true, callerFor(member)).code(), "bad_emoji");
        Assert.assertEquals(photoChat.react("chat/" + tripId + "/none.jpg", member, "👍", true,
                callerFor(member)).code(), "not_found");
    }

    // --- moderation ---

    @Test
    public void authorsDeleteTheirOwnStrangersAreRefusedModeratorsMayAlways() {
        final String key = seedPhoto(false);
        final ChatMessage comment = photoChat.comment(key, stranger, "to be removed", null,
                callerFor(stranger)).getMessageObj();

        Assert.assertFalse(photoChat.deleteComment(key, comment.getId(), callerFor(member)),
                "an ordinary member may not delete someone else's comment");
        Assert.assertTrue(photoChat.deleteComment(key, comment.getId(), callerFor(stranger)),
                "the author always may");
        Assert.assertEquals(photoChat.batchMeta(List.of(key), anonymous()).get(key).getCommentCount(), 0,
                "the tombstone left the badge count");

        final ChatMessage second = photoChat.comment(key, stranger, "again", null,
                callerFor(stranger)).getMessageObj();
        final Person.Id chatMgr = grant("chatMgr", tripId);
        Assert.assertTrue(photoChat.deleteComment(key, second.getId(), callerFor(chatMgr)));
    }

    // --- the delete cascade ---

    @Test
    public void purgingThePhotoRemovesItsWholeThread() {
        final String key = seedPhoto(false);
        Assert.assertTrue(photoChat.comment(key, member, "doomed", null, callerFor(member)).isOk());
        Assert.assertTrue(photoChat.react(key, member, "👍", true, callerFor(member)).ok());

        PhotoChatCommands.onMediaChange(MediaEvents.Change.REMOVED, key);

        Assert.assertNull(photoChat.photoChannelForRead(key));
        Assert.assertEquals(photoChat.thread(key, null, 50).getMessages().size(), 0);
        Assert.assertEquals(photoChat.rootSummary(key).totalCount(), 0);
        // ADDED events and purging twice are both no-ops.
        PhotoChatCommands.onMediaChange(MediaEvents.Change.ADDED, key);
        PhotoChatCommands.purgePhotoThread(key);
        PhotoChatCommands.purgePhotoThread(null);
    }

    // --- mention email: the sender-trust gate ---

    @Test
    public void aTravelersMentionEmailsButAStrangersOnlyHighlights() throws Exception {
        final String key = seedPhoto(false);
        final Person.Id mentioned = seedPersonWithEmail("Mentioned Friend");
        final CapturingNotifier captured = new CapturingNotifier();
        ChatNotifications.setNotifier(captured);

        Assert.assertFalse(photoChat.isKnownTraveler(stranger), "never joined a trip");
        Assert.assertTrue(photoChat.comment(key, stranger,
                "hi " + ChatMentions.token(mentioned), null, callerFor(stranger)).isOk());
        Assert.assertFalse(captured.latch.await(400, TimeUnit.MILLISECONDS),
                "an untrusted sender's mention must not generate mail");

        Assert.assertTrue(photoChat.isKnownTraveler(member), "on this trip's roster");
        Assert.assertTrue(photoChat.comment(key, member,
                "hi " + ChatMentions.token(mentioned), null, callerFor(member)).isOk());
        Assert.assertTrue(captured.latch.await(2, TimeUnit.SECONDS), "a trusted sender's mention mails");
        Assert.assertEquals(captured.notifications.get(0).getRecipients(), List.of(mentioned));
        Assert.assertEquals(captured.notifications.get(0).getChannelId(), ChatChannel.Id.forPhoto(key),
                "the photo channel id drives the photo template and deep link");
    }

    // --- mention search ---

    @Test
    public void mentionSearchIsCappedMaskedAndNeverLeaksAFullAddress() throws IOException {
        final String tag = Long.toString(System.nanoTime());
        seedNamedPerson("Zeb" + tag, "Alpha", "zeb" + tag + "@example.org");
        seedNamedPerson("Zeb" + tag, "Beta", "zed" + tag + "@sample.org");

        Assert.assertTrue(photoChat.mentionSearch("Z", 8).isEmpty(), "below the 2-character minimum");
        final List<Map<String, String>> hits = photoChat.mentionSearch("Zeb" + tag, 8);
        Assert.assertEquals(hits.size(), 2);
        for (final Map<String, String> hit : hits) {
            Assert.assertFalse(hit.get("label").contains("@example.org"), "full address must never appear");
            Assert.assertFalse(hit.get("label").contains("@sample.org"));
            Assert.assertTrue(hit.get("label").contains("•••"),
                    "colliding names are disambiguated by MASKED address: " + hit.get("label"));
        }
        Assert.assertNotEquals(hits.get(0).get("label"), hits.get(1).get("label"));
        Assert.assertTrue(photoChat.mentionSearchAllowed(member));
        Assert.assertFalse(photoChat.mentionSearchAllowed(null));
    }

    // --- guard tails (each of these is a real refusal path, not ceremony) ---

    @Test
    public void guardTails() {
        Assert.assertSame(PhotoChatCommands.getPhotoChatCommands(), PhotoChatCommands.getPhotoChatCommands());
        Assert.assertNull(photoChat.mediaFor(null));
        Assert.assertNull(photoChat.photoChannelForRead("chat/none/none.jpg"));
        Assert.assertFalse(photoChat.canSeeIdentities(tripId, null));
        Assert.assertFalse(photoChat.canSeeIdentities(tripId, anonymous()));
        Assert.assertFalse(photoChat.canModerate(tripId, null));
        Assert.assertFalse(photoChat.isKnownTraveler(null));
        Assert.assertEquals(photoChat.thread("chat/none/none.jpg", null, 0).getMessages().size(), 0,
                "no channel yet answers an empty page, and a GET creates nothing");
        Assert.assertNull(photoChat.photoChannelForRead("chat/none/none.jpg"), "still nothing created");
        Assert.assertEquals(photoChat.rootSummary("chat/none/none.jpg").totalCount(), 0);
        Assert.assertTrue(photoChat.batchMeta(null, anonymous()).isEmpty());
        Assert.assertTrue(photoChat.batchMeta(List.of(), anonymous()).isEmpty());
        Assert.assertTrue(photoChat.reactorNames(null).isEmpty());
        photoChat.ensureChannelsForMessage(null, null);
        // Delete guards: unknown photo, null ids, null caller.
        Assert.assertFalse(photoChat.deleteComment("chat/none/none.jpg",
                org.paulsens.trip.model.chat.ChatMessage.Id.of(1L), callerFor(member)));
        final String key = seedPhoto(false);
        final ChatMessage comment = photoChat.comment(key, member, "kept", null,
                callerFor(member)).getMessageObj();
        Assert.assertFalse(photoChat.deleteComment(key, null, callerFor(member)));
        Assert.assertFalse(photoChat.deleteComment(key, comment.getId(), null));
        Assert.assertFalse(photoChat.deleteComment(key,
                org.paulsens.trip.model.chat.ChatMessage.Id.of(2L), callerFor(member)),
                "a comment that does not exist cannot be deleted");
    }

    @Test
    public void identityDetailComesFromMembershipTripViewOrModeration() {
        final Person.Id viewer = grant(PrivilegeCommands.TRIP_VIEW, tripId);
        Assert.assertTrue(photoChat.canSeeIdentities(tripId, callerFor(viewer)));
        final Person.Id mgr = grant(PrivilegeCommands.TRIP_MGR, tripId);
        Assert.assertTrue(photoChat.canSeeIdentities(tripId, callerFor(mgr)), "moderators see identities");
        Assert.assertTrue(photoChat.canSeeIdentities(tripId, callerFor(member)));
        Assert.assertFalse(photoChat.canSeeIdentities(tripId, callerFor(stranger)));
    }

    @Test
    public void aPhotoWhoseTripRowIsGoneStillTakesComments() {
        // The public-read rule needs the MEDIA row, not the trip: a trip deleted after its photos were
        // uploaded must not strand the album's threads.
        final String ghostTrip = java.util.UUID.randomUUID().toString();
        final String key = "chat/" + ghostTrip + "/" + System.nanoTime() + "-g.jpg";
        Assert.assertTrue(DAO.getInstance().saveMedia(new MediaItem(
                java.util.UUID.randomUUID().toString(), key, "Ghost", null, "image/jpeg", 1L,
                "tripChat-" + ghostTrip, 0, LocalDateTime.now(), member.getValue(), null, null)));
        Assert.assertTrue(photoChat.comment(key, member, "still here", null, callerFor(member)).isOk());
        final ChatPage thread = photoChat.thread(key, null, 5);
        Assert.assertEquals(thread.getMessages().size(), 1);
    }

    @Test
    public void theMentionSearchBrakeTripsAfterThirtyLookups() {
        final Person.Id heavy = Person.Id.from("pc-heavy-" + System.nanoTime());
        boolean refused = false;
        for (int i = 0; i < 35; i++) {
            refused |= !photoChat.mentionSearchAllowed(heavy);
        }
        Assert.assertTrue(refused, "31st lookup inside a minute must be refused");
        // A zero cap is clamped to one result, not rejected: it must not throw and must not hand back a
        // page. Asserting emptiness instead would depend on nobody in the shared fake store matching "ab",
        // which is a hostage to whichever suite seeded people first.
        Assert.assertTrue(photoChat.mentionSearch("ab", 0).size() <= 1, "a zero cap still behaves");
        Assert.assertTrue(photoChat.mentionSearch("zz-nobody-" + System.nanoTime(), 8).isEmpty(),
                "a query matching no one is still empty");
    }

    @Test
    public void aUniqueNameNeedsNoMasking() throws IOException {
        final String name = "Solo" + System.nanoTime();
        seedNamedPerson(name, "Only", "solo@example.org");
        final List<Map<String, String>> hits = photoChat.mentionSearch(name, 8);
        Assert.assertEquals(hits.size(), 1);
        Assert.assertFalse(hits.get(0).get("label").contains("•••"),
                "no collision, no masked address — addresses appear only when needed");
    }

    @Test
    public void maskedEmailShapes() {
        Assert.assertEquals(PhotoChatCommands.maskedEmail("jsmith@example.org"), "j•••h@e…");
        Assert.assertEquals(PhotoChatCommands.maskedEmail("a@b.org"), "a•••@b…");
        Assert.assertEquals(PhotoChatCommands.maskedEmail(null), "no email");
        Assert.assertEquals(PhotoChatCommands.maskedEmail(" "), "no email");
    }

    // --- fixtures ---

    private String seedPhoto(final boolean hidden) {
        final String key = "chat/" + tripId + "/" + System.nanoTime() + "-t.jpg";
        final MediaItem item = new MediaItem(java.util.UUID.randomUUID().toString(), key,
                "Test photo", "Uploaded in a test", "image/jpeg", 10L, "tripChat-" + tripId, 0,
                LocalDateTime.now(), member.getValue(), key + "-small", hidden ? Boolean.TRUE : null);
        Assert.assertTrue(DAO.getInstance().saveMedia(item));
        return key;
    }

    /**
     * Sends a real MEDIA message through the trip-chat send — real staging, real album row, and the eager
     * photo-channel hook — and returns it. The photo's key is the attachment's s3Key.
     */
    private ChatMessage sendCarrierThroughTripChat() {
        final ChatCommands chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
        final AuditActor actor = new AuditActor("member@test", member.getValue());
        chat.ensureChannel(tripId, actor);
        final ChatPhotos.StagedPhoto staged = ChatPhotos.getChatPhotos()
                .stage(tripId, member, PhotoFixtures.jpeg(640, 480));
        final ChatCommands.SendResult sent = chat.send(tripId, member, "carrier", null, null, actor,
                List.of(new ChatPhotos.AttachmentRef(staged.key(), "t")));
        Assert.assertTrue(sent.isOk(), String.valueOf(sent.getMessage()));
        return sent.getMessageObj();
    }

    private Person.Id grant(final String privilege, final String scope) {
        final Person.Id holder = Person.Id.from("pc-priv-" + System.nanoTime());
        final PrivilegeCommands privs = new PrivilegeCommands();
        Assert.assertTrue(privs.savePrivilege(
                privs.createPrivilege(privilege, privilege, scope, List.of(holder))));
        return holder;
    }

    private Person.Id seedPersonWithEmail(final String first) throws IOException {
        final Person person = new Person();
        person.setFirst(first);
        person.setEmail("mentioned-" + System.nanoTime() + "@example.org");
        Assert.assertTrue(DAO.getInstance().savePerson(person));
        return person.getId();
    }

    private void seedNamedPerson(final String first, final String last, final String email)
            throws IOException {
        final Person person = new Person();
        person.setFirst(first);
        person.setLast(last);
        person.setNickname(first);
        person.setEmail(email);
        Assert.assertTrue(DAO.getInstance().savePerson(person));
    }

    private static Caller callerFor(final Person.Id id) {
        return new Caller(id, false, new AuditActor(id.getValue() + "@test", id.getValue()),
                new PrivilegeCommands());
    }

    private static Caller anonymous() {
        return new Caller(null, false, AuditActor.from(null), new PrivilegeCommands());
    }

    private static final class CapturingNotifier implements ChatNotifier {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final List<ChatNotification> notifications = new CopyOnWriteArrayList<>();

        @Override
        public Channel channel() {
            return Channel.EMAIL;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void notify(final ChatNotification notification) {
            notifications.add(notification);
            latch.countDown();
        }
    }
}
