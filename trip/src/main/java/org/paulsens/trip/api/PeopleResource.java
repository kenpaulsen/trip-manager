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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PersonDataValueCommands;
import org.paulsens.trip.action.ProfilePhotos;
import org.paulsens.trip.api.dto.PersonDataValueDto;
import org.paulsens.trip.api.dto.PersonDto;
import org.paulsens.trip.api.mapper.PersonMapper;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;

/**
 * People, and the data hung off a person.
 *
 * <p>Every read goes through {@link PersonDto#redactedFor} with a level from {@link ApiPrivileges#levelFor}.
 * There is no path out of this resource that returns an unredacted person to anyone but themselves.
 *
 * <p>Profile photos and person-data-values live in their own beans ({@code ProfilePhotos},
 * {@code PersonDataValueCommands}) but are properties of a person on the wire, so they are sub-paths here rather
 * than resources of their own.
 */
@Slf4j
@Path("people")
@TripApi
public class PeopleResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.PEOPLE_V1;
    private static final int MAX_SEARCH_RESULTS = 100;

    @Override
    protected String versionedType() {
        return V1;
    }

    /**
     * Free-text search across everyone.
     *
     * <p>Gated on {@code peopleAdmin} rather than offered to any signed-in user. This is a bulk-disclosure
     * endpoint: a member with it can enumerate the whole database a page at a time, which is a different thing
     * from the roster access a traveller legitimately has. A client that wants "who else is on my trip" asks the
     * trip for its roster, where trip membership is what authorizes the answer.
     */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response search(
            @QueryParam("q") final String query,
            @QueryParam("limit") @DefaultValue("25") final int limit) {
        final ApiPrivileges privileges = privileges();
        if (!privileges.has(ApiPrivileges.PEOPLE_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to search people.");
        }
        final List<Person> found = Beans.get(PersonCommands.class)
                .searchPeople(query == null ? "" : query, Math.min(Math.max(limit, 1), MAX_SEARCH_RESULTS));
        // peopleAdmin implies the full record; mapping each through SITE_ADMIN keeps the redaction call on
        // every path out of here rather than making this one an exception that later gets copied.
        return ok(found.stream().map(person -> dto(person, AccessLevel.SITE_ADMIN)).toList());
    }

    /**
     * One person, redacted for whoever is asking.
     *
     * <p>{@code trip} is optional and only ever narrows or widens the answer through the caller's role on that
     * trip -- it is not a filter, and passing a trip the caller has no role on simply yields the peer view.
     */
    @GET
    @Path("{id}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(@PathParam("id") final String id, @QueryParam("trip") final String tripId) {
        final Person.Id personId = Person.Id.from(id);
        final Person person = findPerson(personId);
        if (person == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such person.");
        }
        return ok(dto(person, levelFor(personId, tripId)));
    }

    @POST
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response create(@HeaderParam(CSRF_HEADER) final String csrf, final PersonDto body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!privileges().has(ApiPrivileges.PEOPLE_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to create people.");
        }
        final PersonCommands people = Beans.get(PersonCommands.class);
        final Person person = people.createPerson();
        apply(body, person, AccessLevel.SITE_ADMIN);
        if (people.emailTakenByAnother(person)) {
            return error(409, ApiErrors.CONFLICT, "That email already belongs to another person.");
        }
        if (!people.savePerson(person)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the person.");
        }
        // savePerson does not audit -- the XHTML pages call audit.person() themselves, so this edge must too.
        Beans.get(AuditCommands.class).person(person, "CREATED", actor());
        return ok(dto(person, AccessLevel.SITE_ADMIN));
    }

    /**
     * Updates a person.
     *
     * <p>Fields left absent from the body are left ALONE rather than cleared. That is a real trade -- it means
     * this endpoint cannot null a field out, and a client wanting to clear one sends an empty string. The
     * alternative is worse: responses here are redacted, so a client that reads a person, edits one field and
     * sends the object back would post nulls for everything it was not allowed to see, and a round-trip through
     * a co-traveller's view would silently erase the passport of everyone it touched.
     */
    @PUT
    @Path("{id}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response update(
            @PathParam("id") final String id,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final PersonDto body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final PersonCommands people = Beans.get(PersonCommands.class);
        final Person.Id personId = Person.Id.from(id);
        // The object that gets saved must be the object that was read: a DAO read returns a COPY, so mutating
        // anything else and saving reports success while writing nothing. findPerson rather than getPerson --
        // the latter invents a blank Person on a miss, which this would then populate and save as a junk row.
        final Person person = findPerson(personId);
        if (person == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such person.");
        }
        final AccessLevel level = levelFor(personId, null);
        if (!canEdit(level)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to edit this person.");
        }
        apply(body, person, level);
        // The savePerson funnel would refuse this anyway; checking here turns an opaque 500 into a clear 409.
        if (people.emailTakenByAnother(person)) {
            return error(409, ApiErrors.CONFLICT, "That email already belongs to another person.");
        }
        if (!people.savePerson(person)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the person.");
        }
        Beans.get(AuditCommands.class).person(person, "EDITED", actor());
        // Echo what was just saved rather than re-reading it. Cache invalidation is async, so a read here can
        // still return the pre-save value -- in production only, which is the worst place to discover it.
        return ok(dto(person, level));
    }

    /** Whether this person has a profile photo, and where it is. Names are public to any signed-in user. */
    @GET
    @Path("{id}/photo")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response photo(@PathParam("id") final String id) {
        final ProfilePhotos photos = Beans.get(ProfilePhotos.class);
        final Map<String, Object> result = new LinkedHashMap<>();
        final boolean has = photos.hasPhoto(id);
        result.put("hasPhoto", has);
        result.put("url", has ? photos.getUrl(id) : null);
        return ok(result);
    }

    /** Everything stored against this person by data id. Self, their manager, or an admin. */
    @GET
    @Path("{id}/data")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response data(@PathParam("id") final String id) {
        final Person.Id personId = Person.Id.from(id);
        if (!canEdit(levelFor(personId, null))) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to read this person's data.");
        }
        final Map<DataId, PersonDataValue> values = PersonDataValueCommands.getPersonDataValues(personId);
        return ok(values.values().stream().map(PeopleResource::toDto).toList());
    }

    @GET
    @Path("{id}/data/{dataId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response dataValue(@PathParam("id") final String id, @PathParam("dataId") final String dataId) {
        final Person.Id personId = Person.Id.from(id);
        if (!canEdit(levelFor(personId, null))) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to read this person's data.");
        }
        final PersonDataValue value = PersonDataValueCommands.getPersonDataValue(personId, DataId.from(dataId));
        if (value == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such data value.");
        }
        return ok(toDto(value));
    }

    @PUT
    @Path("{id}/data/{dataId}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response saveDataValue(
            @PathParam("id") final String id,
            @PathParam("dataId") final String dataId,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final PersonDataValueDto body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Person.Id personId = Person.Id.from(id);
        if (!canEdit(levelFor(personId, null))) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to edit this person's data.");
        }
        final PersonDataValue value = PersonDataValueCommands.createPersonDataValue(
                personId, DataId.from(dataId), body == null ? null : body.type());
        value.setContent(body == null ? null : body.content());
        if (!PersonDataValueCommands.savePersonDataValue(value)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the data value.");
        }
        return ok(toDto(value));
    }

    /**
     * Copies the permitted fields of {@code body} onto {@code person}.
     *
     * <p>Field-by-field and gated on the same capabilities that govern reading, so a caller cannot write what
     * they may not see. A blanket copy would let a trip viewer -- who never receives a passport -- post one.
     */
    private static void apply(final PersonDto body, final Person person, final AccessLevel level) {
        if (body == null) {
            return;
        }
        set(body.nickname(), person::setNickname);
        set(body.first(), person::setFirst);
        set(body.middle(), person::setMiddle);
        set(body.last(), person::setLast);
        if (level.seesContactDetail()) {
            set(body.cell(), person::setCell);
            set(body.email(), person::setEmail);
            set(body.emergencyContactName(), person::setEmergencyContactName);
            set(body.emergencyContactPhone(), person::setEmergencyContactPhone);
        }
        if (level.seesTravelDocuments()) {
            set(body.tsa(), person::setTsa);
            if (body.birthdate() != null) {
                person.setBirthdate(body.birthdate());
            }
        }
        if (level.seesNotes()) {
            set(body.notes(), person::setNotes);
        }
    }

    private static void set(final String value, final Consumer<String> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    /** Editing is for the subject, whoever manages them, and administrators -- never a trip role. */
    private static boolean canEdit(final AccessLevel level) {
        return level == AccessLevel.SELF || level == AccessLevel.MANAGER || level == AccessLevel.SITE_ADMIN;
    }

    private AccessLevel levelFor(final Person.Id subject, final String tripId) {
        final ApiPrivileges privileges = privileges();
        if (privileges.has(ApiPrivileges.PEOPLE_ADMIN)) {
            return AccessLevel.SITE_ADMIN;
        }
        return privileges.levelFor(findPerson(personId()), subject, tripId);
    }

    private static PersonDto dto(final Person person, final AccessLevel level) {
        return PersonMapper.INSTANCE.toDto(person).redactedFor(level);
    }

    private static PersonDataValueDto toDto(final PersonDataValue value) {
        return new PersonDataValueDto(
                value.getUserId() == null ? null : value.getUserId().getValue(),
                value.getDataId() == null ? null : value.getDataId().getValue(),
                value.getType(),
                value.getContent());
    }
}
