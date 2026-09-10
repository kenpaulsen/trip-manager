package org.paulsens.trip.media;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The local-mode stand-in for the media bucket: a byte-budgeted, insertion-ordered map of stored objects
 * that evicts oldest-first once it is over budget.
 *
 * <p>Five beans (profile, branding, chat, badge and lodging photos) each carried a private copy of this --
 * the same {@code LinkedHashMap}, running byte total and eviction loop, differing only in whether an entry
 * remembered its content type. Bookkeeping like a running total is exactly what drifts between copies (a
 * re-put of an existing key, for one, was counted twice), so it is one class now, and the store beans keep
 * only their "remote or local?" branch.
 *
 * <p>Guarded by its own monitor; access is rare and brief, so nothing finer is needed.
 */
public final class LocalObjectStore {

    /** One stored object. {@code contentType} is null in stores that only ever hold one kind. */
    public record Stored(byte[] bytes, String contentType) {
    }

    /** Insertion-ordered so eviction is oldest-first. All access synchronized on this map. */
    private final Map<String, Stored> objects = new LinkedHashMap<>();
    private long totalBytes;
    private long maxBytes;

    public LocalObjectStore(final long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** Test seam: a small budget reaches the eviction path without megabytes of fixtures. */
    public void maxBytes(final long budget) {
        synchronized (objects) {
            this.maxBytes = budget;
        }
    }

    public void put(final String key, final byte[] bytes) {
        put(key, bytes, null);
    }

    /** Stores the object, then evicts oldest-first until the total is within budget (the newcomer included). */
    public void put(final String key, final byte[] bytes, final String contentType) {
        synchronized (objects) {
            final Stored previous = objects.put(key, new Stored(bytes, contentType));
            if (previous != null) {
                totalBytes -= previous.bytes().length;
            }
            totalBytes += bytes.length;
            final var iterator = objects.entrySet().iterator();
            while (totalBytes > maxBytes && iterator.hasNext()) {
                totalBytes -= iterator.next().getValue().bytes().length;
                iterator.remove();
            }
        }
    }

    public Optional<byte[]> get(final String key) {
        return stored(key).map(Stored::bytes);
    }

    public Optional<Stored> stored(final String key) {
        synchronized (objects) {
            return Optional.ofNullable(objects.get(key));
        }
    }

    /** @return whether there was anything under that key to remove. */
    public boolean remove(final String key) {
        synchronized (objects) {
            final Stored removed = objects.remove(key);
            if (removed == null) {
                return false;
            }
            totalBytes -= removed.bytes().length;
            return true;
        }
    }

    /** Removes every object whose key starts with the prefix. @return how many there were. */
    public int removeByPrefix(final String prefix) {
        int removed = 0;
        synchronized (objects) {
            final var iterator = objects.entrySet().iterator();
            while (iterator.hasNext()) {
                final var entry = iterator.next();
                if (entry.getKey().startsWith(prefix)) {
                    totalBytes -= entry.getValue().bytes().length;
                    iterator.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    long totalBytes() {
        synchronized (objects) {
            return totalBytes;
        }
    }
}
