package org.paulsens.trip.action;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.paulsens.trip.action.ItineraryAccess.Level;
import org.paulsens.trip.audit.AuditActor;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The whole shared-itinerary rule (docs/shared-itineraries.md): who gets the FULL page, who gets the SHARED
 * event data, and every way a SHARED answer turns into NONE -- the trip switch, the person's own choice, and
 * either side being off the roster.
 */
public class ItineraryAccessTest {

    private Person subject;
    private Person traveler;
    private Person outsider;

    /** Fresh people per test: TestNG shares one instance, and several tests turn the subject's sharing off. */
    @BeforeMethod
    public void freshPeople() {
        subject = new Person();
        traveler = new Person();
        outsider = new Person();
    }

    @Test
    public void thePersonAndTheirFamilyManagerAlwaysGetTheFullItinerary() {
        final Trip trip = trip(false, subject);
        subject.getPrivacy().setItineraryShared(false);
        Assert.assertEquals(of(caller(subject), subject, trip), Level.FULL);
        final Person manager = new Person();
        manager.getManagedUsers().add(subject.getId());
        Assert.assertEquals(of(caller(manager), manager, trip), Level.FULL,
                "A manager sees a member's full itinerary whatever the switches say");
    }

    @Test
    public void aTripManagerAndASiteAdminGetTheFullItineraryEvenWhenNothingIsShared() {
        final Trip trip = trip(false, subject);
        subject.getPrivacy().setItineraryShared(false);
        Assert.assertEquals(of(caller(outsider, PrivilegeCommands.TRIP_MGR, trip), outsider, trip), Level.FULL);
        Assert.assertEquals(of(siteAdmin(outsider), outsider, trip), Level.FULL);
    }

    @Test
    public void aFellowTravelerGetsTheSharedItineraryWhenBothSwitchesAreOn() {
        final Trip trip = trip(true, subject, traveler);
        Assert.assertEquals(of(caller(traveler), traveler, trip), Level.SHARED);
    }

    @Test
    public void theTripSwitchOffMeansNoneForAFellowTraveler() {
        final Trip trip = trip(false, subject, traveler);
        Assert.assertEquals(of(caller(traveler), traveler, trip), Level.NONE);
    }

    @Test
    public void thePersonsOwnChoiceOffMeansNoneForAFellowTraveler() {
        final Trip trip = trip(true, subject, traveler);
        subject.getPrivacy().setItineraryShared(false);
        Assert.assertEquals(of(caller(traveler), traveler, trip), Level.NONE);
    }

    @Test
    public void aSubjectOffTheRosterIsNotSharedWithAnyone() {
        final Trip trip = trip(true, traveler);
        Assert.assertEquals(of(caller(traveler), traveler, trip), Level.NONE,
                "A pending registrant (not on Trip.people) is not seen");
    }

    @Test
    public void aViewerOffTheRosterSeesNothingUnlessTheyHoldTripViewOrAdministerTheOrg() {
        final Trip trip = trip(true, subject);
        Assert.assertEquals(of(caller(outsider), outsider, trip), Level.NONE);
        Assert.assertEquals(of(caller(outsider, PrivilegeCommands.TRIP_VIEW, trip), outsider, trip), Level.SHARED,
                "A Trip Viewer is a fellow traveler, never the full page");
        Assert.assertEquals(ItineraryAccess.of(caller(outsider), outsider, trip, subject, () -> true), Level.SHARED,
                "An org admin is a fellow traveler, never the full page");
    }

    @Test
    public void aTripViewerIsRefusedWhenThePersonOptedOut() {
        final Trip trip = trip(true, subject);
        subject.getPrivacy().setItineraryShared(false);
        Assert.assertEquals(of(caller(outsider, PrivilegeCommands.TRIP_VIEW, trip), outsider, trip), Level.NONE);
    }

    @Test
    public void nobodyAndNothingGetNone() {
        final Trip trip = trip(true, subject, traveler);
        final Caller anonymous = new Caller(null, false, AuditActor.system(), new PrivilegeCommands());
        Assert.assertEquals(ItineraryAccess.of(anonymous, null, trip, subject, () -> true), Level.NONE);
        Assert.assertEquals(ItineraryAccess.of(null, traveler, trip, subject, () -> true), Level.NONE);
        Assert.assertEquals(ItineraryAccess.of(caller(traveler), traveler, null, subject, () -> true), Level.NONE);
        Assert.assertEquals(ItineraryAccess.of(caller(traveler), traveler, trip, null, () -> true), Level.NONE);
    }

    private Level of(final Caller viewer, final Person viewerPerson, final Trip trip) {
        return ItineraryAccess.of(viewer, viewerPerson, trip, subject, () -> false);
    }

    private static Trip trip(final boolean sharing, final Person... roster) {
        final List<Person.Id> people = new ArrayList<>();
        for (final Person person : roster) {
            people.add(person.getId());
        }
        final Trip trip = Trip.builder().people(people).build();
        trip.setItinerarySharing(sharing);
        return trip;
    }

    private static Caller caller(final Person person) {
        return new Caller(person.getId(), false, AuditActor.system(), new Granting(Set.of()));
    }

    private static Caller caller(final Person person, final String privilege, final Trip trip) {
        return new Caller(person.getId(), false, AuditActor.system(),
                new Granting(Set.of(privilege + '@' + trip.getId())));
    }

    private static Caller siteAdmin(final Person person) {
        return new Caller(person.getId(), true, AuditActor.system(), new Granting(Set.of()));
    }

    /** Grants exactly the named {@code privilege@scope} pairs, so no test depends on seeded privilege rows. */
    private static final class Granting extends PrivilegeCommands {
        private final Set<String> held;

        Granting(final Set<String> held) {
            this.held = held;
        }

        @Override
        public boolean check(final String name, final String scopeId, final Person.Id personId) {
            return held.contains(name + '@' + scopeId);
        }
    }
}
