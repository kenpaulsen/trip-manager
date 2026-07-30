package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatNudge;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatSettings;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ChatDAOTest {

    private InMemoryPersistence persistence;
    private InMemoryCacheClient cache;
    private ChatDAO dao;
    private ChatChannel channel;

    @BeforeMethod
    public void setUp() {
        persistence = new InMemoryPersistence();
        cache = new InMemoryCacheClient();
        final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        dao = new ChatDAO(mapper, persistence, cache);
        channel = new ChatChannel(
                ChatChannel.Id.forTrip("t1"), "t1", ChatChannel.Kind.TRIP, "Test",
                null, null, ChatSettings.defaults(), Instant.parse("2026-01-01T00:00:00Z"),
                "admin", null, null);
        Assert.assertTrue(dao.saveChannel(channel).join());
    }

    @Test
    public void saveAndPollRoundTrip() {
        final ChatMessage draft = draft("hello");
        final Optional<ChatMessage> saved = dao.saveMessage(draft, channel, null).join();
        Assert.assertTrue(saved.isPresent());
        Assert.assertEquals(saved.get().getBody(), "hello");
        Assert.assertEquals(saved.get().getId().getValue().length(), 13);

        final ChatPage page = dao.getMessagesSince(
                channel.getId(), null, 50, null, channel, null, Instant.now()).join();
        Assert.assertEquals(page.getMessages().size(), 1);
        Assert.assertEquals(page.getMessages().get(0).getBody(), "hello");
        Assert.assertEquals(page.getCursor(), saved.get().getId());

        // Second poll with cursor returns zero
        final ChatPage empty = dao.getMessagesSince(
                channel.getId(), page.getCursor(), 50, null, channel, null, Instant.now()).join();
        Assert.assertTrue(empty.getMessages().isEmpty());
    }

    @Test
    public void newestFirstPaging() {
        dao.saveMessage(draft("a"), channel, null).join();
        dao.saveMessage(draft("b"), channel, null).join();
        dao.saveMessage(draft("c"), channel, null).join();

        final ChatPage page = dao.getMessagesBefore(
                channel.getId(), null, 2, null, channel, null, Instant.now()).join();
        Assert.assertEquals(page.getMessages().size(), 2);
        // Newest first
        Assert.assertEquals(page.getMessages().get(0).getBody(), "c");
        Assert.assertEquals(page.getMessages().get(1).getBody(), "b");
        Assert.assertTrue(page.isHasMore());
    }

    @Test
    public void tombstoneClearsBody() {
        final ChatMessage saved = dao.saveMessage(draft("secret"), channel, null).join().orElseThrow();
        final ChatMessage tomb = dao.tombstoneMessage(channel.getId(), saved.getId(), "admin").join()
                .orElseThrow();
        Assert.assertTrue(tomb.isDeleted());
        Assert.assertEquals(tomb.getBody(), "");
        Assert.assertEquals(tomb.getDeletedBy(), "admin");

        final ChatPage page = dao.getMessagesSince(
                channel.getId(), null, 50, null, channel, null, Instant.now()).join();
        Assert.assertEquals(page.getMessages().size(), 1);
        Assert.assertTrue(page.getMessages().get(0).isDeleted());
        Assert.assertEquals(page.getMessages().get(0).getBody(), "");
    }

    @Test
    public void expiredItemInvisibleEvenIfStillInTable() {
        final ChatSettings shortRetention = ChatSettings.defaults().toBuilder()
                .retentionSeconds(1L)
                .build();
        final ChatChannel shortChannel = channel.withSettings(shortRetention);
        dao.saveChannel(shortChannel).join();
        final ChatMessage saved = dao.saveMessage(draft("gone soon"), shortChannel, null).join().orElseThrow();

        // Force "now" past expiry
        final Instant later = saved.getSentAt().plusSeconds(10);
        final ChatPage page = dao.getMessagesSince(
                shortChannel.getId(), null, 50, null, shortChannel, null, later).join();
        Assert.assertTrue(page.getMessages().isEmpty(), "expired message must be filtered on read");

        // Still in the table (TTL reaper has not run)
        final Optional<ChatMessage> raw = dao.getMessage(shortChannel.getId(), saved.getId()).join();
        Assert.assertTrue(raw.isPresent());
    }

    /**
     * The cross-task safety net: the per-JVM allocator makes in-partition collisions impossible within one task,
     * so the {@code attribute_not_exists} + {@code +1 ms} retry only fires when two tasks overlap (a blue/green
     * deploy). Simulated here by occupying the id the allocator is about to mint.
     */
    @Test
    public void collisionRetryAdvancesToTheNextFreeMillis() {
        // The allocator returns max(wallClock, prev + 1), so a *past* id can never be re-minted -- rewinding it
        // proves nothing. Occupy a band slightly in the future (inside the 5s drift bound) instead: consecutive
        // saves there advance by prev+1 because the allocator is ahead of the clock, giving contiguous ids.
        final int band = 8;
        final long base = System.currentTimeMillis() + 250;
        dao.forceAllocator(base - 1);
        final List<ChatMessage> occupied = new ArrayList<>();
        for (int i = 0; i < band; i++) {
            occupied.add(dao.saveMessage(draft("occupier" + i), channel, null).join().orElseThrow());
        }
        Assert.assertEquals(occupied.get(0).getId().getEpochMilli(), base);
        Assert.assertEquals(occupied.get(band - 1).getId().getEpochMilli(), base + band - 1);

        final int rejectionsBefore = persistence.getRejectionCount();

        // Now replay the same starting point, as a second task minting from its own clock would.
        dao.forceAllocator(base - 1);
        final ChatMessage collided = dao.saveMessage(draft("collided"), channel, null).join().orElseThrow();

        Assert.assertEquals(collided.getId().getEpochMilli(), base + band,
                "the write must walk forward past every occupied millisecond, one at a time");
        Assert.assertEquals(persistence.getRejectionCount() - rejectionsBefore, band,
                "one conditional-put rejection per occupied id -- if this is 0 the retry path never ran");

        // Nothing was overwritten: every occupier plus the collided message is still readable.
        final ChatPage page = dao.getMessagesSince(
                channel.getId(), null, 100, null, channel, null, Instant.now()).join();
        Assert.assertEquals(page.getMessages().size(), band + 1);
        Assert.assertEquals(page.getMessages().get(band).getBody(), "collided");
    }

    /** Exhausting the retry cap must surface as a failure to the sender, never a silently dropped message. */
    @Test
    public void exhaustingTheRetryCapReportsFailureRatherThanDropping() {
        final long base = System.currentTimeMillis() + 250;
        dao.forceAllocator(base - 1);
        // Occupy more consecutive ids than MAX_WRITE_RETRIES (25) can walk past.
        for (int i = 0; i < 30; i++) {
            dao.saveMessage(draft("occupier" + i), channel, null).join().orElseThrow();
        }
        dao.forceAllocator(base - 1);
        Assert.assertTrue(dao.saveMessage(draft("hopeless"), channel, null).join().isEmpty(),
                "an empty result is how the caller learns to show an error instead of a sent message");
    }

    /**
     * The offline-catch-up guarantee. The hot buffer holds only a recent window, so it may answer a poll only
     * when it provably contains everything after the caller's cursor. Serving the buffered tail to a stale cursor
     * would advance that cursor past the gap and lose the messages in between for good.
     */
    @Test
    public void staleCursorReadsThroughToDynamoRatherThanSkippingTheGap() {
        final ChatMessage oldest = dao.saveMessage(draft("day one"), channel, null).join().orElseThrow();
        dao.saveMessage(draft("day two"), channel, null).join();
        final ChatMessage newest = dao.saveMessage(draft("day three"), channel, null).join().orElseThrow();

        // Evict the middle of the buffer, exactly as bufferMaxMessages trimming or a TTL would.
        cache.removeKey(CacheKeys.chatLogKey(channel.getId().getValue())).join();
        cache.addScoredEntries(CacheKeys.chatLogKey(channel.getId().getValue()),
                Map.of(newest.getId().getValue(), (double) newest.getId().getEpochMilli())).join();

        final ChatPage page = dao.getMessagesSince(
                channel.getId(), oldest.getId(), 50, null, channel, null, Instant.now()).join();

        Assert.assertEquals(page.getMessages().size(), 2,
                "a cursor older than the buffer must fall through to DynamoDB, not be served the tail");
        Assert.assertEquals(page.getMessages().get(0).getBody(), "day two");
        Assert.assertEquals(page.getMessages().get(1).getBody(), "day three");
    }

    /** A log entry whose body is gone must not be silently dropped -- the cursor would advance past it. */
    @Test
    public void missingBodyFallsBackToDynamoInsteadOfDroppingTheMessage() {
        final ChatMessage first = dao.saveMessage(draft("kept"), channel, null).join().orElseThrow();
        final ChatMessage second = dao.saveMessage(draft("body evicted"), channel, null).join().orElseThrow();

        cache.removeHashField(CacheKeys.chatBodyKey(channel.getId().getValue()),
                second.getId().getValue()).join();

        final ChatPage page = dao.getMessagesSince(
                channel.getId(), first.getId(), 50, null, channel, null, Instant.now()).join();
        Assert.assertEquals(page.getMessages().size(), 1);
        Assert.assertEquals(page.getMessages().get(0).getBody(), "body evicted");
    }

    /**
     * The cursor must advance past messages the filter removed. Taking it from the visible list instead leaves it
     * behind an expired tail, so the poll re-reads the same rows forever and never progresses.
     */
    @Test
    public void cursorAdvancesPastFilteredOutMessages() {
        final ChatSettings shortRetention = ChatSettings.defaults().toBuilder()
                .retentionSeconds(1L)
                .build();
        final ChatChannel shortChannel = channel.withSettings(shortRetention);
        dao.saveChannel(shortChannel).join();
        final ChatMessage expiring = dao.saveMessage(draft("expires"), shortChannel, null).join().orElseThrow();

        final Instant later = expiring.getSentAt().plusSeconds(30);
        final ChatPage page = dao.getMessagesSince(
                shortChannel.getId(), null, 50, null, shortChannel, null, later).join();

        Assert.assertTrue(page.getMessages().isEmpty(), "expired message is filtered");
        Assert.assertEquals(page.getCursor(), expiring.getId(),
                "the cursor must still advance, or this page is re-read on every poll forever");
    }

    /** A single-message read for a reader honours the same visibility rule the feed does. */
    @Test
    public void getVisibleMessageHidesWhatTheFeedWouldHide() {
        final ChatSettings shortRetention = ChatSettings.defaults().toBuilder()
                .retentionSeconds(1L)
                .build();
        final ChatChannel shortChannel = channel.withSettings(shortRetention);
        dao.saveChannel(shortChannel).join();
        final ChatMessage saved = dao.saveMessage(draft("gone"), shortChannel, null).join().orElseThrow();
        final Instant later = saved.getSentAt().plusSeconds(30);

        Assert.assertTrue(dao.getMessage(shortChannel.getId(), saved.getId()).join().isPresent(),
                "the unfiltered moderation read still sees it");
        Assert.assertTrue(dao.getVisibleMessage(
                        shortChannel.getId(), saved.getId(), null, shortChannel, null, later).join().isEmpty(),
                "the reader-facing read must not surface an expired body");
    }

    /** A history page's token points backwards; the flag is how a client tells the two apart. */
    @Test
    public void historyPageIsMarkedNewestFirstAndItsCursorPointsBackwards() {
        final ChatMessage oldest = dao.saveMessage(draft("a"), channel, null).join().orElseThrow();
        dao.saveMessage(draft("b"), channel, null).join();
        dao.saveMessage(draft("c"), channel, null).join();

        final ChatPage history = dao.getMessagesBefore(
                channel.getId(), null, 50, null, channel, null, Instant.now()).join();
        Assert.assertTrue(history.isNewestFirst());
        Assert.assertEquals(history.getCursor(), oldest.getId(), "oldest examined, for use as `before`");

        final ChatPage poll = dao.getMessagesSince(
                channel.getId(), null, 50, null, channel, null, Instant.now()).join();
        Assert.assertFalse(poll.isNewestFirst());
    }

    @Test
    public void clientMessageIdIsIdempotent() {
        final ChatMessage draft = new ChatMessage(
                null, channel.getId(), Person.Id.from("p1"), null,
                ChatMessage.MessageKind.TEXT, "once", null, null, null,
                null, null, null, null, "client-abc", null);
        final ChatMessage a = dao.saveMessage(draft, channel, null).join().orElseThrow();
        final ChatMessage b = dao.saveMessage(draft, channel, null).join().orElseThrow();
        Assert.assertEquals(a.getId(), b.getId());
        final ChatPage page = dao.getMessagesSince(
                channel.getId(), null, 50, null, channel, null, Instant.now()).join();
        Assert.assertEquals(page.getMessages().size(), 1);
    }

    /**
     * A successful write nudges the channel. This is the join between the DAO and the real-time path, and it has to
     * fire AFTER the buffer is populated -- a reader woken too early would look, find nothing, and go back to
     * sleep until its next poll, turning sub-second delivery back into P1 latency.
     */
    @Test
    public void aSavedMessageNudgesItsChannel() {
        final List<String> nudges = new ArrayList<>();
        final ChatMessage[] seen = new ChatMessage[1];
        cache.subscribe(List.of(CacheKeys.chatPubSubChannelFor(channel.getId().getValue())),
                (ch, payload) -> {
                    nudges.add(payload);
                    // Read the buffer from inside the callback: whatever a woken reader would see, right now.
                    seen[0] = dao.getMessagesSince(channel.getId(), null, 50, null, channel, null, Instant.now())
                            .join().getMessages().stream().findFirst().orElse(null);
                });

        final ChatMessage saved = dao.saveMessage(draft("ring the bell"), channel, null).join().orElseThrow();

        Assert.assertEquals(nudges.size(), 1, "exactly one nudge per message");
        Assert.assertEquals(ChatNudge.upTo(nudges.get(0)), saved.getId().getEpochMilli(),
                "the watermark is the message's own id");
        Assert.assertNotNull(seen[0], "a reader woken by the nudge must already be able to see the message");
        Assert.assertEquals(seen[0].getBody(), "ring the bell");
    }

    @Test
    public void aFailedWriteNudgesNobody() {
        // The nudge hangs off the success path only: waking readers for a message that was never stored would send
        // every one of them to DynamoDB for nothing.
        final List<String> nudges = new ArrayList<>();
        cache.subscribe(List.of(CacheKeys.chatPubSubChannelFor(channel.getId().getValue())),
                (ch, payload) -> nudges.add(payload));

        final long base = System.currentTimeMillis() + 250;
        dao.forceAllocator(base - 1);
        for (int i = 0; i < 30; i++) {
            dao.saveMessage(draft("occupier" + i), channel, null).join().orElseThrow();
        }
        final int afterOccupiers = nudges.size();
        dao.forceAllocator(base - 1);
        Assert.assertTrue(dao.saveMessage(draft("hopeless"), channel, null).join().isEmpty());
        Assert.assertEquals(nudges.size(), afterOccupiers, "a write that exhausted its retries must not nudge");
    }

    private ChatMessage draft(final String body) {
        return new ChatMessage(
                null, channel.getId(), Person.Id.from("p1"), null,
                ChatMessage.MessageKind.TEXT, body, null, null, null,
                null, null, null, null, null, null);
    }
}
