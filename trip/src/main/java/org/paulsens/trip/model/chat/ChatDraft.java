package org.paulsens.trip.model.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;

/**
 * One person's unsent composer state for one channel: the message text (in the stored {@code @{id}} mention
 * form, never the display form) plus references to their staged-but-unsent photos. Lives ONLY in Valkey with
 * a hard 24h TTL — the same class of convenience state as the read cursor: a lost draft costs re-typing,
 * never a message. Attachment references age out separately (each photo expires 24h after ITS upload, see
 * {@code ChatPhotoStaging}), so readers must re-validate them against the staging registry and prune.
 */
public record ChatDraft(String body, List<Ref> attachments, Instant savedAt) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    public ChatDraft {
        body = body == null ? "" : body;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** Mirrors {@code ChatPhotos.AttachmentRef}: the staged key plus the attach-dialog choices. */
    public record Ref(String key, String title, Boolean hidden) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    }

    // Ignored or Jackson stores it as a phantom "empty" field the deserializer then rejects.
    @JsonIgnore
    public boolean isEmpty() {
        return body.isBlank() && attachments.isEmpty();
    }
}
