package org.paulsens.trip.action;

import java.util.function.Consumer;
import java.util.function.Supplier;
import org.paulsens.trip.model.Trip;

import static org.paulsens.trip.action.PageFeedback.refuse;

/**
 * The one spelling of who may read and write a trip's planning money: the budget ({@link BudgetCommands}) and
 * the expense report ({@link ExpenseCommands}) both ask it, so the two pages can never disagree about a
 * person. Edit = site admin, {@code tripMgr@trip} or {@code tripFinAdmin@trip}; view = an editor,
 * {@code tripFinView@trip} or the global {@code viewFinances}; settle (record payments against an expense
 * report) = site admin or {@code tripFinAdmin@trip} only, so a trip manager, usually the one being paid, cannot
 * mark their own report paid. All answer false for an unknown trip or nobody signed in.
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

    /** May record payments: site admin or the trip's finance admins, never a trip manager as such. */
    boolean canSettle(final String tripId) {
        return signedInOnKnownTrip(tripId) && callerSource.get().has(PrivilegeCommands.TRIP_FIN_ADMIN, tripId);
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
        return canEdit(tripId) ? tripForEdit(tripId, form, refusal) : notAllowed(notAllowed);
    }

    /** {@link #editableTrip} behind the settle gate instead of the edit gate. */
    Trip settleableTrip(final String tripId, final Object form, final Consumer<String> refusal,
            final String notAllowed) {
        return canSettle(tripId) ? tripForEdit(tripId, form, refusal) : notAllowed(notAllowed);
    }

    private static Trip notAllowed(final String notAllowed) {
        refuse(notAllowed);
        return null;
    }

    private Trip tripForEdit(final String tripId, final Object form, final Consumer<String> refusal) {
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
