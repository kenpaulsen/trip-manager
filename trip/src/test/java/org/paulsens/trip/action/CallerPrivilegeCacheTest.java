package org.paulsens.trip.action;

import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * {@link Caller}'s privilege answers, and why they are cached.
 *
 * <p>The underlying check is a map lookup followed by a linear scan of everyone holding that privilege, and the
 * call sites are not one-shot: {@code TripsResource.canRead} asks twice per trip inside a list filter, so a
 * fifty-trip listing was a hundred scans of the same two rows. A caller lives for one request and privileges
 * cannot meaningfully change inside one, so the cache has none of the staleness a longer-lived one would.
 */
public class CallerPrivilegeCacheTest {

    private static final Person.Id ME = Person.Id.from("person-1");

    @Test
    public void repeatedChecksHitTheStoreOnce() {
        final PrivilegeCommands privileges = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(privileges.check(PrivilegeCommands.TRIP_VIEW, "trip-1", ME)).thenReturn(true);
        final Caller caller = new Caller(ME, false, AuditActor.from(null), privileges);

        for (int i = 0; i < 50; i++) {
            Assert.assertTrue(caller.has(PrivilegeCommands.TRIP_VIEW, "trip-1"));
        }

        Mockito.verify(privileges, Mockito.times(1)).check(PrivilegeCommands.TRIP_VIEW, "trip-1", ME);
    }

    @Test
    public void aRefusalIsCachedToo() {
        // Both answers must be remembered. Caching only the "yes" would leave the expensive case -- somebody
        // who holds nothing, checked repeatedly -- paying full price every time.
        final PrivilegeCommands privileges = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(privileges.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        final Caller caller = new Caller(ME, false, AuditActor.from(null), privileges);

        for (int i = 0; i < 10; i++) {
            Assert.assertFalse(caller.has(PrivilegeCommands.CONFIG_ADMIN));
        }

        Mockito.verify(privileges, Mockito.times(1))
                .check(PrivilegeCommands.CONFIG_ADMIN, null, ME);
    }

    @Test
    public void theSameNameOnDifferentTripsIsNotOneAnswer() {
        // The cache key must include the trip. Collapsing them would grant a trip privilege everywhere the
        // moment it was granted anywhere -- a cache that silently widens authorization.
        final PrivilegeCommands privileges = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(privileges.check(PrivilegeCommands.TRIP_MGR, "trip-1", ME)).thenReturn(true);
        Mockito.when(privileges.check(PrivilegeCommands.TRIP_MGR, "trip-2", ME)).thenReturn(false);
        final Caller caller = new Caller(ME, false, AuditActor.from(null), privileges);

        Assert.assertTrue(caller.has(PrivilegeCommands.TRIP_MGR, "trip-1"));
        Assert.assertFalse(caller.has(PrivilegeCommands.TRIP_MGR, "trip-2"),
                "A privilege on one trip must not answer for another.");
    }

    @Test
    public void aSiteAdminNeverConsultsTheStoreAtAll() {
        final PrivilegeCommands privileges = Mockito.mock(PrivilegeCommands.class);
        final Caller caller = new Caller(ME, true, AuditActor.from(null), privileges);

        Assert.assertTrue(caller.has(PrivilegeCommands.CONFIG_ADMIN));

        Mockito.verifyNoInteractions(privileges);
    }

    @Test
    public void anActorOnlyCallerIsAuthorizedByPrivilegeAndNotByAssumedAdmin() {
        // Caller.forActor is the path for beans reachable with an AuditActor alone -- a background thread, a
        // test. An actor carries no role, so site-admin must NOT be inferred from one; if it were, anything
        // able to name an actor could name itself an administrator.
        final Caller caller = Caller.forActor(new AuditActor("someone@example.com", ME.getValue()));

        Assert.assertEquals(caller.personId(), ME);
        Assert.assertFalse(caller.isSiteAdmin(), "An actor is an identity, not a role.");
    }

    @Test
    public void anUnauthenticatedCallerHoldsNothingAndAsksNothing() {
        final PrivilegeCommands privileges = Mockito.mock(PrivilegeCommands.class);
        final Caller caller = new Caller(null, false, AuditActor.from(null), privileges);

        Assert.assertFalse(caller.isAuthenticated());
        Assert.assertFalse(caller.has(PrivilegeCommands.PEOPLE_ADMIN));
        Assert.assertEquals(caller.globalPrivileges(), java.util.Set.of());
        Mockito.verifyNoInteractions(privileges);
    }
}
