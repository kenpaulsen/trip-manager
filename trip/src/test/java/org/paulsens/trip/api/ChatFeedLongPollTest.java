package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.chat.ChatNudgeRegistry;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.paulsens.trip.util.TripThreads;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The long-poll half of {@link ChatResource}'s feed, driven through the REAL {@link ChatNudgeRegistry}.
 *
 * <p>The feed now blocks its (virtual) request thread, so the invariants are: a nudge wakes the parked poller,
 * wake and timeout converge on one final cursor read (which is what heals a lost nudge), an empty answer hands
 * back the caller's own cursor, the waiter always unparks, and an interrupt — the container's client-gone
 * signal — answers rather than hangs.
 */
public class ChatFeedLongPollTest extends ResourceTestSupport {

    private static final String TRIP_ID = "trip-poll";
    private static final String CHANNEL = "trip:" + TRIP_ID;
    private static final Person.Id ME = Person.Id.from("poll-me");
    /** How long a test will wait for cross-thread progress. Generous; only a failing test ever waits it out. */
    private static final Duration PATIENCE = Duration.ofSeconds(5);

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

        Mockito.when(chat.chatEnabledForTrip(TRIP_ID)).thenReturn(true);
        Mockito.when(chat.getChannel(TRIP_ID)).thenReturn(Mockito.mock(ChatChannel.class));
        Mockito.when(chat.readDenial(ArgumentMatchers.any(), ArgumentMatchers.eq(ME))).thenReturn(null);
    }

    @AfterMethod(alwaysRun = true)
    public void closeChatStatic() {
        if (chatStatic != null) {
            chatStatic.close();
            chatStatic = null;
        }
    }

    private static ChatPage empty() {
        return new ChatPage(List.of(), Map.of(), null, 0L, 0L, false, false, Map.of(), Instant.now());
    }

    private static ChatPage oneMessage() {
        return new ChatPage(
                List.of(new ChatMessage(ChatMessage.Id.from("m1"), ChatChannel.Id.forTrip(TRIP_ID), ME,
                        Instant.now(), null, "hi", null, null, null, null, null, null, null, null, null)),
                Map.of(), null, 0L, 0L, false, false, Map.of(), Instant.now());
    }

    private static String registryChannel() {
        return ChatChannel.Id.forTrip(TRIP_ID).getValue();
    }

    // The feed always runs on the TEST thread: Mockito's static mock of ChatCommands.getChatCommands is
    // thread-local, so a feed spawned onto another thread would see the real bean. The background side of each
    // race (the nudge, the interrupt) needs no mocks and is what gets its own virtual thread.

    /** Bounded wait for the blocked feed to actually park, so a nudge cannot outrun the park. */
    private static void awaitParked() {
        final Instant deadline = Instant.now().plus(PATIENCE);
        while (ChatNudgeRegistry.getInstance().parkedCount(registryChannel()) == 0) {
            Assert.assertTrue(Instant.now().isBefore(deadline), "The feed never parked a waiter");
            Thread.onSpinWait();
        }
    }

    /** Once the feed parks, run {@code then} on a fresh virtual thread; surface its failure into the test. */
    private static CompletableFuture<Void> onceParked(final Runnable then) {
        return CompletableFuture.runAsync(then, ChatFeedLongPollTest::runWhenParked);
    }

    private static void runWhenParked(final Runnable task) {
        TripThreads.start(() -> runAfterAwait(task));
    }

    private static void runAfterAwait(final Runnable task) {
        awaitParked();
        task.run();
    }

    @Test
    public void anEmptyFeedWithNoWaitReturnsImmediately() {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt())).thenReturn(empty());

        final Response response = resource.feed(CHANNEL, null, null, null, 0, 200, null, null);

        Assert.assertEquals(response.getStatus(), 200);
        Assert.assertEquals(ChatNudgeRegistry.getInstance().parkedCount(registryChannel()), 0,
                "A no-wait poll must never park");
    }

    @Test
    public void anEmptyFeedWithWaitParksAndANudgeCompletesIt() throws Exception {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt()))
                .thenReturn(empty())   // initial read: nothing yet
                .thenReturn(empty())   // post-park re-read: still nothing
                .thenReturn(oneMessage()); // after the nudge
        final CompletableFuture<Void> nudger = onceParked(ChatFeedLongPollTest::nudgeNow);

        // Blocks here (wait=10s) until the background nudge lands; finishing in far less than 10s IS the
        // wake-on-nudge assertion, enforced by the nudger future's own PATIENCE bound below.
        final Response response = resource.feed(CHANNEL, null, null, null, 10, 200, null, null);

        nudger.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
        Assert.assertFalse(((ChatPage) response.getEntity()).isEmpty(), "A nudge must complete the poll");
        Assert.assertEquals(ChatNudgeRegistry.getInstance().parkedCount(registryChannel()), 0,
                "The waiter must have unparked");
    }

    private static void nudgeNow() {
        ChatNudgeRegistry.getInstance().nudge(registryChannel(), 0L);
    }

    /**
     * A reaction — including a photo-reaction roll-up — creates NO message, so the woken read is
     * message-empty, and the version counters on that empty page are the ONLY path by which the change
     * reaches a parked client. Zeroing them made a nudged wake indistinguishable from a timeout, and
     * real-time reactions on a quiet channel silently did not work (the P2d design's exact warning).
     */
    @Test
    public void aWokenEmptyPageStillCarriesTheVersionCounters() throws Exception {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt()))
                .thenReturn(empty())                 // initial read: nothing yet
                .thenReturn(empty())                 // post-park re-read: still nothing
                .thenReturn(emptyWithVersions(7L, 3L)); // woken: no message, but the counters moved
        final CompletableFuture<Void> nudger = onceParked(ChatFeedLongPollTest::nudgeNow);

        final ChatPage page = (ChatPage) resource.feed(CHANNEL, "m42", null, null, 10, 200, null, null).getEntity();

        nudger.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
        Assert.assertTrue(page.isEmpty(), "still an empty page — no message was fabricated");
        Assert.assertEquals(page.getReactionsVersion(), 7L,
                "the reactions version must ride the empty page or the reaction never reaches the client");
        Assert.assertEquals(page.getMutationsVersion(), 3L, "the mutations version rides along too");
        Assert.assertEquals(page.getCursor(), ChatMessage.Id.from("m42"),
                "and the caller's cursor is still preserved");
    }

    /**
     * The counter the client sends is what lets a reaction that landed BETWEEN two polls come back at once.
     * The client polls with a gap; a reaction in that gap publishes its nudge while nobody is parked, and the
     * next read has no message to return — so without comparing counters the client parked for the full wait
     * while the server held the newer number the whole time. That is the 25-second lag a photo reaction showed
     * on the carrying message's chip.
     */
    @Test
    public void aCounterTheClientHasNotSeenAnswersImmediatelyInsteadOfParking() {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt())).thenReturn(emptyWithVersions(9L, 0L));

        // wait=25 would park for 25 seconds if the counters were ignored; this must return without waiting.
        final long started = System.currentTimeMillis();
        final ChatPage page = (ChatPage) resource.feed(CHANNEL, "m42", null, null, 25, 200, 4L, 0L).getEntity();

        Assert.assertTrue(System.currentTimeMillis() - started < 2000, "it must not have parked at all");
        Assert.assertEquals(page.getReactionsVersion(), 9L, "and it carries the counter the client lacked");
        Assert.assertEquals(page.getCursor(), ChatMessage.Id.from("m42"), "cursor preserved");
        Assert.assertEquals(ChatNudgeRegistry.getInstance().parkedCount(registryChannel()), 0);
    }

    /** Matching counters — and the unreported zeros on either side — must still park, or the poll is a hot loop. */
    @Test
    public void matchingOrUnreportedCountersStillPark() {
        Assert.assertFalse(ChatResource.staleVersions(emptyWithVersions(4L, 2L), 4L, 2L), "same counters");
        Assert.assertFalse(ChatResource.staleVersions(emptyWithVersions(0L, 0L), 4L, 2L),
                "a cold cache reports 0 and must never look like a change");
        Assert.assertFalse(ChatResource.staleVersions(emptyWithVersions(4L, 2L), null, null),
                "an older client sends no counters at all and keeps the old behaviour");
        Assert.assertFalse(ChatResource.staleVersions(null, 4L, 2L));
        Assert.assertTrue(ChatResource.staleVersions(emptyWithVersions(1L, 0L), 0L, 0L),
                "a SENT zero means 'I have seen none' — the state a channel is in for its first reaction");
        Assert.assertTrue(ChatResource.staleVersions(emptyWithVersions(4L, 2L), 3L, 2L), "reactions moved");
        Assert.assertTrue(ChatResource.staleVersions(emptyWithVersions(4L, 2L), 4L, 1L), "mutations moved");
        Assert.assertTrue(ChatResource.staleVersions(emptyWithVersions(2L, 2L), 9L, 2L),
                "a rebuilt cache restarts the count, and a client holding a bigger number must still be told");
    }

    private static ChatPage emptyWithVersions(final long reactions, final long mutations) {
        return new ChatPage(List.of(), Map.of(), null, reactions, mutations, false, false, Map.of(),
                Instant.now());
    }

    /** The timeout is the empty-channel fallback: an empty page carrying the caller's own cursor. */
    @Test
    public void theTimeoutAnswersAnEmptyPageAtTheCallersCursor() {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt())).thenReturn(empty());

        // wait=1 rides the real clock: the cap floor is one second, and stubbing time inside await() would
        // test the stub. One second of one test is the honest price for exercising the actual timeout path.
        final ChatPage page = (ChatPage) resource.feed(CHANNEL, "m42", null, null, 1, 200, null, null).getEntity();

        Assert.assertTrue(page.isEmpty());
        Assert.assertEquals(page.getCursor(), ChatMessage.Id.from("m42"),
                "An empty timeout answer must not reset the client's position");
        Assert.assertEquals(ChatNudgeRegistry.getInstance().parkedCount(registryChannel()), 0);
    }

    /** A lost nudge costs latency, never a message: the timeout path re-reads the cursor and finds it. */
    @Test
    public void theTimeoutReReadHealsALostNudge() {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt()))
                .thenReturn(empty())        // initial read
                .thenReturn(empty())        // post-park re-read
                .thenReturn(oneMessage());  // final read at the timeout: the message whose nudge was lost

        final Response response = resource.feed(CHANNEL, null, null, null, 1, 200, null, null);

        Assert.assertFalse(((ChatPage) response.getEntity()).isEmpty(),
                "The wait elapsing must still deliver a message whose nudge was dropped");
    }

    /** A message that arrived between the first read and the park must not wait out the timeout. */
    @Test
    public void thePostParkReReadCatchesAMessageThatBeatThePark() {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt()))
                .thenReturn(empty())        // initial read
                .thenReturn(oneMessage());  // post-park re-read finds the racer

        final Response response = resource.feed(CHANNEL, null, null, null, 10, 200, null, null);

        Assert.assertFalse(((ChatPage) response.getEntity()).isEmpty(), "The re-read must answer the request");
        Assert.assertEquals(ChatNudgeRegistry.getInstance().parkedCount(registryChannel()), 0,
                "The waiter must have unparked");
    }

    /** The container's client-gone signal: an interrupt answers an empty page at the cursor, and unparks. */
    @Test
    public void anInterruptAnswersInsteadOfHanging() throws Exception {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt())).thenReturn(empty());
        final Thread poller = Thread.currentThread();
        final CompletableFuture<Void> interrupter = onceParked(poller::interrupt);

        final ChatPage page;
        try {
            page = (ChatPage) resource.feed(CHANNEL, "m7", null, null, 25, 200, null, null).getEntity();
        } finally {
            // The feed re-asserts the flag by contract; clear it so it cannot poison the next test.
            Thread.interrupted();
        }

        interrupter.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
        Assert.assertTrue(page.isEmpty());
        Assert.assertEquals(page.getCursor(), ChatMessage.Id.from("m7"));
        Assert.assertEquals(ChatNudgeRegistry.getInstance().parkedCount(registryChannel()), 0,
                "An interrupted poller must not leak its waiter");
    }

    @Test
    public void theWaitIsCappedInsideTheAlbIdleTimeout() {
        Assert.assertEquals(ChatResource.cappedWaitSeconds(3600), 25,
                "Held requests must stay inside the ALB's 60s idle timeout");
        Assert.assertEquals(ChatResource.cappedWaitSeconds(0), 1, "The floor keeps await() from busy-answering");
        Assert.assertEquals(ChatResource.cappedWaitSeconds(10), 10);
    }
}
