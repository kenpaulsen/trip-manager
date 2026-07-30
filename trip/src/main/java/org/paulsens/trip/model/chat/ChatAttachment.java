package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
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

    @JsonCreator
    public ChatAttachment(
            @JsonProperty("kind") final String kind,
            @JsonProperty("s3Key") final String s3Key,
            @JsonProperty("contentType") final String contentType,
            @JsonProperty("size") final Long size,
            @JsonProperty("width") final Integer width,
            @JsonProperty("height") final Integer height,
            @JsonProperty("thumbKey") final String thumbKey,
            @JsonProperty("caption") final String caption) {
        this.kind = kind;
        this.s3Key = s3Key;
        this.contentType = contentType;
        this.size = size == null ? 0L : size;
        this.width = width;
        this.height = height;
        this.thumbKey = thumbKey;
        this.caption = caption;
    }
}
