package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * A document an organization asks travelers to agree to -- a liability waiver, a code of conduct, a photo
 * release ("waiver" is used loosely). ONE row per document in {@code org_waivers} (PK orgId, SK id), so the
 * partition IS the tenancy boundary: a trip resolves its documents as {@code (trip.orgId, waiverId)} and another
 * org's id simply misses.
 *
 * <p>Every published text is kept inline in {@link #versions}, oldest first, and never pruned: a signature names
 * a version, so the words behind it must outlive any edit. (The content-template history ages old versions out,
 * which is why it is not reused here.) {@link #version} is the row's optimistic-lock counter, unrelated to the
 * document's version numbers. See {@code docs/waivers.md}.
 */
@Data
public final class WaiverDocument implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String id;
    private String orgId;
    private String title;
    private boolean retired;
    private List<WaiverVersion> versions;
    private Person.Id createdBy;
    private LocalDateTime created;
    private long version;

    @Builder
    @JsonCreator
    public WaiverDocument(
            @JsonProperty("id") final String id,
            @JsonProperty("orgId") final String orgId,
            @JsonProperty("title") final String title,
            @JsonProperty("retired") final boolean retired,
            @JsonProperty("versions") final List<WaiverVersion> versions,
            @JsonProperty("createdBy") final Person.Id createdBy,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("version") final long version) {
        this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        this.orgId = orgId;
        this.title = (title == null) ? null : title.trim();
        this.retired = retired;
        this.versions = (versions == null) ? new ArrayList<>() : new ArrayList<>(versions);
        this.createdBy = createdBy;
        this.created = created;
        this.version = version;
    }

    public WaiverDocument() {
        this(null, null, null, false, null, null, null, 0L);
    }

    /** The newest published version; null for a document with none (never stored that way). */
    @JsonIgnore
    public WaiverVersion getCurrent() {
        return versions.isEmpty() ? null : versions.get(versions.size() - 1);
    }

    /** The newest version's number, 0 when there is none. */
    @JsonIgnore
    public int getCurrentNumber() {
        final WaiverVersion current = getCurrent();
        return (current == null) ? 0 : current.getNumber();
    }

    /** The version with this number, or null. */
    public WaiverVersion version(final int number) {
        for (final WaiverVersion v : versions) {
            if (v.getNumber() == number) {
                return v;
            }
        }
        return null;
    }
}
