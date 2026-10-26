package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

/**
 * One nightly rate and how it is charged: the flat rate, per-date overrides, the single-room supplement and
 * the {@link PricingModel}. A {@link ReservationOffer} carries up to two of these -- what the participant
 * PAYS ({@code price}) and what the hotel CHARGES ({@code cost}) -- and a {@link RatePlan} carries the same
 * pair as a hotel-owned template. The schedule knows nothing about which side it sits on; the field it sits
 * in IS the side.
 *
 * <p>Money is long cents ({@code pay/MoneyMath}); {@code overrides} is keyed by ISO date ({@code "2026-09-14"})
 * so the JSON stays greppable and needs no jsr310 map-key serializer. Empty means every night costs
 * {@code nightlyCents}. The constructor folds nulls (so Lombok's zeroing builder is harmless) and clamps
 * money at zero; the hand-written setters keep those invariants for the legacy write path on the offer.
 */
@Data
public final class RateSchedule implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private PricingModel pricingModel;
    private long nightlyCents;
    private Map<String, Long> overrides;
    private long singleSupplementCents;

    @Builder
    @JsonCreator
    public RateSchedule(
            @JsonProperty("pricingModel") final PricingModel pricingModel,
            @JsonProperty("nightlyCents") final long nightlyCents,
            @JsonProperty("overrides") final Map<String, Long> overrides,
            @JsonProperty("singleSupplementCents") final long singleSupplementCents) {
        this.pricingModel = (pricingModel == null) ? PricingModel.PER_ROOM : pricingModel;
        this.nightlyCents = Math.max(0L, nightlyCents);
        this.overrides = (overrides == null) ? new HashMap<>() : new HashMap<>(overrides);
        this.singleSupplementCents = Math.max(0L, singleSupplementCents);
    }

    public RateSchedule() {
        this(null, 0L, null, 0L);
    }

    /** The rate for one night: the per-date override when one exists, else the flat rate. */
    @JsonIgnore
    public long nightlyCents(final LocalDate night) {
        final Long override = (night == null) ? null : overrides.get(night.toString());
        return (override == null) ? nightlyCents : Math.max(0L, override);
    }

    @JsonIgnore
    public boolean isPerPerson() {
        return pricingModel == PricingModel.PER_PERSON;
    }

    /** Whether any night is priced differently from the flat rate. */
    @JsonIgnore
    public boolean hasOverrides() {
        return !overrides.isEmpty();
    }

    public void setPricingModel(final PricingModel pricingModel) {
        this.pricingModel = (pricingModel == null) ? PricingModel.PER_ROOM : pricingModel;
    }

    public void setNightlyCents(final long nightlyCents) {
        this.nightlyCents = Math.max(0L, nightlyCents);
    }

    /** Copies the map: the caller's map (a dialog form's, say) must not become the stored one. */
    public void setOverrides(final Map<String, Long> overrides) {
        this.overrides = (overrides == null) ? new HashMap<>() : new HashMap<>(overrides);
    }

    public void setSingleSupplementCents(final long singleSupplementCents) {
        this.singleSupplementCents = Math.max(0L, singleSupplementCents);
    }
}
