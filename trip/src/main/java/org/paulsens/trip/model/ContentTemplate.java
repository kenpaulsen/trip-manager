package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * One version of a reusable content template: an HTML body containing <code>{{name}}</code> tokens plus the
 * {@link Placeholder} declarations that describe them. A template never renders alone -- a
 * {@link ContentInstance} pairs it with values and a page section.
 *
 * <p>This class is one <em>version</em>, not the whole row: {@code TemplateRecord} holds the current version
 * plus the retained history for undo. {@link #version} is 1-based and only ever moves forward; a restore
 * re-saves an old snapshot as a new version. Mutable ({@code @Data}) because the template-manager dialog
 * edits it in place, like {@code TripEvent}.
 */
@Data
@AllArgsConstructor
public final class ContentTemplate implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** Stable slug identifying the template across versions, e.g. {@code youtube-video}. */
    @JsonProperty("id")
    private String id;
    /** 1-based, monotonically increasing; assigned by the DAO on save, never by the editor. */
    @JsonProperty("version")
    private int version;
    @JsonProperty("name")
    private String name;
    @JsonProperty("description")
    private String description;
    /** Raw HTML with {@code {{token}}} placeholders. Rendered unescaped -- template authors are trusted. */
    @JsonProperty("body")
    private String body;
    @JsonProperty("placeholders")
    private List<Placeholder> placeholders;
    @JsonProperty("modified")
    private LocalDateTime modified;
    @JsonProperty("modifiedBy")
    private String modifiedBy;
    /** Null on rows written before v2; {@link #getKind()} folds that to {@link TemplateKind#STANDARD}. */
    @JsonProperty("kind")
    private TemplateKind kind;
    /** CONTAINER only: template ids instances may add as children. Null/empty = any non-container. */
    @JsonProperty("allowedChildTemplateIds")
    private List<String> allowedChildTemplateIds;
    /** CONTAINER only: most children an instance may hold. Null = unlimited. */
    @JsonProperty("maxChildren")
    private Integer maxChildren;
    /** PROGRAMMATIC only: the registered {@code ProgrammaticContentTemplate} type id. */
    @JsonProperty("programmaticTypeId")
    private String programmaticTypeId;
    /**
     * The organization whose site may author content on this template, or null for a SITE-LEVEL template
     * every site shares. Sharing is an AUTHORING-time choice: the Add dialog offers an org site its own
     * templates plus the shared ones, and a shared site only the shared ones; an instance then pins
     * whichever it chose, so rendering never consults this field (the zero-live-read render path is
     * untouched). Null on every row written before org sites existed.
     */
    @JsonProperty("orgId")
    private String orgId;

    private ContentTemplate() {
    }

    /**
     * Compatibility constructor for pre-v2 call sites and rows: a template without kind fields is a
     * STANDARD body-and-placeholders template.
     */
    public ContentTemplate(final String id, final int version, final String name, final String description,
            final String body, final List<Placeholder> placeholders, final LocalDateTime modified,
            final String modifiedBy) {
        this(id, version, name, description, body, placeholders, modified, modifiedBy,
                null, null, null, null);
    }

    /** The v2 shape without an owning organization: a site-level template (the pre-org-sites rows). */
    public ContentTemplate(final String id, final int version, final String name, final String description,
            final String body, final List<Placeholder> placeholders, final LocalDateTime modified,
            final String modifiedBy, final TemplateKind kind, final List<String> allowedChildTemplateIds,
            final Integer maxChildren, final String programmaticTypeId) {
        this(id, version, name, description, body, placeholders, modified, modifiedBy, kind,
                allowedChildTemplateIds, maxChildren, programmaticTypeId, null);
    }

    /** Whether this template belongs to one organization's site rather than to every site. */
    @JsonIgnore
    public boolean isOrgOwned() {
        return orgId != null && !orgId.isBlank();
    }

    /** The template's kind; rows written before v2 have none and read as {@link TemplateKind#STANDARD}. */
    public TemplateKind getKind() {
        return kind == null ? TemplateKind.STANDARD : kind;
    }

    /** STANDARD is stored as null (the pre-v2 shape) so equality is stable across a JSON round trip. */
    public void setKind(final TemplateKind kind) {
        this.kind = kind == TemplateKind.STANDARD ? null : kind;
    }

    public List<Placeholder> getPlaceholders() {
        if (placeholders == null) {
            placeholders = new ArrayList<>();
        }
        return placeholders;
    }

    public void setPlaceholders(final List<Placeholder> placeholders) {
        this.placeholders = new ArrayList<>(placeholders);
    }

    /** @return a deep copy for dialog editing -- placeholders are mutable rows and must not be aliased. */
    public ContentTemplate copy() {
        final List<Placeholder> copies = new ArrayList<>(getPlaceholders().size());
        getPlaceholders().forEach(ph -> copies.add(ph.copy()));
        return new ContentTemplate(id, version, name, description, body, copies, modified, modifiedBy,
                kind, allowedChildTemplateIds == null ? null : new ArrayList<>(allowedChildTemplateIds),
                maxChildren, programmaticTypeId, orgId);
    }
}
