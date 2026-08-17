package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.chat.ChatNudgeRegistry;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatAttachment;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatReaction;
import org.paulsens.trip.model.chat.ChatReactionSummary;
import org.paulsens.trip.model.chat.ChatSettings;
import org.paulsens.trip.model.chat.PhotoChatMeta;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.paulsens.trip.cache.Cached;

/**
 * The DAO half of photo threads: per-photo meta (rebuild, cache, invalidation), the roll-up fold into a trip
 * page's summaries and into the window refetch, the last-activity exclusion, and the purge.
 */
public class ChatDAOPhotoTest {

    private static final Person.Id ANN = Person.Id.from("photo-ann");
    private static final Person.Id BOB = Person.Id.from("photo-bob");

    private final DAO dao = DAO.getInstance();

    // --- photoMeta ---

    @Test
    public void metaCountsNonDeletedCommentsAndRootReactions() {
        final String key = key("meta-count");
        final ChatChannel photo = photoChannel(key);

        Assert.assertTrue(dao.saveChatMessage(comment(photo, ANN, "one"), photo, null).isPresent());
        final ChatMessage second = dao.saveChatMessage(comment(photo, BOB, "two"), photo, null).orElseThrow();
        Assert.assertTrue(dao.tombstoneChatMessage(photo.getId(), second.getId(), "ann").isPresent());
        Assert.assertTrue(dao.putChatReaction(rootReaction(key, ANN, "👍")));
        Assert.assertTrue(dao.putChatReaction(rootReaction(key, BOB, "👍")));
        dao.invalidatePhotoChatMeta(key);

        final PhotoChatMeta meta = dao.getPhotoChatMeta(List.of(key), Cached.NO).get(key);
        Assert.assertEquals(meta.getCommentCount(), 1, "a tombstoned comment must not count");
        Assert.assertEquals(meta.getRootReactions().count("👍"), 2);
    }

    @Test
    public void metaIsCachedUntilInvalidated() {
        final String key = key("meta-cache");
        final ChatChannel photo = photoChannel(key);
        Assert.assertEquals(dao.getPhotoChatMeta(List.of(key), Cached.NO).get(key).getCommentCount(), 0);

        // A write the cache has not been told about is invisible — the hash field is the authority...
        Assert.assertTrue(dao.saveChatMessage(comment(photo, ANN, "hi"), photo, null).isPresent());
        // (the message write itself does not drop pmeta; that is the command layer's job)
        Assert.assertEquals(dao.getPhotoChatMeta(List.of(key), Cached.NO).get(key).getCommentCount(), 0,
                "still the cached zero");

        // ...until the field is dropped, after which the next read rebuilds from the store.
        dao.invalidatePhotoChatMeta(key);
        Assert.assertEquals(dao.getPhotoChatMeta(List.of(key), Cached.NO).get(key).getCommentCount(), 1);
    }

    @Test
    public void metaBatchAnswersEveryKeyEvenTheEmptyOnes() {
        final String a = key("batch-a");
        final String b = key("batch-b");
        final Map<String, PhotoChatMeta> meta = dao.getPhotoChatMeta(List.of(a, b), Cached.NO);
        Assert.assertEquals(meta.get(a).getCommentCount(), 0);
        Assert.assertEquals(meta.get(b).getCommentCount(), 0);
        Assert.assertTrue(dao.getPhotoChatMeta(List.of(), Cached.NO).isEmpty());
        Assert.assertTrue(dao.getPhotoChatMeta(null, Cached.NO).isEmpty());
    }

    // --- the fold (roll-up read side) ---

    @Test
    public void aTripPageFoldsPhotoReactionsIntoTheCarryingMessageWithSumSemantics() throws IOException {
        final String tripId = seedTrip("fold");
        final String key = key("fold");
        final ChatChannel tripChannel = tripChannel(tripId);
        final ChatMessage carrier = dao.saveChatMessage(
                mediaMessage(tripChannel, ANN, key), tripChannel, null).orElseThrow();

        // Ann reacts directly on the message AND on the photo; Bob reacts on the photo only.
        Assert.assertTrue(dao.putChatReaction(new ChatReaction(
                tripChannel.getId(), carrier.getId(), ANN, "👍", Instant.now(), null)));
        Assert.assertTrue(dao.putChatReaction(rootReaction(key, ANN, "👍")));
        Assert.assertTrue(dao.putChatReaction(rootReaction(key, BOB, "🎉")));
        dao.invalidatePhotoChatMeta(key);

        final Map<ChatMessage.Id, ChatReactionSummary> summaries =
                dao.getChatReactionSummaries(tripChannel.getId(), List.of(carrier), Cached.NO);
        final ChatReactionSummary folded = summaries.get(carrier.getId());
        Assert.assertEquals(folded.count("👍"), 2, "direct + photo = 2 (SUM, not union)");
        Assert.assertTrue(folded.mine("👍", ANN), "mine stays bound to the direct reaction");
        Assert.assertEquals(folded.count("🎉"), 1);
        Assert.assertTrue(folded.getByEmoji().containsKey("🎉"),
                "photo-only emoji seeded into byEmoji or the deployed chip JS never renders it");
        Assert.assertFalse(folded.mine("🎉", BOB));
    }

