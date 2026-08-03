package org.paulsens.trip.action;

import jakarta.servlet.http.HttpSession;
import org.mockito.Mockito;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The shared caller.
 *
 * <p>Before this existed, "who is asking and what may they do" was answered differently at each edge, and every
 * one of those answers failed the same way: a check that cannot see the session does not throw, it returns
 * false. A site administrator became indistinguishable from a stranger, silently, on whichever paths had not
 * yet grown their own accommodation.
 *
 * <p>What matters most here is the direction of failure. Every case below that lacks information must deny,
 * never grant.
 */
public class CallerTest {

    private static final Person.Id ME = Person.Id.from("person-1");

    @Test
    public void aCallerFromASessionSeesBothIdentityAndRoleWithoutAFacesContext() {
        // There is no FacesContext in this JVM. That is the point: this is the path a JAX-RS request takes.
        final Caller caller = Caller.of(sessionWith(ME, "admin"));

        Assert.assertEquals(caller.personId(), ME);
        Assert.assertTrue(caller.isSiteAdmin());
        Assert.assertTrue(caller.isAuthenticated());
    }

    @Test
    public void noSessionYieldsACallerWhoHoldsNothing() {
        // Fails CLOSED. An unauthenticated request must look like somebody with no privileges.
        final Caller caller = Caller.of(null);

        Assert.assertFalse(caller.isAuthenticated());
        Assert.assertFalse(caller.isSiteAdmin());
        Assert.assertFalse(caller.has(PrivilegeCommands.CONFIG_ADMIN));
        Assert.assertNull(caller.personId());
    }

    @Test
    public void anUnauthenticatedCallerIsRefusedEvenForAPrivilegeThatWouldOtherwiseMatch() {
        // Guards the null-personId path specifically: check(name, trip, null) must not be reached with a null
        // id and quietly match a row, so the caller short-circuits first.
        final PrivilegeCommands granting = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(granting.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(true);

        final Caller caller = new Caller(null, false, AuditActor.from(null), granting);

        Assert.assertFalse(caller.has(PrivilegeCommands.PEOPLE_ADMIN),
                "Nobody signed in must never hold a privilege, whatever the store says.");
    }

    @Test
    public void aSiteAdminHoldsEverythingWithoutAnyRowExisting() {
        // The rows exist to grant access to people who are NOT administrators, so requiring one for an admin
        // means refusing them whenever nobody remembered to create it.
        final PrivilegeCommands refusing = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(refusing.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);

        final Caller caller = new Caller(ME, true, AuditActor.from(null), refusing);

        Assert.assertTrue(caller.has(PrivilegeCommands.PEOPLE_ADMIN));
        Assert.assertTrue(caller.has(PrivilegeCommands.TRIP_MGR, "trip-1"));
    }

    @Test
    public void anOrdinaryCallerHoldsOnlyWhatIsGrantedAndOnlyWhereItIsGranted() {
        final PrivilegeCommands granting = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(granting.check(PrivilegeCommands.TRIP_VIEW, "trip-1", ME)).thenReturn(true);

        final Caller caller = new Caller(ME, false, AuditActor.from(null), granting);

        Assert.assertTrue(caller.has(PrivilegeCommands.TRIP_VIEW, "trip-1"));
        Assert.assertFalse(caller.has(PrivilegeCommands.TRIP_VIEW, "trip-2"), "Scope must not leak across trips.");
        Assert.assertFalse(caller.has(PrivilegeCommands.TRIP_MGR, "trip-1"), "Nor across privilege names.");
    }

    @Test
    public void theAuditActorTravelsWithTheCallerSoTheyCannotDisagree() {
        // Resolving the actor separately from the authorization decision is how a record ends up naming
        // somebody other than whoever was actually authorized.
        final Caller caller = Caller.of(sessionWith(ME, "admin"));

        Assert.assertEquals(caller.auditActor().id(), ME.getValue());
    }

    @Test
    public void adminSetPassRefusesWithoutThePrivilegeAndDoesNotConsultShowAll() {
        // The legacy path reads viewScope.showAll, which does not exist off a Faces thread. The Caller form
        // asks for peopleAdmin instead -- and must refuse a caller who lacks it rather than falling back.
        final PrivilegeCommands refusing = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(refusing.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        final Caller nobody = new Caller(ME, false, AuditActor.from(null), refusing);

        Assert.assertFalse(new PassCommands().adminSetPass("someone@example.com", "hunter2", nobody));
        Assert.assertFalse(new PassCommands().adminSetPass("someone@example.com", "hunter2", null),
                "A null caller must be refused, not treated as trusted.");
    }

    private static HttpSession sessionWith(final Person.Id id, final String role) {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ID)).thenReturn(id);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ROLE)).thenReturn(role);
        Mockito.when(session.getAttribute("userId")).thenReturn(id);
        return session;
    }
}
