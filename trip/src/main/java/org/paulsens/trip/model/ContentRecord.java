package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import lombok.Value;

/**
 * The {@code content} table row: the current {@link ContentInstance} plus up to <em>n</em> previous versions
 * (newest first) retained for undo. Same shape and rationale as {@link TemplateRecord}.
 */
@Value
public class ContentRecord implements Serializable {

    String id;
    ContentInstance current;
    /** Previous versions, newest first, already trimmed to the retention count by the DAO. */
    List<ContentInstance> previous;

    @JsonCreator
    public ContentRecord(
            @JsonProperty("id") final String id,
            @JsonProperty("current") final ContentInstance current,
            @JsonProperty("previous") final List<ContentInstance> previous) {
        this.id = id;
        this.current = current;
        this.previous = previous == null ? List.of() : List.copyOf(previous);
    }

    /** @return every retained version, current first. */
    @JsonIgnore
    public List<ContentInstance> getAllVersions() {
        final List<ContentInstance> all = new ArrayList<>(previous.size() + 1);
        Stream.concat(Stream.ofNullable(current), previous.stream()).forEach(all::add);
        return all;
    }

    /** @return the retained version with the given number, or null when it has aged out. */
    @JsonIgnore
    public ContentInstance findVersion(final int version) {
        return getAllVersions().stream().filter(c -> c.getVersion() == version).findFirst().orElse(null);
    }
}
