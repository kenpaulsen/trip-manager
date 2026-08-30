package org.paulsens.trip.api;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Properties of the API surface as a whole, checked by reflection because no single file can express them.
 *
 * <p>These are cheap and they close two gaps that unit tests of individual resources cannot see: a resource
 * that exists but was never registered, and a resource that quietly grew a write method it should never have.
 */
public class ApiSurfaceTest {

    private static final List<Class<?>> RESOURCES = List.of(
            AuditResource.class, AuthResource.class, ChatAdminResource.class, ChatResource.class,
            ConfigResource.class, DeployResource.class, MailResource.class, MediaResource.class,
            PasskeyResource.class, PaymentsResource.class, PeopleResource.class, PrivilegesResource.class,
            RegistrationsResource.class, TodosResource.class, TransactionsResource.class, TripsResource.class);

    @Test
    public void theAuditTrailIsReadOnlyAndMustStayThatWay() {
        // The point of the trail is that it cannot be rewritten. AuditCommands -- the WRITER -- is deliberately
        // not exposed anywhere, and records are append-only in IAM as well as in code. A POST/PUT/DELETE here
        // would let a client assert history, which is precisely the thing being defended against.
        final List<String> writes = Arrays.stream(AuditResource.class.getDeclaredMethods())
                .filter(ApiSurfaceTest::isWrite)
                .map(Method::getName)
                .toList();

        Assert.assertEquals(writes, List.of(),
                "AuditResource must expose no write methods; found: " + writes);
    }

    @Test
    public void everyResourceIsRegisteredWithTheApplication() {
        // TripApiApplication.getClasses() is an explicit set with no package scanning, so an unregistered
        // resource does not fail loudly -- it 404s, with nothing in the log to explain why.
        final Set<Class<?>> registered = new TripApiApplication().getClasses();

        for (final Class<?> resource : RESOURCES) {
            Assert.assertTrue(registered.contains(resource),
                    resource.getSimpleName() + " is missing from TripApiApplication.getClasses(); it will 404.");
        }
    }

    @Test
    public void everyResourceRequiresASessionUnlessItIsTheOneThatCannot() {
        // A resource with no @TripApi is not a broken resource, it is an OPEN one -- the auth filter is
        // name-bound, so forgetting the annotation silently serves everybody. The two auth resources are the
        // deliberate exceptions, because ways IN have to work without a session; they bind per-method instead.
        for (final Class<?> resource : RESOURCES) {
            if (resource == AuthResource.class || resource == PasskeyResource.class) {
                continue;
            }
            Assert.assertNotNull(resource.getAnnotation(TripApi.class),
                    resource.getSimpleName() + " is not @TripApi, so it serves unauthenticated callers.");
        }
    }

    @Test
    public void authResourceBindsAuthPerMethodSoOnlyLoginIsOpen() {
        final List<String> unbound = Arrays.stream(AuthResource.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(Path.class) != null)
                .filter(method -> method.getAnnotation(TripApi.class) == null)
                .map(Method::getName)
                .sorted()
                .toList();

        // logout is intentionally open: signing out when already signed out is a success, not a 401. The two
        // code endpoints are the email-code login -- like login, they only make sense WITHOUT a session.
        Assert.assertEquals(unbound, List.of("login", "logout", "requestCode", "verifyCode"),
                "Unexpected open endpoints on AuthResource.");
    }

    @Test
    public void passkeyResourceBindsAuthPerMethodSoOnlyItsLoginIsOpen() {
        final List<String> unbound = Arrays.stream(PasskeyResource.class.getDeclaredMethods())
                .filter(method -> method.getAnnotation(Path.class) != null)
                .filter(method -> method.getAnnotation(TripApi.class) == null)
                .map(Method::getName)
                .sorted()
                .toList();

        // The assertion ceremony IS a login: it must work sessionless, exactly like auth/login. Everything
        // else (registration, listing, deletion) requires the session.
        Assert.assertEquals(unbound, List.of("loginFinish", "loginStart"),
                "Unexpected open endpoints on PasskeyResource.");
    }

    @Test
    public void noTwoResourcesClaimTheSamePath() {
        // ChatAdminResource sits at the literal chat/my-channels precisely so it cannot overlap ChatResource's
        // chat/channels/{channelId}. An exact duplicate would make which one answers a matter of precedence.
        final List<String> paths = RESOURCES.stream()
                .map(resource -> resource.getAnnotation(Path.class).value())
                .toList();

        Assert.assertEquals(paths.size(), Set.copyOf(paths).size(),
                "Two resources declare the same @Path: " + paths);
    }

    private static boolean isWrite(final Method method) {
        return method.getAnnotation(POST.class) != null
                || method.getAnnotation(PUT.class) != null
                || method.getAnnotation(PATCH.class) != null
                || method.getAnnotation(DELETE.class) != null;
    }
}
