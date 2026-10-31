package org.paulsens.trip.media;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Refuses a second rotation of the same image while the first is still running. Every rotation is a
 * read-turn-write of stored bytes, so two overlapping taps on one key would both read the same original and
 * the second write would silently undo the first turn (and both would bump the version to the same value).
 * The loser is told to retry (409) instead.
 *
 * <p>JVM-local on purpose: the deployment is deliberately ONE task, and the cache-lock alternative grants
 * every lock in local mode ({@code NoopCacheClient}), which is where the tests run. If the service ever runs
 * more than one task, move the chat case onto a conditional {@code rev} check in
 * {@code ChatDAO.rewriteAttachment} -- a lock in one JVM cannot see a rotate in another.
 *
 * <p>Usage is always {@code if (!begin(key)) busy; try { ... } finally { end(key); }}.
 */
public final class RotationGuard {

    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private RotationGuard() {
    }

    /** @return true when the caller now owns {@code key}; false when another rotation of it is running. */
    public static boolean begin(final String key) {
        return IN_FLIGHT.add(key);
    }

    /** Releases {@code key}; harmless when it was not held. */
    public static void end(final String key) {
        IN_FLIGHT.remove(key);
    }
}
