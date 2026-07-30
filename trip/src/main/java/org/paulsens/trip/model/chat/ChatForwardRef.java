package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.Instant;
import lombok.Value;
import org.paulsens.trip.model.Person;

/**
 * Reserved (P4). A message id is unique only within a channel, so a back-pointer is always the pair
 * {@code (sourceChannelId, sourceMessageId)} — never a bare msgId alone.
 */
@Value
public class ChatForwardRef implements Serializable {

    ChatChannel.Id sourceChannelId;
    ChatMessage.Id sourceMessageId;
    Person.Id sourceAuthorId;
    String sourceAuthorName;
    Instant sourceSentAt;
    Instant forwardedAt;
    Person.Id forwardedBy;

    @JsonCreator
    public ChatForwardRef(
            @JsonProperty("sourceChannelId") final ChatChannel.Id sourceChannelId,
            @JsonProperty("sourceMessageId") final ChatMessage.Id sourceMessageId,
            @JsonProperty("sourceAuthorId") final Person.Id sourceAuthorId,
            @JsonProperty("sourceAuthorName") final String sourceAuthorName,
            @JsonProperty("sourceSentAt") final Instant sourceSentAt,
            @JsonProperty("forwardedAt") final Instant forwardedAt,
            @JsonProperty("forwardedBy") final Person.Id forwardedBy) {
        this.sourceChannelId = sourceChannelId;
        this.sourceMessageId = sourceMessageId;
        this.sourceAuthorId = sourceAuthorId;
        this.sourceAuthorName = sourceAuthorName;
        this.sourceSentAt = sourceSentAt;
        this.forwardedAt = forwardedAt;
        this.forwardedBy = forwardedBy;
    }
}
