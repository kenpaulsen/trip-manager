package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;

import static java.time.temporal.ChronoUnit.DAYS;

@Slf4j
@Named("trip")
@ApplicationScoped
public class TripCommands {
    private static final long TIMEOUT = 5_000L;

    @Inject
    private BindingCommands bind;

    public Trip createTrip() {
        return Trip.builder().build();
    }

    public boolean saveTrip(final Trip trip) {
        boolean result;
        try {
             result = DAO.getInstance().saveTrip(sortTripPeople(trip)).exceptionally(ex -> {
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

    public Trip sortTripPeople(final Trip trip) {
        final List<Person.Id> sortedIdList = trip.getPeople().stream()
                        .map(id -> DAO.getInstance().getPerson(id).join())
                        .map(opt -> opt.orElse(null))
                        .filter(Objects::nonNull)
                        .sorted()
                        .map(Person::getId)
                        .toList();
        trip.setPeople(new ArrayList<>(sortedIdList));
        return trip;
    }

    public List<Trip> getActiveTrips(final int pastDaysToCountAsActive) {
        return filterActiveTrips(getTrips(), pastDaysToCountAsActive);
    }

    public List<Trip> getInactiveTrips(final Person.Id userId, final boolean isAdmin, final int pastDaysStillActive) {
        final List<Trip> result = new ArrayList<>(getTrips().stream()
                .filter(trip -> trip.getEndDate().isBefore(LocalDateTime.now().minus(pastDaysStillActive, DAYS)))
                .filter((trip -> isAdmin || trip.getPeople().contains(userId)))
                .toList());
        Collections.reverse(result);
        return result;
    }

    public List<Trip> getTrips() {
        return DAO.getInstance().getTrips()
                .exceptionally(ex -> {
                    log.error("Failed to get list of trips!", ex);
                    return Collections.emptyList();
                }).join();
    }

    public Trip getTrip(final String id) {
        return DAO.getInstance().getTrip(id)
                .exceptionally(ex -> {
                    log.error("Failed to get trip '" + id + "'!", ex);
                    return Optional.empty();
                }).join().orElse(Trip.builder().build());
    }

    public Trip getBoundTrip(final String id, final String bindingType) {
        return getBind().getBoundThing(id, bindingType, BindingType.TRIP, this::getTrip);
    }

    public TripEvent getBoundTripEvent(final String id, final String bindingType) {
        return getBind().getBoundThing(id, bindingType, BindingType.TRIP_EVENT, this::getTripEvent);
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
            result = findTrip(trips, userId, false); // Try w/o considering admin privs
            if (result == null && showAll) {
                result = findTrip(trips, userId, showAll);
            }
            if (result == null) {
                // See if there's anything they can join
                result = trips.stream().filter(trip -> trip.canJoin(userId)).findAny().orElse(null);
            }
        }
        return result;
    }

    public List<Trip> getTripsForUser(final Person.Id userId) {
        final List<Trip> result = getTrips();
        return result.stream().filter(trip -> trip.getPeople().contains(userId)).toList();
    }

    public TripEvent getTripEvent(final String eventId) {
        return DAO.getInstance().getTripEvent(eventId)
                .orTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> logAndReturn(ex, null))
                .join();
    }

    // This only works for flights
    public LocalDateTime getLodgingArrivalDate(final Collection<TripEvent> events, final TripEvent lodgingEvent) {
        if (events == null || events.isEmpty() || lodgingEvent == null) {
            return null;
        }
        final List<TripEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(TripEvent::getEnd));
        LocalDateTime result = sorted.get(0).getEnd();
        for (final TripEvent te : sorted) {
            if (te.getType() != TripEvent.Type.FLIGHT) {
                continue;
            }
            final LocalDateTime teEnd = te.getEnd();
            if (teEnd.isBefore(lodgingEvent.getEnd()) && teEnd.isAfter(result)
                    // This tries to guess arrival based on layovers < 36 hours (not perfect)
                    && Duration.between(result, te.getStart()).toHours() < 36) {
                result = teEnd;
            }
        }
        return result;
    }

    // This only works for flights
    public LocalDateTime getLodgingDepartureDate(final Collection<TripEvent> events, final TripEvent lodgingEvent) {
        if (events == null || events.isEmpty() || lodgingEvent == null) {
            return null;
        }
        // Backup 4 hours in case we're slightly past midnight
        final LocalDateTime lodgingStart = getLodgingArrivalDate(events, lodgingEvent);
        // Default to staying the whole time
        LocalDateTime result = lodgingEvent.getEnd();
        for (final TripEvent te : events) {
            if (te.getType() != TripEvent.Type.FLIGHT) {
                continue;
            }
            final LocalDateTime flight = te.getStart();
            if (flight.isBefore(result) && flight.isAfter(lodgingStart)) {
                // Soonest flight we've seen after we've arrived
                result = flight;
            }
        }
        return result;
    }

    public long getLodgingDays(final LocalDateTime start, final LocalDateTime end) {
        final LocalDateTime adjustedStart = ((start.getHour() < 4) ? start.minusHours(4) : start).truncatedTo(DAYS);
        final LocalDateTime adjustedEnd = end.truncatedTo(DAYS);
        return Duration.between(adjustedStart, adjustedEnd).toDays();
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
        final LocalDateTime cutoff = LocalDateTime.now().minusDays(pastDaysToCountAsActive);
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
        return DAO.getInstance().getTrip(tripId).join().orElse(null);
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

    public BindingCommands getBind() {
        if (bind == null) {
            log.warn("Did not getting BindingCommands injected!");
            bind = new BindingCommands();
        }
        return bind;
    }

    private <T> T logAndReturn(final Throwable ex, final T result) {
        log.warn("Exception!", ex);
        return result;
    }
}
