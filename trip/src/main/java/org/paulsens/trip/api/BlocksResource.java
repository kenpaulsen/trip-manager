package org.paulsens.trip.api;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.action.BlockListCommands;
import org.paulsens.trip.model.Person;

/**
 * The caller's block list ({@code docs/moderation.md}). Self-scoped by construction: the person is always
 * the bearer/session identity, never a path parameter, so there is nothing here for an admin to read or
 * write on someone else's behalf -- a block list is the one preference that must stay private to its owner.
 */
@Path("me/blocks")
@TripApi
public class BlocksResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.BLOCKS_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    @GET
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response list() {
        return ok(body(Beans.get(BlockListCommands.class).blockedBy(personId())));
    }

    /** Idempotent: PUT twice is one block. 404 for an unknown person, 409 for a full list. */
    @PUT
    @Path("{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response block(
            @PathParam("personId") final String target,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final BlockListCommands.BlockOutcome outcome =
                Beans.get(BlockListCommands.class).block(personId(), idOf(target));
        return outcome.ok() ? ok(body(outcome.personIds())) : refusal(outcome);
    }

    /** Idempotent: unblocking someone not on the list is a success. */
    @DELETE
    @Path("{personId}")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response unblock(
            @PathParam("personId") final String target,
            @HeaderParam(CSRF_HEADER) final String csrf) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final BlockListCommands.BlockOutcome outcome =
                Beans.get(BlockListCommands.class).unblock(personId(), idOf(target));
        return outcome.ok() ? ok(body(outcome.personIds())) : refusal(outcome);
    }

    private Response refusal(final BlockListCommands.BlockOutcome outcome) {
        return switch (outcome.code()) {
            case BlockListCommands.REFUSED_NOT_FOUND -> error(404, ApiErrors.NOT_FOUND, outcome.message());
            case BlockListCommands.REFUSED_SELF -> error(400, ApiErrors.VALIDATION_FAILED, outcome.message());
            case BlockListCommands.REFUSED_FULL -> error(409, ApiErrors.CONFLICT, outcome.message());
            default -> error(500, ApiErrors.STORE_FAILED, outcome.message());
        };
    }

    private static Person.Id idOf(final String raw) {
        return raw == null || raw.isBlank() ? null : Person.Id.from(raw.trim());
    }

    private static Map<String, Object> body(final List<Person.Id> ids) {
        return Map.of("personIds", ids.stream().map(Person.Id::getValue).toList());
    }
}
