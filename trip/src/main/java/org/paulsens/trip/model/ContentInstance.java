package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * A filled-in {@link ContentTemplate} placed in a page section: the Events list, the Introduction, and any
 * future template-driven spot. {@link #templateVersion} pins the template version the values were authored
 * against, so a later template edit cannot silently reshape published content -- the instance keeps
 * rendering its own version until someone re-saves it.
 *
 * <p>Mutable ({@code @Data}) because the content dialog edits it in place. Like {@code ContentTemplate},
 * this is one version; {@code ContentRecord} carries the undo history.
 */
@Data
@AllArgsConstructor
public final class ContentInstance implements Serializable {

    @JsonProperty("id")
    private String id;
    /** The page-section key this renders in, e.g. {@code home.events} or {@code home.intro}. */
    @JsonProperty("section")
    private String section;
    /** Admin-facing label ("Aug Peace Mass"); never rendered publicly. */
    @JsonProperty("title")
    private String title;
    @JsonProperty("templateId")
    private String templateId;
    @JsonProperty("templateVersion")
    private int templateVersion;
    /** Placeholder name to raw (unrendered) value. */
    @JsonProperty("values")
    private Map<String, String> values;
    /** When set, the instance stops rendering publicly once this moment passes. Null = never expires. */
    @JsonProperty("eventDate")
    private LocalDateTime eventDate;
    /** Display order within the section; lower first. */
    @JsonProperty("position")
    private int position;
    /** The instance's own undo version; assigned by the DAO on save. */
    @JsonProperty("version")
    private int version;
    @JsonProperty("modified")
    private LocalDateTime modified;
    @JsonProperty("modifiedBy")
    private String modifiedBy;

    private ContentInstance() {
    }

    public Map<String, String> getValues() {
        if (values == null) {
            values = new HashMap<>();
        }
        return values;
    }

    public void setValues(final Map<String, String> values) {
        this.values = new HashMap<>(values);
    }

    /** Whether this instance should render on public pages right now. */
    @JsonIgnore
    public boolean isVisibleAt(final LocalDateTime now) {
        return eventDate == null || !now.isAfter(eventDate);
    }

    /** @return a copy safe to hand to the edit dialog without aliasing the cached instance. */
    public ContentInstance copy() {
        return new ContentInstance(id, section, title, templateId, templateVersion,
                new HashMap<>(getValues()), eventDate, position, version, modified, modifiedBy);
    }
}
