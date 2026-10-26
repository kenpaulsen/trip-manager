package org.paulsens.trip.model;

/**
 * What kind of spending an {@link ExpenseLine} is, offered in this order by the expense dialog. It is the
 * budget's {@link BudgetCategory} list plus the kinds a trip spends on that the budget plans elsewhere or not
 * at all (lodging, postage, meals). {@code GENERAL} is the catch-all ("Other"), named like the budget's so a
 * matched line maps across by name.
 */
public enum ExpenseCategory {
    TRANSPORTATION("Transportation"),
    GUIDE("Guide"),
    SUPPLIES("Supplies"),
    TIPS("Tips"),
    SCHOLARSHIP("Scholarships"),
    LEADER("Leader expenses"),
    LODGING("Lodging"),
    POSTAGE("Postage"),
    MEALS("Meals"),
    GENERAL("Other");

    private final String label;

    ExpenseCategory(final String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** The expense category of a budget category: the same name, which every budget category has. */
    public static ExpenseCategory ofBudget(final BudgetCategory category) {
        return (category == null) ? GENERAL : valueOf(category.name());
    }
}
