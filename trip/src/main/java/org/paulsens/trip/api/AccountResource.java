package org.paulsens.trip.api;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import org.paulsens.trip.action.AccountDeletionCommands;

/**
 * The caller's own account: what deleting it would do, and doing it ({@code docs/moderation.md}).
 *
 * <p>Self-scoped by construction like {@link BlocksResource}: the subject is always the bearer/session
 * identity. There is no admin form of this -- an administrator who needs to remove someone uses the admin
 * pages, which keep the person; this is the person removing themselves.
 *
 * <p>The delete is a {@code POST} to {@code account/delete} rather than a {@code DELETE} on {@code account}
 * because it carries a body (the typed confirmation) and a DELETE with a body is dropped or rejected by
 * enough intermediaries that a mobile client cannot rely on it.
 */
@Path("account")
@TripApi
public class AccountResource extends BaseResource {

    private static final String V1 = ApiMediaTypes.ACCOUNT_V1;

    @Override
    protected String versionedType() {
        return V1;
    }

    /** What the app shows before the person confirms: whether deletion is allowed, what goes, what stays. */
    @GET
    @Path("deletion-preview")
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response deletionPreview() {
        final AccountDeletionCommands.Preview preview = Beans.get(AccountDeletionCommands.class).preview(personId());
        if (preview == null) {
            return error(404, ApiErrors.NOT_FOUND, "No such account.");
        }
        return ok(AccountDeletionCommands.previewBody(preview));
    }

    /**
     * Deletes the caller's account. Body {@code {"confirm": "DELETE"}}. 409 with the preview when deletion is
     * blocked (settle-first, sole org admin, unsettled dependents); 200 {@code {"deleted": true}} when done --
     * after which every token this caller holds is dead, so the client must sign out locally.
     */
    @POST
    @Path("delete")
    @Consumes({V1, MediaType.APPLICATION_JSON})
    @Produces({V1, MediaType.APPLICATION_JSON})
    public Response delete(
            @HeaderParam(CSRF_HEADER) final String csrf,
            final Map<String, Object> body) {
        if (csrfMissing(csrf)) {
            return error(403, ApiErrors.CSRF, "Missing " + CSRF_HEADER + " header.");
        }
        final Object confirm = body == null ? null : body.get("confirm");
        final AccountDeletionCommands.Outcome outcome = Beans.get(AccountDeletionCommands.class)
                .deleteOwnAccount(personId(), confirm == null ? null : confirm.toString(), actor());
        if (outcome.ok()) {
            return ok(Map.of("deleted", true));
        }
        return switch (outcome.code() == null ? "" : outcome.code()) {
            case AccountDeletionCommands.REFUSED_CONFIRMATION -> error(400, ApiErrors.VALIDATION_FAILED,
                    outcome.message());
            case AccountDeletionCommands.REFUSED_NOT_FOUND -> error(404, ApiErrors.NOT_FOUND, outcome.message());
            case AccountDeletionCommands.REFUSED_BLOCKED -> blocked(outcome);
            default -> error(500, ApiErrors.STORE_FAILED, outcome.message());
        };
    }

    private Response blocked(final AccountDeletionCommands.Outcome outcome) {
        final Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("error", "ACCOUNT_DELETE_BLOCKED");
        entity.put("message", outcome.message());
        if (outcome.preview() != null) {
            entity.put("preview", AccountDeletionCommands.previewBody(outcome.preview()));
        }
        return Response.status(409)
                .type(negotiatedType())
                .header("Vary", "Accept")
                .entity(entity)
                .build();
    }
}