    @Test
    public void theWindowRefetchFoldsIdenticallyAndKeepsAbsentMeaningNoReactions() throws IOException {
        final String tripId = seedTrip("window");
        final String key = key("window");
        final ChatChannel tripChannel = tripChannel(tripId);
        final ChatMessage bare = dao.saveChatMessage(
                comment(tripChannel, ANN, "no photo here"), tripChannel, null).orElseThrow();
        final ChatMessage carrier = dao.saveChatMessage(
                mediaMessage(tripChannel, ANN, key), tripChannel, null).orElseThrow();

        Assert.assertTrue(dao.putChatReaction(rootReaction(key, BOB, "👍")));
        dao.invalidatePhotoChatMeta(key);

        final Map<ChatMessage.Id, ChatReactionSummary> window =
                dao.getChatReactionWindow(tripChannel.getId(), bare.getId(), carrier.getId(), Cached.NO);
        Assert.assertEquals(window.get(carrier.getId()).count("👍"), 1,
                "a photo-only reaction must reach the window refetch — it is how live clients learn of it");
        Assert.assertFalse(window.containsKey(bare.getId()),
                "a message with no reactions anywhere stays absent — the window contract");
    }

    @Test
    public void photoChannelsThemselvesNeverFoldOrTouchLastActivity() {
        final String key = key("no-recurse");
        final ChatChannel photo = photoChannel(key);
        final ChatMessage c = dao.saveChatMessage(comment(photo, ANN, "hello"), photo, null).orElseThrow();

        Assert.assertFalse(dao.getChatLastActivity(Cached.NO).containsKey(photo.getId().getValue()),
                "a commented photo must not grow the my-chats last-activity hash");
        // And summarising a photo channel's own page must not recurse into pmeta.
        final Map<ChatMessage.Id, ChatReactionSummary> summaries =
                dao.getChatReactionSummaries(photo.getId(), List.of(c), Cached.NO);
        Assert.assertEquals(summaries.get(c.getId()).totalCount(), 0);
    }

    // --- roll-up write side ---

    @Test
    public void rollupBumpsTheParentsVersionAndDropsItsSummary() throws IOException {
        final String tripId = seedTrip("rollup");
        final String key = key("rollup");
        final ChatChannel tripChannel = tripChannel(tripId);
        final ChatMessage carrier = dao.saveChatMessage(
                mediaMessage(tripChannel, ANN, key), tripChannel, null).orElseThrow();
        final ChatChannel photo = photoChannelWithParent(key, tripChannel.getId(), carrier.getId());

        // Warm the parent's cached summary, then react on the photo and roll up.
        Assert.assertEquals(dao.getChatReactionSummaries(
                tripChannel.getId(), List.of(carrier), Cached.NO).get(carrier.getId()).totalCount(), 0);
        final long versionBefore = dao.getChatReactionsVersion(tripChannel.getId(), Cached.NO);

        Assert.assertTrue(dao.putChatReaction(rootReaction(key, BOB, "👍")));
        dao.invalidatePhotoChatMeta(key);
        Assert.assertTrue(dao.rollupPhotoToParent(photo));

        Assert.assertTrue(dao.getChatReactionsVersion(tripChannel.getId(), Cached.NO) > versionBefore,
                "clients watch this version; without the bump the reaction never reaches them");
        Assert.assertEquals(dao.getChatReactionSummaries(
                        tripChannel.getId(), List.of(carrier), Cached.NO).get(carrier.getId()).count("👍"), 1,
                "the dropped summary rebuilds with the folded count");
    }

