package org.paulsens.trip.chat;

import java.time.Instant;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.action.ChatCommands;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The last few one-line tails in the chat area: result factories, the rate-limit wording a pilgrim actually
 * reads, and the lifecycle listener's promise that a broken scheduler cannot abort the container.
 */
public class SmallChatTailsTest {

    @Test
    public void sendResultFactoriesCarryTheirShape() {
        final ChatCommands.SendResult withNotice = ChatCommands.SendResult.ok(null, "your @all was not emailed");
        Assert.assertTrue(withNotice.isOk());

        final ChatRateLimiter.Decision deny = ChatRateLimiter.Decision.deny("burst", 10, 5, 60, null);
        final ChatCommands.SendResult limited = ChatCommands.SendResult.rateLimited(deny);
        Assert.assertFalse(limited.isOk());
    }

    /** The wording is user-facing: each denial reason must explain itself with its own numbers. */
    @Test
    public void rateLimitDecisionsExplainThemselves() {
        Assert.assertNull(ChatRateLimiter.Decision.allow().userMessage(), "an allow needs no apology");
        Assert.assertTrue(ChatRateLimiter.Decision.deny("slow_mode", 30, 0, 0, null)
                .userMessage().contains("Slow mode"));
        Assert.assertTrue(ChatRateLimiter.Decision.deny("burst", 10, 5, 60, null)
                .userMessage().contains("5 messages every 60"));
        Assert.assertTrue(ChatRateLimiter.Decision.deny("other", 10, 0, 0, Instant.now())
                .userMessage().contains("try again in 10"));
    }

    @Test
    public void aRawWithNullHtmlRendersAsEmptyNotTheWordNull() {
        Assert.assertEquals(new MailTemplates.Raw(null).toString(), "");
    }

    @Test
    public void theNotifierInterfaceDefaultCloseIsANoop() throws Exception {
        final ChatNotifier bare = new ChatNotifier() {
            @Override
            public Channel channel() {
                return Channel.IN_APP;
            }

            @Override
            public boolean isEnabled() {
                return false;
            }

            @Override
            public void notify(final ChatNotification notification) {
            }
        };
        bare.close(); // the interface default: closing a route with nothing to close is fine
    }

    /** Startup must not be able to break the app: a throwing scheduler is logged, not propagated. */
    @Test
    public void aThrowingSchedulerCannotAbortTheContainer() {
        try (MockedStatic<ChatDigestScheduler> scheduler = Mockito.mockStatic(ChatDigestScheduler.class)) {
            scheduler.when(ChatDigestScheduler::start).thenThrow(new IllegalStateException("cannot start"));
            scheduler.when(ChatDigestScheduler::stop).thenThrow(new IllegalStateException("cannot stop"));
            final ChatLifecycleListener listener = new ChatLifecycleListener();

            listener.contextInitialized(null);
            listener.contextDestroyed(null);
        }
    }
}
