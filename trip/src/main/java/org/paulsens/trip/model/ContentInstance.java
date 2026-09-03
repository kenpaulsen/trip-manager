package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    @JsonProperty("id")
    private String id;
    /** The page-section key this renders in, e.g. {@code home.events} or {@code home.intro}. */
    @JsonProperty("section")
    private String section;
    /**
     * The instance's heading. Editor lists always show it; on the page, CONTAINER instances render it as
     * their title (plain text gets the default heading style, markup renders verbatim -- see
     * {@code ContentRenderer.renderContainerTitle}), and hosting markup may show it for other kinds too
     * (the v1 Events section did).
     */
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
    /**
     * CONTAINER instances only: additional privileges whose holders may add/edit/reorder/delete this
     * container's CHILDREN (e.g. {@code eventAdmin} on the Events container) -- holding ANY listed
     * privilege is enough. Null/empty elsewhere and by default. Settable only by a contentAdmin --
     * {@code ContentCommands.saveContent} guards the field and silently drops names that match no stored
     * privilege (the dialog's chips flag those red while editing).
     */
    @JsonProperty("editorPrivileges")
    private List<String> editorPrivileges;
    /**
     * CONTAINER instances only: when non-empty, the ONLY template ids this container accepts as children
     * -- a per-container tightening that takes precedence over the container TEMPLATE's own allow-list.
     * Null/empty defers to the template. Settable only by a contentAdmin.
     */
    @JsonProperty("allowedChildTemplateIds")
    private List<String> allowedChildTemplateIds;

    private ContentInstance() {
    }

    /** Compatibility constructor for pre-v2 call sites: no editor privileges, no child allow-list. */
    public ContentInstance(final String id, final String section, final String title, final String templateId,
            final int templateVersion, final Map<String, String> values, final LocalDateTime eventDate,
            final int position, final int version, final LocalDateTime modified, final String modifiedBy) {
        this(id, section, title, templateId, templateVersion, values, eventDate, position, version,
                modified, modifiedBy, null, null);
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

    /**
     * A LIST view over {@link #values} for MULTI_CHOICE properties: the editor binds a checkbox menu to
     * {@code listValues[name]}, and reads and writes go straight through to the single comma-separated
     * string in {@code values} -- no second field, nothing to keep in sync, and a row's JSON shape is
     * unchanged. Computed on each call (never a field), so nothing extra rides the serialized instance.
     */
    @JsonIgnore
    public Map<String, List<String>> getListValues() {
        return new ListValuesView();
    }

    /** The stored form of a list value split back into its items; null/blank = empty. Tolerates whitespace. */
    public static List<String> splitList(final String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split("[,\\s]+"))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .distinct()
                .toList();
    }

    /** The list form joined back into the stored string (empty list = the value is removed). */
    public static String joinList(final List<String> items) {
        return items == null ? "" : items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(","));
    }

    /**
     * The map view {@link #getListValues()} answers; {@code put} writes through into {@link #values}.
     * Serializable only to satisfy the model-package ratchet -- it is computed per call and never stored.
     */
    private final class ListValuesView extends AbstractMap<String, List<String>> implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

        @Override
        public List<String> get(final Object key) {
            return key == null ? List.of() : splitList(getValues().get(key.toString()));
        }

        @Override
        public List<String> put(final String key, final List<String> items) {
            final List<String> before = get(key);
            final String joined = joinList(items);
            if (joined.isEmpty()) {
                getValues().remove(key);
            } else {
                getValues().put(key, joined);
            }
            return before;
        }

        @Override
        public boolean containsKey(final Object key) {
            return key != null && getValues().containsKey(key.toString());
        }

        @Override
        public Set<Entry<String, List<String>>> entrySet() {
            final Set<Entry<String, List<String>>> entries = new LinkedHashSet<>();
            for (final Map.Entry<String, String> entry : getValues().entrySet()) {
                entries.add(Map.entry(entry.getKey(), splitList(entry.getValue())));
            }
            return entries;
        }
    }

    /** Whether this instance should render on public pages right now. */
    @JsonIgnore
    public boolean isVisibleAt(final LocalDateTime now) {
        return eventDate == null || !now.isAfter(eventDate);
    }

    /** @return a copy safe to hand to the edit dialog without aliasing the cached instance. */
    public ContentInstance copy() {
        return new ContentInstance(id, section, title, templateId, templateVersion,
                new HashMap<>(getValues()), eventDate, position, version, modified, modifiedBy,
                editorPrivileges == null ? null : new ArrayList<>(editorPrivileges),
                allowedChildTemplateIds == null ? null : new ArrayList<>(allowedChildTemplateIds));
    }
}
