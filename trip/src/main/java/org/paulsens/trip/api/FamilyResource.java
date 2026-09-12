package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.ConfigCommands;
import org.paulsens.trip.action.FamilyCommands;
import org.paulsens.trip.api.dto.CreateFamilyMemberRequest;
import org.paulsens.trip.api.dto.FamilyDto;
import org.paulsens.trip.api.dto.FamilyMemberDto;
import org.paulsens.trip.api.mapper.PersonMapper;
import org.paulsens.trip.model.Family;
import org.paulsens.trip.model.Person;

/**
 * Family accounts: the household the caller (or, for a manager or site admin, someone they act for) belongs
 * to, and the three self-service writes the family page offers -- add a member (create-and-link), grant or
 * revoke manager, delete a member without history. Every rule lives in {@link FamilyCommands}; this edge
 * only shapes the request, maps the bean's refusal codes onto statuses, and redacts the members' records
 * for the caller the way every person read is.
 *
 * <p>Admin linking of EXISTING people is deliberately not here: it is the one family write that accepts an
 * arbitrary person id, and it stays behind the admin pages.
 */
@Path("family")
@TripApi
public class FamilyResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.FAMILY_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    /** The caller's family, or {@code personId}'s for someone the caller may act for (404 unknown, 403 else). */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response family(@QueryParam("personId") final String personId) {
        final Person.Id subjectId = personId == null || personId.isBlank()
                ? personId() : Person.Id.from(personId.trim());
        if (!canActFor(subjectId)) {
            return error(403, ApiErrors.FORBIDDEN, "Not permitted to see this person's family.");
        }
        final Person subject = requireSubject(subjectId);
        final FamilyCommands family = commands();
        return ok(familyDto(family, subject, family.familyOf(subject)));
    }

    /**
     * Create-and-link a new member into the caller's family ({@code forPersonId}: into that person's). The
     * new Person is created here, so a non-admin can never link an id they did not just create.
     */
    @POST
    @Path("members")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response addMember(
            @HeaderParam(CSRF_HEADER) final String csrf,
            final CreateFamilyMemberRequest body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (body == null) {
            return error(400, ApiErrors.VALIDATION_FAILED,
                    "A member needs a first name, last name, sex and birthdate.");
        }
        final Person.Sex sex;
        try {
            sex = parseSex(body.sex());
        } catch (final IllegalArgumentException ex) {
            return error(422, ApiErrors.VALIDATION_FAILED, "sex must be Male or Female.");
        }
        final FamilyCommands family = commands();
        final FamilyCommands.FamilyResult result = family.addFamilyMemberForOutcome(body.forPersonId(),
                body.first(), body.last(), body.birthdate(), sex, body.email(), Boolean.TRUE.equals(body.manager()));
        if (!result.isOk()) {
            return familyError(result);
        }
        final Person anchor = body.forPersonId() == null || body.forPersonId().isBlank()
                ? findPerson(personId()) : findPerson(Person.Id.from(body.forPersonId().trim()));
        return ok(Map.of(
                "member", memberDto(family, result.member(), result.family(), true),
                "family", familyDto(family, anchor, result.family())));
    }

    /** Grant or revoke the family-manager flag: {@code {"manager": true|false}}. */
    @PUT
    @Path("members/{id}/manager")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response setManager(
            @PathParam("id") final String id,
            @HeaderParam(CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Object raw = body == null ? null : body.get("manager");
        if (!(raw instanceof Boolean grant)) {
            return error(400, ApiErrors.VALIDATION_FAILED, "manager must be true or false.");
        }
        final FamilyCommands family = commands();
        final FamilyCommands.FamilyResult result = family.setManagerOutcome(Person.Id.from(id), grant);
        if (!result.isOk()) {
            return familyError(result);
        }
        return ok(familyDto(family, result.member(), result.family()));
    }

    /** Delete (soft) a member a manager created by mistake -- only while they have no history at all. */
    @DELETE
    @Path("members/{id}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response deleteMember(
            @PathParam("id") final String id,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final FamilyCommands family = commands();
        final FamilyCommands.FamilyResult result = family.deleteFamilyMemberOutcome(Person.Id.from(id));
        if (!result.isOk()) {
            return familyError(result);
        }
        // The family after the removal, anchored on the caller (the deleted member is no longer in it).
        return ok(Map.of("deleted", true, "family", familyDto(family, findPerson(personId()), result.family())));
    }

    // ------------------------------------------------------------------ shaping

    private FamilyCommands commands() {
        return new FamilyCommands(new ConfigCommands(), new AuditCommands(), this::caller);
    }

    /** Yourself, someone whose profile you manage, or a site admin -- the family page's own reach rule. */
    private boolean canActFor(final Person.Id subject) {
        if (subject == null) {
            return false;
        }
        if (subject.equals(personId()) || isSiteAdmin()) {
            return true;
        }
        return privileges().canActFor(findPerson(personId()), subject);
    }

    private FamilyDto familyDto(final FamilyCommands family, final Person subject, final Family row) {
        if (row == null) {
            // No family yet: the subject alone, manageable by whoever may act for them (the first add forms it).
            final boolean canManage = subject != null && canActFor(subject.getId());
            final List<FamilyMemberDto> members = subject == null ? List.of()
                    : List.of(memberDto(family, subject, null, canManage));
            return new FamilyDto(null, 0L, List.of(), List.of(), family.getMaxMembers(), false, canManage, members);
        }
        final boolean canManage = family.canManage(row);
        final List<FamilyMemberDto> members = family.getMembers(row).stream()
                .map(member -> memberDto(family, member, row, canManage))
                .toList();
        return new FamilyDto(row.getId().getValue(), row.getVersion(),
                row.getManagerIds().stream().map(Person.Id::getValue).toList(),
                row.getMemberIds().stream().map(Person.Id::getValue).toList(),
                family.getMaxMembers(), family.isFamilyAtLimit(row), canManage, members);
    }

    private FamilyMemberDto memberDto(final FamilyCommands family, final Person member, final Family row,
            final boolean canManage) {
        final boolean self = member.getId().equals(personId());
        // Two DAO reads per member, so only when the caller could act on the answer.
        final String blocked = canManage && !self && row != null ? family.deleteBlockReason(member.getId()) : null;
        final AccessLevel level = privileges().levelFor(findPerson(personId()), member.getId(), null);
        return new FamilyMemberDto(member.getId().getValue(), member.getPreferredName(), member.getFirst(),
                member.getLast(), row != null && row.isManager(member.getId()),
                family.missingProfileFields(member), blocked,
                PersonMapper.INSTANCE.toDto(member).redactedFor(level));
    }

    /** Blank is "not given" (the bean refuses it with SEX_REQUIRED); anything else must be a known value. */
    private static Person.Sex parseSex(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (final Person.Sex sex : Person.Sex.values()) {
            if (sex.name().equalsIgnoreCase(value.trim())) {
                return sex;
            }
        }
        throw new IllegalArgumentException(value);
    }

    /**
     * The bean's refusal codes on the wire. The generic ones become the API's generic codes; the family-specific
     * ones travel as themselves, because a client's remedy differs per code (a full family, a manager without
     * an email, a member with history) and the message alone would make them indistinguishable.
     */
    private Response familyError(final FamilyCommands.FamilyResult result) {
        final String message = result.message();
        return switch (result.code()) {
            case FamilyCommands.FamilyResult.NOT_SIGNED_IN -> error(401, ApiErrors.NOT_AUTHENTICATED, message);
            case FamilyCommands.FamilyResult.NOT_ALLOWED -> error(403, ApiErrors.FORBIDDEN, message);
            case FamilyCommands.FamilyResult.NOT_MANAGER -> error(403, result.code(), message);
            case FamilyCommands.FamilyResult.NO_FAMILY -> error(404, ApiErrors.NOT_FOUND, message);
            case FamilyCommands.FamilyResult.EMAIL_IN_USE, FamilyCommands.FamilyResult.FAMILY_CHANGED ->
                    error(409, result.code(), message);
            case FamilyCommands.FamilyResult.STORE_FAILED -> error(500, ApiErrors.STORE_FAILED, message);
            default -> error(422, result.code().toUpperCase(Locale.ROOT), message);
        };
    }
}
