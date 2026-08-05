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

        final Response response = resource.feed(CHANNEL, null, null, null, 0, 200);

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
        final Response response = resource.feed(CHANNEL, null, null, null, 10, 200);

        nudger.get(PATIENCE.toMillis(), TimeUnit.MILLISECONDS);
        Assert.assertFalse(((ChatPage) response.getEntity()).isEmpty(), "A nudge must complete the poll");
        Assert.assertEquals(ChatNudgeRegistry.getInstance().parkedCount(registryChannel()), 0,
                "The waiter must have unparked");
    }

    private static void nudgeNow() {
        ChatNudgeRegistry.getInstance().nudge(registryChannel(), 0L);
    }

    /** The timeout is the empty-channel fallback: an empty page carrying the caller's own cursor. */
    @Test
    public void theTimeoutAnswersAnEmptyPageAtTheCallersCursor() {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt())).thenReturn(empty());

        // wait=1 rides the real clock: the cap floor is one second, and stubbing time inside await() would
        // test the stub. One second of one test is the honest price for exercising the actual timeout path.
        final ChatPage page = (ChatPage) resource.feed(CHANNEL, "m42", null, null, 1, 200).getEntity();

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

        final Response response = resource.feed(CHANNEL, null, null, null, 1, 200);

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

        final Response response = resource.feed(CHANNEL, null, null, null, 10, 200);

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
            page = (ChatPage) resource.feed(CHANNEL, "m7", null, null, 25, 200).getEntity();
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
