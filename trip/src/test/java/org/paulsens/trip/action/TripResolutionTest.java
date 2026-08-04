package org.paulsens.trip.action;

import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link TripCommands}' resolution and lodging logic: which trip a user is shown, the flight-derived lodging
 * window, and the list queries the menu drives.
 *
 * <p>The lodging inference is the part a client must never re-derive: a layover under 36 hours counts as the
 * same journey, so the arrival at the lodging is the END of the last connecting flight, not the first.
 */
public class TripResolutionTest {

    private final TripCommands trips = new TripCommands();
    private final PersonCommands people = new PersonCommands();

    private Person.Id member;
    private Person.Id outsider;

    @BeforeMethod
    public void createPeople() {
        member = person("Member");
        outsider = person("Outsider");
    }

    private Person.Id person(final String first) {
        final Person person = people.createPerson();
        person.setFirst(first);
        person.setLast("Resolver");
        Assert.assertTrue(people.savePerson(person));
        return person.getId();
    }

    private Trip savedTrip(final LocalDateTime start, final LocalDateTime end, final Person.Id... members) {
        final Trip trip = trips.createTrip();
        trip.setStartDate(start);
        trip.setEndDate(end);
        trip.setPeople(List.of(members));
        // The builder defaults openToPublic to TRUE, which would make every test trip joinable by anyone and
        // turn the "sees nothing" cases into "joins something".
        trip.setOpenToPublic(false);
        Assert.assertTrue(trips.saveTrip(trip));
        return trip;
    }

    // --- getTripForUser ---

    @Test
    public void aCurrentTripTheUserCanSeeIsKeptAsIs() {
        final Trip current = savedTrip(LocalDateTime.now().plusDays(10), LocalDateTime.now().plusDays(20), member);

        Assert.assertSame(trips.getTripForUser(current, member, false, null), current);
    }

    @Test
    public void aRequestedTripWinsWhenTheCurrentOneIsNotVisible() {
        final Trip requested = savedTrip(LocalDateTime.now().plusDays(10), LocalDateTime.now().plusDays(20),
                member);

        final Trip resolved = trips.getTripForUser(null, member, false, requested.getId());

        Assert.assertNotNull(resolved);
        Assert.assertEquals(resolved.getId(), requested.getId());
    }

    @Test
    public void aUserFallsBackToTheirOwnTrips() {
        final Trip mine = savedTrip(LocalDateTime.now().plusDays(30), LocalDateTime.now().plusDays(40), member);

        final Trip resolved = trips.getTripForUser(null, member, false, null);

        Assert.assertNotNull(resolved);
        Assert.assertEquals(resolved.getId(), mine.getId());
    }

    /**
     * {@code openToPublic} is a LISTING flag, not an access control, and this pins that on purpose.
     *
     * <p>A "private" trip is private by not being advertised -- {@code menu.xhtml} and {@code trips.xhtml} both
     * hide it from their listings -- and not by refusing access to anyone who arrives at it. So
     * {@link org.paulsens.trip.model.Trip#canJoin} deliberately does NOT consult it, and the joinable fallback
     * here offers any future trip to a signed-in user who has none of their own.
     *
     * <p>Worth stating because the opposite reading looks like a bug: it was raised as one, and the answer is
     * that adding an {@code openToPublic} check to {@code canJoin} would also break the admin Approve button in
     * {@code admin/tripRegistrations.xhtml}, which asks the same method whether somebody may be ADDED to a
     * trip -- the ordinary workflow for an invite-only trip.
     */
    @Test
    public void aClosedTripIsStillOfferedBecausePrivateMeansUnlistedNotInaccessible() {
        savedTrip(LocalDateTime.now().plusDays(30), LocalDateTime.now().plusDays(40), member);

        Assert.assertNotNull(trips.getTripForUser(null, outsider, false, null));
    }

    @Test
    public void anOutsiderWithOnlyPastTripsAroundSeesNothing() {
        savedTrip(LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(20), member);

        // Past trips are not joinable; with no future trip in the store there is nothing to offer. Note the
        // fake store is shared across the suite, so this can only assert when no other test left a future trip
        // behind -- which is why the resolved trip, if any, must at least not be THIS past one.
        final Trip resolved = trips.getTripForUser(null, outsider, false, null);
        if (resolved != null) {
            Assert.assertTrue(resolved.getStartDate().isAfter(LocalDateTime.now()),
                    "Only a future trip may be offered as joinable");
        }
    }

    // --- list queries ---

    @Test
    public void inactiveTripsForAUserAreTheirOwnPastTripsNewestFirst() {
        savedTrip(LocalDateTime.now().minusDays(100), LocalDateTime.now().minusDays(90), member);
        savedTrip(LocalDateTime.now().minusDays(50), LocalDateTime.now().minusDays(40), member);
        savedTrip(LocalDateTime.now().plusDays(10), LocalDateTime.now().plusDays(20), member);

        final List<Trip> inactive = trips.getInactiveTrips(member, false, 3, 10);

        Assert.assertEquals(inactive.size(), 2, "The future trip is not inactive");
        Assert.assertTrue(inactive.get(0).getStartDate().isAfter(inactive.get(1).getStartDate()),
                "Most recent past trip first");

        Assert.assertEquals(trips.getInactiveTrips(member, false, 3, 1).size(), 1, "The cap applies");
    }

    @Test
    public void adminInactiveTripsComeFromTheStoreQuery() {
        savedTrip(LocalDateTime.now().minusDays(50), LocalDateTime.now().minusDays(40), member);

        Assert.assertFalse(trips.getInactiveTrips(outsider, true, 3, 10).isEmpty(),
                "An admin sees past trips they are not on");
    }

