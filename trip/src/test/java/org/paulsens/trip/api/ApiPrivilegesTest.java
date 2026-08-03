package org.paulsens.trip.api;

import jakarta.servlet.http.HttpSession;
import org.mockito.Mockito;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Authorization at the REST edge, where there is no {@code FacesContext}.
 *
 * <p>The failure this guards is not "an admin gets an error". It is that the FacesContext-backed checks resolve
 * their session through a ThreadLocal that simply does not exist on a JAX-RS thread, and so return
 * {@code false} without complaint. A site administrator would be indistinguishable from a stranger, on every
 * endpoint, with nothing logged.
 *
 * <p>Since the shared {@link Caller} was introduced, the "is this caller allowed" half lives there and the two
 * edges cannot drift; what remains here is the part specific to this API, which is resolving a viewer's
 * relationship to a SUBJECT into an {@link AccessLevel} for redaction.
 */
public class ApiPrivilegesTest {

    private static final Person.Id VIEWER = Person.Id.from("viewer-1");

    @Test
    public void aSiteAdminIsRecognisedFromTheSessionAlone() {
        // No FacesContext exists anywhere in this test. If the check reached for one, this would fail.
        Assert.assertTrue(privilegesFor(adminCaller(refusingPrivileges())).isSiteAdmin());
    }

    @Test
    public void aSiteAdminHoldsEveryNamedPrivilegeWithoutAnyBeingGranted() {
        // Short-circuit, so an admin is never refused for want of a row nobody remembered to create.
        final ApiPrivileges privileges = privilegesFor(adminCaller(refusingPrivileges()));

        Assert.assertTrue(privileges.has(ApiPrivileges.CONFIG_ADMIN));
        Assert.assertTrue(privileges.has(ApiPrivileges.TRIP_MGR, "trip-1"));
    }

    @Test
    public void noSessionIsRefusedRatherThanTreatedAsAnAdmin() {
        // The important direction. An absent session must fail CLOSED; failing open here would make every
        // unauthenticated request an administrator.
        final ApiPrivileges privileges = privilegesFor(Caller.of(null));

        Assert.assertFalse(privileges.isSiteAdmin());
        Assert.assertFalse(privileges.has(ApiPrivileges.PRIVILEGE_ADMIN));
    }

    @Test
    public void aCallerBuiltFromARealSessionReadsBothIdentityAndRole() {
        // Pins the session keys themselves: Caller.of is what the REST edge actually calls.
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ID)).thenReturn(VIEWER);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ROLE)).thenReturn("admin");

        final Caller caller = Caller.of(session);

        Assert.assertEquals(caller.personId(), VIEWER);
        Assert.assertTrue(caller.isSiteAdmin());
        Assert.assertTrue(caller.isAuthenticated());
    }

    @Test
    public void anOrdinaryUserIsNotAnAdminAndFallsBackToTheGrantedPrivileges() {
        final PrivilegeCommands granting = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(granting.check(ApiPrivileges.TRIP_VIEW, "trip-1", VIEWER)).thenReturn(true);

        final ApiPrivileges privileges = privilegesFor(userCaller(granting));

        Assert.assertFalse(privileges.isSiteAdmin());
        Assert.assertTrue(privileges.has(ApiPrivileges.TRIP_VIEW, "trip-1"));
        Assert.assertFalse(privileges.has(ApiPrivileges.TRIP_MGR, "trip-1"),
                "A privilege on one trip must not leak into another name or scope.");
    }

    @Test
    public void viewingYourOwnRecordOutranksWhateverTripRoleYouHappenToHold() {
        // A trip manager reading their OWN record must not be demoted to what a manager sees of a stranger --
        // that would hide a person's own notes from them on their own profile screen.
        final PrivilegeCommands managing = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(managing.check(Mockito.eq(ApiPrivileges.TRIP_MGR), Mockito.any(), Mockito.any()))
                .thenReturn(true);

        Assert.assertEquals(privilegesFor(adminCaller(managing)).levelFor(null, VIEWER, "trip-1"),
                AccessLevel.SELF);
    }

    @Test
    public void aStrangerOnTheSameTripIsAPeer() {
        final ApiPrivileges privileges = privilegesFor(userCaller(refusingPrivileges()));

        Assert.assertEquals(privileges.levelFor(null, Person.Id.from("someone-else"), "trip-1"),
                AccessLevel.PEER);
    }

    private static ApiPrivileges privilegesFor(final Caller caller) {
        return new ApiPrivileges(caller, new PersonCommands());
    }

    private static Caller adminCaller(final PrivilegeCommands privileges) {
        return new Caller(VIEWER, true, AuditActor.from(null), privileges);
    }

    private static Caller userCaller(final PrivilegeCommands privileges) {
        return new Caller(VIEWER, false, AuditActor.from(null), privileges);
    }

    private static PrivilegeCommands refusingPrivileges() {
        final PrivilegeCommands privileges = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(privileges.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        return privileges;
    }
}
