package org.paulsens.trip.action;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;

/**
 * Publish/subscribe for managed-media changes, keyed by key prefix.
 *
 * <p>Anything that needs to react when a file appears or disappears registers against the prefix it cares
 * about, and the media layer stays ignorant of why. The alternative -- teaching the upload and delete paths
 * about each feature that happens to keep state about files -- means every new use case edits code that has
 * nothing to do with it. Profile photos were the first such case and are now just a subscriber like any other.
 *
 * <p>Delivery is synchronous, after the write has succeeded, so a listener sees only changes that really
 * happened. A listener that throws is logged and skipped: a subscriber's bookkeeping problem must never fail
 * the upload the user actually asked for, nor prevent other subscribers from being told.
 *
 * <p>Registration is process-local and so is delivery: another instance's upload does not notify this one.
 * That is a deliberate limit, not an oversight -- the shared cache already handles data that must be coherent
 * across instances, and the things listening here are local accelerators that a restart rebuilds.
 */
@Slf4j
public final class MediaEvents {

    /** What happened to the file. */
    public enum Change { ADDED, REMOVED }

    /** Told about a change to a key under the prefix it was registered with. */
    @FunctionalInterface
    public interface Listener {
        void onChange(Change change, String key);
    }

    private static final Map<String, List<Listener>> LISTENERS = new ConcurrentHashMap<>();

    private MediaEvents() { }

    /**
     * Registers a listener for keys starting with {@code prefix}, e.g.
     * {@code onPrefix("profilePics/", (change, key) -> ...)}.
     */
    public static void onPrefix(final String prefix, final Listener listener) {
        if (prefix == null || listener == null) {
            return;
        }
        LISTENERS.computeIfAbsent(prefix, ignored -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** Notifies every listener whose prefix matches. Called by the media layer after a successful write. */
    static void fire(final Change change, final String key) {
        if (key == null) {
            return;
        }
        LISTENERS.forEach((prefix, listeners) -> {
            if (key.startsWith(prefix)) {
                listeners.forEach(listener -> notifyOne(listener, change, key));
            }
        });
    }

    private static void notifyOne(final Listener listener, final Change change, final String key) {
        try {
            listener.onChange(change, key);
        } catch (final RuntimeException ex) {
            log.error("Media listener failed for " + change + " " + key, ex);
        }
    }

    /** Visible for tests: drops all registrations. */
    static void clear() {
        LISTENERS.clear();
    }
}
