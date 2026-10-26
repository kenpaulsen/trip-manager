package org.paulsens.trip.model;

/**
 * How a {@link BudgetLine} counts: a SHARED line is a whole-group cost (the guide, the buses, a scholarship)
 * divided across everyone; an INDIVIDUAL line is an amount every participant pays on top (per stay, or per
 * night of the trip).
 */
public enum BudgetLineKind {
    SHARED,
    INDIVIDUAL
}
