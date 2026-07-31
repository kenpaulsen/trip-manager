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
import java.util.Set;
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
    /** Cap for the admin/joinable trip-resolution fallbacks (a user's own trips come from the reverse index). */
    private static final int RECENT_TRIP_LIMIT = 100;

    @Inject
    private BindingCommands bind;

    public Trip createTrip() {
        return Trip.builder().build();
    }

    /**
     * The trip-event types, for the event editor's Type menu.
     *
     * <p>Exposed here rather than read off a {@code TripEvent} instance because the list is a property of the
     * enum, not of any event. Reaching it through an event forces the menu to be populated from whatever event is
     * being edited, and anything that resolves the event at view-build time gets a null on the first open -- the
     * component tree is built during RESTORE_VIEW, before the action that selects the event has run. Binding the
     * menu straight to this accessor keeps it correct whenever it renders.</p>
     */
    public List<TripEvent.Type> getTripEventTypes() {
        return List.of(TripEvent.Type.values());
    }

    public boolean saveTrip(final Trip trip) {
        boolean result;
        // Read the roster BEFORE the write, so anyone dropped in this edit can be taken off the chat too. Done
        // here rather than at each Remove button because there are several ways off a trip and they must all
        // agree: a chat still listing people who are no longer on the trip is the wrong answer everywhere.
        final List<Person.Id> removed = peopleRemovedBy(trip);
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
        if (result) {
            ChatCommands.getChatCommands().leaveOnTripRemoval(trip.getId(), removed);
        }
        return result;
    }

    /**
     * Who is on the stored trip but not on the one about to be saved.
     *
     * <p>Best effort by design: if the previous version cannot be read, nobody is reported and the chat roster is
     * left alone. Failing to tidy the chat must never fail the trip save that prompted it.
     */
    private List<Person.Id> peopleRemovedBy(final Trip updated) {
        if (updated == null || updated.getId() == null) {
            return List.of();
        }
        final Trip stored = DAO.getInstance().getTrip(updated.getId()).join().orElse(null);
        if (stored == null) {
            return List.of();
        }
        final List<Person.Id> keeping = updated.getPeople();
        return stored.getPeople().stream().filter(id -> id != null && !keeping.contains(id)).toList();
    }

    /**
     * Sets exactly which of a trip's events a person takes part in, and saves the trip once.
     *
     * <p>Exists because the page could not safely do this itself. The itinerary's event picker passes its values
     * through {@code TripEventConverter}, which loads from the DAO -- and since the persistence redesign a DAO
     * read deserializes a <b>new</b> object rather than handing back a shared one. The page mutated that detached
     * copy and then saved the trip, which serializes the instances the trip holds, so the edit was written
     * nowhere. It looked like it worked, because the table it updated was bound to the selection rather than to
     * anything stored. Resolving each event by id inside the trip is what makes the change part of the save.
     *
     * <p>Equality is no defence here: {@code TripEvent} is a {@code @Data} class, so the detached copy is
     * {@code equals} to the trip's own instance and every {@code contains} check still behaved. Only identity
     * differs, and only identity matters at save time.
     *
     * @param trip      the trip to modify and save.
     * @param selected  the events the person should be in; any not listed they are removed from.
     * @param personId  whose participation this is.
     * @return true when saved, or when there was nothing to change.
     */
    public boolean setEventParticipation(
            final Trip trip, final Collection<TripEvent> selected, final Person.Id personId) {
        if (trip == null || personId == null) {
            return false;
        }
        final Set<String> wanted = (selected == null) ? Set.of() : selected.stream()
                .filter(Objects::nonNull)
                .map(TripEvent::getId)
                .collect(Collectors.toSet());
        boolean changed = false;
        // Iterate the TRIP's events, never the submitted ones -- these are the objects saveTrip will serialize.
        for (final TripEvent event : trip.getTripEvents()) {
            changed |= setParticipation(event, personId, wanted.contains(event.getId()));
        }
        // Saving unconditionally would write every event on the trip on any stray ajax event, which is both
        // wasteful and a needless way to lose a concurrent edit.
        return !changed || saveTrip(trip);
    }

    /** @return whether this event's participant list actually changed. */
    private boolean setParticipation(final TripEvent event, final Person.Id personId, final boolean participating) {
        final List<Person.Id> current = event.getParticipants();
        if (current.contains(personId) == participating) {
            return false;
        }
        final List<Person.Id> updated = new ArrayList<>(current);
        if (participating) {
            updated.add(personId);
        } else {
            updated.remove(personId);
        }
        event.setParticipants(updated);
        return true;
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
        return DAO.getInstance().getActiveTrips(LocalDateTime.now().minus(pastDaysToCountAsActive, DAYS))
                .exceptionally(ex -> {
                    log.error("Failed to get active trips!", ex);
                    return Collections.emptyList();
                }).join();
    }

    /**
     * Inactive (past) trips. Admins get every past trip capped at {@code limit} (most recent first); non-admins get
     * only their own past trips. {@code limit} is supplied by the caller (e.g. the menu page) so it is configurable
     * without a code change; non-positive means no cap.
     */
    public List<Trip> getInactiveTrips(
            final Person.Id userId, final boolean isAdmin, final int pastDaysStillActive, final int limit) {
        final LocalDateTime cutoff = LocalDateTime.now().minus(pastDaysStillActive, DAYS);
        if (isAdmin) {
            return DAO.getInstance().getInactiveTrips(cutoff, limit)
                    .exceptionally(ex -> {
                        log.error("Failed to get inactive trips!", ex);
                        return Collections.emptyList();
                    }).join();
        }
        return getTripsForUser(userId).stream()
                .filter(trip -> trip.getEndDate() != null && trip.getEndDate().isBefore(cutoff))
                .sorted(Comparator.comparing(Trip::getStartDate).reversed())
                .limit(limit > 0 ? limit : Long.MAX_VALUE)
                .toList();
    }

    /**
     * The {@code limit} most-recently-ending trips (admin pickers), served from the trip index -- not a full-table
     * scan. Callers (e.g. XHTML dropdowns) pass the cap so it is configurable without a code change.
     */
    public List<Trip> getRecentTrips(final int limit) {
        return DAO.getInstance().getRecentTrips(limit)
                .exceptionally(ex -> {
                    log.error("Failed to get recent trips!", ex);
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
            // Anything the user can see... or null. The user's own trips come from the reverse index (unbounded
            // per user); the admin "see any trip" and joinable fallbacks only need recent trips (joinable trips
            // start in the future, so they are always among the most recent).
            result = findTrip(getTripsForUser(userId), userId, false); // Try w/o considering admin privs
            if (result == null && showAll) {
                result = findTrip(getRecentTrips(RECENT_TRIP_LIMIT), userId, showAll);
            }
            if (result == null) {
                // See if there's anything they can join
                result = getRecentTrips(RECENT_TRIP_LIMIT).stream()
                        .filter(trip -> trip.canJoin(userId)).findAny().orElse(null);
            }
        }
        return result;
    }

    public List<Trip> getTripsForUser(final Person.Id userId) {
        return DAO.getInstance().getTripsForUser(userId)
                .exceptionally(ex -> {
                    log.error("Failed to get trips for user!", ex);
                    return Collections.emptyList();
                }).join();
    }

    public TripEvent getTripEvent(final String eventId) {
        return DAO.getInstance().getTripEvent(eventId)
                .orTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> logAndReturn(ex, null))
                .join();
    }

    // This only works for flights
    public LocalDateTime getLodgingArrivalDate(final Collection<TripEvent> events, final TripEvent lodgingEvent) {
        if (events == null || lodgingEvent == null) {
            return null;
        }
        final List<TripEvent> sorted = new ArrayList<>(
                events.stream().filter(e -> e.getType() == TripEvent.Type.FLIGHT).toList());
        sorted.sort(Comparator.comparing(TripEvent::getEnd));
        if (sorted.isEmpty()) {
            return null;
        }
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
        if (start == null || end == null) {
            return -1L;
        }
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
