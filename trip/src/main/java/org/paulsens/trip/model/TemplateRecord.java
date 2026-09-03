package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import lombok.Value;

/**
 * The {@code templates} table row: the current {@link ContentTemplate} plus up to <em>n</em> previous
 * versions (newest first) retained for undo. History lives inside the row rather than as separate versioned
 * rows so the table keeps the standard one-JSON-blob shape and a save stays a single write.
 */
@Value
public class TemplateRecord implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    String id;
    ContentTemplate current;
    /** Previous versions, newest first, already trimmed to the retention count by the DAO. */
    List<ContentTemplate> previous;

    @JsonCreator
    public TemplateRecord(
            @JsonProperty("id") final String id,
            @JsonProperty("current") final ContentTemplate current,
            @JsonProperty("previous") final List<ContentTemplate> previous) {
        this.id = id;
        this.current = current;
        this.previous = previous == null ? List.of() : List.copyOf(previous);
    }

    /** @return every retained version, current first. */
    @JsonIgnore
    public List<ContentTemplate> getAllVersions() {
        final List<ContentTemplate> all = new ArrayList<>(previous.size() + 1);
        Stream.concat(Stream.ofNullable(current), previous.stream()).forEach(all::add);
        return all;
    }

    /** @return the retained version with the given number, or null when it has aged out. */
    @JsonIgnore
    public ContentTemplate findVersion(final int version) {
        return getAllVersions().stream().filter(t -> t.getVersion() == version).findFirst().orElse(null);
    }
}
