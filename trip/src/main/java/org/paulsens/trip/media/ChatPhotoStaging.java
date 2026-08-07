package org.paulsens.trip.media;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The photos a person has uploaded but not yet sent. An upload happens per-attach (one XHR each); the message
 * referencing the keys arrives in a separate request whenever they press Send — this registry is what lets the
 * send trust those keys. A key that was never staged, was staged by someone else, or was staged for a different
 * trip is simply not claimable, which is the entire authorization story for attachment references: without it,
 * a member could attach ANY object key on the CDN to a message, or another member's just-uploaded photo.
 *
 * <p>Process-local by design, the same honest limitation as {@code MediaEvents} and the long-poll nudge
 * registry: there is deliberately one task in this deployment, and the cost of losing the map on a restart is
 * that someone re-attaches a photo. Entries expire after {@link #TTL}; expired entries are drained by the
 * caller (see {@code ChatPhotos}), which also deletes their stored objects — an upload abandoned mid-compose
 * should not become a permanent unlisted file.
 */
public final class ChatPhotoStaging {

    static final Duration TTL = Duration.ofHours(24);

    public record Staged(
            String tripId,
            String uploaderId,
            String key,
            String smallKey,
            String contentType,
            long size,
            int width,
            int height,
            Instant stagedAt) {
    }

    private final Map<String, Staged> byKey = new ConcurrentHashMap<>();

    public void put(final Staged staged) {
        byKey.put(staged.key(), staged);
    }

    /** Read without consuming, for validating a send before anything is written. */
    public Optional<Staged> peek(final String key, final String tripId, final String uploaderId) {
        final Staged staged = byKey.get(key);
        if (staged == null || staged.stagedAt().isBefore(Instant.now().minus(TTL))) {
            return Optional.empty();
        }
        if (!staged.tripId().equals(tripId) || !staged.uploaderId().equals(uploaderId)) {
            return Optional.empty();
        }
        return Optional.of(staged);
    }

    /** Consume after the message is durably saved: the photo now belongs to the message, not the composer. */
    public void consume(final String key) {
        byKey.remove(key);
    }

    /** @return every expired entry, removed from the registry so each is drained exactly once. */
    public List<Staged> drainExpired() {
        final Instant cutoff = Instant.now().minus(TTL);
        final List<Staged> expired = new ArrayList<>();
        for (final Staged staged : byKey.values()) {
            if (staged.stagedAt().isBefore(cutoff) && byKey.remove(staged.key(), staged)) {
                expired.add(staged);
            }
        }
        return expired;
    }

    public int size() {
        return byKey.size();
    }
}
