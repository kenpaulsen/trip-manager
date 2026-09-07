package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One physical room of an {@link Accommodation}, inline on its row. {@code notes} are shown to guests ("view
 * of the mountain"); {@code adminNotes} only to whoever assigns rooms ("creaky floor, avoid for light
 * sleepers"). {@code mapRegion} is where the room sits on its floor's plan image, or null when unmapped.
 *
 * <p>Room ids are stable UUIDs so a reservation's {@code roomId} survives renumbering, and so a later move of
 * rooms into their own table is a data move rather than a redesign.
 */
@Data
public final class Room implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String id;
    private String roomTypeId;
    private String roomNumber;
    private String floor;
    private String notes;
    private String adminNotes;
    private MapRegion mapRegion;

    @Builder
    @JsonCreator
    public Room(
            @JsonProperty("id") final String id,
            @JsonProperty("roomTypeId") final String roomTypeId,
            @JsonProperty("roomNumber") final String roomNumber,
            @JsonProperty("floor") final String floor,
            @JsonProperty("notes") final String notes,
            @JsonProperty("adminNotes") final String adminNotes,
            @JsonProperty("mapRegion") final MapRegion mapRegion) {
        this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        this.roomTypeId = roomTypeId;
        this.roomNumber = (roomNumber == null) ? null : roomNumber.trim();
        this.floor = (floor == null) ? null : floor.trim();
        this.notes = notes;
        this.adminNotes = adminNotes;
        this.mapRegion = mapRegion;
    }

    public Room() {
        this(null, null, null, null, null, null, null);
    }

    @JsonIgnore
    public boolean isMapped() {
        return mapRegion != null;
    }

    /**
     * Where a room sits on its floor plan, as PERCENTAGES of the image's width and height (0..100) so the same
     * region renders correctly over any rendition of the image. Only rectangles exist today; {@code kind}
     * ("rect") is the seam that lets polygons arrive additively.
     *
     * <p>A mutable no-invariant POJO on purpose (the {@link BadgeImage} reasoning): the session codec runs no
     * constructor, and plain setter-written fields have nothing to lose.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static final class MapRegion implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

        public static final String RECT = "rect";

        private String kind;
        private double x;
        private double y;
        private double w;
        private double h;

        public static MapRegion rect(final double x, final double y, final double w, final double h) {
            return new MapRegion(RECT, x, y, w, h);
        }

        /** Inside the image and with a positive size; the command layer refuses anything else. */
        @JsonIgnore
        public boolean isValid() {
            return w > 0 && h > 0 && x >= 0 && y >= 0 && x + w <= 100.0 && y + h <= 100.0;
        }
    }
}
