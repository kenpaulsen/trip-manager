package org.paulsens.trip.model;

import java.util.Locale;

/**
 * Where a {@link TripExpenseReport} stands, DERIVED from its stamps and payments and never stored: once a
 * payment is recorded, Paid when every expense still to be paid is in a payment and Partly paid when some are
 * not; before that Submitted once someone has submitted it, else Draft. Editing stays open in every state.
 */
public enum ExpenseStatus {
    DRAFT,
    SUBMITTED,
    PARTLY_PAID,
    PAID;

    public String getLabel() {
        return EnumLabels.of(this);
    }

    /** The label in an explicit language: a mail or PDF whose reader is not the one making the request. */
    public String getLabel(final Locale locale) {
        return EnumLabels.of(this, locale);
    }
}
