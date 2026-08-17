package org.paulsens.trip.action;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.BindingType;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.Language;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;

import static java.time.temporal.ChronoUnit.DAYS;
import org.paulsens.trip.cache.Cached;

@Slf4j
@Named("trip")
@ApplicationScoped
public class TripCommands {
    private static final long TIMEOUT = 5_000L;
    /** Cap for the admin/joinable trip-resolution fallbacks (a user's own trips come from the reverse index). */
    private static final int RECENT_TRIP_LIMIT = 100;
    /** How long a finished pilgrimage stays on the public landing page (user-set product rule). */
    private static final int PUBLIC_PAST_DAYS = 7;

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
            result = DAO.getInstance().saveTrip(sortTripPeople(trip));
        } catch (final RuntimeException ex) {
            TripUtilCommands.addFacesMessage(FacesMessage.SEVERITY_ERROR, "Error saving '" + trip.getId()
                    + "': " + trip.getTitle(), ex.getMessage());
            log.error("Error while saving trip: ", ex);
            result = false;
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
        // Advisory pre-read: the save it precedes must survive a failure here.
        final Trip stored = advisoryGetTrip(updated.getId());
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

    /**
     * Sets one person's private note on a trip event and saves the trip.
     *
     * <p>Same trap as {@link #setEventParticipation}, reached a different way. The itinerary's event table is
     * bound to {@code viewScope.userEvents}, which is <b>also</b> the event picker's value -- so the moment
     * someone uses the picker, JSF replaces that list with converter output, and every row in the table is then a
     * detached copy. A note typed after that was written onto an object the following {@code saveTrip} does not
     * serialize. On a freshly loaded page the same edit works, because the list still holds the trip's own
     * instances, which is what makes it look intermittent rather than broken.
     *
     * <p>Resolving by id inside the trip makes the note land on the object that gets written, whatever the row
     * happened to be bound to.
     *
     * @return true when saved; false when the event is not part of this trip.
     */
    public boolean saveEventNote(
            final Trip trip, final TripEvent event, final Person.Id personId, final String note) {
        if (trip == null || event == null || personId == null) {
            return false;
        }
        final TripEvent owned = trip.getTripEvent(event.getId());
        if (owned == null) {
            log.warn("Refusing to note event {}: not part of trip {}", event.getId(), trip.getId());
            return false;
        }
        owned.getPrivNotes().put(personId, (note == null) ? "" : note);
        return saveTrip(trip);
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
                        .map(id -> DAO.getInstance().getPerson(id, Cached.NO))
                        .map(opt -> opt.orElse(null))
                        .filter(Objects::nonNull)
                        .sorted()
                        .map(Person::getId)
                        .toList();
        trip.setPeople(new ArrayList<>(sortedIdList));
        return trip;
    }

    public List<Trip> getActiveTrips(final int pastDaysToCountAsActive) {
        try {
            return DAO.getInstance().getActiveTrips(LocalDateTime.now().minus(pastDaysToCountAsActive, DAYS),
                    Cached.YES);
        } catch (final RuntimeException ex) {
            log.error("Failed to get active trips!", ex);
            return Collections.emptyList();
        }
    }

    /**
     * The landing-page listing: publicly-listed trips ({@code openToPublic}), kept for
     * {@link #PUBLIC_PAST_DAYS} days after they end, oldest start date first. Everything the public page and
     * sidebar show derives from this one index-cached read.
     */
    public List<Trip> getPublicTrips() {
        return getActiveTrips(PUBLIC_PAST_DAYS).stream()
                .filter(trip -> Boolean.TRUE.equals(trip.getOpenToPublic()))
                .sorted(Comparator.comparing(Trip::getStartDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * {@link #getPublicTrips()} for one language. Takes the enum constant's name as a string so EL can pass
     * {@code lang.name()}; a trip with no stored language folds into English (the site default) rather than
     * vanishing from every section.
     */
    public List<Trip> getPublicTrips(final String language) {
        final Language wanted = parseLanguage(language);
        return getPublicTrips().stream()
                .filter(trip -> languageOf(trip) == wanted)
                .toList();
    }

    /** The distinct languages present in {@link #getPublicTrips()}, in declaration (display) order --
     *  drives which language sections the landing page renders, so a new language appears automatically. */
    public List<Language> getPublicTripLanguages() {
        final Set<Language> present = getPublicTrips().stream()
                .map(TripCommands::languageOf)
                .collect(Collectors.toSet());
        return Arrays.stream(Language.values()).filter(present::contains).toList();
    }

    /** The sidebar's link list: {@link #getPublicTrips(String)} restricted to CFPW-hosted trips. */
    public List<Trip> getPublicCfpwTrips(final String language) {
        return getPublicTrips(language).stream().filter(Trip::isCfpw).toList();
    }

    /**
     * The pilgrimages a "Pilgrimage Listings" programmatic content instance shows: its admin-provided
     * properties (language, CFPW-only, max count) applied to the same index-cached public listing. Blank or
     * unparsable properties fall back to everything -- a public page renders permissively, never errors.
     */
    public List<Trip> getPublicTripsFor(final ContentInstance instance) {
        if (instance == null) {
            return List.of();
        }
        final Map<String, String> values = instance.getValues();
        final List<Trip> listed = Boolean.parseBoolean(values.get("cfpwOnly"))
                ? getPublicCfpwTrips(values.get("language"))
                : getPublicTrips(values.get("language"));
        final int max = parsePositive(values.get("maxCount"), Integer.MAX_VALUE);
        return listed.stream().limit(max).toList();
    }

    private static int parsePositive(final String raw, final int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            final int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (final NumberFormatException ignored) {
            return fallback;
        }
    }

    /**
     * The sidebar's countdown cards: for each language, the next CFPW trip that has not started, PLUS any
     * public CFPW trip starting within {@code soonDays} -- deduped, soonest first. CFPW-only on purpose:
     * countdown cards link to the hosted trip-details page, which external pilgrimages do not have.
     */
    public List<Trip> getCountdownTrips(final int soonDays) {
        final LocalDateTime now = LocalDateTime.now();
        final LocalDateTime soon = now.plusDays(soonDays);
        final List<Trip> upcoming = getPublicTrips().stream()
                .filter(Trip::isCfpw)
                .filter(trip -> trip.getStartDate() != null && trip.getStartDate().isAfter(now))
                .toList();
        final Map<Language, Trip> nextPerLanguage = new LinkedHashMap<>();
        for (final Trip trip : upcoming) {
            nextPerLanguage.putIfAbsent(languageOf(trip), trip);
        }
        return upcoming.stream()
                .filter(trip -> nextPerLanguage.containsValue(trip) || !trip.getStartDate().isAfter(soon))
                .toList();
    }

    // No server-side daysUntil: "days until" is CALENDAR-day math in the VIEWER's timezone, which only the
    // browser knows. The sidebar renders each trip's zone-naive start date (Trip.getStartDateIso) into a
    // data- attribute and a small script computes the number client-side. A server-computed count was tried
    // and read one day low for most of each day (elapsed-period truncation + the container's UTC clock).

    /** The languages a trip can be offered in, for the trip editor's menu (replaces a hardcoded list). */
    public List<Language> getLanguages() {
        return List.of(Language.values());
    }

    private static Language languageOf(final Trip trip) {
        return trip.getLanguage() == null ? Language.English : trip.getLanguage();
    }

    private static Language parseLanguage(final String language) {
        if (language == null || language.isBlank()) {
            return Language.English;
        }
        try {
            return Language.valueOf(language.trim());
        } catch (final IllegalArgumentException ex) {
            return Language.English;
        }
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
            try {
                return DAO.getInstance().getInactiveTrips(cutoff, limit, Cached.YES);
            } catch (final RuntimeException ex) {
                log.error("Failed to get inactive trips!", ex);
                return Collections.emptyList();
            }
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
        try {
            return DAO.getInstance().getRecentTrips(limit, Cached.YES);
        } catch (final RuntimeException ex) {
            log.error("Failed to get recent trips!", ex);
            return Collections.emptyList();
        }
    }

    public Trip getTrip(final String id) {
        try {
            return DAO.getInstance().getTrip(id, Cached.YES).orElse(Trip.builder().build());
        } catch (final RuntimeException ex) {
            log.error("Failed to get trip '" + id + "'!", ex);
            return Trip.builder().build();
        }
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
    public Trip getTripForUser(final Trip currTrip, final Person.Id userId, final Boolean showAll,
            final String tripId) {
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

    private Trip advisoryGetTrip(final String tripId) {
        try {
            return DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    /**
     * This person's {@code limit} most recent trips (by start date, newest first) -- the profile page's
     * family section shows each member's recent travel at a glance. Roster membership only; upcoming trips
     * sort first because they have the latest start dates, which is the order a "recent trips" list reads
     * naturally in.
     */
    public List<Trip> recentTripsFor(final Person.Id userId, final int limit) {
        return getTripsForUser(userId).stream()
                .filter(trip -> trip.getStartDate() != null)
                .sorted(Comparator.comparing(Trip::getStartDate).reversed())
                .limit(limit > 0 ? limit : 5)
                .toList();
    }

    public List<Trip> getTripsForUser(final Person.Id userId) {
        try {
            return DAO.getInstance().getTripsForUser(userId, Cached.YES);
        } catch (final RuntimeException ex) {
            log.error("Failed to get trips for user!", ex);
            return Collections.emptyList();
        }
    }

    public TripEvent getTripEvent(final String eventId) {
        try {
            return DAO.getInstance().getTripEvent(eventId, Cached.YES);
        } catch (final RuntimeException ex) {
            return logAndReturn(ex, null);
        }
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
        return DAO.getInstance().getTrip(tripId, Cached.YES).orElse(null);
    }

    /**
     * Ensures the trip either contains the person, or the person is an admin.
     * @param trip      The trip to check.
     * @param userId    The userId to check.
     * @param priv      The user's privileges.
     * @return  True if the user is allowed to see this Trip.
     */
    private boolean canSeeTrip(final Trip trip, final Person.Id userId, final Boolean priv) {
        if ((trip == null) || (userId == null)) {
            return false;
        }
        return trip.getPeople().contains(userId) || ((priv != null) && priv)
                || managesSomeoneOn(trip, userId);
    }

    /**
     * The family case the old FIXME declined: a parent whose kid is on the trip -- but not themselves -- may
     * see the trip. Resolved through {@code managedUsers} (kept in sync from the family row), so admin-granted
     * visibility rides along identically.
     */
    private boolean managesSomeoneOn(final Trip trip, final Person.Id userId) {
        final Person person = PersonCommands.getPersonCommands().getPerson(userId);
        for (final Person.Id managedId : person.getManagedUsers()) {
            if (trip.getPeople().contains(managedId)) {
                return true;
            }
        }
        return false;
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
