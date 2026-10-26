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
 */
@Data
public final class BudgetLine implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String id;
    private BudgetLineKind kind;
    private BudgetCategory category;
    private String name;
    private long amountCents;
    private boolean perNight;
    private String note;
    private String payee;

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
            @JsonProperty("payee") final String payee) {
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
    }

    public BudgetLine() {
        this(null, null, null, null, 0L, false, null, null);
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

    static String trimToNull(final String text) {
        if (text == null) {
            return null;
        }
        final String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
