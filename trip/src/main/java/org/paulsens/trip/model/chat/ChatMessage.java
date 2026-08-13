package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.Value;
import org.paulsens.trip.model.Person;

/**
 * One chat message. Sort key / id is the 13-digit zero-padded epoch millis (AuditDAO idiom). The object is
 * immutable; field mutation is via {@code with*} replacements written back as a new Dynamo row version.
 */
@Value
public class ChatMessage implements Serializable {

    public static final int CURRENT_SCHEMA = 1;

    public enum MessageKind {
        TEXT,
        SYSTEM,
        /** Reserved. */
        MEDIA
    }

    /**
     * Channel-local message id: 13-digit zero-padded epoch millis. Not a UUID — unique only within a channel.
     * Cross-channel references must carry the {@code (channelId, msgId)} pair.
     */
    @Value
    public static class Id implements Serializable, Comparable<Id> {
        @JsonValue
        String value;

        public static Id from(final String id) {
            return new Id(id);
        }

        public static Id of(final long millis) {
            return new Id(String.format("%013d", millis));
        }

        @JsonIgnore
        public long getEpochMilli() {
            if (value == null || value.isBlank()) {
                return 0L;
            }
            try {
                return Long.parseLong(value);
            } catch (final NumberFormatException ex) {
                return 0L;
            }
        }

        public Id next() {
            return of(getEpochMilli() + 1);
        }

        @Override
        public int compareTo(final Id o) {
            return value.compareTo(o.getValue());
        }
    }

    Id id;
    ChatChannel.Id channelId;
    /** Null only for {@link MessageKind#SYSTEM}. */
    Person.Id authorId;
    Instant sentAt;
    MessageKind kind;
    String body;
    ChatQuote quote;
    List<ChatAttachment> attachments;
    ChatForwardRef forwardedFrom;
    Instant editedAt;
    Instant deletedAt;
    String deletedBy;
    /**
     * Epoch <em>seconds</em> TTL reaper hint only — never the visibility rule. See {@link ChatVisibility}.
     */
    Long expiresAt;
    /** Client-supplied idempotency key; not displayed. */
    String clientMessageId;
    int schemaVersion;

    @JsonCreator
    public ChatMessage(
            @JsonProperty("id") final Id id,
            @JsonProperty("channelId") final ChatChannel.Id channelId,
            @JsonProperty("authorId") final Person.Id authorId,
            @JsonProperty("sentAt") final Instant sentAt,
            @JsonProperty("kind") final MessageKind kind,
            @JsonProperty("body") final String body,
            @JsonProperty("quote") final ChatQuote quote,
            @JsonProperty("attachments") final List<ChatAttachment> attachments,
            @JsonProperty("forwardedFrom") final ChatForwardRef forwardedFrom,
            @JsonProperty("editedAt") final Instant editedAt,
            @JsonProperty("deletedAt") final Instant deletedAt,
            @JsonProperty("deletedBy") final String deletedBy,
            @JsonProperty("expiresAt") final Long expiresAt,
            @JsonProperty("clientMessageId") final String clientMessageId,
            @JsonProperty("schemaVersion") final Integer schemaVersion) {
        this.id = id;
        this.channelId = channelId;
        this.authorId = authorId;
        this.sentAt = sentAt == null
                ? (id == null ? Instant.now().truncatedTo(ChronoUnit.MILLIS) : Instant.ofEpochMilli(id.getEpochMilli()))
                : sentAt.truncatedTo(ChronoUnit.MILLIS);
        this.kind = kind == null ? MessageKind.TEXT : kind;
        this.body = body == null ? "" : body;
        this.quote = quote;
        this.attachments = attachments == null ? List.of() : List.copyOf(attachments);
        this.forwardedFrom = forwardedFrom;
        this.editedAt = editedAt;
        this.deletedAt = deletedAt;
        this.deletedBy = deletedBy;
        this.expiresAt = expiresAt;
        this.clientMessageId = clientMessageId;
        this.schemaVersion = schemaVersion == null ? CURRENT_SCHEMA : schemaVersion;
    }

    public ChatMessage withBody(final String newBody) {
        return new ChatMessage(id, channelId, authorId, sentAt, kind, newBody, quote, attachments,
                forwardedFrom, Instant.now().truncatedTo(ChronoUnit.MILLIS), deletedAt, deletedBy,
                expiresAt, clientMessageId, schemaVersion);
    }

    public ChatMessage withDeleted(final String by) {
        // Attachments are cleared like the body: a delete is moderation, and the stored renditions are
        // removed by the same cascade, so keys pointing at deleted objects must not linger on the wire.
        return new ChatMessage(id, channelId, authorId, sentAt, kind, "", quote, List.of(),
                forwardedFrom, editedAt, Instant.now().truncatedTo(ChronoUnit.MILLIS), by,
                expiresAt, clientMessageId, schemaVersion);
    }

    /**
     * Replaces the attachment list — the single-photo removal write. Deliberately does NOT stamp
     * {@code editedAt}: that flag renders as "· edited" and means the author rewrote their words, while a
     * removed photo already announces itself via its own tombstone note.
     */
    public ChatMessage withAttachments(final List<ChatAttachment> newAttachments) {
        return new ChatMessage(id, channelId, authorId, sentAt, kind, body, quote, newAttachments,
                forwardedFrom, editedAt, deletedAt, deletedBy, expiresAt, clientMessageId, schemaVersion);
    }

    public ChatMessage withId(final Id newId) {
        return new ChatMessage(newId, channelId, authorId,
                Instant.ofEpochMilli(newId.getEpochMilli()), kind, body, quote, attachments,
                forwardedFrom, editedAt, deletedAt, deletedBy, expiresAt, clientMessageId, schemaVersion);
    }

    public ChatMessage withExpiresAt(final Long newExpiresAt) {
        return new ChatMessage(id, channelId, authorId, sentAt, kind, body, quote, attachments,
                forwardedFrom, editedAt, deletedAt, deletedBy, newExpiresAt, clientMessageId, schemaVersion);
    }

    @JsonIgnore
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
