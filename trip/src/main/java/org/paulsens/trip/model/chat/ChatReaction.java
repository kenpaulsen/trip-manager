package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import lombok.Value;
import org.paulsens.trip.model.Person;

/**
 * One reaction row in {@code chat_reactions}. Key identity is
 * {@code (channelId, targetMessageId, personId, emoji)} — Put/Delete are idempotent and lock-free.
 */
@Value
public class ChatReaction implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    ChatChannel.Id channelId;
    ChatMessage.Id targetMessageId;
    Person.Id personId;
    String emoji;
    Instant reactedAt;
    /** Epoch seconds TTL reaper hint; mirrors the target message. */
    Long expiresAt;

    @JsonCreator
    public ChatReaction(
            @JsonProperty("channelId") final ChatChannel.Id channelId,
            @JsonProperty("targetMessageId") final ChatMessage.Id targetMessageId,
            @JsonProperty("personId") final Person.Id personId,
            @JsonProperty("emoji") final String emoji,
            @JsonProperty("reactedAt") final Instant reactedAt,
            @JsonProperty("expiresAt") final Long expiresAt) {
        this.channelId = channelId;
        this.targetMessageId = targetMessageId;
        this.personId = personId;
        this.emoji = emoji;
        this.reactedAt = reactedAt == null ? Instant.now() : reactedAt;
        this.expiresAt = expiresAt;
    }

    /**
     * Dynamo sort key: {@code {targetMsgId}#{personId}#{emoji}}. Contiguous for a message range query.
     */
    @JsonIgnore
    public String sortKey() {
        return sortKey(targetMessageId, personId, emoji);
    }

    public static String sortKey(final ChatMessage.Id targetMessageId, final Person.Id personId, final String emoji) {
        final String msg = targetMessageId == null ? "" : targetMessageId.getValue();
        final String person = personId == null ? "" : personId.getValue();
        final String e = emoji == null ? "" : emoji;
        return msg + "#" + person + "#" + e;
    }

    /** Lower bound for reactions belonging to messages with id &gt;= {@code oldest}. */
    public static String rangeLower(final ChatMessage.Id oldest) {
        return (oldest == null ? "" : oldest.getValue()) + "#";
    }

    /**
     * Upper bound inclusive-ish for reactions on messages with id &lt;= {@code newest}. Appends a high code
     * point so every reaction SK for that message id is included.
     */
    public static String rangeUpper(final ChatMessage.Id newest) {
        return (newest == null ? "" : newest.getValue()) + "#\uffff";
    }
}
