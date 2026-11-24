package org.paulsens.trip.action;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.cache.InMemoryCacheClient;
import org.paulsens.trip.chat.ChatRateLimiter;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMembership;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.util.TestPeople;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The badge number: other people's messages after the reader's cursor (a chat with four new messages showed "1"
 * when only the flag existed, 2026-11-20).
 */
public class ChatUnreadCountTest {

    private String tripId;
    private ChatCommands chat;
    private Person.Id reader;
    private Person.Id writer;

    @BeforeMethod
    public void setUp() throws IOException {
        DAO.getInstance();
        tripId = UUID.randomUUID().toString();
        reader = person("Reader");
        writer = person("Writer");
        final Trip trip = Trip.builder()
                .id(tripId)
                .title("Unread trip")
                .openToPublic(false)
                .startDate(LocalDateTime.now())
                .endDate(LocalDateTime.now().plusDays(14))
                .people(new ArrayList<>(List.of(reader, writer)))
                .build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip), "test setup: save dedicated trip");
        chat = new ChatCommands(new ChatRateLimiter(new InMemoryCacheClient()));
    }

    private static Person.Id person(final String first) {
        return TestPeople.unreachable(first, "Unread");
    }

    private ChatMessage send(final Person.Id author, final String body) {
        final ChatCommands.SendResult sent =
                chat.send(tripId, author, body, null, null, new AuditActor("unread@test", author.getValue()));
        Assert.assertTrue(sent.isOk(), "send " + body);
        return sent.getMessageObj();
    }

    private int unreadFor(final Person.Id who) {
        return summaryFor(chat.myChatsCounted(who)).unreadCount();
    }

    private ChatCommands.ChatSummary summaryFor(final List<ChatCommands.ChatSummary> summaries) {
        return summaries.stream()
                .filter(summary -> tripId.equals(summary.channel().getTripId()))
                .findFirst()
                .orElseThrow();
    }

    /** Reads up to the writer's first message, so what follows is measured against a real cursor. */
    private void readerCaughtUp() {
        Assert.assertTrue(chat.markRead(tripId, reader, send(writer, "read up to here").getId()));
        Assert.assertEquals(unreadFor(reader), 0, "caught up");
    }

    @Test
    public void countsOtherPeoplesMessagesAfterTheCursor() {
        send(writer, "one");
        send(writer, "two");
        send(reader, "mine does not count");
        final ChatMessage last = send(writer, "three");

        Assert.assertEquals(unreadFor(reader), 1, "no cursor (never opened, or it expired after a week): the old dot");
        Assert.assertTrue(chat.markRead(tripId, reader, last.getId()));
        Assert.assertEquals(unreadFor(reader), 0, "caught up");

        send(writer, "four");
        send(reader, "mine still does not count");
        send(writer, "five");
        Assert.assertEquals(unreadFor(reader), 2, "two new from someone else since the cursor");
    }

    @Test
    public void aCaughtUpWriterHasNoneOfTheirOwn() {
        final ChatMessage mine = send(writer, "hello");
        Assert.assertTrue(chat.markRead(tripId, writer, mine.getId()));

        Assert.assertEquals(unreadFor(writer), 0);
    }

    @Test
    public void theUncountedListShowsTheDotAndReadsNoRows() {
        readerCaughtUp();
        Assert.assertEquals(summaryFor(chat.myChats(reader)).unreadCount(), 0, "caught up: nothing");
        send(writer, "one");
        send(writer, "two");
        final ChatCommands.ChatSummary dot = summaryFor(chat.myChats(reader));
        Assert.assertTrue(dot.unread());
        Assert.assertEquals(dot.unreadCount(), 1, "the web list and account deletion only need the dot");
        Assert.assertEquals(unreadFor(reader), 2);
    }

    @Test
    public void aDeletedMessageIsNotUnread() {
        readerCaughtUp();
        send(writer, "stays");
        final ChatMessage gone = send(writer, "deleted");
        Assert.assertTrue(chat.deleteMessage(tripId, gone.getId(),
                Caller.forActor(new AuditActor("unread@test", writer.getValue()))));
        Assert.assertEquals(unreadFor(reader), 1, "the tombstone is not counted");
    }

    @Test
    public void historyBeforeJoiningIsNotUnread() {
        readerCaughtUp();
        send(writer, "before joining one");
        final ChatMessage before = send(writer, "before joining two");
        final ChatChannel channel = chat.getChannel(tripId);
        final Instant joined = before.getSentAt().plusMillis(1);
        Assert.assertTrue(DAO.getInstance().saveChatMembership(
                ChatMembership.joining(channel.getId(), reader, joined)));
        Assert.assertTrue(DAO.getInstance().saveChatChannel(channel.withSettings(
                channel.getSettings().toBuilder().fullHistoryForNewMembers(false).build())));
        Assert.assertEquals(unreadFor(reader), 0, "only what the feed would show them counts");
        waitPast(joined);
        send(writer, "after joining");
        Assert.assertEquals(unreadFor(reader), 1);
    }

    private static void waitPast(final Instant instant) {
        while (!Instant.now().isAfter(instant)) {
            Thread.onSpinWait();
        }
    }

    private ChatMessage saveDirect(final Person.Id author, final String body) {
        final ChatChannel channel = chat.getChannel(tripId);
        final ChatMessage draft = new ChatMessage(null, channel.getId(), author, null, ChatMessage.MessageKind.TEXT,
                body, null, List.of(), null, null, null, null, null, null, null);
        return DAO.getInstance().saveChatMessage(draft, channel, DAO.getInstance().getTrip(tripId, Cached.NO)
                .orElseThrow()).orElseThrow();
    }

    @Test
    public void aHundredOrMoreSinceTheCursorIsTheCap() {
        readerCaughtUp();
        for (int i = 0; i < ChatUnreadCounter.CAP + 20; i++) {
            saveDirect(writer, "flood " + i);
        }
        Assert.assertEquals(unreadFor(reader), ChatUnreadCounter.CAP, "the apps draw this as 99+");
    }

    @Test
    public void aFullPageReportsOnlyTheRowsThatPass() {
        readerCaughtUp();
        send(writer, "the only one from someone else");
        for (int i = 0; i < ChatUnreadCounter.CAP + 10; i++) {
            saveDirect(reader, "my own " + i);
        }
        Assert.assertEquals(unreadFor(reader), 1, "a full page is filtered before anything is reported");
    }

    @Test
    public void countingNeverRefillsTheHotBuffer() {
        final ChatMessage read = send(writer, "old");
        Assert.assertTrue(chat.markRead(tripId, reader, read.getId()));
        send(writer, "new");
        final String logKey = CacheKeys.chatLogKey(chat.getChannel(tripId).getId().getValue());
        final CacheClient cache = DAO.getInstance().getCacheClient();
        cache.removeKey(logKey);

        Assert.assertEquals(unreadFor(reader), 1);
        Assert.assertTrue(cache.getRangeByScore(logKey, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, 10)
                .isEmpty(), "a count is not a poll: it must not repopulate the buffer");
    }
}
