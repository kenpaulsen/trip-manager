package org.paulsens.trip.media;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A per-key sliding-window counter for photo uploads: at most {@code perWindow} accepted uploads in any
 * {@code windowSeconds}. Process-local like the staging registry it protects, and the same trade -- a second
 * task would double the allowance, which is still far below what a runaway loop produces.
 *
 * <p>ONE instance per feature, shared by every transport: the chat page's upload dialog and the REST upload
 * endpoint count against the same allowance, or a client could double its quota by alternating them.
 */
public final class UploadRateLimiter {

    /** Chat uploads per person per window -- far above honest use (10 per message), low enough to stop a loop. */
    public static final int CHAT_UPLOADS_PER_WINDOW = 40;
    public static final int CHAT_WINDOW_SECONDS = 600;

    private static final UploadRateLimiter CHAT_PHOTOS =
            new UploadRateLimiter(CHAT_UPLOADS_PER_WINDOW, CHAT_WINDOW_SECONDS);

    private final int perWindow;
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> recent = new ConcurrentHashMap<>();

    public UploadRateLimiter(final int perWindow, final int windowSeconds) {
        this(perWindow, windowSeconds, System::currentTimeMillis);
    }

    UploadRateLimiter(final int perWindow, final int windowSeconds, final LongSupplier clock) {
        this.perWindow = perWindow;
        this.windowMillis = windowSeconds * 1_000L;
        this.clock = clock;
    }

    /** The one allowance chat photo uploads count against, whichever way the bytes arrive. */
    public static UploadRateLimiter chatPhotos() {
        return CHAT_PHOTOS;
    }

    /**
     * Records an upload for {@code key} when the window has room.
     *
     * @return false, recording nothing, when the key has used its allowance for the current window.
     */
    public boolean allow(final String key) {
        final long now = clock.getAsLong();
        final Deque<Long> times = timesOf(key);
        synchronized (times) {
            prune(times, now);
            if (times.size() >= perWindow) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    /**
     * How long, in whole seconds (at least 1), until the key's oldest counted upload leaves the window --
     * what a {@code Retry-After} header should say. Zero when the key is not limited right now.
     */
    public int retryAfterSeconds(final String key) {
        final long now = clock.getAsLong();
        final Deque<Long> times = timesOf(key);
        synchronized (times) {
            prune(times, now);
            if (times.size() < perWindow) {
                return 0;
            }
            final long freeAt = times.peekFirst() + windowMillis;
            return (int) Math.max(1L, (freeAt - now + 999L) / 1_000L);
        }
    }

    private Deque<Long> timesOf(final String key) {
        return recent.computeIfAbsent(key == null ? "" : key, ignored -> new ArrayDeque<>());
    }

    /** A stamp exactly one window old is out -- the same boundary {@link #retryAfterSeconds} promises. */
    private void prune(final Deque<Long> times, final long now) {
        final long cutoff = now - windowMillis;
        while (!times.isEmpty() && times.peekFirst() <= cutoff) {
            times.pollFirst();
        }
    }
}
