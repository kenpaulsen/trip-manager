package org.paulsens.trip.model.chat;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.paulsens.trip.model.Trip;

/**
 * Canonical visibility rule for chat messages (and reaction rows that inherit a target's {@code sentAt}).
 *
 * <p><b>Pure, no I/O.</b> The DAO read path, digest builder, export and reaction-summary builder must all call
 * this and nothing else. {@code expiresAt} on a Dynamo row is only a TTL reaper hint — never consulted here.
 *
 * <p>When {@code fullHistoryForNewMembers} is false and {@code member} is null (no row), this fails closed:
 * the caller is responsible for materialising a membership row for every reader in that mode.
 *
 * <p>Pure functions only — no instance state. {@code Serializable} only to satisfy the model package sweep.
 */
public final class ChatVisibility implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private ChatVisibility() {
    }

    /**
     * @param msg     the message (tombstones remain visible — body is what was cleared)
     * @param member  the reader's membership, or {@code null} for an implicit member
     * @param channel the channel (settings)
     * @param trip    the trip (end-date anchor); may be null if the channel has no trip
     * @param now     caller's clock (injected so tests are deterministic)
     */
    public static boolean isVisible(
            final ChatMessage msg,
            final ChatMembership member,
            final ChatChannel channel,
            final Trip trip,
            final Instant now) {
        if (msg == null || channel == null || now == null) {
            return false;
        }
        final Instant expiry = effectiveExpiry(channel, trip, msg.getSentAt());
        if (expiry != null && !expiry.isAfter(now)) {
            return false;
        }
        final ChatSettings settings = channel.getSettings();
        if (settings != null && !settings.isFullHistoryForNewMembers()) {
            if (member == null || member.getJoinedAt() == null) {
                return false;
            }
            if (msg.getSentAt() != null && msg.getSentAt().isBefore(member.getJoinedAt())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Authoritative expiry instant, or {@code null} meaning keep forever.
     *
     * <ul>
     *   <li>trip-end retention wins when set and trip has an end date</li>
     *   <li>{@code retentionSeconds == null} → forever</li>
     *   <li>{@code retentionSeconds == 0} → {@code sentAt + bufferMinutes} (ephemeral; Dynamo still readable
     *       for the buffer window so Valkey-down cannot black-hole a send)</li>
     *   <li>otherwise {@code sentAt + retentionSeconds}</li>
     * </ul>
     */
    public static Instant effectiveExpiry(
            final ChatChannel channel, final Trip trip, final Instant sentAt) {
        if (channel == null || channel.getSettings() == null) {
            return null;
        }
        final ChatSettings s = channel.getSettings();
        if (s.getRetentionDaysAfterTripEnd() != null && trip != null && trip.getEndDate() != null) {
            final LocalDateTime end = trip.getEndDate();
            return end.plusDays(s.getRetentionDaysAfterTripEnd()).toInstant(ZoneOffset.UTC);
        }
        if (s.getRetentionSeconds() == null) {
            return null;
        }
        final Instant base = sentAt == null ? Instant.EPOCH : sentAt;
        if (s.getRetentionSeconds() == 0L) {
            return base.plus(s.getBufferMinutes(), ChronoUnit.MINUTES);
        }
        return base.plusSeconds(s.getRetentionSeconds());
    }

    /**
     * Epoch <em>seconds</em> for the DynamoDB TTL attribute, or {@code null} when the message is kept forever.
     * Derived from {@link #effectiveExpiry}; stored only as a reaper hint.
     */
    public static Long expiresAtEpochSeconds(
            final ChatChannel channel, final Trip trip, final Instant sentAt) {
        final Instant expiry = effectiveExpiry(channel, trip, sentAt);
        return expiry == null ? null : expiry.getEpochSecond();
    }

    /**
     * Whether the channel is archived (read-only) at {@code now}. Uses stored {@code archivedAt} if set,
     * otherwise computes from {@code trip.endDate + archiveAfterTripEndDays}.
     */
    public static boolean isArchived(
            final ChatChannel channel, final Trip trip, final Instant now) {
        if (channel == null || now == null) {
            return false;
        }
        if (channel.getArchivedAt() != null) {
            return !channel.getArchivedAt().isAfter(now);
        }
        if (trip == null || trip.getEndDate() == null || channel.getSettings() == null) {
            return false;
        }
        final Instant freeze = trip.getEndDate()
                .plusDays(channel.getSettings().getArchiveAfterTripEndDays())
                .toInstant(ZoneOffset.UTC);
        return !freeze.isAfter(now);
    }
}
