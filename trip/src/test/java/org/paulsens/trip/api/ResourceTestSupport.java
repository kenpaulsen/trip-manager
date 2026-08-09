package org.paulsens.trip.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

/**
 * What a JAX-RS resource needs around it to be exercised without a container.
 *
 * <p>Two things are deliberately NOT mocked. Identity and authorization run for real: {@code Caller},
 * {@link ApiPrivileges} and {@code PersonCommands.hasRole} all derive from session attributes, so setting the
 * attributes drives the genuine authorization code rather than a mock's idea of it. A test that stubbed
 * {@code isSiteAdmin()} would pass while the real rule was broken, which is the opposite of the point.
 *
 * <p>What IS mocked is {@link Beans#get}, because the alternative is a CDI container in the test JVM. Resources
 * reach for their command beans through it, so each test declares the beans it expects to be asked for and an
 * unexpected lookup fails loudly rather than returning null and surfacing as an NPE ten frames down.
 */
public abstract class ResourceTestSupport {

    /** The value the CSRF sentinel header must carry; its presence is the whole check. */
    protected static final String CSRF_OK = "1";

    protected HttpServletRequest request;
    protected jakarta.servlet.http.HttpServletResponse response;
    protected HttpSession session;

    private MockedStatic<Beans> beans;
    private final Map<Class<?>, Object> bound = new HashMap<>();
    private final Map<String, Object> sessionAttributes = new HashMap<>();
    private final Map<String, Object> requestAttributes = new HashMap<>();

    @BeforeMethod
    public void setUpRequestAndBeans() {
        bound.clear();
        sessionAttributes.clear();
        requestAttributes.clear();

        session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(Mockito.anyString()))
                .thenAnswer(call -> sessionAttributes.get(call.<String>getArgument(0)));

        request = Mockito.mock(HttpServletRequest.class);
        Mockito.when(request.getSession(false)).thenReturn(session);
        Mockito.when(request.getAttribute(Mockito.anyString()))
                .thenAnswer(call -> requestAttributes.get(call.<String>getArgument(0)));
        response = Mockito.mock(jakarta.servlet.http.HttpServletResponse.class);

