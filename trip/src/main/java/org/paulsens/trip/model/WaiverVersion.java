package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

/**
 * One published text of a {@link WaiverDocument}. Immutable and append-only: an edit publishes the NEXT number,
 * never rewrites this one, because a {@link WaiverAcceptance} names the version a person agreed to and the words
 * behind that number must stay exactly what they read. {@code sha256} is the hash of {@code bodyHtml} at publish,
 * copied onto every acceptance so the record proves which text it covers even if a row were ever hand-edited.
 */
@Value
public class WaiverVersion implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    int number;
    String title;
    String bodyHtml;
    String sha256;
    Person.Id publishedBy;
    LocalDateTime publishedAt;
    String note;

    @Builder
    @JsonCreator
    public WaiverVersion(
            @JsonProperty("number") final int number,
            @JsonProperty("title") final String title,
            @JsonProperty("bodyHtml") final String bodyHtml,
            @JsonProperty("sha256") final String sha256,
            @JsonProperty("publishedBy") final Person.Id publishedBy,
            @JsonProperty("publishedAt") final LocalDateTime publishedAt,
            @JsonProperty("note") final String note) {
        this.number = Math.max(1, number);
        this.title = (title == null) ? null : title.trim();
        this.bodyHtml = (bodyHtml == null) ? "" : bodyHtml;
        this.sha256 = sha256;
        this.publishedBy = publishedBy;
        this.publishedAt = publishedAt;
        this.note = (note == null || note.isBlank()) ? null : note.trim();
    }
}
