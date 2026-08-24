package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.RegistrationCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.api.dto.RegistrationDto;
import org.paulsens.trip.api.mapper.RegistrationMapper;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;

/**
 * Registrations for one trip.
 *
 * <p>Nested under the trip because a registration has no identity without one -- its key is (tripId, userId), and
 * there is no meaningful "all registrations" query for a client to make.
 */
@Slf4j
@Path("trips/{tripId}/registrations")
@TripApi
public class RegistrationsResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.REGISTRATIONS_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    /**
     * Everyone registered on this trip. Trip staff only.
     *
     * <p>Answers are stripped from every entry: this is the roster view, and a list endpoint is the easiest
     * place to leak a hundred people's dietary and medical notes at once. A caller who needs one person's
     * answers fetches that one registration, where the check is made per person.
     */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response list(@PathParam("tripId") final String tripId) {
        if (!isTripStaff(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip staff required.");
        }
        return ok(Beans.get(RegistrationCommands.class).getRegistrations(tripId).stream()
                .map(reg -> RegistrationMapper.INSTANCE.toDto(reg).withoutOptions())
                .toList());
    }

    /** How many registrations are awaiting approval. Trip staff only. */
    @GET
    @Path("pending-count")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response pendingCount(@PathParam("tripId") final String tripId) {
        if (!isTripStaff(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip staff required.");
        }
        return ok(Map.of("pending", Beans.get(RegistrationCommands.class).getNumPending(tripId)));
    }

    /**
     * One person's registration, answers included when the caller is entitled to them.
     *
     * <p>{@code getRegistration} invents a transient NOT_REGISTERED record rather than returning null, so this
     * never 404s -- "not registered" is a state, not a missing resource, and a client rendering a Register
     * button needs to be told which it is.
     */
    @GET
    @Path("{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(
            @PathParam("tripId") final String tripId, @PathParam("personId") final String personIdParam) {
        final Person.Id subject = Person.Id.from(personIdParam);
        if (!canSeeAnswers(tripId, subject)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to read this registration.");
        }
        // The registration may be transient, but the TRIP and PERSON must be real: without these checks the
        // invented record makes a nonexistent trip -- or a mistyped person id -- look like someone who simply
        // hasn't registered yet.
        requireTrip(tripId);
        requireSubject(subject);
        final Registration reg = Beans.get(RegistrationCommands.class).getRegistration(tripId, subject);
        return ok(RegistrationMapper.INSTANCE.toDto(reg));
    }

    /**
     * Creates or updates a registration.
     *
     * <p>Status is only writable by trip staff. A traveller may edit their own ANSWERS at any time, but letting
     * them post a status would let anyone approve their own registration, which is the one decision this whole
     * workflow exists to put in a human's hands.
     */
    @PUT
    @Path("{personId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response save(
            @PathParam("tripId") final String tripId,
            @PathParam("personId") final String personIdParam,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final RegistrationDto body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Person.Id subject = Person.Id.from(personIdParam);
        if (!canSeeAnswers(tripId, subject)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to edit this registration.");
        }
        final boolean staff = isTripStaff(tripId);
        if (body != null && body.status() != null && !staff) {
            return error(403, ApiErrors.FORBIDDEN, "Only trip staff may change a registration's status.");
        }
        // getRegistration invents a record keyed to whatever was asked for, so without these checks a PUT to a
        // trip nobody created -- or a staff PUT with a mistyped person id -- saves a junk row under that key.
        final Trip trip = requireTrip(tripId);
        final Person person = requireSubject(subject);
        final RegistrationCommands registrations = Beans.get(RegistrationCommands.class);
        final Registration existing = registrations.getRegistration(tripId, subject);
        final Registration toSave = merge(existing, body);
        if (!registrations.saveRegistration(toSave)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the registration.");
        }
        audit(trip, person, existing, toSave);
        // Echo the object just saved; a re-read can still serve the pre-save value while the cache catches up.
        return ok(RegistrationMapper.INSTANCE.toDto(toSave));
    }

    /** The room this person is assigned to on this trip. */
    @GET
    @Path("{personId}/room")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response room(
            @PathParam("tripId") final String tripId, @PathParam("personId") final String personIdParam) {
        final Person.Id subject = Person.Id.from(personIdParam);
        if (!canSeeAnswers(tripId, subject)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to read this room assignment.");
        }
        // getRoomPDV CREATES AND SAVES a blank record on a miss -- convenient for the JSF room editor, but here
        // it means a GET with a garbage tripId OR a garbage personId writes a junk person_data row. Refuse both
        // before it can.
        requireTrip(tripId);
        requireSubject(subject);
        final PersonDataValue pdv = Beans.get(RegistrationCommands.class).getRoomPDV(tripId, subject);
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("tripId", tripId);
        result.put("personId", personIdParam);
        result.put("room", pdv == null ? null : pdv.getContent());
        return ok(result);
    }

    /** Assigns a room. Trip staff only -- travellers do not room themselves. */
    @PUT
    @Path("{personId}/room")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response saveRoom(
            @PathParam("tripId") final String tripId,
            @PathParam("personId") final String personIdParam,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!isTripStaff(tripId)) {
            return error(403, ApiErrors.FORBIDDEN, "Trip staff required.");
        }
        requireTrip(tripId);
        requireSubject(Person.Id.from(personIdParam));
        final Object room = body == null ? null : body.get("room");
        // The value is passed in rather than read back out of the PDV on purpose: saveRoom documents that a
        // re-read here can hand back the pre-save value in production.
        if (!Beans.get(RegistrationCommands.class)
                .saveRoom(tripId, Person.Id.from(personIdParam), room == null ? "" : room.toString())) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the room.");
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("tripId", tripId);
        result.put("personId", personIdParam);
        result.put("room", room);
        return ok(result);
    }

    /**
     * Applies the submitted fields to the stored registration.
     *
     * <p>{@code Registration} is immutable in the ways that matter ({@code withStatus} returns a copy), so this
     * builds the object to save rather than mutating one. Absent fields are left as they were.
     */
    private static Registration merge(final Registration existing, final RegistrationDto body) {
        if (body == null) {
            return existing;
        }
        final Registration withOptions = (body.options() == null) ? existing
                : new Registration(existing.getTripId(), existing.getUserId(), existing.getCreated(),
                        existing.getStatus(), body.options());
        return (body.status() == null) ? withOptions : withOptions.withStatus(parseStatus(body.status()));
    }

    /**
     * Reads a status off the wire.
     *
     * <p>Deliberately NOT {@code withStatusString}, which is {@code Status.fromDescription} and matches the
     * DISPLAY text ("Pending"). The wire carries {@code name()} ("PENDING"), so routing input through that
     * method means a client that GETs a registration and PUTs it back unchanged parses its own status as null
     * and silently wipes it -- the most ordinary request a client can make, and a corrupting one.
     *
     * <p>Lenient on input, strict on output: the display form is still accepted, because it is what the model's
     * own {@code @JsonValue} produces and something is bound to send it.
     */
    private static Registration.Status parseStatus(final String status) {
        try {
            return Registration.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException ex) {
            final Registration.Status byDescription = Registration.Status.fromDescription(status.trim());
            if (byDescription == null) {
                throw new IllegalArgumentException("Unknown registration status: " + status);
            }
            return byDescription;
        }
    }

    /**
     * Records the registration events the XHTML pages record, since {@code saveRegistration} does not audit.
     *
     * <p>Only a STATUS change is worth a record -- a traveller correcting a typo in their answers is not a
     * registration event, and auditing every save would bury the approvals nobody could then find.
     */
    private void audit(
            final Trip trip, final Person person, final Registration before, final Registration after) {
        if (before.getStatus() == after.getStatus()) {
            return;
        }
        final AuditCommands audit = Beans.get(AuditCommands.class);
        if (after.getStatus() == Registration.Status.NOT_REGISTERED) {
            audit.registrationRemoved(person, trip, actor());
        } else {
            audit.registered(person, trip, actor());
        }
    }

    /** Staff on this trip: a manager, a viewer, a registration admin, or a site admin. */
    private boolean isTripStaff(final String tripId) {
        final ApiPrivileges privileges = privileges();
        return privileges.has(ApiPrivileges.TRIP_MGR, tripId) || privileges.has(ApiPrivileges.TRIP_VIEW, tripId)
                || privileges.has(ApiPrivileges.REGISTRATION_ADMIN, tripId);
    }

    /** The traveller themselves, whoever manages their booking, or trip staff. */
    private boolean canSeeAnswers(final String tripId, final Person.Id subject) {
        if (subject.equals(personId())) {
            return true;
        }
        if (isTripStaff(tripId)) {
            return true;
        }
        return privileges().canActFor(findPerson(personId()), subject);
    }
}
