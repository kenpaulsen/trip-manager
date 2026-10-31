package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Value;

/** Reserved for media (P4). Always empty on messages in v1; still serialized so the shape never migrates. */
@Value
public class ChatAttachment implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

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
    /**
     * How many times the photo was rotated in place. The object keys never change (the key is the photo's
     * identity: its comment thread, reactions and album row all join on it), so this is what a client puts
     * in the URL ({@code ?r=N}) to stop showing the cached unrotated bytes. Null when never rotated, like
     * {@link #hidden}, so old rows and the wire stay compact. Read via {@link #getRev()}.
     */
    @Getter(AccessLevel.NONE)
    Integer rev;

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
            @JsonProperty("deletedBy") final String deletedBy,
            @JsonProperty("rev") final Integer rev) {
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
        this.rev = (rev == null || rev <= 0) ? null : rev;
    }

    /** Compatibility constructor predating {@link #rev}. */
    public ChatAttachment(final String kind, final String s3Key, final String contentType, final Long size,
            final Integer width, final Integer height, final String thumbKey, final String caption,
            final Boolean hidden, final Instant deletedAt, final String deletedBy) {
        this(kind, s3Key, contentType, size, width, height, thumbKey, caption, hidden, deletedAt, deletedBy, null);
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
                Instant.now().truncatedTo(ChronoUnit.MILLIS), by, null);
    }

    /**
     * This attachment with its public-site flag changed and everything else kept: the uploader (or a chat
     * admin) may change their mind about the public site at any time after posting.
     */
    public ChatAttachment withHidden(final boolean newHidden) {
        return new ChatAttachment(kind, s3Key, contentType, size, width, height, thumbKey, caption,
                newHidden, deletedAt, deletedBy, rev);
    }

    /**
     * This attachment after its stored renditions were turned a quarter in place: the dimensions swap
     * (they came from the turned image, so they are passed rather than swapped here), the full rendition's
     * size is the re-encode's, {@code thumbKey} names where the small rendition now is, and {@link #rev} moves
     * on by one so every client's URL changes. Keys, caption and flags are kept.
     */
    public ChatAttachment rotated(final int newWidth, final int newHeight, final long newSize,
            final String newThumbKey) {
        return new ChatAttachment(kind, s3Key, contentType, newSize, newWidth, newHeight, newThumbKey, caption,
                hidden, deletedAt, deletedBy, getRev() + 1);
    }

    /** {@link #rotated(int, int, long, String)} keeping the small rendition where it was. */
    public ChatAttachment rotated(final int newWidth, final int newHeight, final long newSize) {
        return rotated(newWidth, newHeight, newSize, thumbKey);
    }

    /**
     * How many times the photo was rotated; 0 when never. Absent from JSON at 0 (the stored row and every
     * payload carrying messages), so a client reads a missing {@code rev} as 0.
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public int getRev() {
        return rev == null ? 0 : rev;
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
