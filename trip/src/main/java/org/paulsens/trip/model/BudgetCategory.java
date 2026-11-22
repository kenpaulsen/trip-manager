package org.paulsens.trip.model;

import java.util.Locale;

/**
 * What kind of cost a SHARED {@link BudgetLine} is: shown in the Shared Expenses table's Category column and
 * offered, in this order, by the line dialog. {@code GENERAL} is the catch-all ("Other"); its stored name is
 * kept so lines saved before the named categories existed still read.
 */
public enum BudgetCategory {
    TRANSPORTATION,
    GUIDE,
    SUPPLIES,
    TIPS,
    SCHOLARSHIP,
    LEADER,
    GENERAL;

    public String getLabel() {
        return EnumLabels.of(this);
    }

    /** The label in an explicit language: a mail or PDF whose reader is not the one making the request. */
    public String getLabel(final Locale locale) {
        return EnumLabels.of(this, locale);
    }
}
