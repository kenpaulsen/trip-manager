package org.paulsens.trip.chat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.cache.CacheClient;
import org.paulsens.trip.cache.CacheKeys;
import org.paulsens.trip.dynamo.DAO;

/**
 * Parked readers, keyed by channel, woken when that channel gets a nudge.
 *
 * <p>This is the seam between Valkey pub/sub and whatever is waiting. It deliberately knows nothing about JAX-RS:
 * a waiter is a callback, so the long-poll can hand it an {@code AsyncResponse} while tests hand it a latch, and
 * the wake path is provable without a servlet container.
 *
 * <p><b>Subscriptions are per channel and reference counted.</b> The alternative -- one broad subscription -- is not
 * available: ElastiCache Serverless has no PSUBSCRIBE, so every channel a subscriber wants must be named
 * explicitly. So a channel is subscribed when its first reader parks and unsubscribed when its last one leaves,
 * which also means an idle deployment holds no subscriptions at all.
 *
 * <p><b>Waking is best effort by design.</b> A missed nudge -- cache down, a reader that parked a millisecond too
 * late, a payload from another build -- costs latency only: the long-poll still rides its own timeout and the reader
 * still does the cursor read that is the actual source of truth. Nothing here is allowed to be load-bearing for
 * delivery.
 */
@Slf4j
public final class ChatNudgeRegistry {

    /** What a parked reader supplies. Called at most once per park, on a pool thread, never on an event loop. */
    public interface Waiter {
        /**
         * @param upToMillis the nudge watermark, or 0 when it could not be read. Advisory: the reader's own cursor
         *     decides what it actually fetches.
         */
        void wake(long upToMillis);
    }

    private static volatile ChatNudgeRegistry shared;

    private final CacheClient cacheClient;
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    public ChatNudgeRegistry(final CacheClient cacheClient) {
        this.cacheClient = cacheClient;
    }

    /** The process-wide registry. One per JVM so a subscription is shared by every reader of a channel. */
    public static ChatNudgeRegistry getInstance() {
        ChatNudgeRegistry local = shared;
        if (local == null) {
            synchronized (ChatNudgeRegistry.class) {
                local = shared;
                if (local == null) {
                    local = new ChatNudgeRegistry(DAO.getInstance().getCacheClient());
                    shared = local;
                }
            }
        }
        return local;
    }

    /**
     * Parks a waiter on a channel until the returned handle is closed.
     *
     * <p>The caller MUST close the handle -- on wake, on timeout, and on error alike. A handle that is never closed
     * leaks the waiter and keeps the channel's subscription open forever.
     */
    public AutoCloseable park(final String channelId, final Waiter waiter) {
        if (channelId == null || waiter == null) {
            return () -> { };
        }
        final Channel channel = channels.computeIfAbsent(channelId, this::openChannel);
        channel.waiters.add(waiter);
        return () -> leave(channelId, waiter);
    }

    /** Publishes a nudge for a channel. Safe to call on any thread; never throws. */
    public void nudge(final String channelId, final long upToMillis) {
        if (channelId == null) {
            return;
        }
        cacheClient.publish(CacheKeys.chatPubSubChannelFor(channelId), ChatNudge.encode(channelId, upToMillis));
    }

    /** How many readers are parked on a channel. Test and diagnostics only. */
    public int parkedCount(final String channelId) {
        final Channel channel = channels.get(channelId);
        return channel == null ? 0 : channel.waiters.size();
    }

    /** Whether a subscription is currently open for a channel. Test and diagnostics only. */
    public boolean isSubscribed(final String channelId) {
        return channels.containsKey(channelId);
    }

    private Channel openChannel(final String channelId) {
        final Channel channel = new Channel();
        final String pubSubName = CacheKeys.chatPubSubChannelFor(channelId);
        channel.subscription = cacheClient.subscribe(List.of(pubSubName),
                (name, payload) -> wakeAll(channelId, payload));
        return channel;
    }

    private void wakeAll(final String channelId, final String payload) {
        final Channel channel = channels.get(channelId);
        if (channel == null) {
            return;
        }
        final long upTo = ChatNudge.upTo(payload);
        // Snapshot: a waiter's wake() closes its own handle, which mutates the list we are walking.
        for (final Waiter waiter : List.copyOf(channel.waiters)) {
            wakeOne(channelId, waiter, upTo);
        }
    }

    private void wakeOne(final String channelId, final Waiter waiter, final long upTo) {
        try {
            waiter.wake(upTo);
        } catch (final RuntimeException ex) {
            // One broken waiter must not deny the nudge to the others parked on the same channel.
            log.warn("Chat nudge waiter failed on channel {}", channelId, ex);
        }
    }

    /**
     * Removes a waiter and, when it was the last one, closes the channel's subscription.
     *
     * <p>Synchronized against {@link #openChannel} through {@code channels.compute}, so a reader parking at the
     * same moment the last one leaves cannot attach to a subscription that is being torn down.
     */
    private void leave(final String channelId, final Waiter waiter) {
        // Still inside compute(), which is what makes this atomic against a concurrent park().
        channels.compute(channelId, (key, channel) -> withoutWaiter(key, channel, waiter));
    }

    /** Returns the channel to keep, or {@code null} to drop it -- which also closes its subscription. */
    private static Channel withoutWaiter(final String channelId, final Channel channel, final Waiter waiter) {
        if (channel == null) {
            return null;
        }
        channel.waiters.remove(waiter);
        if (!channel.waiters.isEmpty()) {
            return channel;
        }
        closeQuietly(channelId, channel.subscription);
        return null;
    }

    private static void closeQuietly(final String channelId, final AutoCloseable subscription) {
        if (subscription == null) {
            return;
        }
        try {
            subscription.close();
        } catch (final Exception ex) {
            log.warn("Unable to close chat nudge subscription for {}", channelId, ex);
        }
    }

    private static final class Channel {
        private final List<Waiter> waiters = new CopyOnWriteArrayList<>();
        private AutoCloseable subscription;
    }
}
