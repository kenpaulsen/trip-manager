package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The photo-channel model pieces: the {@code photo:} id shape, the parent fields' wire compatibility, the
 * image-root constant's sort properties, and — most load-bearing — {@link ChatReactionSummary#foldCounts},
 * which is the SUM-semantics roll-up the deployed chat client renders without knowing it exists.
 */
public class PhotoChatModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final Person.Id ANN = Person.Id.from("ann");
    private static final Person.Id BOB = Person.Id.from("bob");

    // --- id shape ---

    @Test
    public void photoIdRoundTripsAndNeverReadsAsATrip() {
        final String key = "chat/trip-1/20260809-abc123.jpg";
        final ChatChannel.Id id = ChatChannel.Id.forPhoto(key);
        Assert.assertEquals(id.getValue(), "photo:" + key);
        Assert.assertEquals(id.photoKeyOrNull(), key);
        // A photo id must never parse as a trip — that would hand it the wrong authorization scope.
        Assert.assertNull(id.tripIdOrNull());
        Assert.assertNull(ChatChannel.Id.forTrip("t1").photoKeyOrNull());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void aBlankPhotoKeyIsRefused() {
        ChatChannel.Id.forPhoto(" ");
    }

    // --- the image-root reaction target ---

    @Test
    public void photoRootSortsBelowEveryRealMessageIdAndBuildsCleanRangeBounds() {
        Assert.assertEquals(PhotoChatMeta.PHOTO_ROOT.getValue(), "0000000000000");
        Assert.assertEquals(PhotoChatMeta.PHOTO_ROOT.getValue().length(), 13);
        Assert.assertTrue(PhotoChatMeta.PHOTO_ROOT.compareTo(ChatMessage.Id.of(1L)) < 0);
        Assert.assertTrue(PhotoChatMeta.PHOTO_ROOT.compareTo(ChatMessage.Id.of(System.currentTimeMillis())) < 0);
        // The reaction sort key machinery must accept it unchanged — no '#', 13-digit shape.
        final String sk = ChatReaction.sortKey(PhotoChatMeta.PHOTO_ROOT, ANN, "👍");
        Assert.assertTrue(sk.startsWith("0000000000000#"));
        Assert.assertTrue(ChatReaction.rangeLower(PhotoChatMeta.PHOTO_ROOT).compareTo(sk) <= 0);
        Assert.assertTrue(ChatReaction.rangeUpper(PhotoChatMeta.PHOTO_ROOT).compareTo(sk) >= 0);
    }

    // --- PhotoChatMeta ---

    @Test
    public void photoChatMetaNormalizesNullsAndRoundTrips() throws Exception {
        final PhotoChatMeta empty = new PhotoChatMeta(null, null);
        Assert.assertEquals(empty.getCommentCount(), 0);
        Assert.assertEquals(empty.getRootReactions().getMessageId(), PhotoChatMeta.PHOTO_ROOT);
        Assert.assertEquals(PhotoChatMeta.empty().getCommentCount(), 0);
        Assert.assertEquals(PhotoChatMeta.empty().getRootReactions().totalCount(), 0);

        final PhotoChatMeta meta = new PhotoChatMeta(3, summary(Map.of("👍", List.of(ANN))));
        final PhotoChatMeta back = MAPPER.readValue(MAPPER.writeValueAsString(meta), PhotoChatMeta.class);
        Assert.assertEquals(back.getCommentCount(), 3);
        Assert.assertEquals(back.getRootReactions().count("👍"), 1);
        Assert.assertEquals(javaRoundTrip(meta).getCommentCount(), 3);
    }

    // --- ChatChannel parent fields ---

    @Test
    public void parentFieldsRoundTripAndOldJsonStillParses() throws Exception {
        final ChatChannel channel = new ChatChannel(
                ChatChannel.Id.forPhoto("chat/t1/x.jpg"), "t1", ChatChannel.Kind.PHOTO,
                null, null, List.of(), ChatSettings.builder().allowMedia(false).build(),
                Instant.parse("2026-01-01T00:00:00Z"), "ann", null,
                ChatChannel.Id.forTrip("t1"), ChatMessage.Id.of(1234L), null);
        final ChatChannel back = MAPPER.readValue(MAPPER.writeValueAsString(channel), ChatChannel.class);
        Assert.assertEquals(back.getParentChannelId(), ChatChannel.Id.forTrip("t1"));
        Assert.assertEquals(back.getParentMsgId(), ChatMessage.Id.of(1234L));
        Assert.assertEquals(back.getKind(), ChatChannel.Kind.PHOTO);
        // The copy methods must carry the parents, or one withSettings() silently severs the roll-up.
        Assert.assertEquals(back.withTitle("x").getParentMsgId(), ChatMessage.Id.of(1234L));
        Assert.assertEquals(back.withSettings(ChatSettings.defaults()).getParentChannelId(),
                ChatChannel.Id.forTrip("t1"));

        // A row written before this feature has no parent fields and must parse to nulls, not fail.
        final ChatChannel old = MAPPER.readValue(
                "{\"id\":\"trip:t1\",\"tripId\":\"t1\",\"kind\":\"TRIP\"}", ChatChannel.class);
        Assert.assertNull(old.getParentChannelId());
        Assert.assertNull(old.getParentMsgId());
        Assert.assertEquals(javaRoundTrip(channel).getParentMsgId(), ChatMessage.Id.of(1234L));
    }

    /**
     * The v1ReservedMedia regression pin: a photo channel's settings are built with {@code allowMedia=false}
     * and the builder's NONZERO attachment caps, so the "reserved fingerprint" upgrade (false/0/0 → media on)
     * must not fire on a JSON round trip.
     */
    @Test
    public void photoChannelSettingsSurviveTheReservedMediaFingerprint() throws Exception {
        final ChatSettings settings = ChatSettings.builder().allowMedia(false).build();
        final ChatSettings back = MAPPER.readValue(MAPPER.writeValueAsString(settings), ChatSettings.class);
        Assert.assertFalse(back.isAllowMedia(),
                "allowMedia=false with default caps must survive — only the false/0/0 triple is 'reserved'");
        Assert.assertNull(back.getRetentionSeconds(), "photo comments never expire");
        Assert.assertNull(back.getRetentionDaysAfterTripEnd(), "no trip-end deletion either");
        Assert.assertTrue(back.isFullHistoryForNewMembers(), "everyone sees the whole thread");
    }

    // --- foldCounts: the SUM roll-up ---

    @Test
    public void foldingSumsCountsWithoutTouchingIdentity() {
        // Ann reacted 👍 directly on the message AND on two of its photos: the chip must read 3 (SUM
        // semantics, user decision 2026-08-09) while mine() stays answered by the direct row alone.
        final ChatReactionSummary direct = summary(Map.of("👍", List.of(ANN)));
        final ChatReactionSummary photo1 = summary(Map.of("👍", List.of(ANN)));
        final ChatReactionSummary photo2 = summary(Map.of("👍", List.of(ANN)));

        final ChatReactionSummary folded = ChatReactionSummary.foldCounts(direct, List.of(photo1, photo2));

        Assert.assertEquals(folded.count("👍"), 3);
        Assert.assertTrue(folded.mine("👍", ANN), "the direct reaction still reads as mine");
        Assert.assertEquals(folded.getByEmoji().get("👍"), List.of(ANN),
                "photo reactors must not join the who-list — the tooltip answers for the message alone");
    }

    @Test
    public void aPhotoOnlyEmojiIsSeededIntoByEmoji() {
        // VERIFIED against chat.xhtml: orderedEmoji iterates Object.keys(byEmoji) only, so an emoji that
        // exists purely as overflow would never render a chip. The fold must seed the key.
        final ChatReactionSummary direct = ChatReactionSummary.empty(ChatMessage.Id.of(5L));
        final ChatReactionSummary photo = summary(Map.of("🎉", List.of(BOB)));

        final ChatReactionSummary folded = ChatReactionSummary.foldCounts(direct, List.of(photo));

        Assert.assertTrue(folded.getByEmoji().containsKey("🎉"), "seeded with an empty who-list");
        Assert.assertEquals(folded.getByEmoji().get("🎉"), List.of());
        Assert.assertEquals(folded.count("🎉"), 1);
        Assert.assertFalse(folded.mine("🎉", BOB), "a photo reaction never lights the message chip");
        Assert.assertEquals(folded.totalCount(), 1);
    }

    @Test
    public void foldingMergesRecencyByMaxAndFoldedOverflowCounts() {
        final ChatReactionSummary direct = new ChatReactionSummary(ChatMessage.Id.of(5L),
                Map.of("👍", List.of(ANN)), Map.of(), Map.of("👍", 100L));
        final ChatReactionSummary newer = new ChatReactionSummary(PhotoChatMeta.PHOTO_ROOT,
                Map.of("👍", List.of(BOB)), Map.of("👍", 2), Map.of("👍", 900L));

        final ChatReactionSummary folded = ChatReactionSummary.foldCounts(direct, List.of(newer));

        // The photo's count is its who-list PLUS its own overflow (1 + 2), folded on top of the direct 1.
        Assert.assertEquals(folded.count("👍"), 4);
        Assert.assertEquals(folded.lastReactedAtMillis("👍"), 900L, "recency merges by max");
    }

    @Test
    public void foldingNothingReturnsTheSameInstance() {
        final ChatReactionSummary direct = summary(Map.of("👍", List.of(ANN)));
        Assert.assertSame(ChatReactionSummary.foldCounts(direct, List.of()), direct);
        Assert.assertSame(ChatReactionSummary.foldCounts(direct,
                List.of(ChatReactionSummary.empty(PhotoChatMeta.PHOTO_ROOT))), direct);
        final List<ChatReactionSummary> withNull = new java.util.ArrayList<>();
        withNull.add(null);
        Assert.assertSame(ChatReactionSummary.foldCounts(direct, withNull), direct);
        Assert.assertNull(ChatReactionSummary.foldCounts(null, List.of(direct)));
    }

    // --- helpers ---

    private static ChatReactionSummary summary(final Map<String, List<Person.Id>> byEmoji) {
        return new ChatReactionSummary(ChatMessage.Id.of(5L), byEmoji, Map.of(), Map.of());
    }

    @SuppressWarnings("unchecked")
    private static <T> T javaRoundTrip(final T value) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }
}
