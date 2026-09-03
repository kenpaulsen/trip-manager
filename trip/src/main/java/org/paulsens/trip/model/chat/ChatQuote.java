package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import lombok.Value;
import org.paulsens.trip.model.Person;

/**
 * Snapshot of a replied-to message. Snapshots rather than live lookups so (1) the original can age out or be
 * tombstoned without breaking the reply, (2) a page stays one ZSET+HMGET, and (3) later edits of the original
 * do not rewrite conversation meaning.
 */
@Value
public class ChatQuote implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    public static final int MAX_SNIPPET_CODE_POINTS = 160;

    ChatMessage.Id messageId;
    Person.Id authorId;
    String authorName;
    String snippet;
    boolean snippetTruncated;
    /** Reserved: e.g. {@code image} → camera glyph. */
    String attachmentKind;

    @JsonCreator
    public ChatQuote(
            @JsonProperty("messageId") final ChatMessage.Id messageId,
            @JsonProperty("authorId") final Person.Id authorId,
            @JsonProperty("authorName") final String authorName,
            @JsonProperty("snippet") final String snippet,
            @JsonProperty("snippetTruncated") final Boolean snippetTruncated,
            @JsonProperty("attachmentKind") final String attachmentKind) {
        this.messageId = messageId;
        this.authorId = authorId;
        this.authorName = authorName;
        this.snippet = snippet == null ? "" : snippet;
        this.snippetTruncated = snippetTruncated != null && snippetTruncated;
        this.attachmentKind = attachmentKind;
    }

    /** Builds a snapshot, truncating the body to {@link #MAX_SNIPPET_CODE_POINTS} code points. */
    public static ChatQuote from(final ChatMessage original, final String authorDisplayName) {
        if (original == null) {
            throw new IllegalArgumentException("original message is required");
        }
        final String body = original.getBody() == null ? "" : original.getBody();
        final int cps = body.codePointCount(0, body.length());
        final boolean truncated = cps > MAX_SNIPPET_CODE_POINTS;
        final String snippet = truncated
                ? body.substring(0, body.offsetByCodePoints(0, MAX_SNIPPET_CODE_POINTS))
                : body;
        return new ChatQuote(original.getId(), original.getAuthorId(), authorDisplayName, snippet, truncated, null);
    }
}
