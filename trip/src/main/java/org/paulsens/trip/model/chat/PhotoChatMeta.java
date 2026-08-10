package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import lombok.Value;

/**
 * Cheap per-photo summary: how many comments a photo's thread holds and the reactions on the photo itself.
 * This is the value cached per s3Key for badge rendering and the batch-meta endpoint, and the unit folded
 * into a parent message's reaction summary (photo reactions count toward the message that carried the photo).
 */
@Value
public class PhotoChatMeta implements Serializable {

    /**
     * The synthetic reaction target standing for the photo itself. All zeros so it sorts below every real
     * millis-based message id (reaction range scans stay correct) and, like any id, contains no {@code '#'}
     * (the reaction sort-key delimiter).
     */
    public static final ChatMessage.Id PHOTO_ROOT = ChatMessage.Id.of(0L);

    int commentCount;
    ChatReactionSummary rootReactions;

    @JsonCreator
    public PhotoChatMeta(
            @JsonProperty("commentCount") final Integer commentCount,
            @JsonProperty("rootReactions") final ChatReactionSummary rootReactions) {
        this.commentCount = commentCount == null ? 0 : commentCount;
        this.rootReactions = rootReactions == null ? ChatReactionSummary.empty(PHOTO_ROOT) : rootReactions;
    }

    public static PhotoChatMeta empty() {
        return new PhotoChatMeta(0, null);
    }
}
