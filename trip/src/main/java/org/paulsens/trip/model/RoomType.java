package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * A kind of room an {@link Accommodation} offers ("Single", "Two Queen", "Suite"): the capacity band and the
 * gallery. Lives INLINE on the accommodation row (never its own table), so a {@link Room#getRoomTypeId()} can
 * never dangle across tables. {@code photoIds} are media-table ids in the accommodation's slot, in display
 * order; a missing id (deleted in the media library) is skipped on read and pruned on the next save.
 */
@Data
public final class RoomType implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** Default capacity when nothing was entered: a two-person room is the common case. */
    static final int DEFAULT_MAX_PEOPLE = 2;

    private String id;
    private String name;
    private String description;
    private List<String> photoIds;
    private int minPeople;
    private int maxPeople;

    @Builder
    @JsonCreator
    public RoomType(
            @JsonProperty("id") final String id,
            @JsonProperty("name") final String name,
            @JsonProperty("description") final String description,
            @JsonProperty("photoIds") final List<String> photoIds,
            @JsonProperty("minPeople") final int minPeople,
            @JsonProperty("maxPeople") final int maxPeople) {
        this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        this.name = (name == null) ? null : name.trim();
        this.description = description;
        this.photoIds = (photoIds == null) ? new ArrayList<>() : new ArrayList<>(photoIds);
        this.minPeople = Math.max(1, minPeople);
        final int max = (maxPeople < 1) ? DEFAULT_MAX_PEOPLE : maxPeople;
        this.maxPeople = Math.max(this.minPeople, max);
    }

    public RoomType() {
        this(null, null, null, null, 0, 0);
    }

    /** True when {@code occupants} is within this type's capacity band. */
    @JsonIgnore
    public boolean fits(final int occupants) {
        return occupants >= minPeople && occupants <= maxPeople;
    }

    /** "Two Queen (1-2)" for pickers. */
    @JsonIgnore
    public String getDisplayLabel() {
        return name + " (" + minPeople + "-" + maxPeople + ")";
    }
}
