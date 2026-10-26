package org.paulsens.trip.action;

import java.util.function.Consumer;
import java.util.function.Supplier;
import org.paulsens.trip.model.Trip;

import static org.paulsens.trip.action.PageFeedback.refuse;

/**
 * The one spelling of who may read and write a trip's planning money: the budget ({@link BudgetCommands}) and
 * the expense report ({@link ExpenseCommands}) both ask it, so the two pages can never disagree about a
 * person. Edit = site admin, {@code tripMgr@trip} or {@code tripFinAdmin@trip}; view = an editor,
 * {@code tripFinView@trip} or the global {@code viewFinances}. Both answer false for an unknown trip or nobody
 * signed in.
 */
final class TripFinanceGate {
    private final Supplier<Caller> callerSource;
    private final Supplier<TripCommands> tripSource;

    TripFinanceGate(final Supplier<Caller> callerSource, final Supplier<TripCommands> tripSource) {
        this.callerSource = callerSource;
        this.tripSource = tripSource;
    }

    /** May edit: site admin, the trip's managers or its finance admins. */
    boolean canEdit(final String tripId) {
        if (!signedInOnKnownTrip(tripId)) {
            return false;
        }
        final Caller caller = callerSource.get();
        return caller.has(PrivilegeCommands.TRIP_MGR, tripId) || caller.has(PrivilegeCommands.TRIP_FIN_ADMIN, tripId);
    }

    /** May read: an editor, the trip's finance viewers, or a global finance viewer. */
    boolean canView(final String tripId) {
        if (canEdit(tripId)) {
            return true;
        }
        if (!signedInOnKnownTrip(tripId)) {
            return false;
        }
        final Caller caller = callerSource.get();
        return caller.has(PrivilegeCommands.TRIP_FIN_VIEW, tripId) || caller.has(PrivilegeCommands.VIEW_FINANCES);
    }

    /** The trip, or null for an unknown one ({@code getTrip} answers a miss with a blank trip and a fresh id). */
    Trip knownTrip(final String tripId) {
        if (tripId == null) {
            return null;
        }
        final Trip trip = tripSource.get().getTrip(tripId);
        return (trip == null || !tripId.equals(trip.getId())) ? null : trip;
    }

    /**
     * The edit gate, the form, and the trip read for edit; null (after refusing) when any of them fails. A
     * denial growls {@code notAllowed}, since the caller's form may not even be on the page; an unknown trip
     * goes to {@code refusal}.
     */
    Trip editableTrip(final String tripId, final Object form, final Consumer<String> refusal,
            final String notAllowed) {
        if (!canEdit(tripId)) {
            refuse(notAllowed);
            return null;
        }
        if (form == null) {
            refuse("Nothing to save.");
            return null;
        }
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        if (trip == null || !tripId.equals(trip.getId())) {
            refusal.accept("Unknown trip.");
            return null;
        }
        return trip;
    }

    private boolean signedInOnKnownTrip(final String tripId) {
        return tripId != null && !tripId.isBlank() && callerSource.get().isAuthenticated() && knownTrip(tripId) != null;
    }
}
