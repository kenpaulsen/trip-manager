package org.paulsens.trip.action;

import java.util.function.BooleanSupplier;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;

/**
 * How much of one person's itinerary on one trip a viewer may see -- THE rule for every page and endpoint that
 * shows somebody else's itinerary (docs/shared-itineraries.md).
 *
 * <p>{@link Level#FULL} is the person's own page: private notes, room, stay dates, registration. Only the person,
 * their family manager, a Trip Manager of that trip and a site admin get it. Trip Viewers and org admins
 * deliberately do not (owner decision 2026-11-01); they are treated as fellow travelers.
 *
 * <p>{@link Level#SHARED} is the trip's event data alone, and needs all three of: the subject on the roster, the
 * trip's {@code itinerarySharing} switch on, and the subject's own {@code shareItinerary} choice left on. The
 * viewer must be on the roster too, or hold {@code tripView}, or administer the trip's org. "Roster" is
 * {@code Trip.people}, so a pending registrant is neither seen nor seeing.
 */
public final class ItineraryAccess {

    /** What the viewer may see. */
    public enum Level {
        FULL, SHARED, NONE
    }

    private ItineraryAccess() {
    }

    /**
     * @param viewer        the signed-in caller; site admin is implied by {@link Caller#has}
     * @param viewerPerson  the caller's own record, for the family-manager test (may be null)
     * @param trip          the trip the itinerary belongs to
     * @param subject       whose itinerary
     * @param orgAdmin      whether the viewer administers the trip's org; asked only when it can matter, since it
     *                      costs an org lookup
     */
    public static Level of(final Caller viewer, final Person viewerPerson, final Trip trip, final Person subject,
            final BooleanSupplier orgAdmin) {
        if (viewer == null || !viewer.isAuthenticated() || trip == null || subject == null
                || subject.getId() == null) {
            return Level.NONE;
        }
        final Person.Id subjectId = subject.getId();
        if (subjectId.equals(viewer.personId()) || manages(viewerPerson, subjectId)
                || viewer.has(PrivilegeCommands.TRIP_MGR, trip.getId())) {
            return Level.FULL;
        }
        if (!trip.getPeople().contains(subjectId) || !trip.getItinerarySharing()
                || !subject.getPrivacy().isItineraryShared()) {
            return Level.NONE;
        }
        if (trip.getPeople().contains(viewer.personId()) || viewer.has(PrivilegeCommands.TRIP_VIEW, trip.getId())
                || orgAdmin.getAsBoolean()) {
            return Level.SHARED;
        }
        return Level.NONE;
    }

    private static boolean manages(final Person viewerPerson, final Person.Id subjectId) {
        return viewerPerson != null && viewerPerson.getManagedUsers().contains(subjectId);
    }
}
