package org.paulsens.trip.action;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What one caller may do with one trip's lodging, computed once per request by {@link LodgingAccessCommands}
 * and handed to the pages as {@code requestScope.access}. Scalars and ids only, so a page may keep it for the
 * request and never for longer. Feature doc: {@code docs/lodging.md}, "Who opens the trip's Lodging tab".
 *
 * <p>Two kinds of people reach a trip's lodging. The INTERNAL ones ({@code canManageTripLodging}: site admin,
 * global or org {@code lodgingAdmin}, the trip's {@code tripMgr}) see the whole tab, prices included. A
 * Hotel OPERATOR holds {@code accommodationAdmin} on a hotel that is IN PLAY on the trip -- named by one of
 * its lodging options or by one of its ACTIVE reservations -- and gets the Assignments board for THAT hotel:
 * trip title, dates and guest names, never a price or a bill. An operator's reach is one hotel at a time,
 * which is why the board asks {@link #mayOpenBoard(String)} rather than a trip-wide flag.
 */
public final class LodgingAccess implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String tripId;
    private final boolean internal;
    private final Set<String> operatorAccIds;
    private final Set<String> allowedAccIds;

    LodgingAccess(final String tripId, final boolean internal, final Collection<String> operatorAccIds,
            final Collection<String> inPlayAccIds) {
        this.tripId = tripId;
        this.internal = internal;
        this.operatorAccIds = frozen(operatorAccIds);
        this.allowedAccIds = internal ? frozen(inPlayAccIds) : this.operatorAccIds;
    }

    /** Nothing allowed: the anonymous caller, an unknown trip. */
    public static LodgingAccess none(final String tripId) {
        return new LodgingAccess(tripId, false, Set.of(), Set.of());
    }

    public String getTripId() {
        return tripId;
    }

    /** The full Lodging tab, prices and bills included ({@code canManageTripLodging}). */
    public boolean isInternal() {
        return internal;
    }

    /** May open the Assignments board at all: internal, or operator of a hotel in play. */
    public boolean isCanAssign() {
        return internal || !operatorAccIds.isEmpty();
    }

    /** Operator of at least one hotel in play on this trip (the external door; the cost-only invoice). */
    public boolean isOperatorHere() {
        return !operatorAccIds.isEmpty();
    }

    /** Prices, bills and concessions on the price side are internal-only. */
    public boolean isSeesPrices() {
        return internal;
    }

    /** The hotels the caller operates that are in play on this trip. */
    public Set<String> getOperatorAccIds() {
        return operatorAccIds;
    }

    /** The hotels whose board this caller may open: every hotel in play for internal callers, else theirs. */
    public Set<String> getAllowedAccIds() {
        return allowedAccIds;
    }

    public boolean mayOpenBoard(final String accId) {
        return accId != null && allowedAccIds.contains(accId);
    }

    public boolean operatorOf(final String accId) {
        return accId != null && operatorAccIds.contains(accId);
    }

    private static Set<String> frozen(final Collection<String> ids) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(ids));
    }

    @Override
    public String toString() {
        return "LodgingAccess[trip=" + tripId + ", internal=" + internal + ", operatorOf=" + operatorAccIds
                + ", allowed=" + allowedAccIds + "]";
    }
}
