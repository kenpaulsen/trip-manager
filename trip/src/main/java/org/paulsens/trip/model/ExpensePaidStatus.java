package org.paulsens.trip.model;

import java.util.Locale;

/**
 * Whether an {@link ExpenseLine} still has to be paid back to whoever spent it ("Not yet reimbursed": the
 * submitter paid out of pocket and is owed). A line the organization paid directly, or one already reimbursed
 * before the report, still counts as spent against the budget but drops out of what the report says is due
 * ({@code pay/ExpenseMath.outOfPocket}).
 */
public enum ExpensePaidStatus {
    NOT_PAID,
    ORG,
    REIMBURSED;

    public String getLabel() {
        return EnumLabels.of(this);
    }

    /** The label in an explicit language: a mail or PDF whose reader is not the one making the request. */
    public String getLabel(final Locale locale) {
        return EnumLabels.of(this, locale);
    }

    /** The short text on the line's chip; null for a line still to be paid. */
    public String getChip() {
        return isSettled() ? EnumLabels.of("labels.expensePaidStatus.chip", this) : null;
    }

    /** {@link #getChip()} in an explicit language. */
    public String getChip(final Locale locale) {
        return isSettled() ? EnumLabels.ofIn(locale, "labels.expensePaidStatus.chip", this) : null;
    }

    /** Whether the line was settled outside the report, so it is not part of what is due. */
    public boolean isSettled() {
        return this != NOT_PAID;
    }
}
