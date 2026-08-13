package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One custom itinerary-badge picture a trip's managers uploaded: the managed-media object key that holds the
 * processed square JPEG, and the label the badge page's image dropdown shows for it. The list of these on
 * {@link Trip} IS the inventory — there are no media-table rows, and nothing is derived from an S3 listing —
 * so an image exists for exactly one trip, and deleting the entry (plus its object) is the whole delete.
 *
 * <p>Deliberately a mutable no-invariant POJO rather than a record: instances ride inside the {@code Trip}
 * held in viewScope, and the session codec (Kryo) reconstructs classes without running any constructor —
 * constructor-established state comes back zeroed, silently (see {@code DynamicResourceMap} for the incident
 * that taught this). Plain fields written by setters have nothing to lose.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class BadgeImage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The object key under the managed-media bucket, e.g. {@code badgeImages/{tripId}/{version}.jpg}. */
    @JsonProperty("key")
    private String key;

    /** What the badge page's image dropdown calls this picture. */
    @JsonProperty("label")
    private String label;
}
