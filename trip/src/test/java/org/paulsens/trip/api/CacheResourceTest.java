package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link CacheResource} — the out-of-band writers' invalidation entry point. The negative space matters
 * here the same way it does for deploys: a refused request must not have cleared anything (a clear is safe
 * but briefly expensive, and an unauthenticated one would be a free cache-thrash lever).
 */
public class CacheResourceTest extends ResourceTestSupport {

    private static final Person.Id ADMIN = Person.Id.from("cache-admin");

    private CacheResource resource;

    @BeforeMethod
    public void bindResource() {
        bindMock(org.paulsens.trip.action.PersonCommands.class);
        resource = resource(new CacheResource());
    }

    @Test
    public void invalidateRefusesWithoutCsrf() {
        signedInAsSiteAdmin(ADMIN);
        assertError(resource.invalidate(null, new CacheResource.InvalidateRequest("person")),
                403, ApiErrors.CSRF);
    }

    @Test
    public void invalidateRefusesWithoutConfigAdmin() {
        signedInAs(ADMIN);
        assertError(resource.invalidate(CSRF_OK, new CacheResource.InvalidateRequest("person")),
                403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void invalidateRefusesAnUnknownScope() {
        signedInAsSiteAdmin(ADMIN);
        assertError(resource.invalidate(CSRF_OK, new CacheResource.InvalidateRequest("everything")),
                400, ApiErrors.VALIDATION_FAILED);
        assertError(resource.invalidate(CSRF_OK, new CacheResource.InvalidateRequest("  ")),
                400, ApiErrors.VALIDATION_FAILED);
        assertError(resource.invalidate(CSRF_OK, null), 400, ApiErrors.VALIDATION_FAILED);
    }

    /** The scope name is case-insensitive and maps to the DAO's prefix list. */
    @Test
    public void invalidateClearsTheScopeAndReportsThePrefixes() {
        signedInAsSiteAdmin(ADMIN);

        final Response response = resource.invalidate(CSRF_OK, new CacheResource.InvalidateRequest("Person"));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("scope"), "PERSON");
        @SuppressWarnings("unchecked")
        final List<String> cleared = (List<String>) body.get("invalidated");
        Assert.assertTrue(cleared.contains("t1:person:"), "cleared: " + cleared);
        Assert.assertTrue(cleared.contains("t1:email"), "person invalidation must cover the email index");
    }

    @Test
    public void allScopeClearsTheWholeDataNamespace() {
        signedInAsSiteAdmin(ADMIN);

        final Response response = resource.invalidate(CSRF_OK, new CacheResource.InvalidateRequest("all"));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("invalidated"), List.of("t1:"));
    }
}
