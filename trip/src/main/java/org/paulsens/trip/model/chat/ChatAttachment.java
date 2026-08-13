package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;

/** Reserved for media (P4). Always empty on messages in v1; still serialized so the shape never migrates. */
@Value
public class ChatAttachment implements Serializable {

    String kind;
    String s3Key;
    String contentType;
    long size;
    Integer width;
    Integer height;
    String thumbKey;
    String caption;
    /**
     * The uploader's choice to keep this photo off public pages. Null (every pre-flag message) means
     * visible -- public display is opt-out at upload time. Read via {@link #isHidden()}.
     */
    @Getter(AccessLevel.NONE)
    Boolean hidden;
    /** When this one attachment was removed from its message; null for a live attachment. */
    Instant deletedAt;
    /**
     * Display name of the remover, captured at delete time (the {@link ChatQuote#authorName} idiom) — the
     * feed renders "An image was deleted by X." without another person lookup, and the name stays right
     * even if the person later leaves the roster.
     */
    String deletedBy;

    @JsonCreator
    public ChatAttachment(
            @JsonProperty("kind") final String kind,
            @JsonProperty("s3Key") final String s3Key,
            @JsonProperty("contentType") final String contentType,
            @JsonProperty("size") final Long size,
            @JsonProperty("width") final Integer width,
            @JsonProperty("height") final Integer height,
            @JsonProperty("thumbKey") final String thumbKey,
            @JsonProperty("caption") final String caption,
            @JsonProperty("hidden") final Boolean hidden,
            @JsonProperty("deletedAt") final Instant deletedAt,
            @JsonProperty("deletedBy") final String deletedBy) {
        this.kind = kind;
        this.s3Key = s3Key;
        this.contentType = contentType;
        this.size = size == null ? 0L : size;
        this.width = width;
        this.height = height;
        this.thumbKey = thumbKey;
        this.caption = caption;
        this.hidden = Boolean.TRUE.equals(hidden) ? Boolean.TRUE : null;
        this.deletedAt = deletedAt;
        this.deletedBy = deletedBy;
    }

    /** Compatibility constructor predating the per-attachment tombstone. */
    public ChatAttachment(final String kind, final String s3Key, final String contentType, final Long size,
            final Integer width, final Integer height, final String thumbKey, final String caption,
            final Boolean hidden) {
        this(kind, s3Key, contentType, size, width, height, thumbKey, caption, hidden, null, null);
    }

    /** Compatibility constructor predating the {@link #hidden} flag. */
    public ChatAttachment(final String kind, final String s3Key, final String contentType, final Long size,
            final Integer width, final Integer height, final String thumbKey, final String caption) {
        this(kind, s3Key, contentType, size, width, height, thumbKey, caption, null, null, null);
    }

    /**
     * This attachment as its tombstone: keys, caption and dimensions cleared for the same reason
     * {@link ChatMessage#withDeleted} clears the whole list — the stored renditions are removed by the
     * delete cascade, so keys pointing at deleted objects must not linger on the wire. Only the kind, the
     * remover's name and the timestamp remain, which is exactly what the feed needs to render the note.
     */
    public ChatAttachment deleted(final String by) {
        return new ChatAttachment(kind, null, null, null, null, null, null, null, null,
                Instant.now().truncatedTo(ChronoUnit.MILLIS), by);
    }

    /** Whether this attachment was individually removed; null-safe (old messages read as live). */
    @JsonIgnore
    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * Whether the uploader kept this photo off public pages; null-safe (old messages read as visible).
     * Deliberately NOT {@code @JsonIgnore}: this is the property Jackson serializes, so the flag survives
     * the message round trip.
     */
    public boolean isHidden() {
        return Boolean.TRUE.equals(hidden);
    }
}
