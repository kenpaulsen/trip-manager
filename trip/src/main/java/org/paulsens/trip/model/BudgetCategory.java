package org.paulsens.trip.model;

/**
 * What kind of cost a SHARED {@link BudgetLine} is: shown in the Shared Expenses table's Category column and
 * offered, in this order, by the line dialog. {@code GENERAL} is the catch-all ("Other"); its stored name is
 * kept so lines saved before the named categories existed still read.
 */
public enum BudgetCategory {
    TRANSPORTATION("Transportation"),
    GUIDE("Guide"),
    SUPPLIES("Supplies"),
    TIPS("Tips"),
    SCHOLARSHIP("Scholarships"),
    LEADER("Leader expenses"),
    GENERAL("Other");

    private final String label;

    BudgetCategory(final String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
