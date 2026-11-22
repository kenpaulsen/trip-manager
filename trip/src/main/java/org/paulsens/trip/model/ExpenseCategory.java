package org.paulsens.trip.model;

import java.util.Locale;

/**
 * What kind of spending an {@link ExpenseLine} is, offered in this order by the expense dialog. It is the
 * budget's {@link BudgetCategory} list plus the kinds a trip spends on that the budget plans elsewhere or not
 * at all (lodging, postage, meals). {@code GENERAL} is the catch-all ("Other"), named like the budget's so a
 * matched line maps across by name.
 */
public enum ExpenseCategory {
    TRANSPORTATION(BudgetCategory.TRANSPORTATION),
    GUIDE(BudgetCategory.GUIDE),
    SUPPLIES(BudgetCategory.SUPPLIES),
    TIPS(BudgetCategory.TIPS),
    SCHOLARSHIP(BudgetCategory.SCHOLARSHIP),
    LEADER(BudgetCategory.LEADER),
    LODGING(null),
    POSTAGE(null),
    MEALS(null),
    GENERAL(BudgetCategory.GENERAL);

    private final BudgetCategory budget;

    ExpenseCategory(final BudgetCategory budget) {
        this.budget = budget;
    }

    /**
     * A category the budget also has reads the budget's label: the two are one word with one meaning, so they
     * share one translation and cannot drift apart on a report that shows both.
     */
    public String getLabel() {
        return (budget == null) ? EnumLabels.of(this) : budget.getLabel();
    }

    /** {@link #getLabel()} in an explicit language: a mail or PDF whose reader is not the one making the request. */
    public String getLabel(final Locale locale) {
        return (budget == null) ? EnumLabels.of(this, locale) : budget.getLabel(locale);
    }

    /** The expense category of a budget category: the same name, which every budget category has. */
    public static ExpenseCategory ofBudget(final BudgetCategory category) {
        return (category == null) ? GENERAL : valueOf(category.name());
    }
}
