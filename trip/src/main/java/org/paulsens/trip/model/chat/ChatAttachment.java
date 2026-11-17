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
    /**
     * Whether this attachment is a REFERENCE to a photo that lives in a trip album ("Choose from Albums" in the
     * chat, 2026-11-17): the keys are the ORIGINAL's, which another message (often in another trip's chat) owns.
     * A reference shows the photo and owns nothing: removing it never deletes the photo, its album row or its
     * comment thread, it gets no album row of its own, and it cannot be rotated or re-flagged from here. When the
     * original is removed the reference simply has nothing left to show. Null unless true, like {@link #hidden},
     * so old rows and the wire stay compact. Read via {@link #isRef()}.
     */
    @Getter(AccessLevel.NONE)
    Boolean ref;

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
            @JsonProperty("rev") final Integer rev,
            @JsonProperty("ref") final Boolean ref) {
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
        this.ref = Boolean.TRUE.equals(ref) ? Boolean.TRUE : null;
    }

    /** Compatibility constructor predating {@link #ref}: an attachment that owns its photo. */
    public ChatAttachment(final String kind, final String s3Key, final String contentType, final Long size,
            final Integer width, final Integer height, final String thumbKey, final String caption,
            final Boolean hidden, final Instant deletedAt, final String deletedBy, final Integer rev) {
        this(kind, s3Key, contentType, size, width, height, thumbKey, caption, hidden, deletedAt, deletedBy, rev,
                null);
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
     * This REFERENCE once the photo it points at is gone from its album (the message that shared it, or that
     * message's trip, was removed): a tombstone that stays a reference, so a client can say the photo is no longer
     * available instead of naming a remover nobody here knows. Keys, caption and dimensions are cleared like any
     * tombstone's, so a client holding the photo in its cache drops it with the keys. Its {@code deletedAt} is the
     * epoch: when the original went is not known here, and a fixed value keeps every read of the message the same.
     */
    public ChatAttachment gone() {
        return new ChatAttachment(kind, null, null, null, null, null, null, null, null, Instant.EPOCH, null, null,
                Boolean.TRUE);
    }

    /**
     * This attachment with its public-site flag changed and everything else kept: the uploader (or a chat
     * admin) may change their mind about the public site at any time after posting.
     */
    public ChatAttachment withHidden(final boolean newHidden) {
        return new ChatAttachment(kind, s3Key, contentType, size, width, height, thumbKey, caption,
                newHidden, deletedAt, deletedBy, rev, ref);
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
                hidden, deletedAt, deletedBy, getRev() + 1, ref);
    }

    /**
     * This REFERENCE as its photo is now. A reference copies the photo's facts once, when it is sent, and the
     * photo can be turned afterwards where it was first shared: the album row then holds the truth. Every turn is
     * a quarter that moves {@code rev} on by one, so an odd number of turns since the reference was made swaps
     * its dimensions; the size, the small rendition's key and {@code rev} are the album row's. Unchanged unless
     * the row's rev moved FORWARD: a rev that went back is not a turn (an old row rebuilt without it), and
     * following it would flip a photo that never moved.
     */
    public ChatAttachment followingAlbum(final int albumRev, final long albumSize, final String albumThumbKey) {
        if (albumRev <= getRev()) {
            return this;
        }
        final boolean swapped = (albumRev - getRev()) % 2 != 0;
        return new ChatAttachment(kind, s3Key, contentType, albumSize, swapped ? height : width,
                swapped ? width : height, albumThumbKey, caption, hidden, deletedAt, deletedBy, albumRev, ref);
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

    /**
     * Whether this attachment only references a photo another message owns; false for every ordinary upload.
     * Absent from JSON when false. Deliberately NOT {@code @JsonIgnore}: this is the property Jackson serializes.
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public boolean isRef() {
        return Boolean.TRUE.equals(ref);
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
