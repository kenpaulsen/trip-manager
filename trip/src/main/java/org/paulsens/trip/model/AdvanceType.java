package org.paulsens.trip.model;

/**
 * What kind of {@link AdvanceEntry} a line of the cash-advance worksheet is. The type carries the SIGN: money
 * returned or transferred away is subtracted from what was advanced, every other kind is added. Each type's
 * one free-text answer is stored under {@link #partyKey()} in the entry's fields map, so the map can grow
 * without a model change.
 */
public enum AdvanceType {
    CASH("Cash advance", "from"),
    CREDIT("Credit applied", "source"),
    PARTICIPANT("Cash given by participant(s)", null),
    RETURNED("Money returned / transferred", "to"),
    OTHER("Other", null);

    private final String label;
    private final String partyKey;

    AdvanceType(final String label, final String partyKey) {
        this.label = label;
        this.partyKey = partyKey;
    }

    public String getLabel() {
        return label;
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
