package org.paulsens.trip.action;

import java.util.List;
import java.util.function.Supplier;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;

/**
 * Who may see, write, approve and send a trip's scheduled messages: a site admin, an admin of the trip's
 * organization, a {@code tripMgr@trip}, or a person the trip names as a facilitator. ONE rule for all of it:
 * the point of the page is that whoever can see what goes out is also the person who answers for it, so there
 * is no read-only tier. False for an unknown trip or nobody signed in.
 */
public final class ReminderGate {
    private final Supplier<Caller> callerSource;
    private final Supplier<TripCommands> tripSource;
    private final Supplier<OrgCommands> orgSource;

    ReminderGate(final Supplier<Caller> callerSource, final Supplier<TripCommands> tripSource,
            final Supplier<OrgCommands> orgSource) {
        this.callerSource = callerSource;
        this.tripSource = tripSource;
        this.orgSource = orgSource;
    }

    boolean canManage(final String tripId) {
        final Caller caller = callerSource.get();
        if (tripId == null || tripId.isBlank() || !caller.isAuthenticated()) {
            return false;
        }
        final Trip trip = knownTrip(tripId);
        if (trip == null) {
            return false;
        }
        return caller.has(PrivilegeCommands.TRIP_MGR, tripId) || isFacilitator(trip, caller.personId())
                || orgSource.get().canManageOrg(trip.getOrgId());
    }

    private static boolean isFacilitator(final Trip trip, final Person.Id personId) {
        final List<Person.Id> facilitators = trip.getFacilitatorIds();
        return personId != null && facilitators != null && facilitators.contains(personId);
    }

    /** The trip, or null for an unknown one ({@code getTrip} answers a miss with a blank trip and a fresh id). */
    Trip knownTrip(final String tripId) {
        if (tripId == null) {
            return null;
        }
        final Trip trip = tripSource.get().getTrip(tripId);
        return (trip == null || !tripId.equals(trip.getId())) ? null : trip;
    }

    /** The trip read fresh for a write, behind the gate; null when the caller may not manage it. */
    Trip manageableTrip(final String tripId) {
        if (!canManage(tripId)) {
            return null;
        }
        final Trip trip = tripSource.get().getTripForEdit(tripId);
        return (trip == null || !tripId.equals(trip.getId())) ? null : trip;
    }
}
