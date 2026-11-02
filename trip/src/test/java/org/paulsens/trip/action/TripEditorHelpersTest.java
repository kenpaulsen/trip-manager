package org.paulsens.trip.action;

import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** The trip editor's "Registrations managed" radio and its shared staff dialog, as the bean sees them. */
public class TripEditorHelpersTest {
    private final TripCommands trips = new TripCommands();

    @Test
    public void theRadioIsDerivedFromTheUrl() {
        final Trip trip = Trip.builder().title("Hosting").build();
        assertEquals(trips.regHostingOf(trip), TripCommands.REG_HERE);
        trip.setNonHostedTripUrl("   ");
        assertEquals(trips.regHostingOf(trip), TripCommands.REG_HERE, "A blank URL is not external");
        trip.setNonHostedTripUrl("https://elsewhere.example/register");
        assertEquals(trips.regHostingOf(trip), TripCommands.REG_EXTERNAL);
        assertEquals(trips.regHostingOf(null), TripCommands.REG_HERE);
    }

    @Test
    public void onThisSiteClearsBothExternalFieldsAndExternallyKeepsThem() {
        final Trip trip = externalTrip();
        trips.applyRegHosting(trip, TripCommands.REG_EXTERNAL);
        assertEquals(trip.getNonHostedTripUrl(), "https://elsewhere.example/register");
        assertEquals(trip.getNonHostedRegNumber(), Integer.valueOf(42));
        trips.applyRegHosting(trip, null);
        assertTrue(trip.isExternal(), "No choice changes nothing");
        trips.applyRegHosting(trip, TripCommands.REG_HERE);
        assertNull(trip.getNonHostedTripUrl());
        assertNull(trip.getNonHostedRegNumber());
        trips.applyRegHosting(null, TripCommands.REG_HERE);
    }

    @Test
    public void externallyWithoutAUrlIsNotReadyToSave() {
        final Trip trip = Trip.builder().title("Not ready").build();
        assertFalse(trips.regHostingReady(trip, TripCommands.REG_EXTERNAL), "Externally needs the URL");
        assertTrue(trips.regHostingReady(trip, TripCommands.REG_HERE));
        assertTrue(trips.regHostingReady(trip, null), "A view that never seeded the radio still saves");
        assertTrue(trips.regHostingReady(externalTrip(), TripCommands.REG_EXTERNAL));
        assertTrue(trips.regHostingReady(null, TripCommands.REG_EXTERNAL));
    }

    @Test
    public void staffDispatchesByRole() {
        final Trip trip = Trip.builder().title("Staff").build();
        final Person.Id priest = Person.Id.from("staff-priest");
        final Person.Id coordinator = Person.Id.from("staff-coordinator");
        trips.addStaff(trip, TripCommands.STAFF_DIRECTOR, priest);
        trips.addStaff(trip, TripCommands.STAFF_COORDINATOR, coordinator);
        trips.addStaff(trip, "guide", coordinator);
        trips.addStaff(trip, TripCommands.STAFF_DIRECTOR, null);
        trips.addStaff(null, TripCommands.STAFF_DIRECTOR, priest);
        assertEquals(trip.getDirectorIds(), java.util.List.of(priest));
        assertEquals(trip.getFacilitatorIds(), java.util.List.of(coordinator));

        trips.removeStaff(trip, "guide", priest);
        trips.removeStaff(trip, TripCommands.STAFF_DIRECTOR, null);
        trips.removeStaff(null, TripCommands.STAFF_DIRECTOR, priest);
        assertEquals(trip.getDirectorIds(), java.util.List.of(priest), "An unknown role removes nothing");
        trips.removeStaff(trip, TripCommands.STAFF_DIRECTOR, priest);
        trips.removeStaff(trip, TripCommands.STAFF_COORDINATOR, coordinator);
        assertTrue(trip.getDirectorIds().isEmpty());
        assertTrue(trip.getFacilitatorIds().isEmpty());
    }

    private static Trip externalTrip() {
        final Trip trip = Trip.builder().title("External").build();
        trip.setNonHostedTripUrl("https://elsewhere.example/register");
        trip.setNonHostedRegNumber(42);
        return trip;
    }
}
