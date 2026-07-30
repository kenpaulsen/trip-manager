package org.paulsens.trip.api;

import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.paulsens.trip.action.ChatCommands;

/**
 * Closes the {@code /index.jsf} default error-page trap: always emit JSON, never hand control to the
 * container error page via {@code sendError}.
 */
@Slf4j
@Provider
public class JsonExceptionMapper implements ExceptionMapper<Throwable> {

    @Override
    public Response toResponse(final Throwable exception) {
        if (exception instanceof NotAcceptableException) {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "NOT_ACCEPTABLE");
            body.put("message", "Unsupported Accept media type.");
            body.put("supported", List.of(ChatCommands.MEDIA_TYPE_V1, "application/json"));
            return Response.status(406)
                    .type(MediaType.APPLICATION_JSON)
                    .header("Vary", "Accept")
                    .entity(body)
                    .build();
        }
        if (exception instanceof WebApplicationException wae) {
            final Response existing = wae.getResponse();
            if (existing != null && existing.hasEntity()) {
                return Response.fromResponse(existing).header("Vary", "Accept").build();
            }
            final int status = existing == null ? 500 : existing.getStatus();
            return json(status, "HTTP_" + status, wae.getMessage() == null ? "Request failed." : wae.getMessage());
        }
        log.error("Unhandled API error", exception);
        return json(500, "INTERNAL", "Internal server error.");
    }

    private static Response json(final int status, final String code, final String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .header("Vary", "Accept")
                .entity(Map.of("error", code, "message", message))
                .build();
    }
}
