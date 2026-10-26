package org.paulsens.trip.action;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Room;
import org.paulsens.trip.model.RoomBlock;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** The shared night arithmetic, away from any DAO: who is in a room and what the hotel has taken out of it. */
public class RoomAvailabilityTest {
    private static final LocalDateTime SEP21 = LocalDateTime.of(2026, 9, 21, 15, 0);
    private static final LocalDateTime SEP26 = LocalDateTime.of(2026, 9, 26, 10, 0);
    private static final Room ROOM = Room.builder().id("r-101").roomNumber("101").build();

    @Test
    public void blocksAreFoundOnTheRoomAndTheNightsTheyCover() {
        final List<RoomBlock> blocks = List.of(block("r-101", 23, 25, "another group"),
                block("r-102", 21, 26, "maintenance"));
        assertEquals(RoomAvailability.blocksOn("r-101", blocks, SEP21, SEP26).size(), 1);
        assertEquals(RoomAvailability.blocksOn("r-102", blocks, SEP21, SEP26).size(), 1);
        assertTrue(RoomAvailability.blocksOn("r-103", blocks, SEP21, SEP26).isEmpty());
        assertTrue(RoomAvailability.blocksOn(null, blocks, SEP21, SEP26).isEmpty());
        assertTrue(RoomAvailability.blocksOn("r-101", null, SEP21, SEP26).isEmpty());
        assertEquals(RoomAvailability.blockedNights("r-101", blocks, SEP21, SEP26),
                java.util.Set.of(LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 24)),
                "Sep 23 to Sep 25 is two nights inside the window");
        assertTrue(RoomAvailability.blockedNights("r-101", blocks, SEP21, SEP21).isEmpty(), "an empty window");
    }

    @Test
    public void aBlockReadsTheWayEveryOtherDateRangeDoes() {
        final RoomBlock block = block("r-101", 23, 25, "another group");
        assertEquals(RoomAvailability.nights(block), "Sep 23 – Sep 25");
        assertEquals(RoomAvailability.describe(block), "Sep 23 – Sep 25: another group");
        assertEquals(RoomAvailability.describe(block("r-101", 23, 25, "  ")), "Sep 23 – Sep 25",
                "no reason, no colon");
        assertEquals(RoomAvailability.nights(RoomBlock.builder().build()), "");
        assertEquals(RoomAvailability.label("r-101", List.of(block, block("r-101", 28, 29, "boiler")),
                SEP21, SEP26.plusDays(10)), "Sep 23 – Sep 25: another group; Sep 28 – Sep 29: boiler");
    }

    @Test
    public void aBlockedRoomRefusesAStayAndSaysWhere() {
        final List<RoomBlock> blocks = List.of(block("r-101", 23, 25, "another group"));
        final String problem = RoomAvailability.blockProblem(ROOM, blocks, SEP21, SEP26);
        assertTrue(problem.contains("Room 101"), problem);
        assertTrue(problem.contains("Sep 23 – Sep 25: another group"), problem);
        assertTrue(problem.contains("Availability tab"), "the refusal says how to get past it: " + problem);
        assertNull(RoomAvailability.blockProblem(ROOM, blocks, SEP26, SEP26.plusDays(2)), "after the block");
        assertNull(RoomAvailability.blockProblem(ROOM, List.of(), SEP21, SEP26));
        assertNull(RoomAvailability.blockProblem(null, blocks, SEP21, SEP26));
        assertNull(RoomAvailability.blockProblem(ROOM, blocks, null, SEP26));
    }

    @Test
    public void aRoomBothBlockedAndSleptInIsAClash() {
        final List<RoomBlock> blocks = List.of(block("r-101", 23, 25, "another group"));
        final Reservation over = stay("t-1", "r-101", 22, 24);
        final Reservation before = stay("t-1", "r-101", 20, 23);
        assertTrue(RoomAvailability.conflicts("r-101", blocks, List.of(over), SEP21, SEP26));
        assertFalse(RoomAvailability.conflicts("r-101", blocks, List.of(before), SEP21, SEP26),
                "checks out the morning the block starts");
        assertFalse(RoomAvailability.conflicts("r-101", List.of(), List.of(over), SEP21, SEP26));
    }

    @Test
    public void otherTripsAreTheStaysInTheRoomThatAreNotOurs() {
        final Reservation mine = stay("t-mine", "r-101", 21, 26);
        final Reservation theirs = stay("t-theirs", "r-101", 22, 24);
        final Reservation elsewhere = stay("t-theirs", "r-102", 22, 24);
        final Reservation later = stay("t-theirs", "r-101", 40, 42);
        final List<Reservation> all = List.of(mine, theirs, elsewhere, later);
        assertEquals(RoomAvailability.otherTrips("r-101", all, "t-mine", SEP21, SEP26), List.of(theirs));
        assertTrue(RoomAvailability.otherTrips("r-101", all, "t-theirs", SEP21, SEP26).contains(mine));
        assertTrue(RoomAvailability.otherTrips(null, all, "t-mine", SEP21, SEP26).isEmpty());
        final Reservation cancelled = stay("t-theirs", "r-101", 22, 24);
        cancelled.setStatus(Reservation.Status.CANCELLED);
        assertTrue(RoomAvailability.otherTrips("r-101", List.of(cancelled), "t-mine", SEP21, SEP26).isEmpty());
    }

    @Test
    public void anUnpinnedWindowOverlapsEverything() {
        final Reservation stay = stay("t-1", "r-101", 21, 26);
        assertTrue(RoomAvailability.overlaps(stay, null, SEP26));
        assertTrue(RoomAvailability.overlaps(stay, SEP21, null));
        assertFalse(RoomAvailability.overlaps(stay, SEP26, SEP26.plusDays(3)));
    }

    /** Owner decision 2026-10-26: a room's nights are priced under ONE lodging option, refused outright. */
    @Test
    public void aRoomPricedUnderAnotherOptionRefusesAStayAndNamesWhoIsInIt() {
        final Room room = Room.builder().id("r-003").roomNumber("003").build();
        final ReservationOffer shared = ReservationOffer.builder().name("Double room, shared").build();
        final ReservationOffer single = ReservationOffer.builder().name("Single").build();
        final Reservation trinity = stay("t-1", "r-003", 21, 31);
        trinity.setOfferId(shared.getId());
        trinity.setOccupants(List.of(Person.Id.from("p-trinity")));
        final java.util.function.Function<Person.Id, String> names = id -> "Trinity Paulsen";
        final java.util.function.Function<String, ReservationOffer> offers =
                id -> shared.getId().getValue().equals(id) ? shared : null;
        final String problem = RoomAvailability.optionProblem(room, single, List.of(trinity), SEP21, SEP26, null,
                names, offers);
        assertEquals(problem, "Room 003 already holds Trinity Paulsen under 'Double room, shared' for Sep 21 - "
                + "Oct 1, 2026; a room's nights are priced under one lodging option. Pick another room, or move "
                + "them first.");
        assertNull(RoomAvailability.optionProblem(room, shared, List.of(trinity), SEP21, SEP26, null, names, offers),
                "the same option sharing a room is the shared-room case");
        assertNull(RoomAvailability.optionProblem(room, single, List.of(trinity), SEP21, SEP26, trinity.getId(),
                names, offers), "the stay being edited never conflicts with itself");
        assertNull(RoomAvailability.optionProblem(room, single, List.of(trinity), SEP26.plusDays(5),
                SEP26.plusDays(7), null, names, offers), "no shared night: checking in the morning they leave");
        final Reservation cancelled = stay("t-1", "r-003", 21, 31);
        cancelled.setOfferId(shared.getId());
        cancelled.setStatus(Reservation.Status.CANCELLED);
        assertNull(RoomAvailability.optionProblem(room, single, List.of(cancelled), SEP21, SEP26, null, names,
                offers), "a cancelled stay holds nothing");
        final Reservation elsewhere = stay("t-1", "r-004", 21, 31);
        elsewhere.setOfferId(shared.getId());
        assertNull(RoomAvailability.optionProblem(room, single, List.of(elsewhere), SEP21, SEP26, null, names,
                offers), "another room's stay is not in the way");
        assertTrue(RoomAvailability.optionProblem(room, single, List.of(trinity), SEP21, SEP26, null, names,
                id -> null).contains("under another lodging option for"), "a deleted option is still a refusal");
        assertNull(RoomAvailability.optionProblem(null, single, List.of(trinity), SEP21, SEP26, null, names, offers));
        assertNull(RoomAvailability.optionProblem(room, null, List.of(trinity), SEP21, SEP26, null, names, offers));
        assertNull(RoomAvailability.optionProblem(room, single, null, SEP21, SEP26, null, names, offers));
        assertNull(RoomAvailability.optionProblem(room, single, List.of(trinity), null, SEP26, null, names, offers));
    }

    @Test
    public void anEditedRoomNotAtTheNewOptionsHotelIsNamedAfterItsOwn() {
        final Accommodation pansion = Accommodation.builder().name("Pansion")
                .rooms(List.of(Room.builder().id("r-114").roomNumber("114").build())).build();
        assertEquals(RoomAvailability.hotelProblem(pansion, "r-114"),
                "Room 114 is at Pansion; pick an option at that hotel, or take them out of the room first.");
        assertEquals(RoomAvailability.hotelProblem(pansion, "r-999"), "That room is not at the accommodation.");
        assertEquals(RoomAvailability.hotelProblem(null, "r-114"), "That room is not at the accommodation.");
    }

    @Test
    public void aSharedNightIsADateNotAnInstant() {
        final Reservation stay = stay("t-1", "r-101", 21, 26);
        assertTrue(RoomAvailability.sharesANight(stay, SEP26.minusDays(1), SEP26.plusDays(2)));
        assertFalse(RoomAvailability.sharesANight(stay, SEP26.withHour(1), SEP26.plusDays(2)),
                "arriving the morning they leave shares the date and no night");
        assertFalse(RoomAvailability.sharesANight(stay, null, SEP26));
        assertFalse(RoomAvailability.sharesANight(Reservation.builder().build(), SEP21, SEP26));
    }

    private static RoomBlock block(final String roomId, final int from, final int to, final String reason) {
        return RoomBlock.builder().accommodationId(Accommodation.Id.from("acc-1")).roomIds(List.of(roomId))
                .start(LocalDate.of(2026, 9, from)).end(LocalDate.of(2026, 9, to)).reason(reason).build();
    }

    private static Reservation stay(final String tripId, final String roomId, final int from, final int to) {
        return Reservation.builder().tripId(tripId).roomId(roomId).occupants(List.of(Person.Id.from("p-1")))
                .start(LocalDate.of(2026, 9, 1).plusDays(from - 1).atTime(15, 0))
                .end(LocalDate.of(2026, 9, 1).plusDays(to - 1).atTime(10, 0)).build();
    }
}
