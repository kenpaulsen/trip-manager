package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * One typed line of a {@link TripBudget}: a SHARED whole-group cost under a {@link BudgetCategory}, or an
 * INDIVIDUAL per-person amount (optionally per night of the trip). Money is long cents. The constructor
 * normalizes, so the builder, Jackson and {@code new BudgetLine()} all produce a line that obeys the rules:
 * a SHARED line always has a category and is never per night, an INDIVIDUAL line has no category, the amount
 * is never negative, and blank text is null.
 *
 * <p><b>A percent line</b> ({@code percentOf} set) takes {@code percentBps} basis points of another line of the
 * same kind (Tips = 10% of Guide). Its {@code amountCents} is DERIVED: {@code pay/BudgetMath.resolvePercents}
 * recomputes it on every save, so the stored amount always matches the line it follows and every reader can
 * keep using {@code amountCents}.
 *
 * <p>{@code offSite} is stored inverted (on-site is the default) so a row written before the flag existed, a
 * builder that never mentions it, and a session-revived object all read as an on-site payment.
 */
@Data
public final class BudgetLine implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    /** 1,000% in basis points: a sanity cap on a percent line, not a business rule. */
    public static final int MAX_PERCENT_BPS = 100_000;

    private String id;
    private BudgetLineKind kind;
    private BudgetCategory category;
    private String name;
    private long amountCents;
    private boolean perNight;
    private String note;
    private String payee;
    /** The id of the line this one is a percentage of; null for a fixed amount. */
    private String percentOf;
    /** The percentage in basis points (1250 = 12.5%); 0 unless {@code percentOf} is set. */
    private int percentBps;
    /** True when the line is NOT paid on site: it is listed under Other trip items, not the Payees. */
    private boolean offSite;

    @Builder
    @JsonCreator
    public BudgetLine(
            @JsonProperty("id") final String id,
            @JsonProperty("kind") final BudgetLineKind kind,
            @JsonProperty("category") final BudgetCategory category,
            @JsonProperty("name") final String name,
            @JsonProperty("amountCents") final long amountCents,
            @JsonProperty("perNight") final boolean perNight,
            @JsonProperty("note") final String note,
            @JsonProperty("payee") final String payee,
            @JsonProperty("percentOf") final String percentOf,
            @JsonProperty("percentBps") final int percentBps,
            @JsonProperty("offSite") final boolean offSite) {
        this.id = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        this.kind = (kind == null) ? BudgetLineKind.SHARED : kind;
        if (this.kind == BudgetLineKind.SHARED) {
            this.category = (category == null) ? BudgetCategory.GENERAL : category;
            this.perNight = false;
        } else {
            this.category = null;
            this.perNight = perNight;
        }
        this.name = trimToNull(name);
        this.amountCents = Math.max(0L, amountCents);
        this.note = trimToNull(note);
        this.payee = trimToNull(payee);
        this.percentOf = trimToNull(percentOf);
        this.percentBps = (this.percentOf == null) ? 0 : Math.min(MAX_PERCENT_BPS, Math.max(0, percentBps));
        this.offSite = offSite;
    }

    public BudgetLine() {
        this(null, null, null, null, 0L, false, null, null, null, 0, false);
    }

    /** What the line adds per person for a trip of {@code nights}: per-night lines multiply, the rest do not. */
    @JsonIgnore
    public long totalCents(final int nights) {
        return perNight ? amountCents * Math.max(0, nights) : amountCents;
    }

    @JsonIgnore
    public boolean isShared() {
        return kind == BudgetLineKind.SHARED;
    }

    /** Whether the amount is a percentage of another line rather than typed. */
    @JsonIgnore
    public boolean isPercent() {
        return percentOf != null;
    }

    /** Whether the line is paid on site (listed with the Payees); the inverse of the stored flag. */
    @JsonIgnore
    public boolean isOnSite() {
        return !offSite;
    }

    static String trimToNull(final String text) {
        if (text == null) {
            return null;
        }
        final String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
