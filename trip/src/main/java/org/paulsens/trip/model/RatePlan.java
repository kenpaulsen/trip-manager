package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.Value;

/**
 * A hotel-owned pricing template: what the hotel charges ({@code cost}, plus {@code costExtras}) and, when an
 * internal admin has set it, what a participant pays ({@code price}) for some of its room types over a date
 * range. A row in {@code lodging_rate_plans} (PK accommodationId, SK id), so it is the HOTEL's, not a trip's,
 * and it may span trips of several organizations.
 *
 * <p>A trip takes a plan by copying its schedules onto a {@link ReservationOffer} that remembers
 * {@code ratePlanId}/{@code ratePlanVersion}; {@code linkedTripIds} is the reverse index that lets a plan edit
 * fan out to every linked offer (write-through, never a read-time join). A plan carries PRICING only: minimum
 * nights, default stay, policy and cancellation fee stay on the trip's option, because those are the trip's
 * terms. {@code retired} is a Boolean so a builder that never mentions it produces a live plan.
 */
@Data
public final class RatePlan implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private Id id;
    private Accommodation.Id accommodationId;
    private String name;
    private List<String> roomTypeIds;
    private LocalDate validFrom;
    private LocalDate validUntil;
    /** What the hotel charges; null = not stated yet. */
    private RateSchedule cost;
    /** What a participant pays; null = needs participant pricing (internal admins set it). */
    private RateSchedule price;
    private List<CostExtra> costExtras;
    private String notes;
    private List<String> linkedTripIds;
    private Boolean retired;
    private Person.Id createdBy;
    private LocalDateTime created;
    private long version;

    @Builder
    @JsonCreator
    public RatePlan(
            @JsonProperty("id") final Id id,
            @JsonProperty("accommodationId") final Accommodation.Id accommodationId,
            @JsonProperty("name") final String name,
            @JsonProperty("roomTypeIds") final List<String> roomTypeIds,
            @JsonProperty("validFrom") final LocalDate validFrom,
            @JsonProperty("validUntil") final LocalDate validUntil,
            @JsonProperty("cost") final RateSchedule cost,
            @JsonProperty("price") final RateSchedule price,
            @JsonProperty("costExtras") final List<CostExtra> costExtras,
            @JsonProperty("notes") final String notes,
            @JsonProperty("linkedTripIds") final List<String> linkedTripIds,
            @JsonProperty("retired") final Boolean retired,
            @JsonProperty("createdBy") final Person.Id createdBy,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("version") final long version) {
        this.id = (id == null) ? Id.newInstance() : id;
        this.accommodationId = accommodationId;
        this.name = (name == null) ? null : name.trim();
        this.roomTypeIds = (roomTypeIds == null) ? new ArrayList<>() : new ArrayList<>(roomTypeIds);
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.cost = cost;
        this.price = price;
        this.costExtras = (costExtras == null) ? new ArrayList<>() : new ArrayList<>(costExtras);
        this.notes = notes;
        this.linkedTripIds = new ArrayList<>();
        if (linkedTripIds != null) {
            for (final String tripId : linkedTripIds) {
                link(tripId);
            }
        }
        this.retired = retired;
        this.createdBy = createdBy;
        this.created = created;
        this.version = version;
    }

    public RatePlan() {
        this(null, null, null, null, null, null, null, null, null, null, null, null, null, null, 0L);
    }

    /** Whether a participant price has been set (an offer taken from an unpriced plan is itself unpriced). */
    @JsonIgnore
    public boolean isPriced() {
        return price != null;
    }

    /**
     * No {@code @JsonIgnore} here: it would drop the whole {@code retired} property, Lombok's {@code getRetired()}
     * included. Jackson prefers the get-getter over an is-getter of the same name, so the Boolean is what persists.
     */
    public boolean isRetired() {
        return Boolean.TRUE.equals(retired);
    }

    /**
     * Whether the plan's validity spans the stay {@code [from, to]} (calendar dates; an unset bound on either
     * side is open). Missing stay dates never fit.
     */
    @JsonIgnore
    public boolean covers(final LocalDate from, final LocalDate to) {
        if (from == null || to == null) {
            return false;
        }
        return (validFrom == null || !from.isBefore(validFrom)) && (validUntil == null || !to.isAfter(validUntil));
    }

    /** Records that a trip holds an offer copied from this plan; false when it already did (or the id is blank). */
    public boolean link(final String tripId) {
        if (tripId == null || tripId.isBlank() || linkedTripIds.contains(tripId)) {
            return false;
        }
        return linkedTripIds.add(tripId);
    }

    /** Forgets a trip; false when it was not linked. */
    public boolean unlink(final String tripId) {
        return tripId != null && linkedTripIds.remove(tripId);
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
