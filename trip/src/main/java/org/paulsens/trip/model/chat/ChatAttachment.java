package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
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
            @JsonProperty("hidden") final Boolean hidden) {
        this.kind = kind;
        this.s3Key = s3Key;
        this.contentType = contentType;
        this.size = size == null ? 0L : size;
        this.width = width;
        this.height = height;
        this.thumbKey = thumbKey;
        this.caption = caption;
        this.hidden = Boolean.TRUE.equals(hidden) ? Boolean.TRUE : null;
    }

    /** Compatibility constructor predating the {@link #hidden} flag. */
    public ChatAttachment(final String kind, final String s3Key, final String contentType, final Long size,
            final Integer width, final Integer height, final String thumbKey, final String caption) {
        this(kind, s3Key, contentType, size, width, height, thumbKey, caption, null);
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
