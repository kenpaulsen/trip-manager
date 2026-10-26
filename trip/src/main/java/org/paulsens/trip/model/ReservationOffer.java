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
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.Value;

/**
 * What a trip offers its members at one {@link Accommodation} for one or more {@link RoomType}s: the stay the
 * offer defaults to, the window in which it may be taken, the minimum stay, the policy text, the cancellation
 * fee, and TWO money schedules. A row in {@code lodging_offers} (PK tripId, SK id), so it is org-owned through
 * its trip.
 *
 * <p><b>Price vs cost.</b> {@code price} is what a participant PAYS (the bills); {@code cost} is what the hotel
 * CHARGES us (the Room Invoice), plus {@code costExtras} (taxes, fees, coupons). Either may be null: a null
 * {@code price} means "needs participant pricing" (no reservation may be taken on it); a null {@code cost}
 * means the cost is ASSUMED to equal the price ({@link #effectiveCost()}, {@link #isCostAssumed()}). When the
 * offer was copied from a hotel {@link RatePlan}, {@code ratePlanId}/{@code ratePlanVersion} say which and
 * at what version; the schedules are still the offer's own copy.
 *
 * <p><b>The fold (reading JSON).</b> Rows written before the split carried the price as four flat keys
 * ({@code pricingModel}, {@code nightlyPriceCents}, {@code nightlyPriceOverrides}, {@code singleSupplementCents}).
 * The constructor reads, in order: a {@code price} object wins; else {@code "priced": false} means unpriced;
 * else any of the four legacy keys present (every old row wrote {@code nightlyPriceCents}, so a legacy row
 * always qualifies) builds {@code price} from them -- so every existing row, a $0 one included, reads as
 * PRICED at its old numbers; else (nothing at all: a fresh builder, {@code new ReservationOffer()}) the offer
 * is UNPRICED. A builder that sets {@code nightlyPriceCents(60_00L)} therefore gets a priced offer, which is
 * what the tests and the pre-split callers rely on.
 *
 * <p><b>The mirror (writing JSON).</b> Besides {@code price}/{@code priced}, every row still writes the four
 * legacy keys as mirrors of the price side, so an OLD instance in a rolling deploy prices it correctly
 * ({@code DAO.createObjectMapper} ignores unknown properties, so without the mirror it would read $0 and
 * soft-delete every bill). The mirror comes off one release later ({@code docs/migrations/lodging-price-cost.md}).
 *
 * <p>{@code tripEventId} names the trip's LODGING {@link TripEvent} that tracks everyone staying at this
 * property; several offers (single, double, ...) usually share one event. Every {@link Reservation} of this
 * offer adds its occupants to that event.
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
    /** What a participant pays; null = needs participant pricing. */
    private RateSchedule price;
    /** What the hotel charges; null = assumed equal to the price. */
    private RateSchedule cost;
    /** Hotel-side extras (taxes, fees, coupons); never null, never on a bill. */
    private List<CostExtra> costExtras;
    /** The {@link RatePlan} this offer was copied from, when any; blank = standalone. */
    private String ratePlanId;
    private long ratePlanVersion;
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
            @JsonProperty("nightlyPriceCents") final Long nightlyPriceCents,
            @JsonProperty("nightlyPriceOverrides") final Map<String, Long> nightlyPriceOverrides,
            @JsonProperty("singleSupplementCents") final Long singleSupplementCents,
            @JsonProperty("price") final RateSchedule price,
            @JsonProperty("priced") final Boolean priced,
            @JsonProperty("cost") final RateSchedule cost,
            @JsonProperty("costExtras") final List<CostExtra> costExtras,
            @JsonProperty("ratePlanId") final String ratePlanId,
            @JsonProperty("ratePlanVersion") final Long ratePlanVersion,
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
        this.price = foldPrice(price, priced, pricingModel, nightlyPriceCents, nightlyPriceOverrides,
                singleSupplementCents);
        this.cost = cost;
        this.costExtras = (costExtras == null) ? new ArrayList<>() : new ArrayList<>(costExtras);
        this.ratePlanId = (ratePlanId == null || ratePlanId.isBlank()) ? null : ratePlanId;
        this.ratePlanVersion = (ratePlanVersion == null) ? 0L : ratePlanVersion;
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
        this(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, 0, null, null, null, null, null, null, null, null, null, null, 0L);
    }

    /** The fold the class javadoc describes: price object, then the explicit flag, then the legacy keys. */
    private static RateSchedule foldPrice(final RateSchedule price, final Boolean priced,
            final PricingModel pricingModel, final Long nightlyPriceCents, final Map<String, Long> overrides,
            final Long singleSupplementCents) {
        if (price != null) {
            return price;
        }
        if (Boolean.FALSE.equals(priced)) {
            return null;
        }
        final boolean legacy = pricingModel != null || nightlyPriceCents != null || overrides != null
                || singleSupplementCents != null;
        if (!legacy) {
            return null;
        }
        return new RateSchedule(pricingModel, (nightlyPriceCents == null) ? 0L : nightlyPriceCents, overrides,
                (singleSupplementCents == null) ? 0L : singleSupplementCents);
    }

    /** A stream written before the split has no {@code costExtras}; the list is never null in the live object. */
    @Serial
    private Object readResolve() {
        if (costExtras == null) {
            costExtras = new ArrayList<>();
        }
        if (roomTypeIds == null) {
            roomTypeIds = new ArrayList<>();
        }
        return this;
    }

    // ------------------------------------------------------------------ price side: derived + mirror

    /** Whether a participant price is set. Persisted so an unpriced row is distinguishable from a legacy one. */
    @JsonProperty("priced")
    public boolean isPriced() {
        return price != null;
    }

    /** Legacy mirror of the price side (PER_ROOM when unpriced). Removed one release after the split. */
    @JsonProperty("pricingModel")
    public PricingModel getPricingModel() {
        return (price == null) ? PricingModel.PER_ROOM : price.getPricingModel();
    }

    /** Legacy mirror of the price side (0 when unpriced). Removed one release after the split. */
    @JsonProperty("nightlyPriceCents")
    public long getNightlyPriceCents() {
        return (price == null) ? 0L : price.getNightlyCents();
    }

    /** Legacy mirror of the price side (empty when unpriced; the live map otherwise, so mutations stick). */
    @JsonProperty("nightlyPriceOverrides")
    public Map<String, Long> getNightlyPriceOverrides() {
        return (price == null) ? Map.of() : price.getOverrides();
    }

    /** Legacy mirror of the price side (0 when unpriced). Removed one release after the split. */
    @JsonProperty("singleSupplementCents")
    public long getSingleSupplementCents() {
        return (price == null) ? 0L : price.getSingleSupplementCents();
    }

    /** The participant's price of one night: the per-date override when one exists, else the flat rate; 0 unpriced. */
    @JsonIgnore
    public long nightlyPriceCents(final LocalDate night) {
        return (price == null) ? 0L : price.nightlyCents(night);
    }

    /** Whether the PRICE side charges per person; false when unpriced. */
    @JsonIgnore
    public boolean isPerPerson() {
        return price != null && price.isPerPerson();
    }

    /** What the hotel charges: the cost schedule, or the price when none is set; null when neither is. */
    @JsonIgnore
    public RateSchedule effectiveCost() {
        return (cost != null) ? cost : price;
    }

    /** Whether the Room Invoice is pricing this option at the participant price for want of a stated cost. */
    @JsonIgnore
    public boolean isCostAssumed() {
        return cost == null && price != null;
    }

    /** Whether the offer was copied from a hotel rate plan (and follows its edits). */
    @JsonIgnore
    public boolean isLinked() {
        return ratePlanId != null && !ratePlanId.isBlank();
    }

    // ------------------------------------------------------------------ legacy setters (price side)

    /** @deprecated writes the PRICE side; Phase 2 UI replaces this. */
    @Deprecated
    public void setPricingModel(final PricingModel pricingModel) {
        priceForWrite().setPricingModel(pricingModel);
    }

    /** @deprecated writes the PRICE side; Phase 2 UI replaces this. */
    @Deprecated
    public void setNightlyPriceCents(final long nightlyPriceCents) {
        priceForWrite().setNightlyCents(nightlyPriceCents);
    }

    /** @deprecated writes the PRICE side; Phase 2 UI replaces this. */
    @Deprecated
    public void setNightlyPriceOverrides(final Map<String, Long> nightlyPriceOverrides) {
        priceForWrite().setOverrides(nightlyPriceOverrides);
    }

    /** @deprecated writes the PRICE side; Phase 2 UI replaces this. */
    @Deprecated
    public void setSingleSupplementCents(final long singleSupplementCents) {
        priceForWrite().setSingleSupplementCents(singleSupplementCents);
    }

    /** The price schedule a legacy setter writes into, created on first write (a legacy write means "priced"). */
    private RateSchedule priceForWrite() {
        if (price == null) {
            price = new RateSchedule();
        }
        return price;
    }

    // ------------------------------------------------------------------ the rest

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

    /**
     * The hand-written half of the Lombok builder: the legacy money params are boxed {@code Long}s in the
     * constructor (so Jackson can tell "absent" from 0), but every caller writes {@code nightlyPriceCents(5500)}
     * with a plain literal, which does not box to {@code Long}. Lombok skips a builder method that already
     * exists by name, so these two keep the call sites compiling.
     */
    public static class ReservationOfferBuilder {
        public ReservationOfferBuilder nightlyPriceCents(final long cents) {
            this.nightlyPriceCents = cents;
            return this;
        }

        public ReservationOfferBuilder singleSupplementCents(final long cents) {
            this.singleSupplementCents = cents;
            return this;
        }
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
