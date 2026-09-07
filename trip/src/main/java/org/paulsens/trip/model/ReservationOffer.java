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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.Value;

/**
 * What a trip offers its members at one {@link Accommodation} for one {@link RoomType}: the nightly price and
 * how it is charged ({@link PricingModel}), the single-room supplement, the stay the offer defaults to, the
 * window in which it may be taken, the minimum stay, the policy text and the cancellation fee. A row in
 * {@code lodging_offers} (PK tripId, SK id), so it is org-owned through its trip.
 *
 * <p>{@code tripEventId} names the trip's LODGING {@link TripEvent} that tracks everyone staying at this
 * property; several offers (single, double, ...) usually share one event. Every {@link Reservation} of this
 * offer adds its occupants to that event.
 *
 * <p>Money is long cents ({@code pay/MoneyMath}); {@code nightlyPriceOverrides} is keyed by ISO date
 * ({@code "2026-09-14"}) so the JSON stays greppable and needs no jsr310 map-key serializer. Empty means
 * every night costs {@code nightlyPriceCents}.
 */
@Data
public final class ReservationOffer implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private Id id;
    private String tripId;
    private String orgId;
    private OfferType type;
    private String name;
    private Accommodation.Id accommodationId;
    /** The room types this option sells (several often share one price); never empty once saved. */
    private List<String> roomTypeIds;
    private String tripEventId;
    private PricingModel pricingModel;
    private long nightlyPriceCents;
    private Map<String, Long> nightlyPriceOverrides;
    private long singleSupplementCents;
    private int minNights;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    private LocalDateTime defaultStart;
    private LocalDateTime defaultEnd;
    private String policyHtml;
    private Long cancelFeeFixedCents;
    private Integer cancelFeeBps;
    /** Null or false = offered. Stored inverted so a builder that never mentions it produces a live offer. */
    private Boolean disabled;
    private Person.Id createdBy;
    private LocalDateTime created;
    private long version;

    @Builder
    @JsonCreator
    public ReservationOffer(
            @JsonProperty("id") final Id id,
            @JsonProperty("tripId") final String tripId,
            @JsonProperty("orgId") final String orgId,
            @JsonProperty("type") final OfferType type,
            @JsonProperty("name") final String name,
            @JsonProperty("accommodationId") final Accommodation.Id accommodationId,
            @JsonProperty("roomTypeId") final String roomTypeId,
            @JsonProperty("roomTypeIds") final List<String> roomTypeIds,
            @JsonProperty("tripEventId") final String tripEventId,
            @JsonProperty("pricingModel") final PricingModel pricingModel,
            @JsonProperty("nightlyPriceCents") final long nightlyPriceCents,
            @JsonProperty("nightlyPriceOverrides") final Map<String, Long> nightlyPriceOverrides,
            @JsonProperty("singleSupplementCents") final long singleSupplementCents,
            @JsonProperty("minNights") final int minNights,
            @JsonProperty("validFrom") final LocalDateTime validFrom,
            @JsonProperty("validUntil") final LocalDateTime validUntil,
            @JsonProperty("defaultStart") final LocalDateTime defaultStart,
            @JsonProperty("defaultEnd") final LocalDateTime defaultEnd,
            @JsonProperty("policyHtml") final String policyHtml,
            @JsonProperty("cancelFeeFixedCents") final Long cancelFeeFixedCents,
            @JsonProperty("cancelFeeBps") final Integer cancelFeeBps,
            @JsonProperty("disabled") final Boolean disabled,
            @JsonProperty("createdBy") final Person.Id createdBy,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("version") final long version) {
        this.id = (id == null) ? Id.newInstance() : id;
        this.tripId = tripId;
        this.orgId = orgId;
        this.type = (type == null) ? OfferType.LODGING : type;
        this.name = (name == null) ? null : name.trim();
        this.accommodationId = accommodationId;
        // roomTypeId is the single-type shape rows were first written in; it folds into the list.
        this.roomTypeIds = new ArrayList<>();
        if (roomTypeIds != null) {
            this.roomTypeIds.addAll(roomTypeIds);
        }
        if (roomTypeId != null && !roomTypeId.isBlank() && !this.roomTypeIds.contains(roomTypeId)) {
            this.roomTypeIds.add(0, roomTypeId);
        }
        this.tripEventId = tripEventId;
        this.pricingModel = (pricingModel == null) ? PricingModel.PER_ROOM : pricingModel;
        this.nightlyPriceCents = Math.max(0L, nightlyPriceCents);
        this.nightlyPriceOverrides =
                (nightlyPriceOverrides == null) ? new HashMap<>() : new HashMap<>(nightlyPriceOverrides);
        this.singleSupplementCents = Math.max(0L, singleSupplementCents);
        this.minNights = Math.max(1, minNights);
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.defaultStart = defaultStart;
        this.defaultEnd = defaultEnd;
        this.policyHtml = policyHtml;
        this.cancelFeeFixedCents = cancelFeeFixedCents;
        this.cancelFeeBps = cancelFeeBps;
        this.disabled = disabled;
        this.createdBy = createdBy;
        this.created = created;
        this.version = version;
    }

    public ReservationOffer() {
        this(null, null, null, null, null, null, null, null, null, null, 0L, null, 0L, 0, null, null, null, null,
                null, null, null, null, null, null, 0L);
    }

    /** The price of one night: the per-date override when one exists, else the flat rate. */
    @JsonIgnore
    public long nightlyPriceCents(final LocalDate night) {
        final Long override = (night == null) ? null : nightlyPriceOverrides.get(night.toString());
        return (override == null) ? nightlyPriceCents : Math.max(0L, override);
    }

    @JsonIgnore
    public boolean isPerPerson() {
        return pricingModel == PricingModel.PER_PERSON;
    }

    @JsonIgnore
    public boolean isEnabled() {
        return !Boolean.TRUE.equals(disabled);
    }

    /** Whether cancelling costs anything: a positive fixed fee or a positive percentage. */
    @JsonIgnore
    public boolean hasCancellationFee() {
        return (cancelFeeFixedCents != null && cancelFeeFixedCents > 0)
                || (cancelFeeBps != null && cancelFeeBps > 0);
    }

    /**
     * Whether a stay lies within the option's date range ({@code validFrom}..{@code validUntil}, the dates the
     * option covers; an unset bound is open). The default stay must lie within it, and so must every
     * reservation.
     */
    public boolean coversStay(final LocalDateTime start, final LocalDateTime end) {
        if (start == null || end == null) {
            return false;
        }
        return (validFrom == null || !start.isBefore(validFrom)) && (validUntil == null || !end.isAfter(validUntil));
    }

    /** Whether the option sells this room type. */
    @JsonIgnore
    public boolean covers(final String roomTypeId) {
        return roomTypeId != null && roomTypeIds.contains(roomTypeId);
    }

    /** The first room type, for the places that name ONE type (descriptions, legacy labels); null when none. */
    @JsonIgnore
    public String firstRoomTypeId() {
        return roomTypeIds.isEmpty() ? null : roomTypeIds.get(0);
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
