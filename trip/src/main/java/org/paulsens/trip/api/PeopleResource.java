package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.OrgCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PersonDataValueCommands;
import org.paulsens.trip.action.PhotoUploadBean;
import org.paulsens.trip.action.ProfilePhotoCommands;
import org.paulsens.trip.action.ProfilePhotos;
import org.paulsens.trip.api.dto.AddressDto;
import org.paulsens.trip.api.dto.PassportDto;
import org.paulsens.trip.api.dto.PersonDataValueDto;
import org.paulsens.trip.api.dto.PersonDto;
import org.paulsens.trip.api.dto.PrivacyDto;
import org.paulsens.trip.api.dto.ProfilePhotoDto;
import org.paulsens.trip.api.mapper.PersonMapper;
import org.paulsens.trip.media.PhotoProcessor;
import org.paulsens.trip.model.DataId;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.PersonDataValue;
import org.paulsens.trip.model.PrivacySettings;

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
     * Free-text search, bounded by people-admin reach.
     *
     * <p>Gated on {@code peopleAdmin} rather than offered to any signed-in user. This is a bulk-disclosure
     * endpoint: a member with it can enumerate a whole tenant a page at a time, which is a different thing
     * from the roster access a traveller legitimately has. A client that wants "who else is on my trip" asks the
     * trip for its roster, where trip membership is what authorizes the answer.
     *
     * <p>{@code peopleAdmin} is ORG-scoped (org migration, 2026-08): hits are filtered to the caller's
     * people-admin reach, so an org's people admin enumerates their own org only -- which also means result
     * COUNTS can be smaller than pre-migration clients expect. Site admins still see everyone.
     */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response search(
            @QueryParam("q") final String query,
            @QueryParam("limit") @DefaultValue("25") final int limit) {
        final OrgCommands orgs = new OrgCommands(this::caller);
        if (!privileges().isSiteAdmin() && !orgs.holdsAnywhere(ApiPrivileges.PEOPLE_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to search people.");
        }
        final int capped = Math.min(Math.max(limit, 1), MAX_SEARCH_RESULTS);
        // Over-fetch before the tenancy filter, so a page of out-of-org hits cannot mask permitted ones.
        final List<Person> found = Beans.get(PersonCommands.class)
                .searchPeople(query == null ? "" : query, capped * 4).stream()
                .filter(person -> orgs.canAdminPerson(person.getId()))
                .limit(capped)
                .toList();
        // People-admin reach implies the full record; mapping each through SITE_ADMIN keeps the redaction
        // call on every path out of here rather than making this one an exception that later gets copied.
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

    /**
     * Creates a person. BREAKING CHANGE (org migration, 2026-08): {@code org} names the organization the new
     * person belongs to and is REQUIRED for non-site-admin callers, who must hold {@code peopleAdmin} there
     * (every person must belong to an org). Site admins may omit it for legacy parity.
     */
    @POST
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response create(@HeaderParam(CSRF_HEADER) final String csrf,
            @QueryParam("org") final String orgId, final PersonDto body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final OrgCommands orgs = new OrgCommands(this::caller);
        final boolean siteAdmin = privileges().isSiteAdmin();
        if (!siteAdmin && (orgId == null || orgId.isBlank())) {
            return error(400, ApiErrors.VALIDATION_FAILED, "org is required to create a person.");
        }
        if (!siteAdmin && !caller().has(ApiPrivileges.PEOPLE_ADMIN, orgId)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to create people in this organization.");
        }
        if (sexUnparseable(body)) {
            return error(400, ApiErrors.VALIDATION_FAILED, "sex must be Male or Female.");
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
        if (orgId != null && !orgId.isBlank() && !orgs.addCreatedPerson(orgId, person.getId())) {
            // The person exists but is untenanted; report it rather than silently succeeding.
            return error(500, ApiErrors.STORE_FAILED, "Person created, but adding them to the organization "
                    + "failed.");
        }
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
        if (sexUnparseable(body)) {
            // Pre-checked so the answer is a validation failure naming the field, not the generic
            // BAD_REQUEST JsonExceptionMapper makes of an IllegalArgumentException.
            return error(400, ApiErrors.VALIDATION_FAILED, "sex must be Male or Female.");
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

    /**
     * This person's profile pictures: THE picture and every occupied slot. Readable by any signed-in user,
     * like the name -- a photo is shown wherever a name is.
     */
    @GET
    @Path("{id}/photo")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response photo(@PathParam("id") final String id) {
        return ok(profilePhotoDto(id, null));
    }

    /**
     * Stores a profile picture from a raw image body (any {@code image/*}, or {@code application/octet-stream}),
     * cropped by the four {@code crop*} query parameters in source-pixel space when given, forced square
     * either way. {@code slot} names the slot to replace; absent, the next free one. The same rule as the
     * profile page: yourself, someone you manage, or a site admin.
     */
    @POST
    @Path("{id}/photo")
    @Consumes({"image/*", MediaType.APPLICATION_OCTET_STREAM})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response uploadPhoto(
            @PathParam("id") final String id,
            @HeaderParam(CSRF_HEADER) final String csrf,
            @HeaderParam("Content-Length") final Long contentLength,
            @QueryParam("slot") final Integer slot,
            @QueryParam("cropX") final Integer cropX,
            @QueryParam("cropY") final Integer cropY,
            @QueryParam("cropW") final Integer cropW,
            @QueryParam("cropH") final Integer cropH,
            final InputStream body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Person subject = findPerson(Person.Id.from(id));
        if (subject == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such person.");
        }
        final ProfilePhotoCommands photos = Beans.get(ProfilePhotoCommands.class);
        if (!photos.mayEdit(caller(), subject)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to change this person's photo.");
        }
        if (slot != null && (slot < 1 || slot > ProfilePhotos.MAX_SLOTS)) {
            return error(400, ApiErrors.VALIDATION_FAILED, "slot must be 1-" + ProfilePhotos.MAX_SLOTS + ".");
        }
        final PhotoProcessor.CropRect rect;
        try {
            rect = cropRect(cropX, cropY, cropW, cropH);
        } catch (final IllegalArgumentException ex) {
            return error(400, ApiErrors.BAD_REQUEST, ex.getMessage());
        }
        final Optional<byte[]> bytes;
        try {
            bytes = readUpload(body, contentLength, PhotoUploadBean.MAX_UPLOAD_BYTES);
        } catch (final IOException ex) {
            return error(400, ApiErrors.BAD_REQUEST, "The upload could not be read.");
        }
        if (bytes.isEmpty()) {
            return error(413, ApiErrors.PAYLOAD_TOO_LARGE,
                    "A profile picture can be at most " + (PhotoUploadBean.MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB.");
        }
        if (bytes.get().length == 0) {
            return error(400, ApiErrors.VALIDATION_FAILED, "The upload was empty.");
        }
        final ProfilePhotoCommands.PhotoResult result = photos.storeFor(caller(), subject, bytes.get(), rect, slot);
        if (!result.isOk()) {
            return photoError(result);
        }
        return ok(profilePhotoDto(id, result.slot()));
    }

    /** Removes one slot's picture. 404 when the slot holds none. */
    @DELETE
    @Path("{id}/photo/{slot}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response deletePhoto(
            @PathParam("id") final String id,
            @PathParam("slot") final int slot,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Person subject = findPerson(Person.Id.from(id));
        if (subject == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such person.");
        }
        if (slot < 1 || slot > ProfilePhotos.MAX_SLOTS) {
            return error(400, ApiErrors.VALIDATION_FAILED, "slot must be 1-" + ProfilePhotos.MAX_SLOTS + ".");
        }
        final ProfilePhotoCommands.PhotoResult result =
                Beans.get(ProfilePhotoCommands.class).deleteSlotFor(caller(), subject, slot);
        if (!result.isOk()) {
            return photoError(result);
        }
        return ok(profilePhotoDto(id, null));
    }

    /** Makes one occupied slot THE picture: {@code {"slot": n}}. */
    @PUT
    @Path("{id}/photo/selected")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response selectPhoto(
            @PathParam("id") final String id,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Person subject = findPerson(Person.Id.from(id));
        if (subject == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such person.");
        }
        if (!Beans.get(ProfilePhotoCommands.class).mayEdit(caller(), subject)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to change this person's photo.");
        }
        final Object raw = body == null ? null : body.get("slot");
        final int slot = raw instanceof Number number ? number.intValue() : 0;
        if (slot < 1 || slot > ProfilePhotos.MAX_SLOTS) {
            return error(400, ApiErrors.VALIDATION_FAILED, "slot must be 1-" + ProfilePhotos.MAX_SLOTS + ".");
        }
        final ProfilePhotos photos = Beans.get(ProfilePhotos.class);
        if (photos.getSlots(id).stream().noneMatch(occupied -> occupied.number() == slot)) {
            return error(404, ApiErrors.NOT_FOUND, "That slot holds no picture.");
        }
        if (!Beans.get(PersonCommands.class).selectProfilePhoto(subject, slot)) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the choice.");
        }
        return ok(profilePhotoDto(id, null));
    }

    private Response photoError(final ProfilePhotoCommands.PhotoResult result) {
        return switch (result.code()) {
            case ProfilePhotoCommands.PhotoResult.NOT_ALLOWED -> error(403, ApiErrors.FORBIDDEN, result.message());
            case ProfilePhotoCommands.PhotoResult.NO_FREE_SLOT -> error(409, ApiErrors.CONFLICT, result.message());
            case ProfilePhotoCommands.PhotoResult.REJECTED ->
                    error(422, ApiErrors.VALIDATION_FAILED, result.message());
            case ProfilePhotoCommands.PhotoResult.EMPTY_SLOT ->
                    error(404, ApiErrors.NOT_FOUND, "That slot holds no picture.");
            default -> error(500, ApiErrors.STORE_FAILED, result.message());
        };
    }

    private ProfilePhotoDto profilePhotoDto(final String id, final Integer storedSlot) {
        final ProfilePhotos photos = Beans.get(ProfilePhotos.class);
        final boolean has = photos.hasPhoto(id);
        final List<ProfilePhotoDto.SlotDto> slots = has
                ? photos.getSlots(id).stream().map(this::slotDto).toList() : List.of();
        return new ProfilePhotoDto(has, has ? absoluteUrl(photos.getUrl(id)) : null,
                has ? photos.getSelectedSlot(id) : 0, slots, storedSlot);
    }

    private ProfilePhotoDto.SlotDto slotDto(final ProfilePhotos.Slot slot) {
        return new ProfilePhotoDto.SlotDto(slot.number(), absoluteUrl(slot.url()));
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
            if (body.address() != null) {
                applyAddress(body.address(), person);
            }
        }
        if (level.seesTravelDocuments()) {
            set(body.tsa(), person::setTsa);
            if (body.birthdate() != null) {
                person.setBirthdate(body.birthdate());
            }
            if (body.passport() != null) {
                applyPassport(body.passport(), person);
            }
        }
        if (canEdit(level) && body.sex() != null) {
            // Validated by the endpoint before apply() runs; canEdit is the whole editing gate, so sex follows
            // the same people who may change a name.
            person.setSex(parseSex(body.sex()));
        }
        if (level.seesNotes()) {
            set(body.notes(), person::setNotes);
        }
        if (level.seesAccountStructure() && body.privacy() != null) {
            applyPrivacy(body.privacy(), person.getPrivacy());
        }
    }

    /**
     * Merges the provided privacy knobs onto the stored ones -- absent knobs are left alone, matching the
     * endpoint's absent-means-unchanged contract, and an unrecognized value is ignored rather than 500ing or
     * silently resetting the knob to its default.
     */
    private static void applyPrivacy(final PrivacyDto dto, final PrivacySettings settings) {
        setVisibility(dto.email(), settings::setEmail);
        setVisibility(dto.cell(), settings::setCell);
        setVisibility(dto.city(), settings::setCity);
        setVisibility(dto.street(), settings::setStreet);
    }

    private static void setVisibility(final String value, final Consumer<PrivacySettings.Visibility> setter) {
        final PrivacySettings.Visibility parsed = parseVisibility(value);
        if (parsed != null) {
            setter.accept(parsed);
        }
    }

    private static PrivacySettings.Visibility parseVisibility(final String value) {
        try {
            return (value == null) ? null : PrivacySettings.Visibility.valueOf(value);
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    /** The same absent-means-unchanged, empty-means-clear rule as the top-level fields, per address line. */
    private static void applyAddress(final AddressDto dto, final Person person) {
        final var address = person.getAddress();
        set(dto.street(), address::setStreet);
        set(dto.street2(), address::setStreet2);
        set(dto.city(), address::setCity);
        set(dto.state(), address::setState);
        set(dto.zip(), address::setZip);
        set(dto.country(), address::setCountry);
    }

    /** Dates cannot be cleared over the API (absent = unchanged), the same rule birthdate follows. */
    private static void applyPassport(final PassportDto dto, final Person person) {
        final var passport = person.getPassport();
        set(dto.number(), passport::setNumber);
        set(dto.country(), passport::setCountry);
        set(dto.placeOfBirth(), passport::setPlaceOfBirth);
        if (dto.expires() != null) {
            passport.setExpires(dto.expires());
        }
        if (dto.issued() != null) {
            passport.setIssued(dto.issued());
        }
    }

    private static boolean sexUnparseable(final PersonDto body) {
        return body != null && body.sex() != null && parseSex(body.sex()) == null;
    }

    private static Person.Sex parseSex(final String value) {
        for (final Person.Sex sex : Person.Sex.values()) {
            if (sex.name().equalsIgnoreCase(value.trim())) {
                return sex;
            }
        }
        return null;
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
        // People-admin reach is per-SUBJECT now (org-scoped peopleAdmin): the full record for people in the
        // caller's people-admin orgs, the ordinary relationship-derived level for everyone else.
        if (new OrgCommands(this::caller).canAdminPerson(subject)) {
            return AccessLevel.SITE_ADMIN;
        }
        return privileges().levelFor(findPerson(personId()), subject, tripId);
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
