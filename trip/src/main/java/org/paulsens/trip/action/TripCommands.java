package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDateTime;
import static java.time.temporal.ChronoUnit.DAYS;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.enterprise.context.ApplicationScoped;
import javax.faces.application.FacesMessage;
import javax.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DynamoUtils;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;

@Slf4j
@Named("trip")
@ApplicationScoped
public class TripCommands {
    public Trip createTrip() {
        return new Trip();
    }

    public boolean saveTrip(final Trip trip) {
        boolean result;
        try {
             result = DynamoUtils.getInstance().saveTrip(trip).exceptionally(ex -> {
                    TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Error saving '" + trip.getId()
                            + "': " + trip.getTitle(), ex.getMessage());
                     log.error("Error while saving trip: ", ex);
                    return false;
                }).join();
        } catch (final IOException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Unable to save '" + trip.getId() + "': "
                    + trip.getTitle(), ex.getMessage());
            log.warn("Error while saving trip: ", ex);
            result = false;
        }
        return result;
    }

    public List<Trip> getActiveTrips(final int pastDaysToCountAsActive) {
        return filterActiveTrips(getTrips(), pastDaysToCountAsActive);
    }

    public List<Trip> getInactiveTrips(
            final Person.Id userId, final boolean isAdmin, final int pastDaysToCountAsActive) {
        return getTrips().stream()
                .filter(trip -> trip.getEndDate().isBefore(LocalDateTime.now().minus(pastDaysToCountAsActive, DAYS)))
                .filter((trip -> isAdmin || trip.getPeople().contains(userId)))
                .collect(Collectors.toList());
    }

    public List<Trip> getTrips() {
        return DynamoUtils.getInstance().getTrips()
                .exceptionally(ex -> {
                    log.error("Failed to get list of trips!", ex);
                    return Collections.emptyList();
                }).join();
    }

    public Trip getTrip(final String id) {
        return DynamoUtils.getInstance().getTrip(id)
                .exceptionally(ex -> {
                    log.error("Failed to get trip '" + id + "'!", ex);
                    return Optional.empty();
                }).join().orElse(new Trip());
    }

    /**
     * This is used to help determine the correct trip to show for the particular user. The chosen trip depends on the
     * user's permissions, what trips they are part of, and whether they already have the trip they need.
     *
     * @param currTrip  The resolved trip, which may already be calculated, if supplied this will be returned.
     * @param userId    The userId.
     * @param tripId    The desired tripId -- will be returned if it exists and the user is part of the trip or admin.
     * @param showAll   True if the user is an admin (can see all).
     *
     * @return  The trip to display, or null if the user should not see any trips.
     */
    public Trip getTripForUser(final Trip currTrip, final Person.Id userId, final Boolean showAll, final String tripId) {
        Trip result;
        if (canSeeTrip(currTrip, userId, showAll)) {
            result = currTrip;                          // Use current trip
        } else if ((tripId != null) && canSeeTrip(findTrip(tripId), userId, showAll)) {
            result = findTrip(tripId);                  // Use requested trip
        } else {
            // Anything the user can see... or null
            final List<Trip> trips = getTrips();
            result = findTrip(trips, userId, showAll);
            if (result == null) {
                // See if there's anything they can join
                result = trips.stream().filter(trip -> trip.canJoin(userId)).findAny().orElse(null);
            }
        }
        return result;
    }

    /**
     * This findTrip method looks for any trip the user can see. It's the last resort way to resolve the trip to show
     * the user.
     * @param trips     All the possible trips.
     * @param userId    The userId.
     * @param showAll   True if an admin (admins can see everything).
     * @return  The trip to show the user, if any. {@code null} if none.
     */
    private Trip findTrip(final List<Trip> trips, final Person.Id userId, final Boolean showAll) {
        if (trips == null) {
            return null;
        }
        final List<Trip> ans = trips.stream().filter(t -> canSeeTrip(t, userId, showAll)).collect(Collectors.toList());
        return ans.isEmpty() ? null : getFirstActiveOrLastTrip(ans);
    }

    /**
     *  This method requires a non-null, non-empty List of trips. It will return the first active trip. If none
     *  exist, it will return the last trip in the list (typically the last trip that started).
     */
    private Trip getFirstActiveOrLastTrip(final List<Trip> trips) {
        final List<Trip> active = filterActiveTrips(trips, 0);
        return active.isEmpty() ? trips.get(trips.size() - 1) : active.get(0);
    }

    private List<Trip> filterActiveTrips(final List<Trip> trips, final int pastDaysToCountAsActive) {
        final LocalDateTime cutoff = LocalDateTime.now().minus(pastDaysToCountAsActive, DAYS);
        return trips.stream()
                .filter(trip -> trip.getEndDate().isAfter(cutoff))
                .collect(Collectors.toList());
    }

    /**
     * This findTrip method looks for a specific Trip by id. Only used for the
     * {@link #getTripForUser(Trip, Person.Id, Boolean, String)} method.
     *
     * @param tripId    The trip id.
     * @return The trip or null if not found.
     */
    private Trip findTrip(final String tripId) {
        return DynamoUtils.getInstance().getTrip(tripId).join().orElse(null);
    }

    /**
     * Ensures the trip either contains the person, or the person is an admin.
     * @param trip      The trip to check.
     * @param userId    The userId to check.
     * @param priv      The user's privileges.
     * @return  True if the user is allowed to see this Trip.
     */
    private boolean canSeeTrip(final Trip trip, final Person.Id userId, final Boolean priv) {
        // FIXME: we should load the Person and look at the user's `managedUsers` property if they aren't directly in
        // FIXME: the trip. (i.e. parent has kid in trip, but not themselves). For now, we won't support that usecase.
        if ((trip == null) || (userId == null)) {
            return false;
        }
        return trip.getPeople().contains(userId) || ((priv != null) && priv);
    }
}
