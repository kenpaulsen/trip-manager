package org.paulsens.trip.action;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

public class TripCommandsTest {
    private final TripCommands tripCommands = new TripCommands();
    private static List<Person> people;
    private static List<Trip> trips;

    @BeforeClass
    void beforeClass() {
        FakeData.initFakeData();
        FakeData.addFakeData();
        people = FakeData.getFakePeople();
        trips = FakeData.getFakeTrips();
    }

    @Test
    public void testLateArriver() {
        final Trip trip = trips.get(0);
        final TripEvent lodging = trip.getTripEvents().stream()
                .filter(te -> te.getType() == TripEvent.Type.LODGING)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No Lodging Event in this trip!"));

        final LocalDateTime p2Arrival = tripCommands.getLodgingArrivalDate(
                trip.getTripEventsForUser(people.get(2).getId()), lodging);
        final LocalDateTime p3Arrival = tripCommands.getLodgingArrivalDate(
                trip.getTripEventsForUser(people.get(3).getId()), lodging);

        assertTrue(p2Arrival.isAfter(lodging.getStart()));
        assertTrue(p3Arrival.isBefore(p2Arrival));
        assertTrue(p3Arrival.isAfter(lodging.getStart()),
                String.format("\np3Arrival = %s\n  lodging = %s", p3Arrival, lodging.getStart()));
        assertNotEquals(p2Arrival.getDayOfMonth(), p3Arrival.getDayOfMonth());
        assertTrue(Math.abs(Duration.between(p3Arrival, lodging.getStart()).toHours()) < 4L);
    }

    @Test
    public void testEarlyLeaver() {
        final Trip trip = trips.get(0);
        final TripEvent lodging = trip.getTripEvents().stream()
                .filter(te -> te.getType() == TripEvent.Type.LODGING)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No Lodging Event in this trip!"));
        final LocalDateTime p2Depart = tripCommands.getLodgingDepartureDate(
                trip.getTripEventsForUser(people.get(2).getId()), lodging);
        assertTrue(p2Depart.isBefore(lodging.getEnd()));
        final LocalDateTime p3Depart = tripCommands.getLodgingDepartureDate(
                trip.getTripEventsForUser(people.get(3).getId()), lodging);
        assertTrue(p3Depart.isAfter(p2Depart));
        assertEquals(p3Depart, lodging.getEnd());
        assertNotEquals(p2Depart.getDayOfMonth(), p3Depart.getDayOfMonth());
        assertEquals(p3Depart.getDayOfMonth(), lodging.getEnd().getDayOfMonth());
    }

    @Test
    public void testLodgingDays() {
        assertEquals(tripCommands.getLodgingDays(
                LocalDateTime.of(2025, 5, 23, 20, 0, 0),
                LocalDateTime.of(2025, 5, 24, 12, 0, 0)), 1);
        assertEquals(tripCommands.getLodgingDays(
                LocalDateTime.of(2025, 5, 24,  3, 0, 0),
                LocalDateTime.of(2025, 5, 24, 12, 0, 0)), 1);
        assertEquals(tripCommands.getLodgingDays(
                LocalDateTime.of(2025, 5, 23,  8, 0, 0),
                LocalDateTime.of(2025, 5, 23, 23, 50, 0)), 0);
        assertEquals(tripCommands.getLodgingDays(
                LocalDateTime.of(2025, 5, 23,  5, 0, 0),
                LocalDateTime.of(2025, 5, 24, 12, 0, 0)), 1);
        assertEquals(tripCommands.getLodgingDays(
                LocalDateTime.of(2025, 5, 23,  5, 0, 0),
                LocalDateTime.of(2025, 5, 26, 12, 0, 0)), 3);
        assertEquals(tripCommands.getLodgingDays(
                LocalDateTime.of(2025, 5, 23,  5, 0, 0),
                LocalDateTime.of(2025, 5, 27,  2, 0, 0)), 4);
    }
}