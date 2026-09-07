package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.Value;

/**
 * An agreement that one or more people take a {@link ReservationOffer} for a specific stay: who, from when to
 * when (to the minute), and, once assigned, which {@link Room}. A row in {@code lodging_reservations} (PK
 * tripId, SK id).
 *
 * <p>One reservation = one date range. A late arriver sharing a room with the group is her OWN reservation on
 * the same room; occupancy and pricing look across every active reservation on a room night by night.
 * Room assignment lives HERE ({@code roomId}), which is what the rooming list, the itinerary and the legacy
 * {@code getRoomPDV} compatibility read derive from.
 *
 * <p>Cancelling never deletes: the row flips to {@link Status#CANCELLED} and records the fee and whether a
 * credit was written, because its Bill rows stay in the ledger and the audit trail points at it.
 */
@Data
public final class Reservation implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private Id id;
    private String tripId;
    private String orgId;
    private ReservationOffer.Id offerId;
    private Accommodation.Id accommodationId;
    private List<Person.Id> occupants;
    private LocalDateTime start;
    private LocalDateTime end;
    private String roomId;
    private Status status;
    private String notes;
    /** Admin concession: no single supplement even on nights this reservation's person is alone. */
    private Boolean waiveSingleSupplement;
    private Person.Id createdBy;
    private LocalDateTime created;
    private LocalDateTime cancelledAt;
    private Person.Id cancelledBy;
    private Long cancelFeeCents;
    private Boolean credited;
    private String cancelReason;
    private long version;

    @Builder
    @JsonCreator
    public Reservation(
            @JsonProperty("id") final Id id,
            @JsonProperty("tripId") final String tripId,
            @JsonProperty("orgId") final String orgId,
            @JsonProperty("offerId") final ReservationOffer.Id offerId,
            @JsonProperty("accommodationId") final Accommodation.Id accommodationId,
            @JsonProperty("occupants") final List<Person.Id> occupants,
            @JsonProperty("start") final LocalDateTime start,
            @JsonProperty("end") final LocalDateTime end,
            @JsonProperty("roomId") final String roomId,
            @JsonProperty("status") final Status status,
            @JsonProperty("notes") final String notes,
            @JsonProperty("waiveSingleSupplement") final Boolean waiveSingleSupplement,
            @JsonProperty("createdBy") final Person.Id createdBy,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("cancelledAt") final LocalDateTime cancelledAt,
            @JsonProperty("cancelledBy") final Person.Id cancelledBy,
            @JsonProperty("cancelFeeCents") final Long cancelFeeCents,
            @JsonProperty("credited") final Boolean credited,
            @JsonProperty("cancelReason") final String cancelReason,
            @JsonProperty("version") final long version) {
        this.id = (id == null) ? Id.newInstance() : id;
        this.tripId = tripId;
        this.orgId = orgId;
        this.offerId = offerId;
        this.accommodationId = accommodationId;
        this.occupants = (occupants == null) ? new ArrayList<>() : new ArrayList<>(occupants);
        this.start = start;
        this.end = end;
        this.roomId = (roomId == null || roomId.isBlank()) ? null : roomId;
        this.status = (status == null) ? Status.ACTIVE : status;
        this.notes = notes;
        this.waiveSingleSupplement = waiveSingleSupplement;
        this.createdBy = createdBy;
        this.created = created;
        this.cancelledAt = cancelledAt;
        this.cancelledBy = cancelledBy;
        this.cancelFeeCents = cancelFeeCents;
        this.credited = credited;
        this.cancelReason = cancelReason;
        this.version = version;
    }

    public Reservation() {
        this(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, 0L);
    }

    @JsonIgnore
    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    @JsonIgnore
    public boolean isAssigned() {
        return roomId != null;
    }

    @JsonIgnore
    public boolean occupies(final Person.Id personId) {
        return personId != null && occupants.contains(personId);
    }

    @JsonIgnore
    public boolean isSupplementWaived() {
        return Boolean.TRUE.equals(waiveSingleSupplement);
    }

    /** Nights of this stay: see {@link #nightsBetween(LocalDateTime, LocalDateTime)}. */
    @JsonIgnore
    public int nights() {
        return nightsBetween(start, end);
    }

    /**
     * THE night rule, shared with pricing: a night is a calendar date {@code d} with
     * {@code start.date <= d < end.date}. Times are informational (a 23:30 check-in still counts that date; a
     * 02:00 check-out does not count the check-out date); same date or end before start is zero nights.
     * Deliberately not the 4 a.m. fudge of {@code TripCommands.getLodgingDays}, which is display heuristics.
     */
    public static int nightsBetween(final LocalDateTime start, final LocalDateTime end) {
        if (start == null || end == null) {
            return 0;
        }
        final long nights = ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate());
        return (int) Math.max(0L, nights);
    }

    /** Occupants ordered by id value: the deterministic order every equal split hands its remainder cents to. */
    @JsonIgnore
    public List<Person.Id> sortedOccupants() {
        final List<Person.Id> sorted = new ArrayList<>(occupants);
        sorted.sort(Comparator.comparing(Person.Id::getValue));
        return sorted;
    }

    public enum Status {
        ACTIVE, CANCELLED
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
