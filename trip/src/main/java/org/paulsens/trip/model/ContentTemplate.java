package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    private ContentTemplate() {
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
        return new ContentTemplate(id, version, name, description, body, copies, modified, modifiedBy);
    }
}
