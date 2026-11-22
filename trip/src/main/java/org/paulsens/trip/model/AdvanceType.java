package org.paulsens.trip.model;

import java.util.Locale;

/**
 * What kind of {@link AdvanceEntry} a line of the cash-advance worksheet is. The type carries the SIGN: money
 * returned or transferred away is subtracted from what was advanced, every other kind is added. Each type's
 * one free-text answer is stored under {@link #partyKey()} in the entry's fields map, so the map can grow
 * without a model change.
 */
public enum AdvanceType {
    CASH("from"),
    CREDIT("source"),
    PARTICIPANT(null),
    RETURNED("to"),
    OTHER(null);

    private final String partyKey;

    AdvanceType(final String partyKey) {
        this.partyKey = partyKey;
    }

    public String getLabel() {
        return EnumLabels.of(this);
    }

    /** The label in an explicit language: a mail or PDF whose reader is not the one making the request. */
    public String getLabel(final Locale locale) {
        return EnumLabels.of(this, locale);
    }

    /** The fields-map key of the type's "who" answer; null when the type asks none. */
    public String partyKey() {
        return partyKey;
    }

    /** Whether the amount is money that LEFT the advance (returned or transferred), so it counts negative. */
    public boolean isReverse() {
        return this == RETURNED;
    }

    /** +1, or -1 for a reverse entry. */
    public int getSign() {
        return isReverse() ? -1 : 1;
    }
}
