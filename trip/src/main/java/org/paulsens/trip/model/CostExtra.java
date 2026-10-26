package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import lombok.Builder;
import lombok.Data;

/**
 * Something the hotel charges (or credits) on top of the nightly rate: a city tax per guest-night, a cleaning
 * fee per stay, a flat booking fee, a coupon. Cost side only -- extras never reach a participant's bill and
 * are never attributed to a person; the Room Invoice totals them per option. The amount is SIGNED on
 * purpose: a coupon is a negative extra, not a separate concept. {@code pay/CostExtras} does the arithmetic.
 */
@Data
public final class CostExtra implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String label;
    private Basis basis;
    private long amountCents;

    @Builder
    @JsonCreator
    public CostExtra(
            @JsonProperty("label") final String label,
            @JsonProperty("basis") final Basis basis,
            @JsonProperty("amountCents") final long amountCents) {
        this.label = (label == null) ? null : label.trim();
        this.basis = (basis == null) ? Basis.FIXED : basis;
        this.amountCents = amountCents;
    }

    public CostExtra() {
        this(null, null, 0L);
    }

    /** The plural noun the amount multiplies: "guest-nights", "room-nights", "stays", or "" for FIXED. */
    @JsonIgnore
    public String unit() {
        return basis.unit;
    }

    /** The unit for a count: {@code unit(1)} is {@code "guest-night"}, {@code unit(3)} is {@code "guest-nights"}. */
    @JsonIgnore
    public String unit(final int count) {
        return (count == 1) ? basis.singular : basis.unit;
    }

    public void setBasis(final Basis basis) {
        this.basis = (basis == null) ? Basis.FIXED : basis;
    }

    /** What one unit of the extra is: the quantity it multiplies. */
    public enum Basis {
        /** Once per guest per night (a city tax). */
        PER_PERSON_NIGHT("guest-night", "guest-nights"),
        /** Once per room per night (a resort fee). */
        PER_ROOM_NIGHT("room-night", "room-nights"),
        /** Once per stay of at least one night (a cleaning fee). */
        PER_STAY("stay", "stays"),
        /** Once for the option as a whole (a booking fee, a coupon). */
        FIXED("", "");

        private final String singular;
        private final String unit;

        Basis(final String singular, final String unit) {
            this.singular = singular;
            this.unit = unit;
        }
    }
}