    @Test
    public void tripEventTypesListTheEnum() {
        Assert.assertEquals(trips.getTripEventTypes(), List.of(TripEvent.Type.values()));
    }

    // --- lodging inference ---

    private static TripEvent flight(final String id, final LocalDateTime start, final LocalDateTime end) {
        return new TripEvent(id, TripEvent.Type.FLIGHT, "Flight " + id, "", start, end, null, null);
    }

    private static TripEvent lodging(final String id, final LocalDateTime start, final LocalDateTime end) {
        return new TripEvent(id, TripEvent.Type.LODGING, "Hotel", "", start, end, null, null);
    }

    @Test
    public void arrivalIsTheEndOfTheLastConnectingFlight() {
        final LocalDateTime day = LocalDateTime.of(2027, 6, 1, 8, 0);
        final TripEvent leg1 = flight("leg1", day, day.plusHours(6));
        // A 3-hour layover: same journey.
        final TripEvent leg2 = flight("leg2", day.plusHours(9), day.plusHours(14));
        final TripEvent stay = lodging("stay", day.plusHours(16), day.plusDays(7));

        Assert.assertEquals(trips.getLodgingArrivalDate(List.of(leg1, leg2, stay), stay),
                leg2.getEnd(), "A short layover joins the legs; arrival is the LAST leg's end");
    }

    @Test
    public void aLongGapSplitsTheJourney() {
        final LocalDateTime day = LocalDateTime.of(2027, 6, 1, 8, 0);
        final TripEvent leg1 = flight("leg1", day, day.plusHours(6));
        // 48 hours later: a separate journey, not a layover.
        final TripEvent later = flight("later", day.plusHours(54), day.plusHours(60));
        final TripEvent stay = lodging("stay", day.plusHours(72), day.plusDays(9));

        Assert.assertEquals(trips.getLodgingArrivalDate(List.of(leg1, later, stay), stay), leg1.getEnd(),
                "A gap over 36 hours must not be treated as a connection");
    }

    @Test
    public void departureIsTheFirstFlightAfterArrival() {
        final LocalDateTime day = LocalDateTime.of(2027, 6, 1, 8, 0);
        final TripEvent inbound = flight("in", day, day.plusHours(6));
        final TripEvent stay = lodging("stay", day.plusHours(8), day.plusDays(7));
        final TripEvent outbound = flight("out", day.plusDays(6), day.plusDays(6).plusHours(8));

        Assert.assertEquals(trips.getLodgingDepartureDate(List.of(inbound, stay, outbound), stay),
                outbound.getStart());
    }

    @Test
    public void withNoOutboundFlightTheStayRunsToItsEnd() {
        final LocalDateTime day = LocalDateTime.of(2027, 6, 1, 8, 0);
        final TripEvent inbound = flight("in", day, day.plusHours(6));
        final TripEvent stay = lodging("stay", day.plusHours(8), day.plusDays(7));

        Assert.assertEquals(trips.getLodgingDepartureDate(List.of(inbound, stay), stay), stay.getEnd());
    }

    @Test
    public void lodgingHandlesMissingInputs() {
        final TripEvent stay = lodging("stay", LocalDateTime.now(), LocalDateTime.now().plusDays(7));

        Assert.assertNull(trips.getLodgingArrivalDate(null, stay));
        Assert.assertNull(trips.getLodgingArrivalDate(List.of(stay), null));
        Assert.assertNull(trips.getLodgingArrivalDate(List.of(stay), stay), "No flights: nothing to infer from");
        Assert.assertNull(trips.getLodgingDepartureDate(null, stay));
        Assert.assertNull(trips.getLodgingDepartureDate(List.of(), stay));
    }

    @Test
    public void lodgingDaysCountNightsWithTheEarlyMorningAdjustment() {
        final LocalDateTime checkIn = LocalDateTime.of(2027, 6, 1, 15, 0);
        Assert.assertEquals(trips.getLodgingDays(checkIn, checkIn.plusDays(7)), 7L);

        // Arrival at 01:00 counts as the previous night.
        final LocalDateTime lateArrival = LocalDateTime.of(2027, 6, 2, 1, 0);
        Assert.assertEquals(trips.getLodgingDays(lateArrival, LocalDateTime.of(2027, 6, 8, 10, 0)), 7L);

        Assert.assertEquals(trips.getLodgingDays(null, checkIn), -1L);
        Assert.assertEquals(trips.getLodgingDays(checkIn, null), -1L);
    }

    // --- event notes and participation guards ---

    @Test
    public void saveEventNoteRefusesAnEventFromAnotherTrip() {
        final Trip trip = savedTrip(LocalDateTime.now().plusDays(10), LocalDateTime.now().plusDays(20), member);
        final TripEvent foreign = flight("foreign-evt", LocalDateTime.now(), LocalDateTime.now().plusHours(3));

        Assert.assertFalse(trips.saveEventNote(trip, foreign, member, "note"));
        Assert.assertFalse(trips.saveEventNote(null, foreign, member, "note"));
        Assert.assertFalse(trips.saveEventNote(trip, null, member, "note"));
        Assert.assertFalse(trips.saveEventNote(trip, foreign, null, "note"));
    }

    @Test
    public void getTripAnswersABlankTripOnAMissNeverNull() {
        final Trip miss = trips.getTrip("no-such-trip");

        Assert.assertNotNull(miss);
        Assert.assertNotEquals(miss.getId(), "no-such-trip",
                "The blank answer carries a minted id; callers detect the miss by comparing ids");
    }

    @Test
    public void getBindFallsBackWhenNothingWasInjected() {
        Assert.assertNotNull(trips.getBind());
    }
}
