package org.paulsens.trip.api;

import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.TimeoutHandler;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.action.ChatCommands;
import org.paulsens.trip.chat.ChatNudgeRegistry;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;
import org.paulsens.trip.model.chat.ChatPage;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The long-poll half of {@link ChatResource}'s feed, driven through the REAL {@link ChatNudgeRegistry}.
 *
 * <p>Three exits from a suspended request -- a nudge, the timeout, and the post-park re-read -- and the OneShot
 * rule that only one of them may resume. The invariant a lost race must never break: the client gets exactly one
 * response, and a timeout hands back its own cursor so an empty answer cannot lose the client's place.
 */
public class ChatFeedLongPollTest extends ResourceTestSupport {

    private static final String TRIP_ID = "trip-poll";
    private static final String CHANNEL = "trip:" + TRIP_ID;
    private static final Person.Id ME = Person.Id.from("poll-me");

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

    /** A suspended AsyncResponse whose timeout handler and resume are observable. */
    private static final class Suspended {
        private final AsyncResponse async = Mockito.mock(AsyncResponse.class);
        private final AtomicReference<Response> resumed = new AtomicReference<>();
        private final AtomicReference<TimeoutHandler> timeoutHandler = new AtomicReference<>();
        private int resumeCalls;

        private Suspended() {
            Mockito.when(async.isSuspended()).thenReturn(true);
            Mockito.when(async.resume(ArgumentMatchers.any(Response.class))).thenAnswer(call -> {
                resumeCalls++;
                resumed.set(call.getArgument(0));
                return true;
            });
            Mockito.doAnswer(call -> {
                timeoutHandler.set(call.getArgument(0));
                return null;
            }).when(async).setTimeoutHandler(ArgumentMatchers.any());
        }
    }

    @Test
    public void anEmptyFeedWithNoWaitReturnsImmediately() {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt())).thenReturn(empty());
        final Suspended suspended = new Suspended();

        resource.feed(suspended.async, CHANNEL, null, null, null, 0, 200);

        Assert.assertNotNull(suspended.resumed.get());
        Assert.assertEquals(suspended.resumed.get().getStatus(), 200);
        Mockito.verify(suspended.async, Mockito.never()).setTimeout(ArgumentMatchers.anyLong(),
                ArgumentMatchers.any());
    }

    @Test
    public void anEmptyFeedWithWaitParksAndANudgeCompletesIt() {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt()))
                .thenReturn(empty())   // initial read: nothing yet
                .thenReturn(empty())   // post-park re-read: still nothing
                .thenReturn(oneMessage()); // after the nudge
        final Suspended suspended = new Suspended();

        resource.feed(suspended.async, CHANNEL, null, null, null, 10, 200);
        Assert.assertNull(suspended.resumed.get(), "Must stay suspended until a nudge or the timeout");

        ChatNudgeRegistry.getInstance().nudge(ChatChannel.Id.forTrip(TRIP_ID).getValue(), 0L);

        Assert.assertNotNull(suspended.resumed.get(), "A nudge must complete the poll");
        Assert.assertFalse(((ChatPage) suspended.resumed.get().getEntity()).isEmpty());
        Assert.assertEquals(ChatNudgeRegistry.getInstance()
                .parkedCount(ChatChannel.Id.forTrip(TRIP_ID).getValue()), 0, "The waiter must have unparked");
    }

    /** The timeout is the lost-nudge fallback: an empty page carrying the caller's own cursor. */
    @Test
    public void theTimeoutAnswersAnEmptyPageAtTheCallersCursor() {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt())).thenReturn(empty());
        final Suspended suspended = new Suspended();

        resource.feed(suspended.async, CHANNEL, "m42", null, null, 10, 200);
        Assert.assertNull(suspended.resumed.get());

        suspended.timeoutHandler.get().handleTimeout(suspended.async);

        final ChatPage page = (ChatPage) suspended.resumed.get().getEntity();
        Assert.assertTrue(page.isEmpty());
        Assert.assertEquals(page.getCursor(), ChatMessage.Id.from("m42"),
                "An empty timeout answer must not reset the client's position");
    }

    /** A message that arrived between the first read and the park must not wait out the timeout. */
    @Test
    public void thePostParkReReadCatchesAMessageThatBeatThePark() {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt()))
                .thenReturn(empty())        // initial read
                .thenReturn(oneMessage());  // post-park re-read finds the racer
        final Suspended suspended = new Suspended();

        resource.feed(suspended.async, CHANNEL, null, null, null, 10, 200);

        Assert.assertNotNull(suspended.resumed.get(), "The re-read must complete the request");
        Assert.assertFalse(((ChatPage) suspended.resumed.get().getEntity()).isEmpty());
    }

    /** The OneShot rule: a timeout firing after the nudge already resumed must be swallowed. */
    @Test
    public void aLateTimeoutAfterANudgeDoesNotResumeTwice() {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt()))
                .thenReturn(empty())
                .thenReturn(empty())
                .thenReturn(oneMessage());
        final Suspended suspended = new Suspended();

        resource.feed(suspended.async, CHANNEL, null, null, null, 10, 200);
        ChatNudgeRegistry.getInstance().nudge(ChatChannel.Id.forTrip(TRIP_ID).getValue(), 0L);
        Assert.assertEquals(suspended.resumeCalls, 1);

        suspended.timeoutHandler.get().handleTimeout(suspended.async);

        Assert.assertEquals(suspended.resumeCalls, 1, "The loser of the race must not resume again");
    }

    @Test
    public void theWaitIsCappedInsideTheAlbIdleTimeout() {
        Mockito.when(chat.feed(ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(ME), ArgumentMatchers.any(),
                ArgumentMatchers.anyInt())).thenReturn(empty());
        final Suspended suspended = new Suspended();

        resource.feed(suspended.async, CHANNEL, null, null, null, 3600, 200);

        final ArgumentCaptor<Long> seconds = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(suspended.async).setTimeout(seconds.capture(), ArgumentMatchers.eq(TimeUnit.SECONDS));
        Assert.assertTrue(seconds.getValue() <= 25, "Held requests must stay inside the ALB's 60s idle timeout");
    }
}
