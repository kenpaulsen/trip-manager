package org.paulsens.trip.audit;

import jakarta.servlet.http.HttpSession;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.Mockito;
import org.paulsens.trip.util.TripThreads;
import org.testng.Assert;
import org.testng.annotations.Test;

/** The ScopedValue identity plumbing: bind -> read -> inherit into forks -> explicit rebind for spawns. */
public class RequestContextTest {

    private static HttpSession sessionOf(final String email, final Object userId, final String role) {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute("loginEmail")).thenReturn(email);
        Mockito.when(session.getAttribute("userId")).thenReturn(userId);
        Mockito.when(session.getAttribute("userRole")).thenReturn(role);
        return session;
    }

    @Test
    public void boundContextDrivesAuditActorCurrent() throws Exception {
        final RequestContext ctx = RequestContext.from(sessionOf("kim@x.org", "u-42", "admin"));
        final AuditActor seen = ScopedValue.where(RequestContext.SCOPE, ctx).call(AuditActor::current);
        Assert.assertEquals(seen.email(), "kim@x.org");
        Assert.assertEquals(seen.id(), "u-42");
        Assert.assertEquals(ctx.userRole(), "admin");
    }

    /** The Person.Id-vs-String session divergence must normalize identically to the FacesContext path. */
    @Test
    public void personIdObjectsNormalizeToBareIds() {
        final RequestContext ctx = RequestContext.from(
                sessionOf("kim@x.org", org.paulsens.trip.model.Person.Id.from("u-42"), "user"));
        Assert.assertEquals(ctx.actor().id(), "u-42");
    }

    @Test
    public void unboundFallsBackToUnknownOutsideFaces() {
        Assert.assertFalse(AuditActor.current().isKnown(),
                "no binding and no FacesContext: the actor must read as unknown, never invented");
    }

    /** The whole point of the ScopedValue: a StructuredTaskScope fork sees its request's actor for free. */
    @Test
    public void structuredForksInheritTheActor() throws Exception {
        final RequestContext ctx = RequestContext.from(sessionOf("fork@x.org", "u-7", "user"));
        final String seen = ScopedValue.where(RequestContext.SCOPE, ctx).call(RequestContextTest::actorInFork);
        Assert.assertEquals(seen, "fork@x.org");
    }

    private static String actorInFork() throws InterruptedException {
        try (var scope = StructuredTaskScope.open()) {
            final var subtask = scope.fork(RequestContextTest::readActorEmail);
            scope.join();
            return subtask.get();
        }
    }

    private static String readActorEmail() {
        return AuditActor.current().email();
    }

    /** Plain spawns do NOT inherit; startAs is the explicit rebind fire-and-forget work must use. */
    @Test
    public void startAsRebindsWherePlainStartDoesNot() throws Exception {
        final RequestContext ctx = RequestContext.of(new AuditActor("spawn@x.org", "u-9"));
        final AtomicReference<AuditActor> plain = new AtomicReference<>();
        final AtomicReference<AuditActor> rebound = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(2);
        ScopedValue.where(RequestContext.SCOPE, ctx).run(() -> spawnBoth(plain, rebound, done));
        Assert.assertTrue(done.await(3, TimeUnit.SECONDS));
        Assert.assertFalse(plain.get().isKnown(), "a plain spawn must NOT silently inherit the actor");
        Assert.assertEquals(rebound.get().email(), "spawn@x.org");
    }

    private static void spawnBoth(final AtomicReference<AuditActor> plain,
            final AtomicReference<AuditActor> rebound, final CountDownLatch done) {
        TripThreads.start(() -> record(plain, done));
        TripThreads.startAs(new AuditActor("spawn@x.org", "u-9"), () -> record(rebound, done));
    }

    private static void record(final AtomicReference<AuditActor> into, final CountDownLatch done) {
        into.set(AuditActor.current());
        done.countDown();
    }
}