        beans = Mockito.mockStatic(Beans.class);
        beans.when(() -> Beans.get(Mockito.any())).thenAnswer(call -> resolve(call.getArgument(0)));
    }

    @AfterMethod(alwaysRun = true)
    public void closeStaticMocks() {
        if (beans != null) {
            beans.close();
            beans = null;
        }
    }

    private Object resolve(final Class<?> type) {
        final Object bean = bound.get(type);
        if (bean == null) {
            throw new AssertionError("The resource asked for a " + type.getSimpleName()
                    + " that this test never bound. Add bindMock(" + type.getSimpleName() + ".class).");
        }
        return bean;
    }

    /** Registers a mock for a bean the resource under test will look up, and returns it for stubbing. */
    protected <T> T bindMock(final Class<T> type) {
        final T mock = Mockito.mock(type);
        bound.put(type, mock);
        return mock;
    }

    /** A bean bound earlier, for stubbing it from a test that did not do the binding itself. */
    protected <T> T bean(final Class<T> type) {
        return type.cast(resolve(type));
    }

    /** Registers a real instance, for beans whose genuine behaviour is what the test is about. */
    protected <T> T bind(final Class<T> type, final T instance) {
        bound.put(type, instance);
        return instance;
    }

    /**
     * Hands the resource this test's mocked request. JAX-RS would normally {@code @Context}-inject it.
     *
     * <p>One resource instance serves ONE caller. {@code BaseResource} memoizes its {@code Caller} and
     * {@code ApiPrivileges} on first use, which is correct because JAX-RS builds a resource per request; in a
     * test it means changing the session attributes after an endpoint has been called has no effect. A test
     * that needs two identities needs two resources, so build a fresh one rather than re-signing-in.
     */
    protected <T extends BaseResource> T resource(final T resource) {
        resource.request = request;
        resource.response = response;
        return resource;
    }

    /**
     * Signs in as an ordinary user.
     *
     * <p>Sets the filter's request attribute AND the raw session key, because {@code BaseResource.personId()}
     * prefers the former and falls back to the latter, and both paths need to be reachable.
     */
    protected void signedInAs(final Person.Id id) {
        requestAttributes.put(TripAuthFilter.PERSON_ID_PROP, id);
        sessionAttributes.put(PersonCommands.ACTIVE_USER_ID, id);
        // Explicitly NOT an admin. Without this an earlier signedInAsSiteAdmin in the same test would leave the
        // role behind and the "ordinary user is refused" assertion would quietly be testing an administrator.
        sessionAttributes.remove(PersonCommands.ACTIVE_USER_ROLE);
    }

    protected void signedInAsSiteAdmin(final Person.Id id) {
        signedInAs(id);
        sessionAttributes.put(PersonCommands.ACTIVE_USER_ROLE, "admin");
    }

    /**
     * Signed in via the SESSION only, with no filter-stamped request attribute -- the shape of a request that
     * reached a resource without passing TripAuthFilter. Exercises personId()'s fallback path.
     */
    protected void signedInWithoutTheFilter(final Person.Id id) {
        requestAttributes.remove(TripAuthFilter.PERSON_ID_PROP);
        sessionAttributes.put(PersonCommands.ACTIVE_USER_ID, id);
    }

    /** Sets a raw session attribute, for the keys that have no dedicated helper (loginEmail and friends). */
    protected void session(final String name, final Object value) {
        sessionAttributes.put(name, value);
    }

    /**
     * No session and no filter attribute, which is what an unauthenticated request looks like here. Clearing
     * only the session is not enough: personId() prefers the attribute the auth filter stamped, so a test that
     * signed in earlier would remain signed in through it.
     */
    protected void anonymous() {
        requestAttributes.remove(TripAuthFilter.PERSON_ID_PROP);
        sessionAttributes.clear();
        Mockito.when(request.getSession(false)).thenReturn(null);
    }

    protected void accepting(final String mediaType) {
        Mockito.when(request.getHeader("Accept")).thenReturn(mediaType);
    }

    protected void assertOk(final Response response) {
        Assert.assertEquals(response.getStatus(), 200, "Expected 200, body: " + response.getEntity());
        assertVaryAccept(response);
    }

    /**
     * Asserts the error shape every resource is supposed to produce.
     *
     * <p>Checks the machine-readable {@code error} code, not the human message: the message is free to be
     * reworded, the code is what a client branches on.
     */
    protected void assertError(final Response response, final int status, final String code) {
        Assert.assertEquals(response.getStatus(), status);
        Assert.assertTrue(response.getEntity() instanceof Map, "Error body should be a map");
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("error"), code);
        assertVaryAccept(response);
    }

    /**
     * Asserts a call ends in the thrown flavour of the same error shape.
     *
     * <p>{@code requireTrip}/{@code requireSubject} abort the request with a {@code WebApplicationException}
     * carrying the negotiated error body (which {@code JsonExceptionMapper} passes through untouched), so the
     * endpoint methods never see the miss. Container behaviour is "same JSON, same status"; in a direct-call
     * test it surfaces as the exception this helper unwraps.</p>
     */
    protected void assertThrown(final Runnable call, final int status, final String code) {
        try {
            call.run();
            Assert.fail("expected a " + status + " " + code + " WebApplicationException");
        } catch (final jakarta.ws.rs.WebApplicationException expected) {
            assertError(expected.getResponse(), status, code);
        }
    }

    /**
     * Vary: Accept is not decoration. v1 and a future v2 share byte-identical URLs, so a cache keyed on URL
     * alone would hand a v1 body to a v2 request; the browser cache is the live risk here.
     */
    protected void assertVaryAccept(final Response response) {
        Assert.assertEquals(response.getHeaderString("Vary"), "Accept", "Missing Vary: Accept");
    }

    /** A caller that named the version gets it echoed back. */
    protected void assertVersionedType(final Response response, final String versionedType) {
        Assert.assertEquals(response.getMediaType().toString(), versionedType);
    }

    /** A caller that named no version is told plain JSON, never a version it did not ask for. */
    protected void assertPlainJsonType(final Response response) {
        Assert.assertEquals(response.getMediaType().toString(), MediaType.APPLICATION_JSON);
    }
}
