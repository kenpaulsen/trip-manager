package org.paulsens.trip.chat;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import lombok.Value;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.chat.ChatChannel;
import org.paulsens.trip.model.chat.ChatMessage;

/**
 * One thing worth telling someone about. Shaped like an audit event on purpose: built on the request thread,
 * delivered elsewhere.
 */
@Value
public class ChatNotification implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    public enum Reason {
        MENTION,
        /** The recipient authored the message this one quotes — a reply addresses them like a mention. */
        REPLY,
        /** The recipient uploaded the photo this comment is on — commenting replies to the photo. */
        PHOTO_COMMENT,
        ALL_MESSAGES,
        DIGEST,
        ADMIN_ANNOUNCEMENT
    }

    ChatChannel.Id channelId;
    ChatMessage.Id messageId;
    String tripId;
    String tripTitle;
    Person.Id authorId;
    String authorName;
    List<Person.Id> recipients;
    /** At most 200 code points, and <b>omitted entirely</b> for a short-retention channel — see below. */
    String snippet;
    Reason reason;
    /**
     * Idempotency key, {@code "{messageId}|{personId}|{channel}"}.
     *
     * <p>Without it a restart mid-run re-notifies everyone already told. It is checked against a cache marker rather
     * than a durable row because a duplicate email is an annoyance, not a correctness failure, and the marker's TTL
     * bounds the memory for it.
     */
    String dedupeKey;
    Instant queuedAt;

    public ChatNotification(
            final ChatChannel.Id channelId,
            final ChatMessage.Id messageId,
            final String tripId,
            final String tripTitle,
            final Person.Id authorId,
            final String authorName,
            final List<Person.Id> recipients,
            final String snippet,
            final Reason reason,
            final String dedupeKey,
            final Instant queuedAt) {
        this.channelId = channelId;
        this.messageId = messageId;
        this.tripId = tripId;
        this.tripTitle = tripTitle;
        this.authorId = authorId;
        this.authorName = authorName;
        this.recipients = recipients == null ? List.of() : List.copyOf(recipients);
        this.snippet = snippet;
        this.reason = reason;
        this.dedupeKey = dedupeKey;
        this.queuedAt = queuedAt == null ? Instant.now() : queuedAt;
    }

    public static String dedupeKeyFor(
            final ChatMessage.Id messageId, final Person.Id personId, final String channel) {
        return (messageId == null ? "-" : messageId.getValue()) + "|"
                + (personId == null ? "-" : personId.getValue()) + "|" + channel;
    }

    /** Whether there is any body text to show. False for a content-free mention from a short-retention channel. */
    public boolean hasSnippet() {
        return snippet != null && !snippet.isBlank();
    }
}