    /**
     * The bump alone is not enough: a client parked in a long poll learns of it only when the roll-up also
     * NUDGES the parent channel. Without this, a photo reaction reached the message chip a whole poll timeout
     * later — which reads as "reactions don't work" and sent people reloading the page.
     */
    @Test
    public void rollupWakesReadersParkedOnTheParentChannel() throws IOException {
        final String tripId = seedTrip("nudge");
        final String key = key("nudge");
        final ChatChannel tripChannel = tripChannel(tripId);
        final ChatMessage carrier = dao.saveChatMessage(
                mediaMessage(tripChannel, ANN, key), tripChannel, null).orElseThrow();
        final ChatChannel photo = photoChannelWithParent(key, tripChannel.getId(), carrier.getId());

        final java.util.concurrent.CountDownLatch woken = new java.util.concurrent.CountDownLatch(1);
        try (AutoCloseable parked = ChatNudgeRegistry.getInstance()
                .park(tripChannel.getId().getValue(), upTo -> woken.countDown())) {
            Assert.assertTrue(dao.putChatReaction(rootReaction(key, BOB, "👍")));
            dao.invalidatePhotoChatMeta(key);
            Assert.assertTrue(dao.rollupPhotoToParent(photo));
            Assert.assertTrue(woken.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "a reader parked on the carrying message's channel must be woken by the roll-up");
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        } catch (final Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    public void rollupWithoutAParentIsANoOp() {
        final ChatChannel orphan = photoChannel(key("orphan"));
        Assert.assertFalse(dao.rollupPhotoToParent(orphan));
        Assert.assertFalse(dao.rollupPhotoToParent(null));
    }

    // --- purge ---

    @Test
    public void purgeRemovesRowsCachesAndTheChannelAndIsIdempotent() {
        final String key = key("purge");
        final ChatChannel photo = photoChannel(key);
        final ChatMessage c = dao.saveChatMessage(comment(photo, ANN, "bye"), photo, null).orElseThrow();
        Assert.assertTrue(dao.putChatReaction(rootReaction(key, ANN, "👍")));
        dao.invalidatePhotoChatMeta(key);
        Assert.assertEquals(dao.getPhotoChatMeta(List.of(key), Cached.NO).get(key).getCommentCount(), 1);

        Assert.assertTrue(dao.purgeChatChannel(photo.getId()).isPresent(),
                "the purge returns the channel it removed — the caller rolls up to its parent");

        Assert.assertTrue(dao.getChatChannel(photo.getId(), Cached.NO).isEmpty(), "channel row gone");
        Assert.assertTrue(dao.getChatMessage(photo.getId(), c.getId(), Cached.NO).isEmpty(), "comment rows gone");
        Assert.assertEquals(dao.getPhotoChatMeta(List.of(key), Cached.NO).get(key).getCommentCount(), 0,
                "meta rebuilt from an empty partition");
        Assert.assertEquals(dao.getPhotoChatMeta(List.of(key), Cached.NO).get(key).getRootReactions().totalCount(), 0);
        // Idempotent: a replayed cascade must be harmless.
        Assert.assertTrue(dao.purgeChatChannel(photo.getId()).isEmpty());
        Assert.assertTrue(dao.purgeChatChannel(null).isEmpty());
    }

    // --- fixtures ---

    private static String key(final String tag) {
        return "chat/trip-" + tag + "/" + System.nanoTime() + "-" + tag + ".jpg";
    }

    private ChatChannel photoChannel(final String key) {
        return photoChannelWithParent(key, null, null);
    }

    private ChatChannel photoChannelWithParent(
            final String key, final ChatChannel.Id parentChannel, final ChatMessage.Id parentMsg) {
        final ChatChannel channel = new ChatChannel(
                ChatChannel.Id.forPhoto(key), PhotoTestKeys.tripIdOf(key), ChatChannel.Kind.PHOTO,
                null, null, List.of(), ChatSettings.builder().allowMedia(false).build(),
                Instant.now(), "test", null, parentChannel, parentMsg, null);
        Assert.assertTrue(dao.saveChatChannel(channel));
        return channel;
    }

    private ChatChannel tripChannel(final String tripId) {
        final ChatChannel channel = new ChatChannel(
                ChatChannel.Id.forTrip(tripId), tripId, ChatChannel.Kind.TRIP,
                "Test", null, List.of(), ChatSettings.defaults(), Instant.now(), "test", null, null);
        Assert.assertTrue(dao.saveChatChannel(channel));
        return channel;
    }

    private String seedTrip(final String tag) throws IOException {
        final String tripId = java.util.UUID.randomUUID().toString();
        final Trip trip = Trip.builder()
                .id(tripId)
                .title("ChatDAOPhotoTest " + tag)
                .openToPublic(false)
                .description("photo fold fixture")
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(7))
                .people(new ArrayList<>(List.of(ANN, BOB)))
                .build();
        Assert.assertTrue(dao.saveTrip(trip));
        return tripId;
    }

    private static ChatMessage comment(final ChatChannel channel, final Person.Id author, final String body) {
        return new ChatMessage(null, channel.getId(), author, null, ChatMessage.MessageKind.TEXT,
                body, null, List.of(), null, null, null, null, null, null, null);
    }

    private static ChatMessage mediaMessage(
            final ChatChannel channel, final Person.Id author, final String s3Key) {
        final ChatAttachment attachment = new ChatAttachment(
                "image", s3Key, "image/jpeg", 10L, 4, 3, s3Key + "-small", null);
        return new ChatMessage(null, channel.getId(), author, null, ChatMessage.MessageKind.MEDIA,
                "look!", null, List.of(attachment), null, null, null, null, null, null, null);
    }

    private static ChatReaction rootReaction(final String key, final Person.Id who, final String emoji) {
        return new ChatReaction(
                ChatChannel.Id.forPhoto(key), PhotoChatMeta.PHOTO_ROOT, who, emoji, Instant.now(), null);
    }

    /** Tiny local copy of the key→trip parse, so this test does not depend on the action layer. */
    private static final class PhotoTestKeys {
        private PhotoTestKeys() {
        }

        static String tripIdOf(final String key) {
            final int slash = key.indexOf('/', "chat/".length());
            return key.substring("chat/".length(), slash);
        }
    }
}
