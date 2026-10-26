package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;
import org.paulsens.trip.pay.MoneyMath;

/**
 * A discount granted on ONE stay: a percentage, a fixed amount per night, a fixed amount off the total, or
 * the whole stay free. A {@link Reservation} carries up to two -- {@code priceConcession} (what the participant
 * is forgiven, so their bill shrinks in place) and {@code costConcession} (what the hotel forgave us, so the
 * Room Invoice shrinks) -- and the field it sits in IS the side; the concession itself has no side.
 *
 * <p>{@link #label()} is what a bill note and an invoice row show. {@code reason} is deliberately NOT part of
 * it: a bill note is compared byte-for-byte on every recompute, so a reason edit would rewrite every row,
 * and a reason may be internal ("hotel comped the guide"). {@code by}/{@code at} are the audit of who granted
 * it.
 */
@Data
public final class Concession implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private static final int BPS_SCALE = 10_000;

    private Kind kind;
    /** PERCENT only: basis points off, 0..10000 (1250 = 12.5%). */
    private int percentBps;
    /** PER_NIGHT and TOTAL: the amount off, never negative. */
    private long amountCents;
    private String reason;
    private Person.Id by;
    private LocalDateTime at;

    @Builder
    @JsonCreator
    public Concession(
            @JsonProperty("kind") final Kind kind,
            @JsonProperty("percentBps") final int percentBps,
            @JsonProperty("amountCents") final long amountCents,
            @JsonProperty("reason") final String reason,
            @JsonProperty("by") final Person.Id by,
            @JsonProperty("at") final LocalDateTime at) {
        this.kind = (kind == null) ? Kind.TOTAL : kind;
        this.percentBps = Math.max(0, Math.min(BPS_SCALE, percentBps));
        this.amountCents = Math.max(0L, amountCents);
        this.reason = (reason == null) ? null : reason.trim();
        this.by = by;
        this.at = at;
    }

    public Concession() {
        this(null, 0, 0L, null, null, null);
    }

    /**
     * What is left of {@code grossCents} after this concession, for a stay of {@code nights} nights. PERCENT
     * rounds half-up; the result is clamped to {@code [0, gross]}, so a discount never flips a bill into a
     * credit.
     */
    public long netCents(final long grossCents, final int nights) {
        final long net;
        switch (kind) {
            case PERCENT:
                net = BigDecimal.valueOf(grossCents).multiply(BigDecimal.valueOf(BPS_SCALE - percentBps))
                        .divide(BigDecimal.valueOf(BPS_SCALE), 0, RoundingMode.HALF_UP).longValueExact();
                break;
            case PER_NIGHT:
                net = grossCents - amountCents * Math.max(0, nights);
                break;
            case TOTAL:
                net = grossCents - amountCents;
                break;
            case COMPLIMENTARY:
            default:
                net = 0L;
                break;
        }
        return Math.max(0L, Math.min(Math.max(0L, grossCents), net));
    }

    /**
     * The words a bill note or invoice row uses: {@code "10% discount"}, {@code "12.5% discount"},
     * {@code "$5.00/night discount"}, {@code "$20.00 discount"}, {@code "complimentary"}. Never the reason.
     */
    public String label() {
        switch (kind) {
            case PERCENT:
                return percentText() + "% discount";
            case PER_NIGHT:
                return MoneyMath.formatCents(amountCents) + "/night discount";
            case TOTAL:
                return MoneyMath.formatCents(amountCents) + " discount";
            case COMPLIMENTARY:
            default:
                return "complimentary";
        }
    }

    @JsonIgnore
    public boolean isComplimentary() {
        return kind == Kind.COMPLIMENTARY;
    }

    /** Basis points as a percentage with up to two decimals and no trailing zeros: 1000 -> "10", 1250 -> "12.5". */
    private String percentText() {
        return BigDecimal.valueOf(percentBps).movePointLeft(2).stripTrailingZeros().toPlainString();
    }

    public void setKind(final Kind kind) {
        this.kind = (kind == null) ? Kind.TOTAL : kind;
    }

    public void setPercentBps(final int percentBps) {
        this.percentBps = Math.max(0, Math.min(BPS_SCALE, percentBps));
    }

    public void setAmountCents(final long amountCents) {
        this.amountCents = Math.max(0L, amountCents);
    }

    public enum Kind {
        /** {@code percentBps} off the gross. */
        PERCENT,
        /** {@code amountCents} off for every night of the stay. */
        PER_NIGHT,
        /** {@code amountCents} off the stay. */
        TOTAL,
        /** The stay is free. */
        COMPLIMENTARY
    }
}
