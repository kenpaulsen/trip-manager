package org.paulsens.trip.action;

import java.io.IOException;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Whom a trip's registration staff may register: the trip's own people. A registration is what makes someone "on
 * the trip", and "on the trip" is what opens a traveler's profile and ledger to the trip's staff, so without a
 * bound the staff could assert it for anybody (the 2026-11-12 review of the audit fixes).
 */
public class RegistrationStaffReachTest {

    private static RegistrationCommands registrationsAs(final Person person) {
        return new RegistrationCommands(() -> TenancyFixtures.callerFor(person));
    }

    private static void register(final Trip trip, final Person person, final Registration.Status status)
            throws IOException {
        assertTrue(DAO.getInstance().saveRegistration(
                new Registration(trip.getId(), person.getId()).withStatus(status)));
    }

    @Test
    public void staffReachIsTheTripsRosterItsRegistrationsAndItsOrganizationsMembers() throws IOException {
        final Person registrar = TenancyFixtures.savedPerson("Registrar");
        final Person traveler = TenancyFixtures.savedPerson("Traveler");
        final Person member = TenancyFixtures.savedPerson("Member");
        final Person applicant = TenancyFixtures.savedPerson("Applicant");
        final Person withdrawn = TenancyFixtures.savedPerson("Withdrawn");
        final Person stranger = TenancyFixtures.savedPerson("Stranger");
        final Organization owner = TenancyFixtures.org("ReachOwner", member);
        TenancyFixtures.org("ReachOther", stranger, applicant, withdrawn);
        final Trip trip = TenancyFixtures.tripOwnedBy(owner, traveler);
        register(trip, applicant, Registration.Status.PENDING);
        register(trip, withdrawn, Registration.Status.NOT_REGISTERED);
        final RegistrationCommands asRegistrar = registrationsAs(registrar);

        assertTrue(asRegistrar.withinStaffReach(trip, traveler.getId()), "on the roster");
        assertTrue(asRegistrar.withinStaffReach(trip, member.getId()), "one of the organization's people");
        assertTrue(asRegistrar.withinStaffReach(trip, applicant.getId()), "they registered themselves");
        assertFalse(asRegistrar.withinStaffReach(trip, withdrawn.getId()), "a removed registration is no seat");
        assertFalse(asRegistrar.withinStaffReach(trip, stranger.getId()), "another organization's person");
        assertFalse(asRegistrar.withinStaffReach(null, traveler.getId()));
        assertFalse(asRegistrar.withinStaffReach(trip, null));
        assertTrue(new RegistrationCommands(TenancyFixtures::siteAdminCaller)
                .withinStaffReach(trip, stranger.getId()), "a site admin is not bounded");
    }

    /** The Registrations page's status menu: a crafted row id must not revive a stranger into Pending. */
    @Test
    public void theStatusMenuCannotFileARegistrationForAStranger() {
        final Person registrar = TenancyFixtures.savedPerson("Registrar");
        final Person stranger = TenancyFixtures.savedPerson("Stranger");
        final Person member = TenancyFixtures.savedPerson("Member");
        final Trip trip = TenancyFixtures.tripOwnedBy(TenancyFixtures.org("ReachMenu", member));
        TenancyFixtures.org("ReachMenuOther", stranger);
        final RegistrationCommands asRegistrar = registrationsAs(registrar);
        final String pending = Registration.Status.PENDING.getDescription();

        assertFalse(asRegistrar.applyStatusChange(trip.getId(), stranger.getId(), pending, false));
        assertFalse(TripCommands.isOnTrip(trip, stranger.getId()), "nothing was stored for them");
        assertTrue(asRegistrar.applyStatusChange(trip.getId(), member.getId(), pending, false));
        assertEquals(DAO.getInstance().getRegistration(trip.getId(), member.getId(),
                org.paulsens.trip.cache.Cached.NO).orElseThrow().getStatus(), Registration.Status.PENDING);
    }

