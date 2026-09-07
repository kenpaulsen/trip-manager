package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class ReservationTest {
    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 21, 23, 30);
    private static final Person.Id ADA = Person.Id.from("person-ada");
    private static final Person.Id BOB = Person.Id.from("person-bob");

    @Test
    public void builderWithNoValuesEqualsNewInstance() {
        final Reservation built = Reservation.builder().build();
        final Reservation fresh = new Reservation();
        built.setId(fresh.getId());
        assertEquals(built, fresh);
        assertEquals(fresh.getStatus(), Reservation.Status.ACTIVE);
        assertTrue(fresh.isActive());
        assertFalse(fresh.isAssigned());
        assertFalse(fresh.isSupplementWaived());
        assertFalse(fresh.occupies(ADA));
        assertFalse(fresh.occupies(null));
        assertEquals(fresh.nights(), 0);
        assertTrue(fresh.getOccupants().isEmpty());
        assertNotNull(fresh.getId());
    }

    @Test
    public void nightsAreCalendarDatesTimesAreInformational() {
        assertEquals(Reservation.nightsBetween(AT, AT.plusHours(9)), 1, "23:30 to 08:30 next day is one night");
        assertEquals(Reservation.nightsBetween(AT.withHour(14), AT.withHour(16)), 0, "Same date is zero nights");
        assertEquals(Reservation.nightsBetween(AT, AT.plusDays(4).withHour(2)), 4,
                "A 02:00 check-out does not count the check-out date");
        assertEquals(Reservation.nightsBetween(AT.plusDays(2), AT), 0, "End before start is zero, never negative");
        assertEquals(Reservation.nightsBetween(null, AT), 0);
        assertEquals(Reservation.nightsBetween(AT, null), 0);
        assertEquals(Reservation.builder().start(AT).end(AT.plusDays(10)).build().nights(), 10);
    }

    @Test
    public void blankRoomIdReadsAsUnassignedAndOccupantsAreSortedById() {
        final Reservation res = Reservation.builder().roomId("  ").occupants(List.of(BOB, ADA)).build();
        assertNull(res.getRoomId());
        assertFalse(res.isAssigned());
        assertEquals(res.getOccupants(), List.of(BOB, ADA), "Stored order is preserved");
        assertEquals(res.sortedOccupants(), List.of(ADA, BOB), "Split order is by id value");
        assertTrue(res.occupies(BOB));
        assertTrue(Reservation.builder().roomId("r-114").build().isAssigned());
        assertTrue(Reservation.builder().waiveSingleSupplement(true).build().isSupplementWaived());
        assertFalse(Reservation.builder().status(Reservation.Status.CANCELLED).build().isActive());
    }

    @Test
    public void jacksonRoundTripPreservesEverything() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final Reservation res = Reservation.builder().id(Reservation.Id.from("res-1")).tripId("trip-1").orgId("org-1")
                .offerId(ReservationOffer.Id.from("offer-1")).accommodationId(Accommodation.Id.from("acc-1"))
                .occupants(List.of(ADA, BOB)).start(AT).end(AT.plusDays(3)).roomId("r-114")
                .status(Reservation.Status.CANCELLED).notes("Late flight").waiveSingleSupplement(true)
                .createdBy(ADA).created(AT).cancelledAt(AT.plusDays(1)).cancelledBy(BOB).cancelFeeCents(1500L)
                .credited(true).cancelReason("Changed plans").version(4L).build();
        final String json = mapper.writeValueAsString(res);
        assertFalse(json.contains("\"active\""), "Derived properties are not persisted");
        assertEquals(mapper.readValue(json, Reservation.class), res);
        assertTrue(Reservation.Id.from("a").compareTo(Reservation.Id.from("b")) < 0);
        assertNotNull(Reservation.Id.newInstance().getValue());
    }
}
