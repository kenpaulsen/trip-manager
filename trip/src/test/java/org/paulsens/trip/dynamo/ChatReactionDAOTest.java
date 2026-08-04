package org.paulsens.trip.dynamo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.cache.NoopCacheClient;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.model.chat.ChatReaction;
import org.paulsens.trip.model.chat.ChatReactionSummary;
import org.paulsens.trip.model.chat.ChatSettings;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class ChatReactionDAOTest {

    private ChatDAO dao;
    private ChatChannel channel;
    private Persistence persistence;
    private InMemoryCacheClient cache;

    /**
     * A fresh CHANNEL per test, not a fresh store.
     *
     * <p>The engine is shared for the whole JVM and deliberately not reset between tests -- resetting a shared
     * store is how one test's teardown starts deleting another's rows. So isolation is by key: each method gets
     * its own trip id, and therefore its own channel partition. With a fixed "t1" these tests saw each other's
     * messages the moment they stopped getting a private in-memory store.
     */
    @BeforeMethod
    public void setUp() {
        final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        persistence = DynamoLocal.persistence();
        cache = new InMemoryCacheClient();
        dao = new ChatDAO(mapper, persistence, cache);
        final String tripId = DynamoLocal.uniqueId("reaction-trip");
        channel = new ChatChannel(
                ChatChannel.Id.forTrip(tripId), tripId, ChatChannel.Kind.TRIP, "Test",
                null, null, ChatSettings.defaults(), Instant.parse("2026-01-01T00:00:00Z"),
                "admin", null, null);
        dao.saveChannel(channel).join();
    }

    @Test
    public void reactTwiceIsIdempotent() {
        final ChatMessage msg = save("hi");
        final ChatReaction r = reaction(msg, "p1", "👍");
        Assert.assertTrue(dao.putReaction(r).join());
        Assert.assertTrue(dao.putReaction(r).join());
        final List<ChatReaction> all = dao.getReactionsForRange(channel.getId(), msg.getId(), msg.getId()).join();
        Assert.assertEquals(all.size(), 1);
    }

    @Test
    public void unReactDeletes() {
        final ChatMessage msg = save("hi");
        dao.putReaction(reaction(msg, "p1", "👍")).join();
        Assert.assertTrue(dao.deleteReaction(
                channel.getId(), msg.getId(), Person.Id.from("p1"), "👍").join());
        final List<ChatReaction> all = dao.getReactionsForRange(channel.getId(), msg.getId(), msg.getId()).join();
        Assert.assertTrue(all.isEmpty());
    }

    @Test
    public void rangeQueryReturnsWholePageAndNoMessageRows() {
        final ChatMessage m1 = save("one");
        final ChatMessage m2 = save("two");
        dao.putReaction(reaction(m1, "p1", "👍")).join();
        dao.putReaction(reaction(m1, "p2", "❤️")).join();
        dao.putReaction(reaction(m2, "p1", "👍")).join();

        final List<ChatReaction> page = dao.getReactionsForRange(channel.getId(), m1.getId(), m2.getId()).join();
        Assert.assertEquals(page.size(), 3);

        final Map<ChatMessage.Id, ChatReactionSummary> summaries =
                dao.summariesForMessages(channel.getId(), List.of(m1, m2)).join();
        Assert.assertEquals(summaries.get(m1.getId()).count("👍"), 1);
        Assert.assertEquals(summaries.get(m1.getId()).count("❤️"), 1);
        Assert.assertEquals(summaries.get(m2.getId()).totalCount(), 1);
    }

    @Test
    public void newestFirstMessagePageReturnsOnlyMessagesEvenWithManyReactions() {
        // Regression for the co-mingled SK bug: more reactions than messages must not pollute message pages.
        final ChatMessage msg = save("only message");
        for (int i = 0; i < 50; i++) {
            dao.putReaction(reaction(msg, "p" + i, "👍")).join();
        }
        final ChatPage page = dao.getMessagesBefore(
                channel.getId(), null, 50, null, channel, null, Instant.now()).join();
        Assert.assertEquals(page.getMessages().size(), 1);
        Assert.assertEquals(page.getMessages().get(0).getBody(), "only message");
        Assert.assertEquals(page.getMessages().get(0).getId(), msg.getId());
    }

    // --- P2d: summaries and the version reach the page, and the cache stays a cache ---

    @Test
    public void thePageCarriesSummariesAndAVersion() {
        final ChatMessage msg = save("hi");
        dao.putReaction(reaction(msg, "p1", "👍")).join();

        final ChatPage page = dao.getMessagesSince(
                channel.getId(), null, 50, null, channel, null, Instant.now()).join();
        Assert.assertEquals(page.getReactions().get(msg.getId()).count("👍"), 1,
                "summaries must reach the page, or the client never renders a chip");
        Assert.assertTrue(page.getReactionsVersion() > 0, "the version is what tells a client to refetch");
    }

    @Test
    public void everyReactionWriteAdvancesTheVersion() {
        final ChatMessage msg = save("hi");
        final long start = dao.currentReactionsVersion(channel.getId()).join();
        dao.putReaction(reaction(msg, "p1", "👍")).join();
        final long afterAdd = dao.currentReactionsVersion(channel.getId()).join();
        dao.deleteReaction(channel.getId(), msg.getId(), Person.Id.from("p1"), "👍").join();
        final long afterRemove = dao.currentReactionsVersion(channel.getId()).join();

        Assert.assertTrue(afterAdd > start, "adding must advance it");
        Assert.assertTrue(afterRemove > afterAdd, "removing must too -- a client has to unrender the chip");
    }

    /**
     * The property real-time reactions actually rest on. A reaction creates no message, so a woken long-poll finds
     * nothing on the message cursor and returns an empty page. If that page carried no version, a reaction on an
     * already-delivered message could never reach the client — every individual piece would look correct.
     */
    @Test
    public void anEmptyPageStillCarriesTheVersion() {
        final ChatMessage msg = save("hi");
        dao.putReaction(reaction(msg, "p1", "👍")).join();
        final ChatPage first = dao.getMessagesSince(
                channel.getId(), null, 50, null, channel, null, Instant.now()).join();

        dao.putReaction(reaction(msg, "p2", "❤️")).join();
        final ChatPage empty = dao.getMessagesSince(
                channel.getId(), first.getCursor(), 50, null, channel, null, Instant.now()).join();

        Assert.assertTrue(empty.getMessages().isEmpty());
        Assert.assertTrue(empty.getReactionsVersion() > first.getReactionsVersion(),
                "an empty page must still report the version");
    }

    @Test
    public void aWriteInvalidatesTheCachedSummary() {
        final ChatMessage msg = save("hi");
        dao.putReaction(reaction(msg, "p1", "👍")).join();
        Assert.assertEquals(pageSummary(msg).count("👍"), 1);

        dao.putReaction(reaction(msg, "p2", "👍")).join();
        // A stale cached summary would show 1 forever: the row is in Dynamo but the chip never updates.
        Assert.assertEquals(pageSummary(msg).count("👍"), 2);

        dao.deleteReaction(channel.getId(), msg.getId(), Person.Id.from("p1"), "👍").join();
        Assert.assertEquals(pageSummary(msg).count("👍"), 1);
    }

    @Test
    public void theSummaryCacheRebuildsExactlyAfterAFlush() {
        final ChatMessage msg = save("hi");
        dao.putReaction(reaction(msg, "p1", "👍")).join();
        dao.putReaction(reaction(msg, "p2", "👍")).join();
        dao.putReaction(reaction(msg, "p3", "❤️")).join();
        final ChatReactionSummary before = pageSummary(msg);

        // A derived view of an authoritative store: a flush must cost a rebuild, never correctness.
        cache.removeKey(CacheKeys.chatReactionSummaryKey(channel.getId().getValue())).join();
        final ChatReactionSummary after = pageSummary(msg);

        Assert.assertEquals(after.getByEmoji(), before.getByEmoji());
        Assert.assertEquals(after.count("👍"), 2);
        Assert.assertEquals(after.count("❤️"), 1);
    }

    @Test
    public void messagesWithNoReactionsAreCachedToo() {
        final ChatMessage withOne = save("a");
        final ChatMessage without = save("b");
        dao.putReaction(reaction(withOne, "p1", "👍")).join();
        pageSummary(withOne);

        // Caching only messages that HAVE reactions means any page containing one reaction-free message misses on
        // every read -- which is most pages, so the cache would never serve anything.
        final Map<String, String> cached = cache.getHash(
                CacheKeys.chatReactionSummaryKey(channel.getId().getValue())).join();
        Assert.assertTrue(cached.containsKey(without.getId().getValue()),
                "an empty summary must be cached, or the cache is useless in the common case");
    }

    @Test
    public void withTheCacheDownReactionsStillReadCorrectlyFromDynamo() {
        final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        final ChatDAO noCache = new ChatDAO(mapper, persistence, new NoopCacheClient());
        final ChatMessage msg = save("hi");
        Assert.assertTrue(noCache.putReaction(reaction(msg, "p1", "👍")).join());

        final ChatPage page = noCache.getMessagesSince(
                channel.getId(), null, 50, null, channel, null, Instant.now()).join();
        Assert.assertEquals(page.getReactions().get(msg.getId()).count("👍"), 1,
                "DynamoDB is the authority; the cache only makes it faster");
        Assert.assertEquals(page.getReactionsVersion(), 0L,
                "no counter means no version, and 0 must read as 'not reported'");
    }

    @Test
    public void aReactionNudgesItsChannelSoParkedReadersWake() {
        final ChatMessage msg = save("hi");
        final List<String> got = new CopyOnWriteArrayList<>();
        try (AutoCloseable sub = cache.subscribe(
                List.of(CacheKeys.chatPubSubChannelFor(channel.getId().getValue())),
                (name, payload) -> got.add(payload))) {
            dao.putReaction(reaction(msg, "p1", "👍")).join();
        } catch (final Exception ex) {
            Assert.fail("close must not throw: " + ex);
        }
        Assert.assertEquals(got.size(), 1, "a reaction must wake parked readers, or it is not real-time");
    }

    @Test
    public void aRejectedReactionWriteNudgesNobody() {
        final ChatMessage msg = save("hi");
        final List<String> got = new CopyOnWriteArrayList<>();
        try (AutoCloseable sub = cache.subscribe(
                List.of(CacheKeys.chatPubSubChannelFor(channel.getId().getValue())),
                (name, payload) -> got.add(payload))) {
            // Rejected before the write: nothing changed, so nothing may be announced.
            Assert.assertFalse(dao.putReaction(new ChatReaction(
                    channel.getId(), msg.getId(), null, "👍", Instant.now(), null)).join());
        } catch (final Exception ex) {
            Assert.fail("close must not throw: " + ex);
        }
        Assert.assertTrue(got.isEmpty());
    }

    @Test
    public void aReactionInheritsItsTargetsExpiry() {
        final ChatChannel expiring = channel.withSettings(
                ChatSettings.defaults().toBuilder().retentionSeconds(3600L).build());
        Assert.assertTrue(dao.saveChannel(expiring).join());
        final ChatMessage draft = new ChatMessage(
                null, expiring.getId(), Person.Id.from("author"), null,
                ChatMessage.MessageKind.TEXT, "hi", null, null, null,
                null, null, null, null, null, null);
        final ChatMessage msg = dao.saveMessage(draft, expiring, null).join().orElseThrow();
        Assert.assertNotNull(msg.getExpiresAt(), "a TTL channel must stamp expiresAt on the message");

        Assert.assertTrue(dao.putReaction(reaction(msg, "p1", "👍")).join());
        // Reactions must never outlive their subject, or a reaped message leaves orphan chips behind.
        final ChatReaction stored = dao.getReactionsForRange(
                expiring.getId(), msg.getId(), msg.getId()).join().get(0);
        Assert.assertEquals(stored.getExpiresAt(), msg.getExpiresAt());
    }

    // --- mutations (edits and tombstones), which the message cursor also cannot carry ---

    @Test
    public void anEditAdvancesTheMutationsVersionAndKeepsThePosition() {
        final ChatMessage a = save("first");
        final ChatMessage b = save("teh second");
        final long before = versionOf(dao.getMessagesBefore(
                channel.getId(), null, 50, null, channel, null, Instant.now()).join());

        final ChatMessage edited = dao.editMessage(channel.getId(), b.getId(), "the second").join().orElseThrow();
        Assert.assertEquals(edited.getBody(), "the second");
        Assert.assertNotNull(edited.getEditedAt());
        Assert.assertEquals(edited.getId(), b.getId(), "an edit must not change the id");
        Assert.assertEquals(edited.getSentAt(), b.getSentAt(), "nor move the message in the conversation");

        final ChatPage page = dao.getMessagesBefore(
                channel.getId(), null, 50, null, channel, null, Instant.now()).join();
        Assert.assertTrue(page.getMutationsVersion() > before,
                "without this an edit is invisible to anyone already holding the message");
        Assert.assertEquals(page.getMessages().get(0).getBody(), "the second");
        Assert.assertEquals(page.getMessages().get(1).getBody(), "first", "order must be untouched");
        Assert.assertEquals(a.getId().compareTo(b.getId()) < 0, true);
    }

    @Test
    public void aTombstoneAdvancesTheMutationsVersionToo() {
        final ChatMessage msg = save("delete me");
        final long before = versionOf(dao.getMessagesBefore(
                channel.getId(), null, 50, null, channel, null, Instant.now()).join());

        Assert.assertTrue(dao.tombstoneMessage(channel.getId(), msg.getId(), "admin").join().isPresent());

        // This is what was missing before: a delete changes a message the client already has, so nothing advanced
        // its cursor and the correction only ever appeared on a full page reload.
        final ChatPage page = dao.getMessagesBefore(
                channel.getId(), null, 50, null, channel, null, Instant.now()).join();
        Assert.assertTrue(page.getMutationsVersion() > before);
        Assert.assertTrue(page.getMessages().get(0).isDeleted());
    }

    @Test
    public void anEditNudgesTheChannel() {
        final ChatMessage msg = save("nudge on edit");
        final List<String> got = new CopyOnWriteArrayList<>();
        try (AutoCloseable sub = cache.subscribe(
                List.of(CacheKeys.chatPubSubChannelFor(channel.getId().getValue())),
                (name, payload) -> got.add(payload))) {
            dao.editMessage(channel.getId(), msg.getId(), "edited").join();
        } catch (final Exception ex) {
            Assert.fail("close must not throw: " + ex);
        }
        Assert.assertEquals(got.size(), 1, "a parked reader must be woken for an edit as well as a message");
    }

    @Test
    public void aDeletedMessageCannotBeEditedAtTheStoreLevel() {
        final ChatMessage msg = save("will be removed");
        dao.tombstoneMessage(channel.getId(), msg.getId(), "admin").join();

        // Belt and braces with the action-layer check: editing a tombstone would resurrect a removed body.
        Assert.assertTrue(dao.editMessage(channel.getId(), msg.getId(), "back again").join().isEmpty());
    }

    @Test
    public void theReadCursorRoundTrips() {
        final ChatMessage msg = save("read me");
        final Person.Id me = Person.Id.from("p1");
        Assert.assertTrue(dao.getCursor(channel.getId(), me).join().isEmpty(),
                "no cursor means never opened -- which must NOT read as caught up");

        Assert.assertTrue(dao.saveCursor(channel.getId(), me, msg.getId()).join());
        Assert.assertEquals(dao.getCursor(channel.getId(), me).join().orElseThrow(), msg.getId());
    }

    private static long versionOf(final ChatPage page) {
        return page.getMutationsVersion();
    }

    /** The summary as a page read produces it — i.e. through the rsum cache rather than straight from the rows. */
    private ChatReactionSummary pageSummary(final ChatMessage msg) {
        final ChatPage page = dao.getMessagesBefore(
                channel.getId(), null, 50, null, channel, null, Instant.now()).join();
        return page.getReactions().getOrDefault(msg.getId(), ChatReactionSummary.empty(msg.getId()));
    }

    private ChatMessage save(final String body) {
        final ChatMessage draft = new ChatMessage(
                null, channel.getId(), Person.Id.from("author"), null,
                ChatMessage.MessageKind.TEXT, body, null, null, null,
                null, null, null, null, null, null);
        return dao.saveMessage(draft, channel, null).join().orElseThrow();
    }

    private ChatReaction reaction(final ChatMessage msg, final String person, final String emoji) {
        return new ChatReaction(
                channel.getId(), msg.getId(), Person.Id.from(person), emoji,
                Instant.now(), msg.getExpiresAt());
    }
}
