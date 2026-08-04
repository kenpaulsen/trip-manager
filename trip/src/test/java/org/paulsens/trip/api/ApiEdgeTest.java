package org.paulsens.trip.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Field;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The API's edge pieces: {@link TripAuthFilter} and {@link JsonExceptionMapper}.
 *
 * <p>Their shared contract is "JSON, always". Both exist because the container's default behaviour on failure is
 * an HTML error page (or a redirect to {@code /index.jsf}), which a mobile client cannot parse; a single
 * {@code sendError} sneaking back in would break every client's error handling at once.
 */
public class ApiEdgeTest {

    private static final Person.Id ME = Person.Id.from("edge-me");

    // --- TripAuthFilter ---

    private static TripAuthFilter filterWith(final HttpServletRequest request) throws Exception {
        final TripAuthFilter filter = new TripAuthFilter();
        final Field field = TripAuthFilter.class.getDeclaredField("request");
        field.setAccessible(true);
        field.set(filter, request);
        return filter;
    }

    private static Response abortedResponse(final ContainerRequestContext ctx) {
        final ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        Mockito.verify(ctx).abortWith(response.capture());
        return response.getValue();
    }

    @Test
    public void noSessionIs401JsonNeverAnHtmlErrorPage() throws Exception {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getSession(false)).thenReturn(null);
        final ContainerRequestContext ctx = Mockito.mock(ContainerRequestContext.class);

        filterWith(request).filter(ctx);

        final Response response = abortedResponse(ctx);
        Assert.assertEquals(response.getStatus(), 401);
        Assert.assertEquals(response.getMediaType().toString(), "application/json");
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("error"), ApiErrors.NOT_AUTHENTICATED);
        // getSession(false), never (true): only login may create a session.
        Mockito.verify(request, Mockito.never()).getSession(true);
    }

    @Test
    public void aSessionWithNoUserIdIs401() throws Exception {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(request.getSession(false)).thenReturn(session);
        final ContainerRequestContext ctx = Mockito.mock(ContainerRequestContext.class);

        filterWith(request).filter(ctx);

        Assert.assertEquals(abortedResponse(ctx).getStatus(), 401);
    }

    @Test
    public void aSignedInSessionStampsThePersonIdOnBothRequests() throws Exception {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(request.getSession(false)).thenReturn(session);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ID)).thenReturn(ME);
        final ContainerRequestContext ctx = Mockito.mock(ContainerRequestContext.class);

        filterWith(request).filter(ctx);

        Mockito.verify(ctx, Mockito.never()).abortWith(ArgumentMatchers.any());
        Mockito.verify(ctx).setProperty(TripAuthFilter.PERSON_ID_PROP, ME);
        Mockito.verify(request).setAttribute(TripAuthFilter.PERSON_ID_PROP, ME);
    }

    /** Sessions written before Person.Id was stored directly hold a String; both forms must authenticate. */
    @Test
    public void aStringUserIdFromAnOlderSessionStillAuthenticates() throws Exception {
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(request.getSession(false)).thenReturn(session);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ID)).thenReturn(ME.getValue());
        final ContainerRequestContext ctx = Mockito.mock(ContainerRequestContext.class);

        filterWith(request).filter(ctx);

        Mockito.verify(ctx).setProperty(TripAuthFilter.PERSON_ID_PROP, ME);
    }

    // --- JsonExceptionMapper ---

    private final JsonExceptionMapper mapper = new JsonExceptionMapper();

    @Test
    public void notAcceptableListsWhatIsSupported() {
        final Response response = mapper.toResponse(new NotAcceptableException());

        Assert.assertEquals(response.getStatus(), 406);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("error"), "NOT_ACCEPTABLE");
        Assert.assertNotNull(body.get("supported"));
    }

    /** A bean rejecting its arguments is the caller's fault: 400, not a retryable-looking 500. */
    @Test
    public void illegalArgumentIs400NotInternal() {
        final Response response = mapper.toResponse(new IllegalArgumentException("Unknown status: nope"));

        Assert.assertEquals(response.getStatus(), 400);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("error"), ApiErrors.BAD_REQUEST);
        Assert.assertEquals(body.get("message"), "Unknown status: nope");
    }

    @Test
    public void aWebApplicationExceptionKeepsItsStatusAndGainsJson() {
        final Response response = mapper.toResponse(new NotFoundException());

        Assert.assertEquals(response.getStatus(), 404);
        Assert.assertEquals(response.getMediaType().toString(), "application/json");
        Assert.assertEquals(response.getHeaderString("Vary"), "Accept");
    }

    @Test
    public void aWebApplicationExceptionWithABodyPassesThrough() {
        final Response original = Response.status(409).entity(Map.of("error", "CONFLICT")).build();

        final Response response = mapper.toResponse(new WebApplicationException(original));

        Assert.assertEquals(response.getStatus(), 409);
        Assert.assertEquals(response.getHeaderString("Vary"), "Accept");
    }

    @Test
    public void anythingElseIs500WithoutLeakingTheException() {
        final Response response = mapper.toResponse(new IllegalStateException("secret internal detail"));

        Assert.assertEquals(response.getStatus(), 500);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("message"), "Internal server error.");
        Assert.assertFalse(String.valueOf(body).contains("secret internal detail"),
                "Exception detail must not reach the wire");
    }
}
