package org.paulsens.trip.api;

import jakarta.annotation.Priority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.Map;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.model.Person;

/**
 * Session auth for the whole API. Never creates a session ({@code getSession(false)}) -- only
 * {@code AuthResource.login} may do that. Sets the {@code personId} property for the resource. Returns 401 JSON,
 * never {@code sendError}, which would hit the default HTML error page and hand a mobile client an HTML body
 * where it expects JSON.
 */
@Provider
@TripApi
@Priority(Priorities.AUTHENTICATION)
public class TripAuthFilter implements ContainerRequestFilter {

    public static final String PERSON_ID_PROP = "trip.personId";

    @Context
    private HttpServletRequest request;

    @Override
    public void filter(final ContainerRequestContext ctx) throws IOException {
        final HttpSession session = request.getSession(false);
        if (session == null) {
            abort(ctx, 401, ApiErrors.NOT_AUTHENTICATED, "Sign in required.");
            return;
        }
        final Object raw = session.getAttribute(PersonCommands.ACTIVE_USER_ID);
        if (raw == null) {
            abort(ctx, 401, ApiErrors.NOT_AUTHENTICATED, "Sign in required.");
            return;
        }
        final Person.Id personId = raw instanceof Person.Id pid ? pid : Person.Id.from(raw.toString());
        ctx.setProperty(PERSON_ID_PROP, personId);
        // Also on the servlet request so the resource can read it without ContainerRequestContext injection.
        request.setAttribute(PERSON_ID_PROP, personId);
    }

    static void abort(
            final ContainerRequestContext ctx, final int status, final String code, final String message) {
        ctx.abortWith(Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .header("Vary", "Accept")
                .entity(Map.of("error", code, "message", message))
                .build());
    }
}
