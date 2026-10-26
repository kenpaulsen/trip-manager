package org.paulsens.trip.model;

/**
 * Whether an {@link ExpenseLine} still has to be paid back to whoever spent it. A line the organization paid
 * directly, or one already reimbursed before the report, still counts as spent against the budget but drops
 * out of what the report says is due ({@code pay/ExpenseMath.outOfPocket}).
 */
public enum ExpensePaidStatus {
    NOT_PAID("Not paid", null),
    ORG("Paid by organization", "Org paid"),
    REIMBURSED("Already reimbursed", "Reimbursed");

    private final String label;
    private final String chip;

    ExpensePaidStatus(final String label, final String chip) {
        this.label = label;
        this.chip = chip;
    }

    public String getLabel() {
        return label;
    }

    /** The short text on the line's chip; null for a line still to be paid. */
    public String getChip() {
        return chip;
    }

    /** Whether the line was settled outside the report, so it is not part of what is due. */
    public boolean isSettled() {
        return this != NOT_PAID;
    }
}
