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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.ItineraryAccess;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.action.PaymentCommands;
import org.paulsens.trip.action.ProfilePhotos;
import org.paulsens.trip.action.LodgingCommands;
import org.paulsens.trip.action.LodgingViews;
import org.paulsens.trip.action.TripPageCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.api.dto.RegChoiceDto;
import org.paulsens.trip.api.dto.RegOptionDto;
import org.paulsens.trip.api.dto.SharedItineraryDto;
import org.paulsens.trip.api.dto.TripDto;
import org.paulsens.trip.config.KnownSettings;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.LocalMode;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.FeesPaidBy;
import org.paulsens.trip.model.TripPaymentConfig;
import org.paulsens.trip.model.TripPageOptions;
import org.paulsens.trip.api.dto.TripPaymentConfigDto;
import org.paulsens.trip.api.dto.TripPageOptionsDto;
import org.paulsens.trip.api.dto.TripEventDto;
import org.paulsens.trip.api.dto.TripRosterDto;
import org.paulsens.trip.api.mapper.TripMapper;
import org.paulsens.trip.model.OptionChoice;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripKind;
import org.paulsens.trip.site.SiteContext;
import org.paulsens.trip.site.SiteUrls;
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
    private static final String NOT_THEIR_ITINERARY = "Not permitted to view this person's itinerary.";
    private static final int MAX_LIST_LIMIT = 200;
    private static final String PAYMENTS_ADMIN_REQUIRED =
            "Payment settings need the organization's Payments Admin privilege.";

    @Override
    protected String versionedType() {
        return V1;
    }

    /**
     * Trips, scoped by {@code filter}: {@code mine} (default), {@code active}, {@code inactive}, {@code recent},
     * {@code open} -- the last being the publicly listed, joinable trips of this host (the landing page's list),
     * readable by any signed-in person because it is what the anonymous public already sees. The public never
     * sees who is traveling, though, so an {@code open} entry the caller could not otherwise read carries only
     * the caller and the people they manage in {@code people} ({@link #openListingDto}).
     *
     * <p>Itineraries are omitted from every entry here. A traveller on eight trips would otherwise pull every
     * flight, hotel and bus of all eight to render a list of titles.
     *
     * <p>{@code mine} takes an optional {@code personId}: a family manager acting as a member lists THAT
     * member's trips (yourself, someone you manage, or a site admin; 403 otherwise). The other filters are not
     * per-user and ignore it. Every entry still passes {@link #canReadTrip} for the CALLER.
     */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response list(
            @QueryParam("filter") @DefaultValue("mine") final String filter,
            @QueryParam("limit") @DefaultValue("50") final int limit,
            @QueryParam("personId") final String personIdParam) {
        final TripCommands trips = Beans.get(TripCommands.class);
        final Person.Id me = personId();
        final Person.Id subject = subjectOf(personIdParam);
        final boolean admin = privileges().isSiteAdmin();
        if (!subject.equals(me) && !admin && !actsFor(subject)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to list this person's trips.");
        }
        final int capped = Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
        final List<Trip> found = switch (filter == null ? "mine" : filter.toLowerCase()) {
            case "active" -> trips.getActiveTrips(PAST_DAYS_STILL_ACTIVE);
            case "inactive" -> trips.getInactiveTrips(me, admin, PAST_DAYS_STILL_ACTIVE, capped);
            case "recent" -> trips.getRecentTrips(capped);
            case "mine" -> trips.getTripsForUser(subject);
            case "open" -> trips.getPublicTrips();
            default -> null;
        };
        if (found == null) {
            return error(400, ApiErrors.BAD_REQUEST,
                    "Unknown filter; expected mine, active, inactive, recent or open.");
        }
        final boolean listing = "open".equalsIgnoreCase(filter);
        // active/recent are not per-user queries, so they are filtered down to what this caller may see. Without
        // this, "active" would list every trip in the system to any signed-in traveller. On an organization's
        // host the list is further that org's alone -- the site boundary holds for API clients too.
        final SiteContext site = SiteContext.current();
        final Set<String> own = listing ? ownAndManaged(me) : Set.of();
        return ok(found.stream()
                .filter(trip -> site.admits(trip.getOrgId()))
                .filter(trip -> listing || canReadTrip(trip))
                .map(trip -> listing ? openListingDto(trip, own) : listingDto(trip))
                .toList());
    }

    private TripDto listingDto(final Trip trip) {
        return TripMapper.INSTANCE.toDto(trip).withoutEvents().withDetailsUrl(detailsUrl(trip));
    }

    /**
     * An {@code open} listing's entry. The listing skips the membership check on purpose, but the roster is
     * the one field on it that membership authorizes ({@link #roster}): sent whole it handed any signed-in
     * account the person ids of everyone booked on every public trip, across organizations. A caller who
     * cannot read the trip gets {@code people} narrowed to themselves and the people they manage, which is
     * all the apps ask of it there ("am I, or is my family, on this trip?").
     */
    private TripDto openListingDto(final Trip trip, final Set<String> own) {
        final TripDto dto = listingDto(trip);
        if (canReadTrip(trip) || dto.people() == null) {
            return dto;
        }
        return dto.withPeople(dto.people().stream().filter(own::contains).toList());
    }

    /** The caller's id and the ids of everyone they manage, as the wire spells them. */
    private Set<String> ownAndManaged(final Person.Id me) {
        final Set<String> ids = new LinkedHashSet<>();
        ids.add(me.getValue());
        final Person person = findPerson(me);
        if (person != null) {
            person.getManagedUsers().forEach(managed -> ids.add(managed.getValue()));
        }
        return ids;
    }

    /**
     * The absolute address of the trip's embedded page ({@code trip/tripPage.jsf}, docs/conference.md "The embed")
     * on the site of the ORGANIZATION that owns the trip ({@code SiteUrls}, the base its tickets and emails link
     * to): its own subdomain, else the "Link base URL" setting. Never this request's host: the app talks to the API
     * on the product host, where an ordinary page reaches only the trips the marketing page lists, so a link built
     * there answered "This page is not available." for the rest (2026-11-08). Local mode keeps the request's host,
     * since the link-base setting names the production site there.
     */
    private String detailsUrl(final Trip trip) {
        return detailsUrl(trip, LocalMode.isLocal(), this::absoluteUrl, new ConfigCommands());
    }

    /** {@link #detailsUrl(Trip)} on explicit facts (the testable half). */
    static String detailsUrl(final Trip trip, final boolean local,
            final UnaryOperator<String> requestAbsolute, final ConfigCommands config) {
        final String path = "/trip/tripPage.jsf?trip=" + trip.getId();
        return local ? requestAbsolute.apply(path)
                : SiteUrls.baseUrlForTrip(trip, KnownSettings.REG_MAIL_BASE_URL, config) + path;
    }

    /**
     * The trip with its itinerary, viewer-relative fields filled in for the SUBJECT: the caller, or the family
     * member named by {@code personId} (the app's "acting as"; the website shows the same under its "Viewing:"
     * chip). The trip gate is the caller's ({@link #canReadTrip}); the subject gate is {@link #canActFor}.
     */
    @GET
    @Path("{tripId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(@PathParam("tripId") final String tripId,
            @QueryParam("personId") final String personIdParam) {
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such trip.");
        }
        if (!canReadTrip(trip)) {
            return error(403, ApiErrors.FORBIDDEN, "Not a member of this trip.");
        }
        final Person.Id subject = subjectOf(personIdParam);
        if (!canActFor(subject, tripId)) {
            return error(403, ApiErrors.FORBIDDEN, NOT_THEIR_ITINERARY);
        }
        // The page options are the managers' business; the payment settings (processor, who pays fees, the
        // mail addresses) are the organization's payments admins', exactly whom the trip editor shows its
        // Payment Settings to. A manager without that grant neither reads nor writes them.
        return ok(withViewerEvents(trip, subject).redactedFor(payments().isPaymentsAdmin(trip),
                privileges().has(ApiPrivileges.TRIP_MGR, tripId)));
    }

    /** The payments bean bound to THIS caller: {@code isPaymentsAdmin} is the trip editor's own rule. */
    private PaymentCommands payments() {
        return new PaymentCommands(this::caller);
    }

    /**
     * The itinerary, with the subject's participation and private note filled in on each event. The private
     * note is the SUBJECT's own: a manager reading a member's itinerary sees the member's note, as on the
     * website, and a MANAGER already reads that member's travel documents ({@code AccessLevel}).
     */
    @GET
    @Path("{tripId}/events")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response events(@PathParam("tripId") final String tripId,
            @QueryParam("personId") final String personIdParam) {
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such trip.");
        }
        if (!canReadTrip(trip)) {
            return error(403, ApiErrors.FORBIDDEN, "Not a member of this trip.");
        }
        final Person.Id subject = subjectOf(personIdParam);
        if (!canActFor(subject, tripId)) {
            return error(403, ApiErrors.FORBIDDEN, NOT_THEIR_ITINERARY);
        }
        return ok(eventDtos(trip, subject));
    }

    @GET
    @Path("{tripId}/events/{eventId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response event(
            @PathParam("tripId") final String tripId, @PathParam("eventId") final String eventId,
            @QueryParam("personId") final String personIdParam) {
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such trip.");
        }
        if (!canReadTrip(trip)) {
            return error(403, ApiErrors.FORBIDDEN, "Not a member of this trip.");
        }
        final Person.Id subject = subjectOf(personIdParam);
        if (!canActFor(subject, tripId)) {
            return error(403, ApiErrors.FORBIDDEN, NOT_THEIR_ITINERARY);
        }
        final TripEvent event = trip.getTripEvent(eventId);
        if (event == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such event on this trip.");
        }
        return ok(eventDto(event, subject));
    }

    /**
     * Lodging arrival/departure for the subject (the caller, or the family member named by {@code personId}):
     * the dates of their own reservation on the event when they hold one (what the itinerary page shows, with
     * the room when the trip reveals numbers), else the flight-inferred dates.
     *
     * <p>The inference is exposed because a client should not reimplement it: it treats a layover under 36
     * hours as part of the same journey. But it looks at EVERY flight on the trip, so an early arriver's
     * flight used to move everyone's hotel dates in the app (2026-09-14); the reservation is the truth when
     * it exists.
     */
    @GET
    @Path("{tripId}/events/{eventId}/lodging")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response lodging(
            @PathParam("tripId") final String tripId, @PathParam("eventId") final String eventId,
            @QueryParam("personId") final String personIdParam) {
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such trip.");
        }
        if (!canReadTrip(trip)) {
            return error(403, ApiErrors.FORBIDDEN, "Not a member of this trip.");
        }
        final Person.Id subject = subjectOf(personIdParam);
        if (!canActFor(subject, tripId)) {
            return error(403, ApiErrors.FORBIDDEN, NOT_THEIR_ITINERARY);
        }
        final TripEvent event = trip.getTripEvent(eventId);
        if (event == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such event on this trip.");
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        final LodgingViews.ItineraryRow reserved = reservationRow(trip, event, subject);
        if (reserved != null) {
            result.put("arrival", reserved.getEffectiveStart());
            result.put("departure", reserved.getEffectiveEnd());
            result.put("nights", (long) reserved.getNights());
            result.put("fromReservation", true);
            result.put("accommodation", reserved.getAccommodationName());
            result.put("roomType", reserved.getRoomTypeName());
            result.put("room", reserved.getRoomLabel());
            result.put("reservationNotes", reserved.getReservationNotes());
            return ok(result);
        }
        final TripCommands trips = Beans.get(TripCommands.class);
        final var arrival = trips.getLodgingArrivalDate(trip.getTripEvents(), event);
        final var departure = trips.getLodgingDepartureDate(trip.getTripEvents(), event);
        result.put("arrival", arrival);
        result.put("departure", departure);
        result.put("nights", trips.getLodgingDays(arrival, departure));
        result.put("fromReservation", false);
        return ok(result);
    }

    /** The caller's itinerary row for this event when it carries one of their reservations, else null. */
    private static LodgingViews.ItineraryRow reservationRow(final Trip trip, final TripEvent event,
            final Person.Id me) {
        for (final LodgingViews.ItineraryRow row : Beans.get(LodgingCommands.class).itineraryRowsFor(trip, me)) {
            if (event.getId().equals(row.getId()) && row.getReservationId() != null) {
                return row;
            }
        }
        return null;
    }

    /**
     * Who is on this trip, as names: the roster plus the director(s) and facilitator(s), each with the
     * roles they hold and a profile photo URL when they have one. Membership authorizes the answer -- the
     * same rule as the trip itself -- and the shape is deliberately names-only (PEER-visible fields), so a
     * traveller learns who is travelling with them and nothing more. The bulk person search stays behind
     * {@code peopleAdmin}; this is the roster a member is entitled to.
     */
    @GET
    @Path("{tripId}/roster")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response roster(@PathParam("tripId") final String tripId) {
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such trip.");
        }
        if (!canReadTrip(trip)) {
            return error(403, ApiErrors.FORBIDDEN, "Not a member of this trip.");
        }
        final List<Person.Id> directors = trip.getDirectorIds() == null ? List.of() : trip.getDirectorIds();
        final List<Person.Id> facilitators = trip.getFacilitatorIds() == null ? List.of() : trip.getFacilitatorIds();
        final Set<Person.Id> everyone = new LinkedHashSet<>(trip.getPeople());
        everyone.addAll(directors);
        everyone.addAll(facilitators);
        final ProfilePhotos photos = Beans.get(ProfilePhotos.class);
        final RosterViewer viewer = rosterViewer(trip);
        final List<TripRosterDto.RosterEntryDto> entries = new ArrayList<>();
        for (final Person.Id id : everyone) {
            final Person person = findPerson(id);
            if (person == null) {
                continue;   // a stale roster id (soft-deleted, or the row is gone) is not this caller's problem
            }
            entries.add(rosterEntry(person, trip, directors, facilitators, photos, viewer));
        }
        return ok(new TripRosterDto(trip.getId(), entries, directors.stream().map(Person.Id::getValue).toList(),
                facilitators.stream().map(Person.Id::getValue).toList(), trip.getLocalGuide()));
    }

    /**
     * Who is looking at a roster, resolved once per call: their record, and whether they get the admin (masked)
     * contact view -- the web contacts page's {@code tripAdmin || showAll}: Trip Manager, org admin, site admin.
     */
    private record RosterViewer(Person person, boolean adminView, boolean orgAdmin) {
    }

    private RosterViewer rosterViewer(final Trip trip) {
        final boolean orgAdmin = new OrgCommands(this::caller).canManageOrg(trip.getOrgId());
        return new RosterViewer(findPerson(personId()),
                orgAdmin || privileges().has(ApiPrivileges.TRIP_MGR, trip.getId()), orgAdmin);
    }

    private TripRosterDto.RosterEntryDto rosterEntry(final Person person, final Trip trip,
            final List<Person.Id> directors, final List<Person.Id> facilitators, final ProfilePhotos photos,
            final RosterViewer viewer) {
        final List<String> roles = new ArrayList<>();
        if (trip.getPeople().contains(person.getId())) {
            roles.add(TripRosterDto.ROLE_MEMBER);
        }
        if (directors.contains(person.getId())) {
            roles.add(TripRosterDto.ROLE_DIRECTOR);
        }
        if (facilitators.contains(person.getId())) {
            roles.add(TripRosterDto.ROLE_FACILITATOR);
        }
        final String personId = person.getId().getValue();
        final String photoUrl = photos.hasPhoto(personId) ? absoluteUrl(photos.getUrl(personId)) : null;
        final ItineraryAccess.Level itinerary = ItineraryAccess.of(caller(), viewer.person(), trip, person,
                viewer::orgAdmin);
        return new TripRosterDto.RosterEntryDto(personId, person.getFirst(), person.getLast(), person.getNickname(),
                person.getPreferredName(), photoUrl, roles,
                itinerary == ItineraryAccess.Level.NONE ? null : itinerary.name(),
                RosterContact.of(viewer.person(), person, viewer.adminView()));
    }

    /**
     * Another person's itinerary as the trip's SHARED event data: the events they are on, with no private note,
     * no participation flag of the caller's and no lodging overlay (docs/shared-itineraries.md). The one gate is
     * {@code ItineraryAccess}, so a caller who may not see it gets 403 whatever the reason -- the trip does not
     * share itineraries, the person opted out, or they are not on the roster. A FULL viewer gets the same shape
     * here; the person-specific view is the {@code ?personId=} reads above.
     */
    @GET
    @Path("{tripId}/people/{personId}/itinerary")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response sharedItinerary(@PathParam("tripId") final String tripId,
            @PathParam("personId") final String personIdParam) {
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such trip.");
        }
        final Person subject = findPerson(Person.Id.from(personIdParam));
        if (subject == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such person.");
        }
        final ItineraryAccess.Level level = ItineraryAccess.of(caller(), findPerson(personId()), trip, subject,
                () -> new OrgCommands(this::caller).canManageOrg(trip.getOrgId()));
        if (level == ItineraryAccess.Level.NONE) {
            return error(403, ApiErrors.ITINERARY_NOT_SHARED, "This person's itinerary is not shared with you.");
        }
        final List<TripEventDto> events = trip.getTripEventsForUser(subject.getId()).stream()
                .map(TripsResource::sharedEventDto)
                .toList();
        return ok(new SharedItineraryDto(subject.getId().getValue(), events));
    }

    /** {@link #eventDto} without either viewer-relative field: nothing on it belongs to any one person. */
    private static TripEventDto sharedEventDto(final TripEvent event) {
        final TripEventDto dto = TripMapper.INSTANCE.toDto(event);
        return new TripEventDto(dto.id(), dto.type(), dto.title(), dto.notes(), dto.start(), dto.end(),
                dto.participants(), null, null, hasAnyDetail(event) ? new LinkedHashMap<>(event.getDetails()) : null);
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
        // OrgCommands with THIS request's caller, so the page rule and the REST rule are one rule. A conference
        // has its own gate (docs/conference.md, "Access"); a trip-creator may not make one.
        final OrgCommands orgs = new OrgCommands(this::caller);
        final boolean conference = isConference(body.kind());
        if (conference ? !orgs.canCreateConferenceFor(orgId) : !orgs.canCreateTripFor(orgId)) {
            return error(403, ApiErrors.FORBIDDEN, conference
                    ? "Not permitted to create conferences for this organization (Create Conferences required)."
                    : "Not permitted to create trips for this organization.");
        }
        if (eventsCarryIds(body)) {
            return error(400, ApiErrors.VALIDATION_FAILED, "tripEvents on a write are append-only: ids "
                    + "must be null (editing an existing event is the editor page's job).");
        }
        final String badValue = badEnumValue(body);
        if (badValue != null) {
            return error(400, ApiErrors.VALIDATION_FAILED, badValue);
        }
        final String paymentProblem = paymentConfigProblem(body, orgId, null);
        if (paymentProblem != null) {
            return error(400, ApiErrors.VALIDATION_FAILED, paymentProblem);
        }
        final TripCommands trips = Beans.get(TripCommands.class);
        final Trip trip = trips.createTrip();
        // Creating a trip is not a license to set its payment settings: addTrip@org is not paymentsAdmin@org.
        if (writesPaymentConfig(body, trip) && !isPaymentsAdminOf(orgId)) {
            return error(403, ApiErrors.FORBIDDEN, PAYMENTS_ADMIN_REQUIRED);
        }
        apply(body, trip);
        if (trip.getOpenToPublic() == null) {
            // Same default the page's create path stamps: never public until someone chooses it.
            trip.setOpenToPublic(false);
        }
        TripCommands.stampCreationDefaults(trip);
        final String pageProblem = pageProblem(trip);
        if (pageProblem != null) {
            return error(400, ApiErrors.VALIDATION_FAILED, pageProblem);
        }
        if (!trips.saveTrip(trip, orgs)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the trip.");
        }
        // Creator roles, allow-list-bounded; withheld roles were already reported to the support channel.
        new OrgCommands(this::caller).grantCreatorTripRoles(trip);
        return ok(listingDto(trip));
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
        if (eventsCarryIds(body)) {
            return error(400, ApiErrors.VALIDATION_FAILED, "tripEvents on a write are append-only: ids "
                    + "must be null (editing an existing event is the editor page's job).");
        }
        final String badValue = badEnumValue(body);
        if (badValue != null) {
            return error(400, ApiErrors.VALIDATION_FAILED, badValue);
        }
        final OrgCommands orgs = new OrgCommands(this::caller);
        final String fromOrg = trip.getOrgId();
        final boolean moved = movesOrg(body, trip);
        // A trip manager manages the trip, not which organization owns it: the editor page never changes a
        // trip's org, and the tenancy boundary is not theirs to move a trip (and its roster) across.
        if (moved && !privileges().isSiteAdmin()
                && !(orgs.canManageOrg(fromOrg) && orgs.canManageOrg(body.orgId()))) {
            return error(403, ApiErrors.FORBIDDEN,
                    "Moving a trip to another organization needs an administrator of both organizations.");
        }
        // Payment settings and people are checked against the org the trip will END UP in, not the one it
        // is leaving (applyPeople reads the body's orgId the same way).
        final String toOrg = moved ? body.orgId() : fromOrg;
        final String paymentProblem = paymentConfigProblem(body, toOrg, trip.getPaymentConfig().getMailFrom());
        if (paymentProblem != null) {
            return error(400, ApiErrors.VALIDATION_FAILED, paymentProblem);
        }
        // An echoed, unchanged paymentConfig is not a payment write: a manager's client that sends back what
        // it read (or nothing) keeps working without the payments grant.
        if (writesPaymentConfig(body, trip) && !isPaymentsAdminOf(toOrg)) {
            return error(403, ApiErrors.FORBIDDEN, PAYMENTS_ADMIN_REQUIRED);
        }
        final String carried = moved ? carriedPaymentProblem(paymentConfigAfter(body, trip), toOrg) : null;
        if (carried != null) {
            // The overrides named the OLD org's processor account or sending domain; neither follows the trip
            // to a new owner.
            return error(400, ApiErrors.VALIDATION_FAILED, carried);
        }
        if (body.kind() != null && isConference(body.kind()) != trip.isConference()
                && !orgs.canCreateConferenceFor(trip.getOrgId())) {
            return error(403, ApiErrors.FORBIDDEN,
                    "Only a holder of Create Conferences for the organization may make or unmake a conference.");
        }
        apply(body, trip);
        final String pageProblem = pageProblem(trip);
        if (pageProblem != null) {
            // The trip is this request's own copy (DAO reads are copies), so a refusal here changes nothing stored.
            return error(400, ApiErrors.VALIDATION_FAILED, pageProblem);
        }
        if (!Beans.get(TripCommands.class).saveTrip(trip, orgs)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the trip.");
        }
        return ok(listingDto(trip));
    }

    /** Whether the body names an organization other than the one the trip belongs to. */
    private static boolean movesOrg(final TripDto body, final Trip trip) {
        return body != null && body.orgId() != null && !body.orgId().equals(trip.getOrgId());
    }

    /**
     * The trip's payment settings as the body would leave them: a copy with the body applied, the trip
     * untouched, so the answer is by definition what {@link #applyPaymentConfig} will do. Call after
     * {@link #paymentConfigProblem} (an unknown {@code feesPaidBy} is its to refuse).
     */
    private static TripPaymentConfig paymentConfigAfter(final TripDto body, final Trip trip) {
        final TripPaymentConfig after = trip.getPaymentConfig().overlayOn(null);
        applyPaymentConfig(body == null ? null : body.paymentConfig(), after);
        return after;
    }

    /**
     * Whether the body would CHANGE the trip's payment settings (an absent or merely echoed one would not).
     * Compared copy to copy, so the copy's own normalization (a blank reads as unset) is never the difference.
     */
    private static boolean writesPaymentConfig(final TripDto body, final Trip trip) {
        return !paymentConfigAfter(body, trip).equals(trip.getPaymentConfig().overlayOn(null));
    }

    /**
     * The trip editor's Payment Settings rule asked of an ORGANIZATION: the one a new trip is being created
     * in, or the one an update leaves the trip in.
     */
    private boolean isPaymentsAdminOf(final String orgId) {
        return payments().isPaymentsAdminOf(orgId);
    }

    private static boolean isConference(final String kind) {
        return kind != null && org.paulsens.trip.model.TripKind.CONFERENCE.name().equalsIgnoreCase(kind.trim());
    }

    /**
     * Edits one itinerary event: type, title, notes, start and end, each left alone when absent. Participants
     * and private notes have their own endpoints and are never touched here. Trip managers only. Added
     * 2026-10-16 for the App Store review seed, which moves the demo trip's dates on every run and had no way
     * to move the itinerary with them (a trip write is append-only for events).
     */
    @PUT
    @Path("{tripId}/events/{eventId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response updateEvent(
            @PathParam("tripId") final String tripId,
            @PathParam("eventId") final String eventId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final TripEventDto body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!privileges().has(ApiPrivileges.TRIP_MGR, tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip manager required.");
        }
        final Trip trip = findTrip(tripId);
        if (trip == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such trip.");
        }
        // The trip's OWN instance, for the same reason setParticipation resolves ids: a copy saves nothing.
        final TripEvent event = trip.getTripEvent(eventId);
        if (event == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such event on this trip.");
        }
        if (body == null) {
            return error(400, ApiErrors.BAD_REQUEST, "A body is required.");
        }
        if (body.type() != null) {
            final TripEvent.Type type = eventType(body.type());
            if (type == null) {
                return error(400, ApiErrors.VALIDATION_FAILED, "Unknown event type: " + body.type());
            }
            event.setType(type);
        }
        if (body.title() != null) {
            event.setTitle(body.title());
        }
        if (body.notes() != null) {
            event.setNotes(body.notes());
        }
        if (body.start() != null) {
            event.setStart(body.start());
        }
        if (body.end() != null) {
            event.setEnd(body.end());
        }
        if (!Beans.get(TripCommands.class).saveTrip(trip, new OrgCommands(this::caller))) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the trip.");
        }
        return ok(eventDto(event, personId()));
    }

    private static TripEvent.Type eventType(final String name) {
        for (final TripEvent.Type type : TripEvent.Type.values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
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
        // The trip gate first, as on every itinerary read: acting "for yourself" is always allowed, so by
        // itself it let any signed-in account join the events of a trip it has nothing to do with.
        if (!canReadTrip(trip)) {
            return error(403, ApiErrors.FORBIDDEN, "Not a member of this trip.");
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
        // Before the event lookup, so a stranger learns nothing about which event ids exist.
        if (!canReadTrip(trip)) {
            return error(403, ApiErrors.FORBIDDEN, "Not a member of this trip.");
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

    /**
     * Acting for yourself is always allowed; acting for somebody else needs tripMgr on that trip, or the
     * managed-users relationship -- the same {@code actsFor} the other person-scoped resources honor (this
     * one predated family accounts and was the odd one out).
     */
    private boolean canActFor(final Person.Id subject, final String tripId) {
        return actsFor(subject) || privileges().has(ApiPrivileges.TRIP_MGR, tripId);
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
                dto.nonHostedTripUrl(), dto.nonHostedRegNumber(), dto.regOptions(), dto.people(),
                eventDtos(trip, me), dto.kind(), dto.paymentConfig(), dto.page(), detailsUrl(trip));
    }

    private static List<TripEventDto> eventDtos(final Trip trip, final Person.Id viewer) {
        return trip.getTripEvents().stream().map(event -> eventDto(event, viewer)).toList();
    }

    /**
     * Fills in the two viewer-relative fields the mapper deliberately leaves alone, and sends the structured
     * details only when the event has some (an empty map would read as "structured, but blank").
     */
    private static TripEventDto eventDto(final TripEvent event, final Person.Id viewer) {
        final TripEventDto dto = TripMapper.INSTANCE.toDto(event);
        return new TripEventDto(dto.id(), dto.type(), dto.title(), dto.notes(), dto.start(), dto.end(),
                dto.participants(), event.getParticipants().contains(viewer), event.getPrivNotes().get(viewer),
                hasAnyDetail(event) ? new LinkedHashMap<>(event.getDetails()) : null);
    }

    /** Transport components OR agenda fields: the app shows both, so any stored detail is sent. */
    private static boolean hasAnyDetail(final TripEvent event) {
        return event.getDetails() != null && !event.getDetails().isEmpty();
    }

    private static void apply(final TripDto body, final Trip trip) {
        if (body == null) {
            return;
        }
        if (body.title() != null) {
            trip.setTitle(body.title());
        }
        applyPeople(body, trip);
        applyRegOptions(body, trip);
        appendEvents(body, trip);
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
        if (body.kind() != null) {
            trip.setKind(parseKind(body.kind()));
        }
        applyDetails(body, trip);
        applyPaymentConfig(body.paymentConfig(), trip.getPaymentConfig());
        applyPage(body, trip);
    }

    /**
     * The conference page's own look, field by field, the {@link #applyPaymentConfig} way: an absent field is left
     * alone, an empty string clears it back to "the organization's", anything else is set. Nothing is allocated
     * on a trip whose body carries no options.
     */
    private static void applyPage(final TripDto body, final Trip trip) {
        final TripPageOptionsDto options = body.page();
        if (options == null) {
            return;
        }
        final TripPageOptions target = trip.getPage();
        if (options.layout() != null) {
            target.setLayout(blankToNull(options.layout()));
        }
        if (options.backgroundUrl() != null) {
            target.setBackgroundUrl(blankToNull(options.backgroundUrl()));
        }
        if (options.backgroundColor() != null) {
            target.setBackgroundColor(blankToNull(options.backgroundColor()));
        }
        if (options.showTitle() != null) {
            target.setShowTitle(options.showTitle());
        }
        if (options.showNav() != null) {
            target.setShowNav(options.showNav());
        }
        if (options.stylesheetUrl() != null) {
            target.setStylesheetUrl(blankToNull(options.stylesheetUrl()));
        }
    }

    /** Why the trip's page options cannot be saved ({@code TripPageCommands.pageProblems}), or null. */
    private static String pageProblem(final Trip trip) {
        final List<String> problems = new TripPageCommands().pageProblems(trip);
        return problems.isEmpty() ? null : "page: " + String.join(" ", problems);
    }

    /**
     * Why the body's payment settings cannot be applied, or null. The processor must be one of THIS trip's
     * organization's configurations (organization is the tenancy boundary; a raw id must not point a trip at
     * another tenant's account), and {@code feesPaidBy} must be a known value. Checked before anything is
     * mutated, so a refused write changes nothing.
     */
    private static String paymentConfigProblem(final TripDto body, final String orgId, final String storedFrom) {
        final TripPaymentConfigDto config = (body == null) ? null : body.paymentConfig();
        if (config == null) {
            return null;
        }
        if (config.feesPaidBy() != null && !config.feesPaidBy().isBlank()
                && !List.of("ORGANIZATION", "PAYER").contains(config.feesPaidBy().trim())) {
            return "paymentConfig.feesPaidBy must be ORGANIZATION or PAYER.";
        }
        // The trip editor's composer offers only the owning org's domains; the API must not be the way around
        // it (a From on another tenant's domain would send as that tenant). Only a CHANGED From is checked: an
        // echo of the stored one is not a payment write, and a From saved under an older, wider rule must not
        // make every PUT that sends it back fail. A move re-checks the carried From (carriedPaymentProblem).
        final String from = blankToNull(config.mailFrom());
        if (from != null && !from.equals(blankToNull(storedFrom))) {
            final String problem = mailFromProblem(from, orgId);
            if (problem != null) {
                return problem;
            }
        }
        return processorProblem(config.processorConfigId(), orgId);
    }

    /**
     * Why the payment settings a trip carries into {@code toOrg} on a move cannot go with it, or null: a
     * processor override must be one of the new org's configurations and a From override on one of its sending
     * domains, whether the body sent them or they were stored before.
     */
    private static String carriedPaymentProblem(final TripPaymentConfig carried, final String toOrg) {
        final String processor = processorProblem(carried.getProcessorConfigId(), toOrg);
        return (processor != null || carried.getMailFrom() == null) ? processor
                : mailFromProblem(carried.getMailFrom(), toOrg);
    }

    /** Why a trip of {@code orgId} cannot send payment mail from {@code from}, or null. */
    private static String mailFromProblem(final String from, final String orgId) {
        return new OrgCommands().isOrgFromSendable(orgId, from) ? null
                : "paymentConfig.mailFrom must be an address on one of this organization's sending domains.";
    }

    /** Why a trip of {@code orgId} cannot name this processor configuration, or null (blank names none). */
    private static String processorProblem(final String processorId, final String orgId) {
        if (processorId == null || processorId.isBlank()) {
            return null;
        }
        if (orgId == null || orgId.isBlank()) {
            return "paymentConfig.processorConfigId needs a trip that belongs to an organization.";
        }
        final boolean known = DAO.getInstance().getPaymentProcessorConfig(Organization.Id.from(orgId),
                PaymentProcessorConfig.Id.from(processorId.trim()), Cached.NO).isPresent();
        return known ? null : "paymentConfig.processorConfigId is not one of this organization's payment "
                + "processor configurations.";
    }

    /**
     * The trip's own payment settings, field by field: an absent (null) field is left alone, an empty string
     * clears the override back to "inherit", anything else is set. Mirrors the trip editor's dialog, whose
     * blank menu item means inherit.
     */
    private static void applyPaymentConfig(final TripPaymentConfigDto config, final TripPaymentConfig target) {
        if (config == null) {
            return;
        }
        if (config.processorConfigId() != null) {
            target.setProcessorConfigId(blankToNull(config.processorConfigId()));
        }
        if (config.feesPaidBy() != null) {
            target.setFeesPaidBy(config.feesPaidBy().isBlank() ? null : FeesPaidBy.valueOf(config.feesPaidBy().trim()));
        }
        if (config.donationEnabled() != null) {
            target.setDonationEnabled(config.donationEnabled());
        }
        if (config.donationLabel() != null) {
            target.setDonationLabel(blankToNull(config.donationLabel()));
        }
        if (config.confirmationTemplateId() != null) {
            target.setConfirmationTemplateId(blankToNull(config.confirmationTemplateId()));
        }
        if (config.mailFrom() != null) {
            target.setMailFrom(blankToNull(config.mailFrom()));
        }
        if (config.replyTo() != null) {
            target.setReplyTo(blankToNull(config.replyTo()));
        }
        if (config.bcc() != null) {
            target.setBcc(blankToNull(config.bcc()));
        }
        if (config.extraTokens() != null) {
            target.setExtraTokens(new java.util.HashMap<>(config.extraTokens()));
        }
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /** A kind off the wire, by constant name; null for one that names no kind ({@link #badEnumValue} refuses it). */
    static TripKind parseKind(final String raw) {
        return parseEnum(TripKind.class, raw);
    }

    /**
     * The 400 message for a body naming an unknown trip kind or question type, or null when every value is one the
     * model knows. Checked before {@link #apply} so a bad value never half-applies a write.
     */
    static String badEnumValue(final TripDto body) {
        if (body == null) {
            return null;
        }
        if (body.kind() != null && parseKind(body.kind()) == null) {
            return "Unknown trip kind: " + body.kind();
        }
        if (body.regOptions() != null) {
            for (final RegOptionDto option : body.regOptions()) {
                if (option.type() != null && !option.type().isBlank() && parseQuestionType(option.type()) == null) {
                    return "Unknown question type: " + option.type();
                }
            }
        }
        return null;
    }

    private static <E extends Enum<E>> E parseEnum(final Class<E> type, final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * The roster, settable like every other field the caller may already edit — with the tenancy rule the
     * page's org-scoped picker enforces applied here too: a person from OUTSIDE the trip's org is silently
     * skipped, never added (organization is THE tenancy boundary; a raw setter must not be the one door
     * around it). Null means "not sent" (leave the roster alone); an empty list clears it.
     */
    private static void applyPeople(final TripDto body, final Trip trip) {
        if (body.people() == null) {
            return;
        }
        final String orgId = body.orgId() != null ? body.orgId() : trip.getOrgId();
        final OrgCommands org = Beans.get(OrgCommands.class);
        final List<Person.Id> allowed = new ArrayList<>();
        for (final String raw : body.people()) {
            final Person.Id id = Person.Id.from(raw);
            if (orgId != null && org.isMember(orgId, id)) {
                allowed.add(id);
            }
        }
        trip.setPeople(allowed);
    }

    /**
     * The registration-page questions, settable like the rest of the trip the caller may already edit.
     * Null means "not sent" (leave them alone); an empty list clears them. A null option id takes the next
     * index, the same rule the page's Add Row uses, so a client can append without knowing the count.
     */
    private static void applyRegOptions(final TripDto body, final Trip trip) {
        if (body.regOptions() == null) {
            return;
        }
        final List<RegistrationOption> options = new ArrayList<>();
        for (final RegOptionDto dto : body.regOptions()) {
            final int id = dto.id() != null ? dto.id() : options.size();
            options.add(new RegistrationOption(id, dto.shortDesc(), dto.longDesc(), dto.show(),
                    parseQuestionType(dto.type()), dto.required(), choicesOf(dto.choices())));
        }
        trip.setRegOptions(options);
    }

    /** A question type off the wire, by constant name; blank keeps the free-text default. */
    private static RegistrationOption.QuestionType parseQuestionType(final String raw) {
        return parseEnum(RegistrationOption.QuestionType.class, raw);
    }

    private static List<OptionChoice> choicesOf(final List<RegChoiceDto> dtos) {
        return dtos == null ? null : dtos.stream().map(TripsResource::choiceOf).toList();
    }

    private static OptionChoice choiceOf(final RegChoiceDto dto) {
        return new OptionChoice(dto.id(), dto.label(), dto.priceCents() == null ? 0L : dto.priceCents(),
                dto.capacity(), dto.attestationText(), dto.days(), dto.enabled());
    }

    /**
     * Itinerary events on a WRITE are APPEND-ONLY: each entry must carry a null id and is added as a new
     * event. Deliberately narrower than the read shape — replacing an existing event by id would have to
     * round-trip participants and everybody's private notes, and a partial body would silently erase them
     * (the exact hazard {@code TripEventDto}'s own javadoc exists to prevent). Editing stays with the
     * editor page and {@code setParticipation}; an id here is a client bug and answers 400 upstream.
     */
    private static boolean eventsCarryIds(final TripDto body) {
        if (body == null || body.tripEvents() == null) {
            return false;
        }
        return body.tripEvents().stream().anyMatch(dto -> dto.id() != null);
    }

    private static void appendEvents(final TripDto body, final Trip trip) {
        if (body.tripEvents() == null) {
            return;
        }
        for (final TripEventDto dto : body.tripEvents()) {
            final TripEvent event = new TripEvent(java.util.UUID.randomUUID().toString(),
                    dto.type() == null ? TripEvent.Type.EVENT : TripEvent.Type.valueOf(dto.type()),
                    dto.title(), dto.notes(), dto.start(), dto.end(), null, null);
            if (dto.details() != null) {
                event.setDetails(new LinkedHashMap<>(dto.details()));
            }
            trip.getTripEvents().add(event);
        }
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
