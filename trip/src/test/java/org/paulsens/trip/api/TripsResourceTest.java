package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.api.dto.TripDto;
import org.paulsens.trip.api.dto.TripEventDto;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link TripsResource}: reads authorized by MEMBERSHIP first, and viewer-relative itineraries.
 *
 * <p>The list filter's post-query membership check is the one that protects real data: {@code active} and
 * {@code recent} are not per-user queries, so without the filter any signed-in traveller could list every trip
 * in the system.
 */
public class TripsResourceTest extends ResourceTestSupport {

    private static final Person.Id ME = Person.Id.from("trips-me");
    private static final Person.Id OTHER = Person.Id.from("trips-other");
    private static final String TRIP_ID = "trip-1";

    private TripCommands trips;
    private TripsResource resource;

    @BeforeMethod
    public void bindBeans() {
        trips = bindMock(TripCommands.class);
        bindMock(PersonCommands.class);
        resource = resource(new TripsResource());
    }

    private static Trip trip(final String id, final Person.Id... members) {
        final Trip trip = Trip.builder()
                .id(id)
                .title("Pilgrimage")
                .startDate(LocalDateTime.now().plusDays(30))
                .endDate(LocalDateTime.now().plusDays(40))
                .people(List.of(members))
                .build();
        trip.getTripEvents().add(event("evt-1"));
        return trip;
    }

    private static TripEvent event(final String id) {
        final TripEvent event = new TripEvent(id, TripEvent.Type.FLIGHT, "Outbound", "notes",
                LocalDateTime.now().plusDays(30), null, null, null);
        event.getParticipants().add(ME);
        event.getPrivNotes().put(ME, "my seat is 12A");
        return event;
    }

    private void tripExists(final Trip trip) {
        Mockito.when(trips.getTrip(trip.getId())).thenReturn(trip);
    }

    @Test
    public void anUnknownFilterIs400() {
        signedInAs(ME);

        assertError(resource.list("everything", 50), 400, ApiErrors.BAD_REQUEST);
    }

