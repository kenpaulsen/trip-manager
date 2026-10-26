package org.paulsens.trip.model;

/**
 * The group a SHARED {@link BudgetLine} is listed under on the budget page, each with its own subtotal. The
 * sheet the budget replaced kept scholarships and leader expenses as named sub-lists that rolled up into one
 * shared line each; the categories are those sub-lists.
 */
public enum BudgetCategory {
    GENERAL("General"),
    SCHOLARSHIP("Scholarships"),
    LEADER("Leader expenses");

    private final String label;

    BudgetCategory(final String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
