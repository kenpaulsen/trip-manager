package org.paulsens.trip.api;

import jakarta.servlet.http.HttpSession;
import org.mockito.Mockito;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.model.Person;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Authorization at the REST edge, where there is no {@code FacesContext}.
 *
 * <p>The failure this guards is not "an admin gets an error". It is that {@code PersonCommands.hasRole(String)}
 * resolves its session through {@code FacesContext} — a ThreadLocal that simply does not exist on a JAX-RS
 * thread — and so returns {@code false} without complaint. A site administrator would be indistinguishable from
 * a stranger, on every endpoint, with nothing logged. {@code ApiPrivileges} may therefore only ever use the
 * session-taking overloads, and these tests pin that it does.
 */
public class ApiPrivilegesTest {

    private static final Person.Id VIEWER = Person.Id.from("viewer-1");

    @Test
    public void aSiteAdminIsRecognisedFromTheSessionAlone() {
        // No FacesContext exists anywhere in this test. If the check reached for one, this would fail.
        final ApiPrivileges privileges = privilegesFor(adminSession(), new PrivilegeCommands());

        Assert.assertTrue(privileges.isSiteAdmin(), "The role is on the session; nothing else is needed to read it.");
    }

    @Test
    public void aSiteAdminHoldsEveryNamedPrivilegeWithoutAnyBeingGranted() {
        // Short-circuit, so an admin is never refused for want of a row nobody remembered to create.
        final ApiPrivileges privileges = privilegesFor(adminSession(), refusingPrivileges());

        Assert.assertTrue(privileges.has(ApiPrivileges.CONFIG_ADMIN));
        Assert.assertTrue(privileges.has(ApiPrivileges.TRIP_MGR, "trip-1"));
    }

    @Test
    public void noSessionIsRefusedRatherThanTreatedAsAnAdmin() {
        // The important direction. A null session must fail CLOSED; failing open here would make every
        // unauthenticated request an administrator.
        final ApiPrivileges privileges = privilegesFor(null, refusingPrivileges());

        Assert.assertFalse(privileges.isSiteAdmin());
        Assert.assertFalse(privileges.has(ApiPrivileges.PRIVILEGE_ADMIN));
    }

    @Test
    public void anOrdinaryUserIsNotAnAdminAndFallsBackToTheGrantedPrivileges() {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ROLE)).thenReturn("user");
        final PrivilegeCommands granting = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(granting.check(ApiPrivileges.TRIP_VIEW, "trip-1", VIEWER)).thenReturn(true);

        final ApiPrivileges privileges = privilegesFor(session, granting);

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
        final ApiPrivileges privileges = privilegesFor(adminSession(), managing);

        Assert.assertEquals(privileges.levelFor(null, VIEWER, "trip-1"), AccessLevel.SELF);
    }

    @Test
    public void aStrangerOnTheSameTripIsAPeer() {
        final ApiPrivileges privileges = privilegesFor(userSession(), refusingPrivileges());

        Assert.assertEquals(privileges.levelFor(null, Person.Id.from("someone-else"), "trip-1"), AccessLevel.PEER);
    }

    private static ApiPrivileges privilegesFor(final HttpSession session, final PrivilegeCommands privileges) {
        return new ApiPrivileges(session, VIEWER, privileges, new PersonCommands());
    }

    private static PrivilegeCommands refusingPrivileges() {
        final PrivilegeCommands privileges = Mockito.mock(PrivilegeCommands.class);
        Mockito.when(privileges.check(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(false);
        return privileges;
    }

    private static HttpSession adminSession() {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ROLE)).thenReturn("admin");
        return session;
    }

    private static HttpSession userSession() {
        final HttpSession session = Mockito.mock(HttpSession.class);
        Mockito.when(session.getAttribute(PersonCommands.ACTIVE_USER_ROLE)).thenReturn("user");
        return session;
    }
}
