package org.paulsens.trip.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * A trip's planning budget: ONE row per trip in {@code trip_budgets} (PK tripId), its typed lines and lodging
 * lines inline, saved by one versioned conditional put ({@code BudgetDAO}) so a lost race refuses the whole
 * sheet rather than merging half of it. A budget is tens of lines and the page always reads all of it, so
 * there is no reason to split it into rows. See {@code docs/budget.md}.
 *
 * <p>The four inputs are what the trip administrator TYPES: head count, trip nights, the per-person budget and
 * the amount to bill per person. Everything else ({@code pay/BudgetMath}) is derived when the page renders.
 */
@Data
public final class TripBudget implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L; // Pinned: SerializationCompatibilityTest says when to bump.

    private String tripId;
    private String orgId;
    private int people;
    private int tripNights;
    private long perPersonBudgetCents;
    private long toBillCents;
    private List<BudgetLine> lines;
    private List<BudgetLodgingLine> lodging;
    private Person.Id createdBy;
    private LocalDateTime created;
    private long version;

    @Builder
    @JsonCreator
    public TripBudget(
            @JsonProperty("tripId") final String tripId,
            @JsonProperty("orgId") final String orgId,
            @JsonProperty("people") final int people,
            @JsonProperty("tripNights") final int tripNights,
            @JsonProperty("perPersonBudgetCents") final long perPersonBudgetCents,
            @JsonProperty("toBillCents") final long toBillCents,
            @JsonProperty("lines") final List<BudgetLine> lines,
            @JsonProperty("lodging") final List<BudgetLodgingLine> lodging,
            @JsonProperty("createdBy") final Person.Id createdBy,
            @JsonProperty("created") final LocalDateTime created,
            @JsonProperty("version") final long version) {
        this.tripId = tripId;
        this.orgId = orgId;
        this.people = Math.max(0, people);
        this.tripNights = Math.max(0, tripNights);
        this.perPersonBudgetCents = Math.max(0L, perPersonBudgetCents);
        this.toBillCents = Math.max(0L, toBillCents);
        this.lines = (lines == null) ? new ArrayList<>() : new ArrayList<>(lines);
        this.lodging = (lodging == null) ? new ArrayList<>() : new ArrayList<>(lodging);
        this.createdBy = createdBy;
        this.created = created;
        this.version = version;
    }

    public TripBudget() {
        this(null, null, 0, 0, 0L, 0L, null, null, null, null, 0L);
    }

    /** The typed line with this id; null when there is none. */
    public BudgetLine line(final String id) {
        for (final BudgetLine line : lines) {
            if (line.getId().equals(id)) {
                return line;
            }
        }
        return null;
    }

    /** The lodging line with this id; null when there is none. */
    public BudgetLodgingLine lodgingLine(final String id) {
        for (final BudgetLodgingLine line : lodging) {
            if (line.getId().equals(id)) {
                return line;
            }
        }
        return null;
    }

    /** The SHARED lines of one category, in the budget's order. */
    public List<BudgetLine> sharedLines(final BudgetCategory category) {
        final List<BudgetLine> out = new ArrayList<>();
        for (final BudgetLine line : lines) {
            if (line.isShared() && line.getCategory() == category) {
                out.add(line);
            }
        }
        return out;
    }

    /** The INDIVIDUAL (per-person) lines, in the budget's order. */
    public List<BudgetLine> individualLines() {
        final List<BudgetLine> out = new ArrayList<>();
        for (final BudgetLine line : lines) {
            if (!line.isShared()) {
                out.add(line);
            }
        }
        return out;
    }

    /** Whether any lodging line exists; without one the per-person figures are incomplete. */
    @JsonIgnore
    public boolean hasLodging() {
        return !lodging.isEmpty();
    }
}