    @Test
    public void mineListsTheCallersTrips() {
        signedInAs(ME);
        Mockito.when(trips.getTripsForUser(ME)).thenReturn(List.of(trip(TRIP_ID, ME)));

        final Response response = resource.list("mine", 50);

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), 1);
    }

    /** The membership filter on non-per-user queries: the difference between "my trips" and "all trips". */
    @Test
    public void activeTripsAreFilteredToWhatTheCallerMaySee() {
        signedInAs(ME);
        Mockito.when(trips.getActiveTrips(ArgumentMatchers.anyInt()))
                .thenReturn(List.of(trip("mine-1", ME), trip("not-mine", OTHER)));

        final Response response = resource.list("active", 50);

        assertOk(response);
        final List<?> visible = (List<?>) response.getEntity();
        Assert.assertEquals(visible.size(), 1, "A non-member must not see somebody else's trip in a list");
        Assert.assertEquals(((TripDto) visible.get(0)).id(), "mine-1");
    }

    @Test
    public void aSiteAdminSeesEverythingInActive() {
        signedInAsSiteAdmin(ME);
        Mockito.when(trips.getActiveTrips(ArgumentMatchers.anyInt()))
                .thenReturn(List.of(trip("mine-1", ME), trip("not-mine", OTHER)));

        Assert.assertEquals(((List<?>) resource.list("active", 50).getEntity()).size(), 2);
    }

    /** Eight trips' worth of flights and hotels has no place in a list of titles. */
    @Test
    public void listEntriesCarryNoItineraries() {
        signedInAs(ME);
        Mockito.when(trips.getTripsForUser(ME)).thenReturn(List.of(trip(TRIP_ID, ME)));

        final TripDto dto = (TripDto) ((List<?>) resource.list("mine", 50).getEntity()).get(0);

        Assert.assertNull(dto.tripEvents(), "List entries must omit the itinerary");
    }

    @Test
    public void listCapsTheLimitBeforePassingItDown() {
        signedInAs(ME);
        Mockito.when(trips.getRecentTrips(ArgumentMatchers.anyInt())).thenReturn(List.of());

        resource.list("recent", 100_000);

        Mockito.verify(trips).getRecentTrips(200);
    }

    /**
     * getTrip answers a miss with Trip.builder().build(), whose builder MINTS a random id -- so a null-id check
     * never fires. Found by this test: an admin GET of a nonexistent id returned a blank made-up trip, and an
     * admin PUT saved it as a junk row. The miss is detectable only because the minted id differs from the one
     * asked for.
     */
    @Test
    public void aMissingTripIs404DespiteTheMintedIdOnTheBlankAnswer() {
        signedInAs(ME);
        Mockito.when(trips.getTrip("gone")).thenReturn(Trip.builder().build());

        assertError(resource.get("gone"), 404, ApiErrors.NOT_FOUND);
        assertError(resource.events("gone"), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void anAdminUpdateOfAMissingTripSavesNoJunkRow() {
        signedInAsSiteAdmin(ME);
        Mockito.when(trips.getTrip("gone")).thenReturn(Trip.builder().build());

        assertError(resource.update("gone", CSRF_OK, null), 404, ApiErrors.NOT_FOUND);
        Mockito.verify(trips, Mockito.never()).saveTrip(ArgumentMatchers.any());
    }

    @Test
    public void aNonMemberCannotReadATrip() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, OTHER));

        assertError(resource.get(TRIP_ID), 403, ApiErrors.FORBIDDEN);
        assertError(resource.events(TRIP_ID), 403, ApiErrors.FORBIDDEN);
        assertError(resource.event(TRIP_ID, "evt-1"), 403, ApiErrors.FORBIDDEN);
        assertError(resource.lodging(TRIP_ID, "evt-1"), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void aMemberGetsTheTripWithTheirOwnViewOfEachEvent() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, ME, OTHER));

        final Response response = resource.get(TRIP_ID);

        assertOk(response);
        final TripDto dto = (TripDto) response.getEntity();
        Assert.assertEquals(dto.id(), TRIP_ID);
        final TripEventDto event = dto.tripEvents().get(0);
        Assert.assertEquals(event.participating(), Boolean.TRUE);
        Assert.assertEquals(event.privNote(), "my seat is 12A");
    }

    /** The private note is the viewer's own. Another member must never receive it. */
    @Test
    public void anotherMembersViewCarriesNeitherMyParticipationNorMyNote() {
        signedInAs(OTHER);
        tripExists(trip(TRIP_ID, ME, OTHER));

        final TripDto dto = (TripDto) resource.get(TRIP_ID).getEntity();

        final TripEventDto event = dto.tripEvents().get(0);
        Assert.assertEquals(event.participating(), Boolean.FALSE);
        Assert.assertNull(event.privNote(), "A private note belongs to its writer alone");
    }

    @Test
    public void anUnknownEventIs404() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, ME));

        assertError(resource.event(TRIP_ID, "no-such-event"), 404, ApiErrors.NOT_FOUND);
        assertError(resource.lodging(TRIP_ID, "no-such-event"), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void lodgingDelegatesTheInferenceRatherThanReimplementingIt() {
        signedInAs(ME);
        final Trip trip = trip(TRIP_ID, ME);
        tripExists(trip);
        final LocalDateTime arrival = LocalDate.of(2026, 9, 1).atStartOfDay();
        final LocalDateTime departure = LocalDate.of(2026, 9, 8).atStartOfDay();
        Mockito.when(trips.getLodgingArrivalDate(ArgumentMatchers.anyList(), ArgumentMatchers.any()))
                .thenReturn(arrival);
        Mockito.when(trips.getLodgingDepartureDate(ArgumentMatchers.anyList(), ArgumentMatchers.any()))
                .thenReturn(departure);
        Mockito.when(trips.getLodgingDays(arrival, departure)).thenReturn(7L);

        final Response response = resource.lodging(TRIP_ID, "evt-1");

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("nights"), 7L);
    }

    @Test
    public void eventTypesListsTheServersEnum() {
        signedInAs(ME);

        final Response response = resource.eventTypes();

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), TripEvent.Type.values().length);
    }

    @Test
    public void creatingATripNeedsCsrfAndTheAddTripPrivilege() {
        signedInAsSiteAdmin(ME);
        assertError(resource.create(null, null), 403, ApiErrors.CSRF);

        signedInAs(ME);
        final TripsResource ordinary = resource(new TripsResource());
        assertError(ordinary.create(CSRF_OK, null), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(trips, Mockito.never()).saveTrip(ArgumentMatchers.any());
    }

    @Test
    public void createAppliesTheBodyAndSaves() {
        signedInAsSiteAdmin(ME);
        final Trip created = trip("new-trip", ME);
        Mockito.when(trips.createTrip()).thenReturn(created);
        Mockito.when(trips.saveTrip(created)).thenReturn(true);

        final TripDto body = new TripDto(null, "Rome 2027", "Ten days", null, false, null, null, 40, null,
                null, null, null, null, null, null, null, null, null, null);
        final Response response = resource.create(CSRF_OK, body);

        assertOk(response);
        Assert.assertEquals(created.getTitle(), "Rome 2027");
        Assert.assertEquals(created.getRegLimit(), Integer.valueOf(40));
    }

    @Test
    public void aFailedCreateIsReported() {
        signedInAsSiteAdmin(ME);
        Mockito.when(trips.createTrip()).thenReturn(trip("new-trip", ME));
        Mockito.when(trips.saveTrip(ArgumentMatchers.any())).thenReturn(false);

        assertError(resource.create(CSRF_OK, null), 500, ApiErrors.STORE_FAILED);
    }

    @Test
    public void updateNeedsTripManagerOnThatTrip() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, ME));

        // Membership lets you read; it does not let you write.
        assertError(resource.update(TRIP_ID, CSRF_OK, null), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(trips, Mockito.never()).saveTrip(ArgumentMatchers.any());
    }

    /** The DAO-copies rule: what gets saved must be the very object that was read. */
    @Test
    public void updateMutatesAndSavesTheObjectItRead() {
        signedInAsSiteAdmin(ME);
        final Trip existing = trip(TRIP_ID, ME);
        tripExists(existing);
        Mockito.when(trips.saveTrip(existing)).thenReturn(true);

        final TripDto body = new TripDto(null, "Renamed", null, null, false, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertOk(resource.update(TRIP_ID, CSRF_OK, body));

        Assert.assertEquals(existing.getTitle(), "Renamed");
        Mockito.verify(trips).saveTrip(ArgumentMatchers.same(existing));
    }

    @Test
    public void participationRejectsAnUnknownEventBeforeSavingAnything() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, ME));

        final Response response = resource.setParticipation(TRIP_ID, CSRF_OK, null,
                Map.of("eventIds", List.of("evt-1", "bogus")));

        assertError(response, 400, ApiErrors.BAD_REQUEST);
        Mockito.verify(trips, Mockito.never()).setEventParticipation(ArgumentMatchers.any(),
                ArgumentMatchers.anyList(), ArgumentMatchers.any());
    }

    @Test
    public void aMemberSetsTheirOwnParticipation() {
        signedInAs(ME);
        final Trip trip = trip(TRIP_ID, ME);
        tripExists(trip);
        Mockito.when(trips.setEventParticipation(ArgumentMatchers.eq(trip), ArgumentMatchers.anyList(),
                ArgumentMatchers.eq(ME))).thenReturn(true);

        assertOk(resource.setParticipation(TRIP_ID, CSRF_OK, null, Map.of("eventIds", List.of("evt-1"))));
    }

    /** Changing somebody ELSE's itinerary needs tripMgr; being a fellow member is not enough. */
    @Test
    public void aMemberCannotChangeAnotherMembersItinerary() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, ME, OTHER));

        final Response response = resource.setParticipation(TRIP_ID, CSRF_OK, OTHER.getValue(),
                Map.of("eventIds", List.of("evt-1")));

        assertError(response, 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void saveNoteWritesTheCallersOwnNote() {
        signedInAs(ME);
        final Trip trip = trip(TRIP_ID, ME);
        tripExists(trip);
        Mockito.when(trips.saveEventNote(ArgumentMatchers.eq(trip), ArgumentMatchers.any(),
                ArgumentMatchers.eq(ME), ArgumentMatchers.eq("aisle please"))).thenReturn(true);

        assertOk(resource.saveNote(TRIP_ID, "evt-1", CSRF_OK, null, Map.of("note", "aisle please")));
    }

    @Test
    public void saveNoteRefusesWritingSomebodyElsesNote() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, ME, OTHER));

        assertError(resource.saveNote(TRIP_ID, "evt-1", CSRF_OK, OTHER.getValue(), Map.of("note", "x")),
                403, ApiErrors.FORBIDDEN);
        Mockito.verify(trips, Mockito.never()).saveEventNote(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

    @Test
    public void aFailedNoteSaveIsReported() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, ME));
        Mockito.when(trips.saveEventNote(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.anyString())).thenReturn(false);

        assertError(resource.saveNote(TRIP_ID, "evt-1", CSRF_OK, null, null), 500, ApiErrors.STORE_FAILED);
    }

    @Test
    public void theProducedTypeIsTheTripsMediaType() {
        Assert.assertEquals(new TripsResource().versionedType(), ApiMediaTypes.TRIPS_V1);
    }
}
