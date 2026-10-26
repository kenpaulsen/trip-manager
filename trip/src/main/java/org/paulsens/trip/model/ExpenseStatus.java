package org.paulsens.trip.model;

/**
 * Where a {@link TripExpenseReport} stands, DERIVED from its stamps and payments and never stored: once a
 * payment is recorded, Paid when every expense still to be paid is in a payment and Partly paid when some are
 * not; before that Submitted once someone has submitted it, else Draft. Editing stays open in every state.
 */
public enum ExpenseStatus {
    DRAFT("Draft"),
    SUBMITTED("Submitted"),
    PARTLY_PAID("Partly paid"),
    PAID("Paid");

    private final String label;

    ExpenseStatus(final String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
