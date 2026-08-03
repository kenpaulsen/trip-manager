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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Privilege;

/**
 * Who is allowed to do what.
 *
 * <p>This resource grants access, which makes its own audit trail the one that matters most: every other
 * record answers "who did this", and these answer "who was able to". {@code savePrivilege} is called through
 * its {@code AuditActor} overload so a grant made over the API is attributed to a person rather than to nobody.
 *
 * <p>Gated on {@code privilegeAdmin}. Note what that means: anyone holding it can grant themselves anything
 * else, so it is effectively site-admin-adjacent and should be given out accordingly.
 */
@Slf4j
@Path("privileges")
@TripApi
public class PrivilegesResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.PRIVILEGES_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    /** Global privileges, or a trip's if {@code trip} is given. */
    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response list(@QueryParam("trip") final String tripId) {
        if (!privileges().has(ApiPrivileges.PRIVILEGE_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Privilege administration required.");
        }
        final PrivilegeCommands commands = Beans.get(PrivilegeCommands.class);
        final List<Privilege> found = (tripId == null || tripId.isBlank())
                ? commands.getGlobalPrivileges() : commands.getTripPrivileges(tripId);
        return ok(found.stream().map(PrivilegesResource::toDto).toList());
    }

    @GET
    @Path("{name}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response get(@PathParam("name") final String name, @QueryParam("trip") final String tripId) {
        if (!privileges().has(ApiPrivileges.PRIVILEGE_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Privilege administration required.");
        }
        final Privilege privilege = Beans.get(PrivilegeCommands.class).getPrivilege(name, tripId);
        if (privilege == null || privilege == Privilege.NONE) {
            return error(404, ApiErrors.NOT_FOUND, "No such privilege.");
        }
        return ok(toDto(privilege));
    }

    /** Whether a specific person holds a privilege. The question the pages ask constantly. */
    @GET
    @Path("{name}/holders/{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response check(
            @PathParam("name") final String name,
            @PathParam("personId") final String personIdParam,
            @QueryParam("trip") final String tripId) {
        if (!privileges().has(ApiPrivileges.PRIVILEGE_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Privilege administration required.");
        }
        final boolean held = Beans.get(PrivilegeCommands.class)
                .check(name, tripId, Person.Id.from(personIdParam));
        return ok(Map.of("name", name, "personId", personIdParam, "held", held));
    }

    /** Creates a privilege, or replaces its membership wholesale. */
    @PUT
    @Path("{name}")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response save(
            @PathParam("name") final String name,
            @HeaderParam(CSRF_HEADER) final String csrf,
            @QueryParam("trip") final String tripId,
            final PrivilegeBody body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!privileges().has(ApiPrivileges.PRIVILEGE_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Privilege administration required.");
        }
        final PrivilegeCommands commands = Beans.get(PrivilegeCommands.class);
        final String description = (body == null || body.description() == null)
                ? commands.getOrCreate(name, tripId, "").getDescription() : body.description();
        final List<Person.Id> people = (body == null || body.people() == null)
                ? List.of() : body.people().stream().map(Person.Id::from).toList();
        final Privilege privilege = commands.createPrivilege(name, description, tripId, people);
        // The actor-taking overload: the no-arg one resolves through FacesContext and would record every grant
        // made over this API as having no actor at all.
        if (!commands.savePrivilege(privilege, actor())) {
            return error(500, ApiErrors.STORE_FAILED, "Could not save the privilege.");
        }
        return ok(toDto(privilege));
    }

    /** Grants a privilege to one person, leaving the rest of the membership alone. */
    @POST
    @Path("{name}/holders/{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response add(
            @PathParam("name") final String name,
            @PathParam("personId") final String personIdParam,
            @HeaderParam(CSRF_HEADER) final String csrf,
            @QueryParam("trip") final String tripId) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!privileges().has(ApiPrivileges.PRIVILEGE_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Privilege administration required.");
        }
        final boolean added = Beans.get(PrivilegeCommands.class)
                .add(name, tripId, Person.Id.from(personIdParam));
        // add() answers false when the person already holds it, which is not a failure -- the caller's intent
        // is satisfied either way, so this is 200 with the distinction reported rather than an error.
        return ok(Map.of("granted", added, "alreadyHeld", !added));
    }

    @DELETE
    @Path("{name}/holders/{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response remove(
            @PathParam("name") final String name,
            @PathParam("personId") final String personIdParam,
            @HeaderParam(CSRF_HEADER) final String csrf,
            @QueryParam("trip") final String tripId) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        if (!privileges().has(ApiPrivileges.PRIVILEGE_ADMIN)) {
            return error(403, ApiErrors.FORBIDDEN, "Privilege administration required.");
        }
        final boolean removed = Beans.get(PrivilegeCommands.class)
                .remove(name, tripId, Person.Id.from(personIdParam));
        return ok(Map.of("revoked", removed, "didNotHold", !removed));
    }

    /** A privilege and its membership. */
    public record PrivilegeBody(String description, List<String> people) {
    }

    private static Map<String, Object> toDto(final Privilege privilege) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", privilege.getId());
        result.put("description", privilege.getDescription());
        result.put("people", privilege.getPeople().stream().map(Person.Id::getValue).toList());
        return result;
    }
}
