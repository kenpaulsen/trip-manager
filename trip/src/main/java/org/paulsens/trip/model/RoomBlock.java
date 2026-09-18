package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.Value;

/**
 * Nights on which rooms of an {@link Accommodation} are NOT available to us, for a reason that has nothing to
 * do with our reservations: another group holds them, or they are under maintenance. A row in
 * {@code lodging_blocks} (PK accommodationId, SK id).
 *
 * <p>This is the HOTEL's inventory, not a trip's: it is kept by whoever may edit the accommodation and it is
 * honored by every trip staying there. Its own table rather than a list on the accommodation row, because an
 * accommodation's row carries the room inventory under one optimistic version: a hotel manager blocking a
 * room must not lose a race with an admin renaming one.
 *
 * <p>Dates follow the stay convention: nights are {@code [start, end)}, so a block from Sep 21 to Sep 26 holds
 * five nights and the room is free again on the morning of the 26th. The reason is free text on purpose --
 * "another group", "boiler", "the owner's family" are all things a hotel says, and an enum would only be a
 * list to keep extending.
 */
@Data
public final class RoomBlock implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private Id id;
    private Accommodation.Id accommodationId;
    /** The rooms this block holds; one block can take a floor out at once. */
    private List<String> roomIds;
    private LocalDate start;
    private LocalDate end;
    private String reason;
    private Person.Id createdBy;
    private LocalDateTime created;
    private long version;

    @Builder
    @JsonCreator
    public RoomBlock(
            @JsonProperty("id") final Id id,
            @JsonProperty("accommodationId") final Accommodation.Id accommodationId,
            @JsonProperty("roomIds") final List<String> roomIds,
            @JsonProperty("start") final LocalDate start,
            @JsonProperty("end") final LocalDate end,
            @JsonProperty("reason") final String reason,
            @JsonProperty("createdBy") final Person.Id createdBy,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("version") final long version) {
        this.id = (id == null) ? Id.newInstance() : id;
        this.accommodationId = accommodationId;
        this.roomIds = (roomIds == null) ? new ArrayList<>() : new ArrayList<>(roomIds);
        this.start = start;
        this.end = end;
        this.reason = (reason == null) ? null : reason.trim();
        this.createdBy = createdBy;
        this.created = created;
        this.version = version;
    }

    public RoomBlock() {
        this(null, null, null, null, null, null, null, null, 0L);
    }

    /** How many nights the block holds; zero when the dates are missing or the end is not after the start. */
    @JsonIgnore
    public int getNights() {
        if (start == null || end == null || !end.isAfter(start)) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(start, end);
    }

    /** Whether this block holds {@code roomId} on the night of {@code night}. */
    @JsonIgnore
    public boolean covers(final String roomId, final LocalDate night) {
        return holds(roomId) && night != null && start != null && end != null
                && !night.isBefore(start) && night.isBefore(end);
    }

    /** Whether this block holds {@code roomId} at all (whatever its dates). */
    @JsonIgnore
    public boolean holds(final String roomId) {
        return roomId != null && roomIds.contains(roomId);
    }

    /**
     * Whether any night of this block falls in {@code [from, to)}. Both bounds are calendar dates; a null
     * bound is open, which is what an unpinned board window means.
     */
    @JsonIgnore
    public boolean overlapsNights(final LocalDate from, final LocalDate to) {
        if (getNights() == 0) {
            return false;
        }
        return (to == null || start.isBefore(to)) && (from == null || end.isAfter(from));
    }

    @Value
    public static class Id implements Serializable, Comparable<Id> {
        @Serial
        private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

        @JsonValue
        String value;

        public static Id from(final String id) {
            return new Id(id);
        }

        public static Id newInstance() {
            return new Id(UUID.randomUUID().toString());
        }

        @Override
        public int compareTo(final Id o) {
            return value.compareTo(o.getValue());
        }
    }
}
