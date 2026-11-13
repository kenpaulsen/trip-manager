package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Leaving an organization ends what membership was the bound for: a trip role on the org's trips is granted to
 * members only, so it must not outlive the membership any more than an org-scoped privilege does.
 */
public class OrgRemoveMemberRolesTest {

    private static final List<String> ROLES = List.of(PrivilegeCommands.TRIP_MGR, PrivilegeCommands.TRIP_FIN_ADMIN,
            PrivilegeCommands.REGISTRATION_ADMIN);

    @Test
    public void removingAMemberRevokesTheirRolesOnTheOrgsTrips() {
        final Person staff = TenancyFixtures.savedPerson("Staff");
        final Organization leaving = TenancyFixtures.org("Leave", staff);
        final Organization staying = TenancyFixtures.org("Stay", staff);
        // Staff, not a traveler: on no roster, so the "on a trip" refusal does not stand in for the revoke.
        final Trip theirs = TenancyFixtures.tripOwnedBy(leaving);
        final Trip other = TenancyFixtures.tripOwnedBy(staying);
        final OrgCommands admin = TenancyFixtures.asSiteAdmin();
        for (final String role : ROLES) {
            assertTrue(admin.setTripRole(theirs.getId(), staff.getId(), role, true));
        }
        assertTrue(admin.setTripRole(other.getId(), staff.getId(), PrivilegeCommands.TRIP_MGR, true));

        assertTrue(admin.removeMember(leaving.getId().getValue(), staff.getId()));

        final PrivilegeCommands privileges = new PrivilegeCommands();
        for (final String role : ROLES) {
            assertFalse(privileges.check(role, theirs.getId(), staff.getId()),
                    role + " on the org's trip must not outlive the membership");
        }
        assertTrue(privileges.check(PrivilegeCommands.TRIP_MGR, other.getId(), staff.getId()),
                "a role on another organization's trip is not this removal's to take");
    }
}
