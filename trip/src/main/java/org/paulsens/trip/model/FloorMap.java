package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Which media-library image is the plan of one floor of an {@link Accommodation}. The image itself is a
 * {@link MediaItem} in the accommodation's slot (so the media manager can manage it); this entry is what says
 * "that picture is floor 2". A floor with no entry simply has no plan yet.
 *
 * <p>Mutable no-invariant POJO (the {@link BadgeImage} reasoning).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class FloorMap implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    @JsonProperty("floor")
    private String floor;

    @JsonProperty("mediaId")
    private String mediaId;
}
