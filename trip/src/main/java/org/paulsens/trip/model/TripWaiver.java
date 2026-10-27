package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * One document a trip requires, pinned to a version of its organization's {@link WaiverDocument}. The trip
 * administrator chooses the version (owner decision 2026-10-27): publishing a new org version changes nothing
 * here until somebody moves the pin, and moving it is when they decide {@link #acceptOlder} -- whether a
 * signature of an earlier version still counts (the default) or everyone must sign again.
 *
 * <p>Stored inline on {@link Trip#getWaivers()}. A plain mutable bean with primitives only, because a Trip
 * never sits in a session but the trip editor's draft copies it, and Kryo runs no constructor.
 */
@Data
public final class TripWaiver implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String waiverId;
    private int version;
    private boolean acceptOlder;
    private Person.Id addedBy;
    private LocalDateTime added;

    @Builder
    @JsonCreator
    public TripWaiver(
            @JsonProperty("waiverId") final String waiverId,
            @JsonProperty("version") final int version,
            @JsonProperty("acceptOlder") final Boolean acceptOlder,
            @JsonProperty("addedBy") final Person.Id addedBy,
            @JsonProperty("added") final LocalDateTime added) {
        this.waiverId = waiverId;
        this.version = Math.max(1, version);
        // Absent reads as accepting: it is the default the owner chose, and the safe one for a hand-written row.
        this.acceptOlder = acceptOlder == null || acceptOlder;
        this.addedBy = addedBy;
        this.added = added;
    }

    public TripWaiver() {
        this(null, 1, null, null, null);
    }
}
