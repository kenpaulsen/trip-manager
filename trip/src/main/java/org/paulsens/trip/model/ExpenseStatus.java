package org.paulsens.trip.model;

/**
 * Where a {@link TripExpenseReport} stands, DERIVED from its stamps and never stored: Paid once a paid-on date
 * is set, else Submitted once someone has submitted it, else Draft. Editing stays open in every state.
 */
public enum ExpenseStatus {
    DRAFT("Draft"),
    SUBMITTED("Submitted"),
    PAID("Paid");

    private final String label;

    ExpenseStatus(final String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