    /**
     * The Registrations page's Move files a Pending registration on the destination trip, so it asks for the
     * registration role on BOTH trips and the destination's staff reach: the destination came from a hidden field.
     */
    @Test
    public void aMoveNeedsBothTripsRolesAndTheDestinationsReach() {
        final Person registrar = TenancyFixtures.savedPerson("Registrar");
        final Person traveler = TenancyFixtures.savedPerson("Traveler");
        final Organization home = TenancyFixtures.org("MoveHome", traveler);
        final Trip from = TenancyFixtures.tripOwnedBy(home, traveler);
        final Trip sibling = TenancyFixtures.tripOwnedBy(home);
        final Trip foreign = TenancyFixtures.tripOwnedBy(TenancyFixtures.org("MoveForeign"));
        grantRegistrationAdmin(registrar, from, sibling, foreign);
        final RegistrationCommands asRegistrar = registrationsAs(registrar);

        assertTrue(asRegistrar.mayMoveRegistration(from.getId(), traveler.getId(), sibling.getId()),
                "both trips are theirs and the traveler is one of the destination organization's people");
        assertFalse(asRegistrar.mayMoveRegistration(from.getId(), traveler.getId(), foreign.getId()),
                "another organization's trip: the traveler is nobody there");
        assertFalse(asRegistrar.mayMoveRegistration(from.getId(), traveler.getId(), from.getId()), "the same trip");
        assertFalse(asRegistrar.mayMoveRegistration(from.getId(), traveler.getId(), "no-such-trip"));
        assertFalse(asRegistrar.mayMoveRegistration(from.getId(), null, sibling.getId()));

        final Person onlyHere = TenancyFixtures.savedPerson("OnlyHere");
        grantRegistrationAdmin(onlyHere, from);
        assertFalse(registrationsAs(onlyHere).mayMoveRegistration(from.getId(), traveler.getId(), sibling.getId()),
                "no registration role on the destination");
        assertTrue(new RegistrationCommands(TenancyFixtures::siteAdminCaller)
                .mayMoveRegistration(from.getId(), traveler.getId(), foreign.getId()), "a site admin is not bounded");
    }

    /** The Move menu offers the trips {@link RegistrationCommands#mayMoveRegistration} could allow, and no others. */
    @Test
    public void theMoveMenuOffersOnlyOtherTripsWhoseRegistrationsTheMoverAdministers() {
        final Person registrar = TenancyFixtures.savedPerson("Registrar");
        final Organization home = TenancyFixtures.org("MenuHome");
        final Trip from = TenancyFixtures.tripOwnedBy(home);
        final Trip sibling = TenancyFixtures.tripOwnedBy(home);
        final Trip unadministered = TenancyFixtures.tripOwnedBy(home);
        final Trip foreign = TenancyFixtures.tripOwnedBy(TenancyFixtures.org("MenuForeign"));
        grantRegistrationAdmin(registrar, from, sibling);
        final List<Trip> candidates = List.of(from, sibling, unadministered, foreign);

        assertEquals(RegistrationCommands.moveDestinationsOf(TenancyFixtures.callerFor(registrar), from.getId(),
                candidates), List.of(sibling), "never the trip it is on, never a trip they do not administer");
        assertEquals(RegistrationCommands.moveDestinationsOf(TenancyFixtures.siteAdminCaller(), from.getId(),
                candidates), List.of(sibling, unadministered, foreign), "a site admin may move anywhere else");
        // The live list reads the shared active-trips index, which other tests also fill: no content assert.
        assertNotNull(registrationsAs(registrar).moveDestinations(from.getId()));
        assertTrue(new RegistrationCommands(() -> Caller.forActor(null)).moveDestinations(from.getId()).isEmpty(),
                "nobody signed in");
    }

    private static void grantRegistrationAdmin(final Person person, final Trip... trips) {
        final PrivilegeCommands privileges = new PrivilegeCommands();
        for (final Trip trip : trips) {
            assertTrue(privileges.savePrivilege(privileges.getOrCreate(PrivilegeCommands.REGISTRATION_ADMIN,
                    trip.getId(), "Registration admin").withNewPerson(person.getId())));
        }
    }
}
