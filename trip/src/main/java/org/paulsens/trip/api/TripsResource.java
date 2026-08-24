package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.api.dto.TripDto;
import org.paulsens.trip.api.dto.TripEventDto;
import org.paulsens.trip.api.mapper.TripMapper;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;

/**
 * Trips and their itineraries.
 *
 * <p>Reads are authorized by MEMBERSHIP first and privilege second: a traveller may read a trip they are on
 * without holding any privilege at all, which is the ordinary case and must not require one. Writes need
 * {@code tripMgr} on that trip, or {@code addTrip} to create.
 */
@Slf4j
@Path("trips")
@TripApi
public class TripsResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.TRIPS_V1;
    /** Matches the window the itinerary pages use, so "active" means the same thing in both places. */
    private static final int PAST_DAYS_STILL_ACTIVE = 3;
    private static final int MAX_LIST_LIMIT = 200;

    @Override
    protected String versionedType() {
        return V1;
    }

    /**
     * Trips, scoped by {@code filter}: {@code mine} (default), {@code active}, {@code inactive}, {@code recent}.
     *
     * <p>Itineraries are omitted from every entry here. A traveller on eight trips would otherwise pull every
     * flight, hotel and bus of all eight to render a list of titles.
     */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response list(
            @QueryParam("filter") @DefaultValue("mine") final String filter,
            @QueryParam("limit") @DefaultValue("50") final int limit) {
        final TripCommands trips = Beans.get(TripCommands.class);
        final Person.Id me = personId();
        final boolean admin = privileges().isSiteAdmin();
        final int capped = Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
        final List<Trip> found = switch (filter == null ? "mine" : filter.toLowerCase()) {
            case "active" -> trips.getActiveTrips(PAST_DAYS_STILL_ACTIVE);
            case "inactive" -> trips.getInactiveTrips(me, admin, PAST_DAYS_STILL_ACTIVE, capped);
            case "recent" -> trips.getRecentTrips(capped);
            case "mine" -> trips.getTripsForUser(me);
            default -> null;
        };
        if (found == null) {
            return error(400, ApiErrors.BAD_REQUEST, "Unknown filter; expected mine, active, inactive or recent.");
        }
        // active/recent are not per-user queries, so they are filtered down to what this caller may see. Without
        // this, "active" would list every trip in the system to any signed-in traveller.
        return ok(found.stream()
                .filter(trip -> canRead(trip, me))
                .map(trip -> TripMapper.INSTANCE.toDto(trip).withoutEvents())
                .toList());
    }

    @GET
    @Path("{tripId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(@PathParam("tripId") final String tripId) {
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such trip.");
        }
        final Person.Id me = personId();
        if (!canRead(trip, me)) {
            return error(403, ApiErrors.FORBIDDEN, "Not a member of this trip.");
        }
        return ok(withViewerEvents(trip, me));
    }

    /** The itinerary, with this caller's participation and private note filled in on each event. */
    @GET
    @Path("{tripId}/events")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response events(@PathParam("tripId") final String tripId) {
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such trip.");
        }
        final Person.Id me = personId();
        if (!canRead(trip, me)) {
            return error(403, ApiErrors.FORBIDDEN, "Not a member of this trip.");
        }
        return ok(eventDtos(trip, me));
    }

    @GET
    @Path("{tripId}/events/{eventId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response event(
            @PathParam("tripId") final String tripId, @PathParam("eventId") final String eventId) {
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such trip.");
        }
        final Person.Id me = personId();
        if (!canRead(trip, me)) {
            return error(403, ApiErrors.FORBIDDEN, "Not a member of this trip.");
        }
        final TripEvent event = trip.getTripEvent(eventId);
        if (event == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such event on this trip.");
        }
        return ok(eventDto(event, me));
    }

    /**
     * Lodging arrival/departure, inferred from the surrounding flights.
     *
     * <p>Exposed because the inference is not something a client should reimplement: it treats a layover under
     * 36 hours as part of the same journey, and a client guessing differently would show a different number of
     * nights than the trip pages do for the same booking.
     */
    @GET
    @Path("{tripId}/events/{eventId}/lodging")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response lodging(
            @PathParam("tripId") final String tripId, @PathParam("eventId") final String eventId) {
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such trip.");
        }
        if (!canRead(trip, personId())) {
            return error(403, ApiErrors.FORBIDDEN, "Not a member of this trip.");
        }
        final TripEvent event = trip.getTripEvent(eventId);
        if (event == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such event on this trip.");
        }
        final TripCommands trips = Beans.get(TripCommands.class);
        final var arrival = trips.getLodgingArrivalDate(trip.getTripEvents(), event);
        final var departure = trips.getLodgingDepartureDate(trip.getTripEvents(), event);
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("arrival", arrival);
        result.put("departure", departure);
        result.put("nights", trips.getLodgingDays(arrival, departure));
        return ok(result);
    }

    /** The itinerary item types, for a client building a picker. */
    @GET
    @Path("event-types")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response eventTypes() {
        return ok(Arrays.stream(TripEvent.Type.values())
                .map(type -> Map.of("name", type.name(), "label", type.getDisplayValue()))
                .toList());
    }

    /**
     * Creates a trip. BREAKING CHANGE (org migration, 2026-08): {@code orgId} is required in the body, and
     * authorization is org-scoped -- site admin, an admin of that org, or a holder of {@code addTrip@org}.
     * The old global {@code addTrip} privilege no longer grants anything.
     */
    @POST
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response create(@HeaderParam(CSRF_HEADER) final String csrf, final TripDto body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final String orgId = (body == null) ? null : body.orgId();
        if (orgId == null || orgId.isBlank()) {
            return error(400, ApiErrors.VALIDATION_FAILED, "orgId is required to create a trip.");
        }
        // OrgCommands with THIS request's caller, so the page rule and the REST rule are one rule.
        if (!new OrgCommands(this::caller).canCreateTripFor(orgId)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to create trips for this organization.");
        }
        final TripCommands trips = Beans.get(TripCommands.class);
        final Trip trip = trips.createTrip();
        apply(body, trip);
        if (trip.getOpenToPublic() == null) {
            // Same default the page's create path stamps: never public until someone chooses it.
            trip.setOpenToPublic(false);
        }
        if (!trips.saveTrip(trip)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the trip.");
        }
        // Creator roles, allow-list-bounded; withheld roles were already reported to the support channel.
        new OrgCommands(this::caller).grantCreatorTripRoles(trip);
        return ok(TripMapper.INSTANCE.toDto(trip).withoutEvents());
    }

    @PUT
    @Path("{tripId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response update(
            @PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final TripDto body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!privileges().has(ApiPrivileges.TRIP_MGR, tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip manager required.");
        }
        // Load, mutate, save the SAME object: a DAO read hands back a copy, so editing anything else and saving
        // its parent writes nothing while reporting success.
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such trip.");
        }
        apply(body, trip);
        if (!Beans.get(TripCommands.class).saveTrip(trip)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the trip.");
        }
        return ok(TripMapper.INSTANCE.toDto(trip).withoutEvents());
    }

    /**
     * Joins or leaves itinerary events, for the caller or -- with {@code tripMgr} -- for somebody else.
     *
     * <p>Delegates to {@code TripCommands.setEventParticipation}, which resolves each submitted event back to the
     * instance held by the trip before touching it. That indirection exists because JSF converters hand back
     * detached copies whose edits are never serialized, and it is equally necessary here: the ids in a JSON body
     * are no more the trip's own objects than a converter's copies were.
     */
    @PUT
    @Path("{tripId}/events/participation")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response setParticipation(
            @PathParam("tripId") final String tripId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            @QueryParam("personId") final String personIdParam,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such trip.");
        }
        final Person.Id subject = subjectOf(personIdParam);
        if (!canActFor(subject, tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to change this person's itinerary.");
        }
        final List<TripEvent> selected = new ArrayList<>();
        for (final String eventId : eventIds(body)) {
            final TripEvent event = trip.getTripEvent(eventId);
            if (event == null) {
                return error(400, ApiErrors.BAD_REQUEST, "Unknown event on this trip: " + eventId);
            }
            selected.add(event);
        }
        if (!Beans.get(TripCommands.class).setEventParticipation(trip, selected, subject)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save participation.");
        }
        return ok(eventDtos(trip, subject));
    }

    /** This caller's private note on one event. Private means private: only ever their own. */
    @PUT
    @Path("{tripId}/events/{eventId}/note")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response saveNote(
            @PathParam("tripId") final String tripId,
            @PathParam("eventId") final String eventId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            @QueryParam("personId") final String personIdParam,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such trip.");
        }
        final TripEvent event = trip.getTripEvent(eventId);
        if (event == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such event on this trip.");
        }
        final Person.Id subject = subjectOf(personIdParam);
        if (!canActFor(subject, tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to write this person's note.");
        }
        final Object note = body == null ? null : body.get("note");
        if (!Beans.get(TripCommands.class).saveEventNote(trip, event, subject, note == null ? "" : note.toString())) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the note.");
        }
        return ok(eventDto(trip.getTripEvent(eventId), subject));
    }

    /** Membership is enough to read; a trip role or site admin also is. */
    private boolean canRead(final Trip trip, final Person.Id me) {
        return trip.getPeople().contains(me)
                || privileges().has(ApiPrivileges.TRIP_VIEW, trip.getId())
                || privileges().has(ApiPrivileges.TRIP_MGR, trip.getId());
    }

    /**
     * Acting for yourself is always allowed; acting for somebody else needs tripMgr on that trip, or the
     * managed-users relationship -- the same {@code canActFor} the other person-scoped resources honor (this
     * one predated family accounts and was the odd one out).
     */
    private boolean canActFor(final Person.Id subject, final String tripId) {
        return subject.equals(personId()) || privileges().has(ApiPrivileges.TRIP_MGR, tripId)
                || privileges().canActFor(findPerson(personId()), subject);
    }

    private Person.Id subjectOf(final String personIdParam) {
        return (personIdParam == null || personIdParam.isBlank())
                ? personId() : Person.Id.from(personIdParam);
    }

    @SuppressWarnings("unchecked")
    private static List<String> eventIds(final Map<String, Object> body) {
        final Object ids = body == null ? null : body.get("eventIds");
        return (ids instanceof List<?> list) ? (List<String>) list : List.of();
    }

    private TripDto withViewerEvents(final Trip trip, final Person.Id me) {
        final TripDto dto = TripMapper.INSTANCE.toDto(trip);
        return new TripDto(dto.id(), dto.title(), dto.description(), dto.openToPublic(), dto.chatEnabled(),
                dto.startDate(), dto.endDate(), dto.regLimit(), dto.provider(), dto.orgId(), dto.language(),
                dto.estimatedPrice(), dto.director(), dto.localGuide(), dto.facilitators(), dto.flyerUrl(),
                dto.nonHostedTripUrl(), dto.nonHostedRegNumber(), dto.people(), eventDtos(trip, me));
    }

    private static List<TripEventDto> eventDtos(final Trip trip, final Person.Id viewer) {
        return trip.getTripEvents().stream().map(event -> eventDto(event, viewer)).toList();
    }

    /** Fills in the two viewer-relative fields the mapper deliberately leaves alone. */
    private static TripEventDto eventDto(final TripEvent event, final Person.Id viewer) {
        final TripEventDto dto = TripMapper.INSTANCE.toDto(event);
        return new TripEventDto(dto.id(), dto.type(), dto.title(), dto.notes(), dto.start(), dto.end(),
                dto.participants(), event.getParticipants().contains(viewer), event.getPrivNotes().get(viewer));
    }

    private static void apply(final TripDto body, final Trip trip) {
        if (body == null) {
            return;
        }
        if (body.title() != null) {
            trip.setTitle(body.title());
        }
        if (body.description() != null) {
            trip.setDescription(body.description());
        }
        if (body.openToPublic() != null) {
            trip.setOpenToPublic(body.openToPublic());
        }
        if (body.startDate() != null) {
            trip.setStartDate(body.startDate());
        }
        if (body.endDate() != null) {
            trip.setEndDate(body.endDate());
        }
        if (body.regLimit() != null) {
            trip.setRegLimit(body.regLimit());
        }
        applyDetails(body, trip);
    }

    private static void applyDetails(final TripDto body, final Trip trip) {
        if (body.provider() != null) {
            trip.setProvider(body.provider());
        }
        if (body.orgId() != null) {
            // saveTrip re-syncs provider from the org, so an orgId in the body wins over a provider string.
            trip.setOrgId(body.orgId());
        }
        if (body.estimatedPrice() != null) {
            trip.setEstimatedPrice(body.estimatedPrice());
        }
        if (body.director() != null) {
            trip.setDirector(body.director());
        }
        if (body.localGuide() != null) {
            trip.setLocalGuide(body.localGuide());
        }
        if (body.facilitators() != null) {
            trip.setFacilitators(body.facilitators());
        }
        if (body.flyerUrl() != null) {
            trip.setFlyerUrl(body.flyerUrl());
        }
        if (body.nonHostedTripUrl() != null) {
            trip.setNonHostedTripUrl(body.nonHostedTripUrl());
        }
    }
}
