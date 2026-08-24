package org.paulsens.trip.action;

import java.util.List;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.RandomData;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Temporary probe: revoking ONE of two trip roles must keep the holder on the manager roster. */
public class RosterRevokeProbeTest {
    @Test
    public void revokeOneOfTwoKeepsThePersonListed() throws Exception {
        final Person who = Person.builder().first("P").last(RandomData.genAlpha(6))
                .email("probe." + RandomData.genAlpha(8) + "@example.com").build();
        Assert.assertTrue(DAO.getInstance().savePerson(who));
        final Trip trip = Trip.builder().title("Probe " + RandomData.genAlpha(6)).build();
        Assert.assertTrue(DAO.getInstance().saveTrip(trip));
        final OrgCommands org = new OrgCommands(() -> new Caller(Person.Id.from("admin-p"), true,
                new AuditActor("a@t", "admin"), new PrivilegeCommands()));

        Assert.assertTrue(org.setTripRole(trip.getId(), who.getId(), PrivilegeCommands.TRIP_MGR, true));
        Assert.assertTrue(org.setTripRole(trip.getId(), who.getId(),
                PrivilegeCommands.REGISTRATION_ADMIN, true));
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertEquals(priv.getPeopleWithPriv(org.allTripRoleBases(), trip.getId()),
                List.of(who.getId()), "both roles held");

        Assert.assertTrue(org.setTripRole(trip.getId(), who.getId(), PrivilegeCommands.TRIP_MGR, false));
        Assert.assertEquals(priv.getPeopleWithPriv(org.allTripRoleBases(), trip.getId()),
                List.of(who.getId()), "still holds registrationAdmin, must stay listed");
        Assert.assertTrue(priv.check(PrivilegeCommands.REGISTRATION_ADMIN, trip.getId(), who.getId()));
        Assert.assertFalse(priv.check(PrivilegeCommands.TRIP_MGR, trip.getId(), who.getId()));
    }
}
