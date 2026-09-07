package org.paulsens.trip.dynamo;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.model.Accommodation;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Reservation;
import org.paulsens.trip.model.ReservationOffer;
import org.paulsens.trip.model.Room;
import org.paulsens.trip.model.RoomType;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** The three lodging tables through the DAO facade against the in-memory fake. */
public class LodgingDAOTest {
    private static final LocalDateTime AT = LocalDateTime.of(2026, 9, 20, 15, 0);

    @Test
    public void accommodationSaveGetListWithVersioning() throws IOException {
        final Accommodation acc = accommodation("Pansion " + RandomData.genAlpha(6));
        assertTrue(DAO.getInstance().saveAccommodation(acc));
        assertEquals(acc.getVersion(), 1L);
        final Accommodation read = DAO.getInstance().getAccommodation(acc.getId(), Cached.NO).orElseThrow();
        assertEquals(read, acc);
        assertTrue(DAO.getInstance().getAccommodations(Cached.NO).stream().anyMatch(a -> a.getId().equals(acc.getId())),
                "The whole-table list carries every saved accommodation");

        read.setDescription("Edited");
        assertTrue(DAO.getInstance().saveAccommodation(read));
        acc.setDescription("Stale write");
        assertThrows(ConditionalCheckFailedException.class, () -> DAO.getInstance().saveAccommodation(acc));
        assertEquals(acc.getVersion(), 1L, "A rejected save restores the caller's version");
        assertEquals(DAO.getInstance().getAccommodation(acc.getId(), Cached.NO).orElseThrow().getDescription(),
                "Edited");
        assertTrue(DAO.getInstance().getAccommodation(null, Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getAccommodation(Accommodation.Id.newInstance(), Cached.NO).isEmpty());
    }

    @Test
    public void secondCreateOfTheSameAccommodationIdIsRejected() throws IOException {
        final Accommodation first = accommodation("First " + RandomData.genAlpha(6));
        assertTrue(DAO.getInstance().saveAccommodation(first));
        final Accommodation imposter = accommodation("Imposter");
        imposter.setId(first.getId());
        assertThrows(ConditionalCheckFailedException.class, () -> DAO.getInstance().saveAccommodation(imposter));
        assertEquals(imposter.getVersion(), 0L);
    }

    @Test
    public void oversizedAccommodationIsRefusedBeforeTheStore() {
        final Accommodation huge = accommodation("Huge");
        final List<Room> rooms = new ArrayList<>();
        final String notes = "x".repeat(4000);
        for (int i = 0; i < 100; i++) {
            rooms.add(Room.builder().roomNumber(Integer.toString(i)).notes(notes).build());
        }
        huge.setRooms(rooms);
        assertThrows(IllegalArgumentException.class, () -> DAO.getInstance().saveAccommodation(huge));
        assertEquals(huge.getVersion(), 0L, "Refused before the version bump: the object is untouched");
        assertTrue(DAO.getInstance().getAccommodation(huge.getId(), Cached.NO).isEmpty());
    }

    @Test
    public void offersAndReservationsArePartitionedByTripAndVersioned() throws IOException {
        final String trip = "trip-" + RandomData.genAlpha(8);
        final String other = "trip-" + RandomData.genAlpha(8);
        final ReservationOffer offer = offer(trip, "Double");
        assertTrue(DAO.getInstance().saveReservationOffer(offer));
        assertTrue(DAO.getInstance().saveReservationOffer(offer(trip, "Single")));
        assertTrue(DAO.getInstance().saveReservationOffer(offer(other, "Theirs")));
        assertEquals(DAO.getInstance().getReservationOffers(trip, Cached.NO).size(), 2);
        assertEquals(DAO.getInstance().getReservationOffer(trip, offer.getId(), Cached.NO).orElseThrow(), offer);
        assertTrue(DAO.getInstance().getReservationOffer(other, offer.getId(), Cached.NO).isEmpty(),
                "The composite get IS the partition check");
        assertTrue(DAO.getInstance().getReservationOffer(null, offer.getId(), Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getReservationOffer(trip, null, Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getReservationOffers(null, Cached.NO).isEmpty());

        final ReservationOffer stale = DAO.getInstance().getReservationOffer(trip, offer.getId(), Cached.NO)
                .orElseThrow();
        offer.setName("Winner");
        assertTrue(DAO.getInstance().saveReservationOffer(offer));
        stale.setName("Loser");
        assertThrows(ConditionalCheckFailedException.class, () -> DAO.getInstance().saveReservationOffer(stale));
        assertEquals(stale.getVersion(), 1L);

        final Reservation res = reservation(trip, offer);
        assertTrue(DAO.getInstance().saveReservation(res));
        assertTrue(DAO.getInstance().saveReservation(reservation(other, offer)));
        assertEquals(DAO.getInstance().getReservations(trip, Cached.NO), List.of(res));
        assertEquals(DAO.getInstance().getReservation(trip, res.getId(), Cached.NO).orElseThrow(), res);
        assertTrue(DAO.getInstance().getReservation(other, res.getId(), Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getReservation(trip, null, Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getReservations(null, Cached.NO).isEmpty());
        res.setNotes("late");
        assertTrue(DAO.getInstance().saveReservation(res));
        assertEquals(res.getVersion(), 2L);
        final Reservation loser = Reservation.builder().id(res.getId()).tripId(trip).version(1L).build();
        assertThrows(ConditionalCheckFailedException.class, () -> DAO.getInstance().saveReservation(loser));

        assertTrue(DAO.getInstance().deleteReservationOffer(trip, offer.getId()));
        assertTrue(DAO.getInstance().getReservationOffer(trip, offer.getId(), Cached.NO).isEmpty());
        assertEquals(DAO.getInstance().getReservationOffers(trip, Cached.NO).size(), 1);
    }

    @Test
    public void deleteAllForTripRemovesBothPartitionsAndNothingElse() throws IOException {
        final String trip = "trip-" + RandomData.genAlpha(8);
        final String other = "trip-" + RandomData.genAlpha(8);
        final ReservationOffer offer = offer(trip, "Double");
        assertTrue(DAO.getInstance().saveReservationOffer(offer));
        assertTrue(DAO.getInstance().saveReservation(reservation(trip, offer)));
        assertTrue(DAO.getInstance().saveReservation(reservation(trip, offer)));
        final ReservationOffer theirs = offer(other, "Theirs");
        assertTrue(DAO.getInstance().saveReservationOffer(theirs));

        assertEquals(DAO.getInstance().deleteLodgingForTrip(trip), 3);
        assertTrue(DAO.getInstance().getReservationOffers(trip, Cached.NO).isEmpty());
        assertTrue(DAO.getInstance().getReservations(trip, Cached.NO).isEmpty());
        assertEquals(DAO.getInstance().getReservationOffers(other, Cached.NO), List.of(theirs));
        assertEquals(DAO.getInstance().deleteLodgingForTrip(trip), 0, "Idempotent");
    }

    @Test
    public void lodgingScopeClearsAllThreeNamespaces() {
        final List<String> cleared = DAO.getInstance().invalidate(DAO.CacheScope.LODGING);
        assertFalse(cleared.isEmpty());
        assertTrue(cleared.stream().anyMatch(p -> p.contains("lodging_acc")));
        assertTrue(cleared.stream().anyMatch(p -> p.contains("lodging_offer")));
        assertTrue(cleared.stream().anyMatch(p -> p.contains("lodging_res")));
    }

    @Test
    public void storeFailuresAndCorruptRowsDegradeToEmptyReads() throws IOException {
        final org.paulsens.trip.cache.CacheClient cache = new org.paulsens.trip.cache.NoopCacheClient();
        final Persistence broken = org.mockito.Mockito.mock(Persistence.class);
        org.mockito.Mockito.when(broken.getItem(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("down"));
        final java.util.Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> corrupt =
                java.util.Map.of("id", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                        .s("x").build(), "content", software.amazon.awssdk.services.dynamodb.model.AttributeValue
                        .builder().s("{not json").build());
        final java.util.Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> bare =
                java.util.Map.of("id", software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                        .s("y").build());
        org.mockito.Mockito.when(broken.scanAll(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(corrupt, bare));
        org.mockito.Mockito.when(broken.queryAll(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(corrupt, bare));
        final software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse deleted =
                (software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse)
                        software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse.builder()
                                .sdkHttpResponse(software.amazon.awssdk.http.SdkHttpResponse.builder()
                                        .statusCode(200).build()).build();
        org.mockito.Mockito.when(broken.deleteItem(org.mockito.ArgumentMatchers.any())).thenReturn(deleted);
        final LodgingDAO dao = new LodgingDAO(DAO.getInstance().getMapper(), broken, cache);
        assertTrue(dao.getAccommodation(Accommodation.Id.from("x")).isEmpty(), "A failing point read is empty");
        assertTrue(dao.getAccommodations().isEmpty(), "Corrupt and content-less rows are skipped");
        assertTrue(dao.getOffers("trip").isEmpty());
        assertTrue(dao.getReservations("trip").isEmpty());
        assertEquals(dao.deleteAllForTrip("trip"), 4, "The raw sweep deletes by id whatever the content is");
        dao.clearCache();
    }

    private static Accommodation accommodation(final String name) {
        final RoomType type = RoomType.builder().name("Double").build();
        return Accommodation.builder().name(name).roomTypes(List.of(type))
                .rooms(List.of(Room.builder().roomNumber("114").floor("1").roomTypeId(type.getId()).build()))
                .createdBy(Person.Id.from("person-ken")).created(AT).build();
    }

    private static ReservationOffer offer(final String tripId, final String name) {
        return ReservationOffer.builder().tripId(tripId).name(name).nightlyPriceCents(5500).defaultStart(AT)
                .defaultEnd(AT.plusDays(3)).build();
    }

    private static Reservation reservation(final String tripId, final ReservationOffer offer) {
        return Reservation.builder().tripId(tripId).offerId(offer.getId()).occupants(List.of(Person.Id.newInstance()))
                .start(AT).end(AT.plusDays(3)).build();
    }
}
