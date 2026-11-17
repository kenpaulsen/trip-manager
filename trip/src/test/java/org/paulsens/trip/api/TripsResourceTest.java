package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.action.Caller;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.action.TripPictureCommands;
import org.paulsens.trip.api.dto.TripPictureDto;
import org.paulsens.trip.model.TripPicture;
import org.paulsens.trip.api.dto.TripDto;
import org.paulsens.trip.api.dto.TripEventDto;
import org.paulsens.trip.api.dto.TripPaymentConfigDto;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.FeesPaidBy;
import org.paulsens.trip.model.Organization;
import org.paulsens.trip.model.PaymentProcessorConfig;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.ProcessorType;
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
    private PersonCommands people;
    private TripsResource resource;

    @BeforeMethod
    public void bindBeans() {
        trips = bindMock(TripCommands.class);
        people = bindMock(PersonCommands.class);
        resource = resource(new TripsResource());
    }

    /** ME manages OTHER: the family switcher's reach, as {@code ApiPrivileges.canActFor} resolves it. */
    private void meManages(final Person.Id managed) {
        final Person me = new Person();
        me.setId(ME);
        Mockito.when(people.getPerson(ME)).thenReturn(me);
        Mockito.when(people.canAccessUserId(me, managed)).thenReturn(true);
    }

    /** An event OTHER participates in, carrying OTHER's private note (and none of ME's). */
    private static TripEvent othersEvent(final String id) {
        final TripEvent event = new TripEvent(id, TripEvent.Type.FLIGHT, "Return", "notes",
                LocalDateTime.now().plusDays(40), null, null, null);
        event.getParticipants().add(OTHER);
        event.getPrivNotes().put(OTHER, "window seat for the kid");
        return event;
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

        assertError(resource.list("everything", 50, null), 400, ApiErrors.BAD_REQUEST);
    }

    /** Every trip carries the absolute address of its embedded page (docs/api-contract.md "Trips", 2026-11). */
    @Test
    public void everyTripCarriesItsDetailsUrl() {
        signedInAs(ME);
        Mockito.when(request.getScheme()).thenReturn("https");
        Mockito.when(request.getServerName()).thenReturn("unitetrip.com");
        Mockito.when(request.getServerPort()).thenReturn(443);
        Mockito.when(request.getContextPath()).thenReturn("");
        Mockito.when(trips.getTripsForUser(ME)).thenReturn(List.of(trip(TRIP_ID, ME)));
        tripExists(trip(TRIP_ID, ME));

        final List<?> listed = (List<?>) resource.list("mine", 50, null).getEntity();
        final TripDto row = (TripDto) listed.get(0);
        Assert.assertEquals(row.detailsUrl(), "https://unitetrip.com/trip/tripPage.jsf?trip=" + TRIP_ID);
        Assert.assertNull(row.tripEvents(), "a listing still carries no itinerary");

        final TripDto one = (TripDto) resource.get(TRIP_ID, null).getEntity();
        Assert.assertEquals(one.detailsUrl(), "https://unitetrip.com/trip/tripPage.jsf?trip=" + TRIP_ID);
        Assert.assertEquals(one.redactedFor(false, false).detailsUrl(), one.detailsUrl(), "kept for a plain member");
        Assert.assertEquals(one.withoutEvents().detailsUrl(), one.detailsUrl());
    }

    /**
     * In production the page is addressed on the site of the organization that OWNS the trip, never on the host
     * the app asked (2026-11-08: built on the product host, a shared-site org's trip answered "This page is not
     * available."): its own subdomain, else the "Link base URL" setting its tickets and emails use.
     */
    @Test
    public void productionLinksTheTripOnItsOwnOrganizationsSite() throws java.io.IOException {
        final org.paulsens.trip.action.ConfigCommands config = new org.paulsens.trip.action.ConfigCommands();
        final java.util.function.UnaryOperator<String> requestHost = path -> "https://unitetrip.com" + path;

        final org.paulsens.trip.model.Organization own = new org.paulsens.trip.model.Organization();
        own.setName("Own site " + java.util.UUID.randomUUID());
        final String slug = "ds" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        own.setSlug(slug);
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(own));
        final Trip onOwnSite = trip(TRIP_ID, ME);
        onOwnSite.setOrgId(own.getId().getValue());
        Assert.assertEquals(TripsResource.detailsUrl(onOwnSite, false, requestHost, config),
                "https://" + slug + ".unitetrip.com/trip/tripPage.jsf?trip=" + TRIP_ID);

        final Trip shared = trip(TRIP_ID, ME);
        shared.setOrgId(org.paulsens.trip.dynamo.FakeData.CFPW_ORG_ID);
        Assert.assertEquals(TripsResource.detailsUrl(shared, false, requestHost, config),
                config.getString(org.paulsens.trip.config.KnownSettings.REG_MAIL_BASE_URL).replaceAll("/+$", "")
                        + "/trip/tripPage.jsf?trip=" + TRIP_ID,
                "an org on the shared site: the link base its tickets use (visitqueenofpeace.com in production)");

        Assert.assertEquals(TripsResource.detailsUrl(shared, true, requestHost, config),
                "https://unitetrip.com/trip/tripPage.jsf?trip=" + TRIP_ID, "local mode keeps the request's host");
    }

    @Test
    public void mineListsTheCallersTrips() {
        signedInAs(ME);
        Mockito.when(trips.getTripsForUser(ME)).thenReturn(List.of(trip(TRIP_ID, ME)));

        final Response response = resource.list("mine", 50, null);

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), 1);
    }

    /** The membership filter on non-per-user queries: the difference between "my trips" and "all trips". */
    @Test
    public void activeTripsAreFilteredToWhatTheCallerMaySee() {
        signedInAs(ME);
        Mockito.when(trips.getActiveTrips(ArgumentMatchers.anyInt()))
                .thenReturn(List.of(trip("mine-1", ME), trip("not-mine", OTHER)));

        final Response response = resource.list("active", 50, null);

        assertOk(response);
        final List<?> visible = (List<?>) response.getEntity();
        Assert.assertEquals(visible.size(), 1, "A non-member must not see somebody else's trip in a list");
        Assert.assertEquals(((TripDto) visible.get(0)).id(), "mine-1");
    }

    /** The landing page's listing: joinable, publicly listed trips need no membership to be seen. */
    @Test
    public void openListsPublicTripsToNonMembers() {
        signedInAs(ME);
        Mockito.when(trips.getPublicTrips()).thenReturn(List.of(trip("open-1", OTHER)));

        final Response response = resource.list("open", 50, null);

        assertOk(response);
        final List<?> visible = (List<?>) response.getEntity();
        Assert.assertEquals(visible.size(), 1);
        Assert.assertEquals(((TripDto) visible.get(0)).id(), "open-1");
    }

    @Test
    public void aSiteAdminSeesEverythingInActive() {
        signedInAsSiteAdmin(ME);
        Mockito.when(trips.getActiveTrips(ArgumentMatchers.anyInt()))
                .thenReturn(List.of(trip("mine-1", ME), trip("not-mine", OTHER)));

        Assert.assertEquals(((List<?>) resource.list("active", 50, null).getEntity()).size(), 2);
    }

    // --- acting as a family member: the subject travels as ?personId= ---

    @Test
    public void mineListsTheManagedMembersTripsWhenAskedFor() {
        signedInAs(ME);
        meManages(OTHER);
        final Trip kidsTrip = trip("kid-trip", OTHER);
        Mockito.when(trips.getTripsForUser(OTHER)).thenReturn(List.of(kidsTrip));
        Mockito.when(trips.managesSomeoneOn(kidsTrip, ME)).thenReturn(true);

        final Response response = resource.list("mine", 50, OTHER.getValue());

        assertOk(response);
        final List<?> visible = (List<?>) response.getEntity();
        Assert.assertEquals(visible.size(), 1);
        Assert.assertEquals(((TripDto) visible.get(0)).id(), "kid-trip");
        Mockito.verify(trips, Mockito.never()).getTripsForUser(ME);
    }

    @Test
    public void mineRefusesAStrangerAsSubject() {
        signedInAs(ME);

        assertError(resource.list("mine", 50, OTHER.getValue()), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(trips, Mockito.never()).getTripsForUser(ArgumentMatchers.any());
    }

    /** The website's canSeeTrip rule, now on the API: a parent reads the kid's trip without a seat of their own. */
    @Test
    public void aManagerReadsAMembersTripWithoutARosterSeat() {
        signedInAs(ME);
        meManages(OTHER);
        final Trip trip = trip(TRIP_ID, OTHER);
        trip.getTripEvents().clear();
        trip.getTripEvents().add(othersEvent("evt-2"));
        tripExists(trip);
        Mockito.when(trips.managesSomeoneOn(trip, ME)).thenReturn(true);

        assertOk(resource.get(TRIP_ID, null));
        final Response events = resource.events(TRIP_ID, OTHER.getValue());

        assertOk(events);
        @SuppressWarnings("unchecked")
        final TripEventDto event = ((List<TripEventDto>) events.getEntity()).get(0);
        Assert.assertEquals(event.participating(), Boolean.TRUE, "the subject's participation, not the caller's");
        Assert.assertEquals(event.privNote(), "window seat for the kid", "the subject's own note, as on the website");
    }

    @Test
    public void eventsCarryTheSubjectsParticipationAndNote() {
        signedInAs(ME);
        meManages(OTHER);
        final Trip trip = trip(TRIP_ID, ME, OTHER);
        trip.getTripEvents().add(othersEvent("evt-2"));
        tripExists(trip);

        @SuppressWarnings("unchecked")
        final List<TripEventDto> mine = (List<TripEventDto>) resource.events(TRIP_ID, null).getEntity();
        @SuppressWarnings("unchecked")
        final List<TripEventDto> kids = (List<TripEventDto>) resource.events(TRIP_ID, OTHER.getValue()).getEntity();

        Assert.assertEquals(mine.get(0).participating(), Boolean.TRUE);
        Assert.assertEquals(mine.get(1).participating(), Boolean.FALSE);
        Assert.assertEquals(mine.get(0).privNote(), "my seat is 12A");
        Assert.assertEquals(kids.get(0).participating(), Boolean.FALSE);
        Assert.assertEquals(kids.get(1).participating(), Boolean.TRUE);
        Assert.assertNull(kids.get(0).privNote(), "ME's note never rides a read for OTHER");
        Assert.assertEquals(kids.get(1).privNote(), "window seat for the kid");
        final TripEventDto single = (TripEventDto) resource.event(TRIP_ID, "evt-2", OTHER.getValue()).getEntity();
        Assert.assertEquals(single.privNote(), "window seat for the kid");
        final TripDto whole = (TripDto) resource.get(TRIP_ID, OTHER.getValue()).getEntity();
        Assert.assertEquals(whole.tripEvents().get(1).privNote(), "window seat for the kid");
    }

    /** Being on the same trip is not managing someone: a peer naming a fellow member is refused every read. */
    @Test
    public void aPeerCannotReadAnotherMembersNoteViaPersonId() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, ME, OTHER));

        assertError(resource.get(TRIP_ID, OTHER.getValue()), 403, ApiErrors.FORBIDDEN);
        assertError(resource.events(TRIP_ID, OTHER.getValue()), 403, ApiErrors.FORBIDDEN);
        assertError(resource.event(TRIP_ID, "evt-1", OTHER.getValue()), 403, ApiErrors.FORBIDDEN);
        assertError(resource.lodging(TRIP_ID, "evt-1", OTHER.getValue()), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void lodgingUsesTheSubjectsReservation() {
        signedInAs(ME);
        meManages(OTHER);
        final Trip trip = trip(TRIP_ID, ME, OTHER);
        tripExists(trip);
        final org.paulsens.trip.action.LodgingCommands lodging =
                bindMock(org.paulsens.trip.action.LodgingCommands.class);
        final org.paulsens.trip.action.LodgingViews.ItineraryRow row =
                new org.paulsens.trip.action.LodgingViews.ItineraryRow();
        row.setId("evt-1");
        row.setReservationId("res-kid");
        row.setEffectiveStart(LocalDate.of(2026, 9, 21).atTime(23, 0));
        row.setEffectiveEnd(LocalDate.of(2026, 9, 28).atTime(10, 0));
        row.setNights(7);
        Mockito.when(lodging.itineraryRowsFor(trip, OTHER)).thenReturn(List.of(row));

        final Response response = resource.lodging(TRIP_ID, "evt-1", OTHER.getValue());

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("fromReservation"), true, "the SUBJECT's reservation, not the caller's");
        Assert.assertEquals(body.get("nights"), 7L);
        Mockito.verify(lodging, Mockito.never()).itineraryRowsFor(trip, ME);
    }

    /** Eight trips' worth of flights and hotels has no place in a list of titles. */
    @Test
    public void listEntriesCarryNoItineraries() {
        signedInAs(ME);
        Mockito.when(trips.getTripsForUser(ME)).thenReturn(List.of(trip(TRIP_ID, ME)));

        final TripDto dto = (TripDto) ((List<?>) resource.list("mine", 50, null).getEntity()).get(0);

        Assert.assertNull(dto.tripEvents(), "List entries must omit the itinerary");
    }

    @Test
    public void listCapsTheLimitBeforePassingItDown() {
        signedInAs(ME);
        Mockito.when(trips.getRecentTrips(ArgumentMatchers.anyInt())).thenReturn(List.of());

        resource.list("recent", 100_000, null);

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

        assertError(resource.get("gone", null), 404, ApiErrors.NOT_FOUND);
        assertError(resource.events("gone", null), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void anAdminUpdateOfAMissingTripSavesNoJunkRow() {
        signedInAsSiteAdmin(ME);
        Mockito.when(trips.getTrip("gone")).thenReturn(Trip.builder().build());

        assertError(resource.update("gone", CSRF_OK, null), 404, ApiErrors.NOT_FOUND);
        Mockito.verify(trips, Mockito.never()).saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void aNonMemberCannotReadATrip() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, OTHER));

        assertError(resource.get(TRIP_ID, null), 403, ApiErrors.FORBIDDEN);
        assertError(resource.events(TRIP_ID, null), 403, ApiErrors.FORBIDDEN);
        assertError(resource.event(TRIP_ID, "evt-1", null), 403, ApiErrors.FORBIDDEN);
        assertError(resource.lodging(TRIP_ID, "evt-1", null), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void aMemberGetsTheTripWithTheirOwnViewOfEachEvent() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, ME, OTHER));

        final Response response = resource.get(TRIP_ID, null);

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

        final TripDto dto = (TripDto) resource.get(TRIP_ID, null).getEntity();

        final TripEventDto event = dto.tripEvents().get(0);
        Assert.assertEquals(event.participating(), Boolean.FALSE);
        Assert.assertNull(event.privNote(), "A private note belongs to its writer alone");
    }

    @Test
    public void anUnknownEventIs404() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, ME));

        assertError(resource.event(TRIP_ID, "no-such-event", null), 404, ApiErrors.NOT_FOUND);
        assertError(resource.lodging(TRIP_ID, "no-such-event", null), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void lodgingDelegatesTheInferenceRatherThanReimplementingIt() {
        signedInAs(ME);
        final Trip trip = trip(TRIP_ID, ME);
        tripExists(trip);
        bindMock(org.paulsens.trip.action.LodgingCommands.class);   // no reservation rows: the fallback
        final LocalDateTime arrival = LocalDate.of(2026, 9, 1).atStartOfDay();
        final LocalDateTime departure = LocalDate.of(2026, 9, 8).atStartOfDay();
        Mockito.when(trips.getLodgingArrivalDate(ArgumentMatchers.anyList(), ArgumentMatchers.any()))
                .thenReturn(arrival);
        Mockito.when(trips.getLodgingDepartureDate(ArgumentMatchers.anyList(), ArgumentMatchers.any()))
                .thenReturn(departure);
        Mockito.when(trips.getLodgingDays(arrival, departure)).thenReturn(7L);

        final Response response = resource.lodging(TRIP_ID, "evt-1", null);

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("nights"), 7L);
        Assert.assertEquals(body.get("fromReservation"), false);
    }

    /**
     * The caller's own reservation wins over the flight inference: the inference reads every flight on the
     * trip, so one early arriver used to move everybody's hotel dates in the app (2026-09-14).
     */
    @Test
    public void lodgingAnswersTheCallersReservationWhenTheyHoldOne() {
        signedInAs(ME);
        final Trip trip = trip(TRIP_ID, ME);
        tripExists(trip);
        final org.paulsens.trip.action.LodgingCommands lodging =
                bindMock(org.paulsens.trip.action.LodgingCommands.class);
        final org.paulsens.trip.action.LodgingViews.ItineraryRow row =
                new org.paulsens.trip.action.LodgingViews.ItineraryRow();
        row.setId("evt-1");
        row.setReservationId("res-1");
        row.setEffectiveStart(LocalDate.of(2026, 9, 21).atTime(23, 0));
        row.setEffectiveEnd(LocalDate.of(2026, 10, 1).atTime(10, 0));
        row.setNights(10);
        row.setAccommodationName("Pansion Dragicevic");
        row.setRoomTypeName("Family");
        row.setRoomLabel("106");
        Mockito.when(lodging.itineraryRowsFor(trip, ME)).thenReturn(List.of(row));

        final Response response = resource.lodging(TRIP_ID, "evt-1", null);

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("arrival"), LocalDate.of(2026, 9, 21).atTime(23, 0));
        Assert.assertEquals(body.get("nights"), 10L);
        Assert.assertEquals(body.get("fromReservation"), true);
        Assert.assertEquals(body.get("room"), "106");
        Assert.assertEquals(body.get("roomType"), "Family");
        Mockito.verify(trips, Mockito.never()).getLodgingArrivalDate(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void eventTypesListsTheServersEnum() {
        signedInAs(ME);

        final Response response = resource.eventTypes();

        assertOk(response);
        Assert.assertEquals(((List<?>) response.getEntity()).size(), TripEvent.Type.values().length);
    }

    /** A body whose only interesting fields are title/regLimit/orgId -- the create matrix's fixture. */
    private static TripDto createBody(final String orgId) {
        return new TripDto(null, "Rome 2027", "Ten days", null, false, null, null, 40, null,
                orgId, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    public void creatingATripNeedsCsrfAnOrgIdAndOrgAuthority() {
        signedInAsSiteAdmin(ME);
        assertError(resource.create(null, null), 403, ApiErrors.CSRF);
        assertError(resource.create(CSRF_OK, null), 400, ApiErrors.VALIDATION_FAILED);
        assertError(resource.create(CSRF_OK, createBody("  ")), 400, ApiErrors.VALIDATION_FAILED);

        signedInAs(ME);
        final TripsResource ordinary = resource(new TripsResource());
        assertError(ordinary.create(CSRF_OK, createBody(java.util.UUID.randomUUID().toString())),
                403, ApiErrors.FORBIDDEN);
        Mockito.verify(trips, Mockito.never()).saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void anAddTripHolderCreatesInTheirOrgOnly() {
        // Real privilege row: addTrip scoped to one org admits creation there and nowhere else.
        final String orgId = java.util.UUID.randomUUID().toString();
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(
                priv.createPrivilege("addTrip", "May create trips", orgId, List.of(ME)), null));
        Mockito.when(trips.createTrip()).thenReturn(trip("new-trip", ME));
        Mockito.when(trips.saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);

        signedInAs(ME);
        assertOk(resource(new TripsResource()).create(CSRF_OK, createBody(orgId)));
        signedInAs(ME);
        assertError(resource(new TripsResource())
                        .create(CSRF_OK, createBody(java.util.UUID.randomUUID().toString())),
                403, ApiErrors.FORBIDDEN);
    }

    /** A conference takes Create Conferences, never Create Trips, and only while the org is offered it. */
    @Test
    public void aConferenceTakesCreateConferences() throws java.io.IOException {
        final org.paulsens.trip.model.Organization organization = new org.paulsens.trip.model.Organization(null,
                "Conf REST " + ME.getValue(), null, null, null, null, null, 0L);
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(organization));
        final String orgId = organization.getId().getValue();
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.createPrivilege("addTrip", "trips", orgId, List.of(ME)), null));
        Mockito.when(trips.createTrip()).thenReturn(trip(java.util.UUID.randomUUID().toString(), ME));
        Mockito.when(trips.saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);
        signedInAs(ME);
        assertError(resource(new TripsResource()).create(CSRF_OK, conferenceBody(orgId)), 403, ApiErrors.FORBIDDEN);

        Assert.assertTrue(priv.savePrivilege(priv.createPrivilege("addConference", "conferences", orgId,
                List.of(ME)), null));
        signedInAs(ME);
        assertOk(resource(new TripsResource()).create(CSRF_OK, conferenceBody(orgId)));

        organization.setGrantablePrivileges(List.of("addTrip"));
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveOrganization(organization));
        signedInAs(ME);
        assertError(resource(new TripsResource()).create(CSRF_OK, conferenceBody(orgId)), 403, ApiErrors.FORBIDDEN);
    }

    /** A trip manager edits a trip but cannot make it a conference. */
    @Test
    public void aTripManagerMayNotMakeAConference() {
        signedInAs(ME);
        final String tripId = java.util.UUID.randomUUID().toString();
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.createPrivilege("tripMgr", "mgr", tripId, List.of(ME)), null));
        final Trip existing = trip(tripId, ME);
        tripExists(existing);
        assertError(resource(new TripsResource()).update(tripId, CSRF_OK, conferenceBody(null)), 403,
                ApiErrors.FORBIDDEN);
        Assert.assertFalse(existing.isConference());
    }

    private static TripDto conferenceBody(final String orgId) {
        return new TripDto(null, "Fest 2027", "Three days", null, false, null, null, 40, null, orgId, null, null,
                null, null, null, null, null, null, null, null, null, "conference");
    }

    @Test
    public void createAppliesTheBodyAndSaves() {
        signedInAsSiteAdmin(ME);
        final Trip created = trip("new-trip", ME);
        Mockito.when(trips.createTrip()).thenReturn(created);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(created), ArgumentMatchers.any())).thenReturn(true);

        final String orgId = java.util.UUID.randomUUID().toString();
        final Response response = resource.create(CSRF_OK, createBody(orgId));

        assertOk(response);
        Assert.assertEquals(created.getTitle(), "Rome 2027");
        Assert.assertEquals(created.getRegLimit(), Integer.valueOf(40));
        Assert.assertEquals(created.getOrgId(), orgId, "The body's orgId is stamped on the trip");
    }

    @Test
    public void aFailedCreateIsReported() {
        signedInAsSiteAdmin(ME);
        Mockito.when(trips.createTrip()).thenReturn(trip("new-trip", ME));
        Mockito.when(trips.saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(false);

        assertError(resource.create(CSRF_OK, createBody(java.util.UUID.randomUUID().toString())),
                500, ApiErrors.STORE_FAILED);
    }

    @Test
    public void updateNeedsTripManagerOnThatTrip() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, ME));

        // Membership lets you read; it does not let you write.
        assertError(resource.update(TRIP_ID, CSRF_OK, null), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(trips, Mockito.never()).saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    /** The DAO-copies rule: what gets saved must be the very object that was read. */
    @Test
    public void updateMutatesAndSavesTheObjectItRead() {
        signedInAsSiteAdmin(ME);
        final Trip existing = trip(TRIP_ID, ME);
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);

        final TripDto body = new TripDto(null, "Renamed", null, null, false, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertOk(resource.update(TRIP_ID, CSRF_OK, body));

        Assert.assertEquals(existing.getTitle(), "Renamed");
        Mockito.verify(trips).saveTrip(ArgumentMatchers.same(existing), ArgumentMatchers.any());
    }

    /**
     * The roster is settable like any other field the caller may edit — but tenancy holds: a person from
     * outside the trip's org is SKIPPED, never added, because organization is the tenancy boundary and a
     * raw REST setter must not be the one door around the page picker's org scoping.
     */
    @Test
    public void updateAppliesTheRosterButOnlyWithinTheTripsOrg() {
        signedInAsSiteAdmin(ME);
        final Trip existing = trip(TRIP_ID, ME);
        existing.setOrgId("org-1");
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);
        final org.paulsens.trip.action.OrgCommands orgs =
                bindMock(org.paulsens.trip.action.OrgCommands.class);
        Mockito.when(orgs.isMember("org-1", Person.Id.from("in-org"))).thenReturn(true);

        final TripDto body = new TripDto(null, null, null, null, false, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                List.of("in-org", "outside-org"), null, null);
        assertOk(resource.update(TRIP_ID, CSRF_OK, body));

        Assert.assertEquals(existing.getPeople(), List.of(Person.Id.from("in-org")),
                "only the org member may land on the roster; the outsider is silently skipped");
    }

    /** Registration options are settable; a null option id appends at the next index (the Add Row rule). */
    @Test
    public void updateSetsTheRegistrationOptions() {
        signedInAsSiteAdmin(ME);
        final Trip existing = trip(TRIP_ID, ME);
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);

        final TripDto body = new TripDto(null, null, null, null, false, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                List.of(new org.paulsens.trip.api.dto.RegOptionDto(null, "Room Preference", "Who with?", null),
                        new org.paulsens.trip.api.dto.RegOptionDto(null, "Diet", "Anything we should know?",
                                false)),
                null, null, null);
        assertOk(resource.update(TRIP_ID, CSRF_OK, body));

        Assert.assertEquals(existing.getRegOptions().size(), 2);
        Assert.assertEquals(existing.getRegOptions().get(0).getId(), 0);
        Assert.assertEquals(existing.getRegOptions().get(0).getShortDesc(), "Room Preference");
        Assert.assertEquals(existing.getRegOptions().get(1).getId(), 1);
        Assert.assertEquals(existing.getRegOptions().get(1).getShow(), Boolean.FALSE);
    }

    /** The kind and typed questions ride the trip write (docs/conference.md, docs/checkout.md). */
    @Test
    public void updateSetsTheKindAndTypedQuestions() {
        signedInAsSiteAdmin(ME);
        final Trip existing = trip(TRIP_ID, ME);
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);

        final org.paulsens.trip.api.dto.RegChoiceDto full = new org.paulsens.trip.api.dto.RegChoiceDto("full",
                "Full weekend", 15000L, 200, null, List.of("2027-03-05"), null);
        final org.paulsens.trip.api.dto.RegChoiceDto hotel = new org.paulsens.trip.api.dto.RegChoiceDto(null,
                "Hotel guest", null, null, "I am staying at the hotel", null, Boolean.FALSE);
        final TripDto body = new TripDto(null, null, null, null, false, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                List.of(new org.paulsens.trip.api.dto.RegOptionDto(null, "Admission", "", null, "choice", true,
                        List.of(full, hotel))),
                null, null, "conference");
        final jakarta.ws.rs.core.Response response = resource.update(TRIP_ID, CSRF_OK, body);
        assertOk(response);

        Assert.assertTrue(existing.isConference());
        final org.paulsens.trip.model.RegistrationOption admission = existing.getRegOptions().get(0);
        Assert.assertEquals(admission.getType(), org.paulsens.trip.model.RegistrationOption.QuestionType.CHOICE);
        Assert.assertTrue(admission.isRequiredFlag());
        Assert.assertEquals(admission.choice("full").getPriceCents(), 15000L);
        Assert.assertEquals(admission.getChoices().get(1).getPriceCents(), 0L, "a missing price is free");
        Assert.assertFalse(admission.getChoices().get(1).isOffered());
        Assert.assertEquals(TripsResource.parseKind("Pilgrimage"), org.paulsens.trip.model.TripKind.PILGRIMAGE);
    }

    /** An unknown kind or question type is refused before anything is applied. */
    @Test
    public void anUnknownKindOrQuestionTypeIs400AndChangesNothing() {
        signedInAsSiteAdmin(ME);
        final Trip existing = trip(TRIP_ID, ME);
        tripExists(existing);

        final TripDto badKind = new TripDto(null, "Renamed", null, null, false, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, "retreat");
        Assert.assertEquals(resource.update(TRIP_ID, CSRF_OK, badKind).getStatus(), 400);
        Assert.assertEquals(existing.getTitle(), "Pilgrimage", "a refused write half-applies nothing");

        final TripDto badType = new TripDto(null, null, null, null, false, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                List.of(new org.paulsens.trip.api.dto.RegOptionDto(null, "Q", "", null, "essay", null, null)),
                null, null);
        Assert.assertEquals(resource.update(TRIP_ID, CSRF_OK, badType).getStatus(), 400);
        Assert.assertNull(TripsResource.badEnumValue(null));
        Mockito.verify(trips, Mockito.never()).saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    /**
     * The trip's own payment settings (2026-11): a manager sets them through the API like the editor's
     * dialog does, field by field (absent = leave alone, "" = inherit), the processor must belong to the
     * trip's organization, and only a manager reads them back.
     */
    @Test
    public void updateAppliesThePaymentConfigWithinTheTripsOrg() throws java.io.IOException {
        signedInAsSiteAdmin(ME);
        final Trip existing = trip(TRIP_ID, ME);
        existing.setOrgId("org-pay");
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);
        final org.paulsens.trip.model.PaymentProcessorConfig config =
                org.paulsens.trip.model.PaymentProcessorConfig.builder()
                        .orgId(org.paulsens.trip.model.Organization.Id.from("org-pay"))
                        .label("Org Stripe").type(org.paulsens.trip.model.ProcessorType.STRIPE)
                        .mode(org.paulsens.trip.model.PaymentProcessorConfig.ProcessorMode.LIVE).enabled(true)
                        .publicConfig(Map.of("clientId", "pk_live_x")).createdBy(ME)
                        .created(LocalDateTime.now()).build();
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().savePaymentProcessorConfig(config));

        final TripDto body = paymentBody(new org.paulsens.trip.api.dto.TripPaymentConfigDto(
                config.getId().getValue(), "PAYER", Boolean.TRUE, "Help a traveler", null, null, "reply@org.example",
                null, Map.of("officePhone", "555-1212")));
        assertOk(resource.update(TRIP_ID, CSRF_OK, body));
        Assert.assertEquals(existing.getPaymentConfig().getProcessorConfigId(), config.getId().getValue());
        Assert.assertEquals(existing.getPaymentConfig().getFeesPaidBy(), org.paulsens.trip.model.FeesPaidBy.PAYER);
        Assert.assertEquals(existing.getPaymentConfig().getDonationEnabled(), Boolean.TRUE);
        Assert.assertEquals(existing.getPaymentConfig().getReplyTo(), "reply@org.example");
        Assert.assertEquals(existing.getPaymentConfig().getExtraTokens().get("officePhone"), "555-1212");
        Assert.assertNull(existing.getPaymentConfig().getConfirmationTemplateId(), "absent = untouched");

        // "" clears back to inherit; absent fields stay as they were.
        assertOk(resource.update(TRIP_ID, CSRF_OK, paymentBody(new org.paulsens.trip.api.dto.TripPaymentConfigDto(
                "", "", null, null, null, null, null, null, null))));
        Assert.assertNull(existing.getPaymentConfig().getProcessorConfigId());
        Assert.assertNull(existing.getPaymentConfig().getFeesPaidBy());
        Assert.assertEquals(existing.getPaymentConfig().getDonationEnabled(), Boolean.TRUE, "left alone");

        // Tenancy: another organization's processor (or a made-up id) is refused before anything changes.
        assertError(resource.update(TRIP_ID, CSRF_OK, paymentBody(new org.paulsens.trip.api.dto.TripPaymentConfigDto(
                "someone-elses-config", null, null, null, null, null, null, null, null))), 400,
                ApiErrors.VALIDATION_FAILED);
        assertError(resource.update(TRIP_ID, CSRF_OK, paymentBody(new org.paulsens.trip.api.dto.TripPaymentConfigDto(
                null, "SPLIT", null, null, null, null, null, null, null))), 400, ApiErrors.VALIDATION_FAILED);
        // A From on a domain this trip's org may not send from (another tenant's custom domain) is refused too;
        // a shared domain is fine.
        assertError(resource.update(TRIP_ID, CSRF_OK, paymentBody(new org.paulsens.trip.api.dto.TripPaymentConfigDto(
                null, null, null, null, null, "x@centerforpeacewest.com", null, null, null))), 400,
                ApiErrors.VALIDATION_FAILED);
        assertOk(resource.update(TRIP_ID, CSRF_OK, paymentBody(new org.paulsens.trip.api.dto.TripPaymentConfigDto(
                null, null, null, null, null, "Org <no-reply@unitetrip.com>", null, null, null))));
        Assert.assertEquals(existing.getPaymentConfig().getMailFrom(), "Org <no-reply@unitetrip.com>");
        Assert.assertNull(existing.getPaymentConfig().getProcessorConfigId());

        // Read-back: the site admin (a manager) sees it, a plain member does not.
        existing.getPaymentConfig().setProcessorConfigId(config.getId().getValue());
        final TripDto admin = (TripDto) resource.get(TRIP_ID, null).getEntity();
        Assert.assertEquals(admin.paymentConfig().processorConfigId(), config.getId().getValue());
        signedInAs(ME);
        final TripDto member = (TripDto) resource(new TripsResource()).get(TRIP_ID, null).getEntity();
        Assert.assertNull(member.paymentConfig(), "the payment settings are the managers' business");
        Assert.assertNull(member.withoutEvents().paymentConfig());
    }

    @Test
    public void createRefusesAForeignProcessorBeforeSaving() {
        signedInAsSiteAdmin(ME);
        Mockito.when(trips.createTrip()).thenReturn(trip("new-trip", ME));
        final String orgId = java.util.UUID.randomUUID().toString();
        final TripDto body = new TripDto(null, "Rome 2027", null, null, false, null, null, null, null, orgId, null,
                null, null, null, null, null, null, null, null, null, null, null,
                new org.paulsens.trip.api.dto.TripPaymentConfigDto("not-ours", null, null, null, null, null, null,
                        null, null));
        assertError(resource.create(CSRF_OK, body), 400, ApiErrors.VALIDATION_FAILED);
        Mockito.verify(trips, Mockito.never()).saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    private static TripDto paymentBody(final org.paulsens.trip.api.dto.TripPaymentConfigDto config) {
        return new TripDto(null, null, null, null, false, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, config);
    }

    /**
     * A conference page's own look (docs/conference.md, "Look and feel"): field by field like the payment settings
     * (absent = leave alone, "" = back to the organization's), the layout must be a known one, and a stylesheet must
     * be a CSS file in the trip's organization's library, else 400 and nothing is saved.
     */
    @Test
    public void updateAppliesThePageOptionsAndRefusesWhatTheEditorWould() throws java.io.IOException {
        signedInAsSiteAdmin(ME);
        final Trip existing = trip(TRIP_ID, ME);
        existing.setOrgId("org-page");
        existing.setKind(org.paulsens.trip.model.TripKind.CONFERENCE);
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);
        final org.paulsens.trip.model.MediaItem css = new org.paulsens.trip.model.MediaItem("trips-page-css",
                "org/org-page/page.css", "Page CSS", null, "text/css", 10L, null, 0, LocalDateTime.now(), "seed",
                null, null, "org-page");
        Assert.assertTrue(org.paulsens.trip.dynamo.DAO.getInstance().saveMedia(css));
        final String cssUrl = new org.paulsens.trip.action.MediaCommands().getUrl(css);

        assertOk(resource.update(TRIP_ID, CSRF_OK, pageBody(new org.paulsens.trip.api.dto.TripPageOptionsDto(
                "full-width", null, "#336699", Boolean.TRUE, Boolean.FALSE, cssUrl))));
        Assert.assertEquals(existing.getPage().getLayout(), "full-width");
        Assert.assertEquals(existing.getPage().getBackgroundColor(), "#336699");
        Assert.assertEquals(existing.getPage().getShowTitle(), Boolean.TRUE);
        Assert.assertEquals(existing.getPage().getShowNav(), Boolean.FALSE);
        Assert.assertEquals(existing.getPage().getStylesheetUrl(), cssUrl);

        assertError(resource.update(TRIP_ID, CSRF_OK, pageBody(new org.paulsens.trip.api.dto.TripPageOptionsDto(
                "sideways", null, null, null, null, null))), 400, ApiErrors.VALIDATION_FAILED);
        assertError(resource.update(TRIP_ID, CSRF_OK, pageBody(new org.paulsens.trip.api.dto.TripPageOptionsDto(
                null, null, null, null, null, "https://elsewhere.example/x.css"))), 400, ApiErrors.VALIDATION_FAILED);
        Mockito.verify(trips, Mockito.times(1)).saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any());

        tripExists(trip(TRIP_ID, ME));
        final Trip again = trips.getTrip(TRIP_ID);
        again.setOrgId("org-page");
        again.getPage().setLayout("full-width");
        again.getPage().setStylesheetUrl(cssUrl);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(again), ArgumentMatchers.any())).thenReturn(true);
        assertOk(resource.update(TRIP_ID, CSRF_OK, pageBody(new org.paulsens.trip.api.dto.TripPageOptionsDto(
                "", null, null, null, null, ""))));
        Assert.assertNull(again.getPage().getLayout(), "\"\" clears back to the organization's");
        Assert.assertNull(again.getPage().getStylesheetUrl());

        // Read-back: a manager sees the options, a plain member does not.
        again.getPage().setLayout("classic");
        final TripDto admin = (TripDto) resource.get(TRIP_ID, null).getEntity();
        Assert.assertEquals(admin.page().layout(), "classic");
        signedInAs(ME);
        final TripDto member = (TripDto) resource(new TripsResource()).get(TRIP_ID, null).getEntity();
        Assert.assertNull(member.page(), "the page options are the managers' business");
        Assert.assertNull(member.withoutEvents().page());
    }

    private static TripDto linksBody(final String flyerUrl, final String externalUrl) {
        return new TripDto(null, null, null, null, false, null, null, null, null, null, null, null, null, null,
                null, flyerUrl, externalUrl, null, null, null, null, null, null, null);
    }

    /**
     * A link that is no web address is a client's mistake, answered 400 with the reason before any save; and only
     * a CHANGED link is judged, so a trip stored under the older free-text rule still accepts other writes.
     */
    @Test
    public void updateRefusesAChangedLinkThatIsNoWebAddressWith400() throws java.io.IOException {
        signedInAsSiteAdmin(ME);
        final Trip existing = trip(TRIP_ID, ME);
        existing.setFlyerUrl("www.example.org/legacy-flyer.pdf");
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);

        assertError(resource.update(TRIP_ID, CSRF_OK, linksBody(null, "javascript:alert(1)")), 400,
                ApiErrors.VALIDATION_FAILED);
        tripExists(existing);
        existing.setNonHostedTripUrl(null);
        assertError(resource.update(TRIP_ID, CSRF_OK, linksBody("javascript:alert(2)", null)), 400,
                ApiErrors.VALIDATION_FAILED);
        Mockito.verify(trips, Mockito.never()).saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any());

        existing.setFlyerUrl("www.example.org/legacy-flyer.pdf");
        assertOk(resource.update(TRIP_ID, CSRF_OK, linksBody("www.example.org/legacy-flyer.pdf", null)));
        assertOk(resource.update(TRIP_ID, CSRF_OK, linksBody(null, "https://example.org/register")));
        Assert.assertEquals(existing.getNonHostedTripUrl(), "https://example.org/register");
    }

    private static TripDto pageBody(final org.paulsens.trip.api.dto.TripPageOptionsDto page) {
        return new TripDto(null, null, null, null, false, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, page);
    }

    /** Events on a write APPEND (null ids only): replacing by id would erase participants/private notes. */
    @Test
    public void updateAppendsNewEventsAndRefusesIds() {
        signedInAsSiteAdmin(ME);
        final Trip existing = trip(TRIP_ID, ME);
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);

        final TripDto append = new TripDto(null, null, null, null, false, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                List.of(new TripEventDto(null, "LODGING", "Hotel Ruža", null,
                        LocalDateTime.now().plusDays(31), null, null, null, null,
                        Map.of("from", "SEA", "carrier", "Alaska"))), null);
        assertOk(resource.update(TRIP_ID, CSRF_OK, append));
        Assert.assertEquals(existing.getTripEvents().size(), 2, "the new event appends after evt-1");
        Assert.assertEquals(existing.getTripEvents().get(1).getDetails().get("carrier"), "Alaska",
                "structured details ride along on an appended event");
        Assert.assertEquals(existing.getTripEvents().get(1).getTitle(), "Hotel Ruža");
        Assert.assertEquals(existing.getTripEvents().get(1).getType(), TripEvent.Type.LODGING);

        final TripDto withId = new TripDto(null, null, null, null, false, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                List.of(new TripEventDto("evt-1", null, "Rewritten", null, null, null, null, null, null, null)), null);
        assertError(resource.update(TRIP_ID, CSRF_OK, withId), 400, ApiErrors.VALIDATION_FAILED);
    }

    /** Null means "not sent": a body without a people list must leave the roster untouched. */
    @Test
    public void anAbsentPeopleListLeavesTheRosterAlone() {
        signedInAsSiteAdmin(ME);
        final Trip existing = trip(TRIP_ID, ME);
        existing.setOrgId("org-1");
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);
        bindMock(org.paulsens.trip.action.OrgCommands.class);

        final TripDto body = new TripDto(null, "Renamed", null, null, false, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertOk(resource.update(TRIP_ID, CSRF_OK, body));

        Assert.assertEquals(existing.getPeople(), List.of(ME),
                "an absent people list must not clear the roster");
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

    @Test
    public void updateEventMovesTheTripsOwnInstance() {
        signedInAsSiteAdmin(ME);
        final Trip existing = trip(TRIP_ID, ME);
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);
        final LocalDateTime start = LocalDateTime.of(2026, 11, 6, 9, 30);
        final TripEventDto body = new TripEventDto(null, "ground", null, "Coach from the airport", start,
                start.plusHours(2), null, null, null, null);

        final Response response = resource.updateEvent(TRIP_ID, "evt-1", CSRF_OK, body);

        assertOk(response);
        final TripEvent event = existing.getTripEvent("evt-1");
        Assert.assertEquals(event.getStart(), start);
        Assert.assertEquals(event.getEnd(), start.plusHours(2));
        Assert.assertEquals(event.getType(), TripEvent.Type.GROUND);
        Assert.assertEquals(event.getNotes(), "Coach from the airport");
        Assert.assertEquals(event.getTitle(), "Outbound", "an absent field is left alone");
        Assert.assertTrue(event.getParticipants().contains(ME), "participants are never touched here");
        Mockito.verify(trips).saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any());
    }

    @Test
    public void updateEventRefusesTheWrongCallerEventAndType() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, ME));
        final TripEventDto body = new TripEventDto(null, null, "Renamed", null, null, null, null, null, null, null);
        assertError(resource.updateEvent(TRIP_ID, "evt-1", CSRF_OK, body), 403, ApiErrors.FORBIDDEN);
        assertError(resource.updateEvent(TRIP_ID, "evt-1", null, body), 403, ApiErrors.CSRF);

        signedInAsSiteAdmin(ME);
        resource = resource(new TripsResource());
        assertError(resource.updateEvent("gone", "evt-1", CSRF_OK, body), 404, ApiErrors.NOT_FOUND);
        assertError(resource.updateEvent(TRIP_ID, "evt-9", CSRF_OK, body), 404, ApiErrors.NOT_FOUND);
        assertError(resource.updateEvent(TRIP_ID, "evt-1", CSRF_OK, null), 400, ApiErrors.BAD_REQUEST);
        final TripEventDto badType = new TripEventDto(null, "teleport", null, null, null, null, null, null, null, null);
        assertError(resource.updateEvent(TRIP_ID, "evt-1", CSRF_OK, badType), 400, ApiErrors.VALIDATION_FAILED);
        Mockito.when(trips.saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(false);
        assertError(resource.updateEvent(TRIP_ID, "evt-1", CSRF_OK, body), 500, ApiErrors.STORE_FAILED);
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

    // ------------------------------------------------------------------ audit 2026-11-11: F6, F7, F22, F23

    private static String orgAdministeredBy(final Person.Id... admins) throws IOException {
        final Organization org = new Organization(null, "Org " + System.nanoTime(), null, null, List.of(admins),
                null, null, 0L);
        Assert.assertTrue(DAO.getInstance().saveOrganization(org));
        return org.getId().getValue();
    }

    private static Person.Id holderOf(final String base, final String scope) {
        final Person.Id who = Person.Id.newInstance();
        grant(base, scope, who);
        return who;
    }

    private static void grant(final String base, final String scope, final Person.Id who) {
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate(base, scope, base).withNewPerson(who), null));
    }

    private TripsResource as(final Person.Id who) {
        signedInAs(who);
        return resource(new TripsResource());
    }

    private static TripDto orgBody(final String orgId) {
        return new TripDto(null, null, null, null, false, null, null, null, null, orgId, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private static String processorOf(final String orgId) throws IOException {
        final PaymentProcessorConfig config = PaymentProcessorConfig.builder()
                .orgId(Organization.Id.from(orgId)).label("Stripe").type(ProcessorType.STRIPE)
                .mode(PaymentProcessorConfig.ProcessorMode.LIVE).enabled(true)
                .publicConfig(Map.of("clientId", "pk_live_x")).createdBy(ME).created(LocalDateTime.now()).build();
        Assert.assertTrue(DAO.getInstance().savePaymentProcessorConfig(config));
        return config.getId().getValue();
    }

    /**
     * F6: a trip manager manages the trip, not which organization owns it. The update used to copy the
     * body's orgId onto the trip for anyone holding tripMgr there, moving the trip (and a roster the caller
     * then picks from the new org) across the tenancy boundary.
     */
    @Test
    public void aTripManagerCannotMoveATripToAnotherOrganization() throws IOException {
        final String home = orgAdministeredBy();
        final String away = orgAdministeredBy();
        final String tripId = UUID.randomUUID().toString();
        final Trip existing = trip(tripId, ME);
        existing.setOrgId(home);
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);
        final TripsResource manager = as(holderOf(PrivilegeCommands.TRIP_MGR, tripId));

        assertError(manager.update(tripId, CSRF_OK, orgBody(away)), 403, ApiErrors.FORBIDDEN);
        assertError(manager.update(tripId, CSRF_OK, orgBody("")), 403, ApiErrors.FORBIDDEN);
        Assert.assertEquals(existing.getOrgId(), home, "the trip stayed where it was");
        Mockito.verify(trips, Mockito.never()).saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any());

        // A body that merely names the org the trip is already in is not a move.
        assertOk(manager.update(tripId, CSRF_OK, orgBody(home)));
        Assert.assertEquals(existing.getOrgId(), home);
    }

    /** F6, the other side: an administrator of BOTH organizations (or a site admin) may move one. */
    @Test
    public void movingATripTakesAnAdministratorOfBothOrganizations() throws IOException {
        final Person.Id ofBoth = Person.Id.newInstance();
        final Person.Id ofHomeOnly = Person.Id.newInstance();
        final String home = orgAdministeredBy(ofBoth, ofHomeOnly);
        final String away = orgAdministeredBy(ofBoth);
        final String tripId = UUID.randomUUID().toString();
        grant(PrivilegeCommands.TRIP_MGR, tripId, ofBoth);
        grant(PrivilegeCommands.TRIP_MGR, tripId, ofHomeOnly);
        final Trip existing = trip(tripId, ME);
        existing.setOrgId(home);
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);

        assertError(as(ofHomeOnly).update(tripId, CSRF_OK, orgBody(away)), 403, ApiErrors.FORBIDDEN);
        Assert.assertEquals(existing.getOrgId(), home);

        assertOk(as(ofBoth).update(tripId, CSRF_OK, orgBody(away)));
        Assert.assertEquals(existing.getOrgId(), away);

        signedInAsSiteAdmin(ME);
        assertOk(resource(new TripsResource()).update(tripId, CSRF_OK, orgBody(home)));
        Assert.assertEquals(existing.getOrgId(), home);
    }

    /** F6: the processor override named the OLD organization's account; it does not follow the trip. */
    @Test
    public void aMovedTripDoesNotKeepItsOldOrganizationsProcessor() throws IOException {
        final String home = orgAdministeredBy();
        final String away = orgAdministeredBy();
        final String tripId = UUID.randomUUID().toString();
        final Trip existing = trip(tripId, ME);
        existing.setOrgId(home);
        existing.getPaymentConfig().setProcessorConfigId(processorOf(home));
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);
        signedInAsSiteAdmin(ME);

        assertError(resource.update(tripId, CSRF_OK, orgBody(away)), 400, ApiErrors.VALIDATION_FAILED);
        Assert.assertEquals(existing.getOrgId(), home);
        // A processor of the NEW organization is validated against the new organization, not the old one.
        final String awaysProcessor = processorOf(away);
        final TripDto moveWithNewProcessor = new TripDto(null, null, null, null, false, null, null, null, null,
                away, null, null, null, null, null, null, null, null, null, null, null, null,
                new TripPaymentConfigDto(awaysProcessor, null, null, null, null, null, null, null, null));
        assertOk(resource.update(tripId, CSRF_OK, moveWithNewProcessor));
        Assert.assertEquals(existing.getOrgId(), away);
        Assert.assertEquals(existing.getPaymentConfig().getProcessorConfigId(), awaysProcessor);
    }

    /**
     * The payment From follows the trip too: a From the old organization could send from is checked against the
     * new one on a move, whether the body sent it or it was stored before, exactly like the processor above.
     */
    @Test
    public void aMovedTripDoesNotKeepAFromItsNewOrganizationMayNotSendFrom() throws IOException {
        final String home = orgAdministeredBy();
        final String away = orgAdministeredBy();
        final String tripId = UUID.randomUUID().toString();
        final Trip existing = trip(tripId, ME);
        existing.setOrgId(home);
        existing.getPaymentConfig().setMailFrom("Home <no-reply@centerforpeacewest.com>");
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);
        signedInAsSiteAdmin(ME);

        assertError(resource.update(tripId, CSRF_OK, orgBody(away)), 400, ApiErrors.VALIDATION_FAILED);
        Assert.assertEquals(existing.getOrgId(), home, "a stored From the new owner may not use blocks the move");
        assertError(resource.update(tripId, CSRF_OK, moveWithFrom(away, existing.getPaymentConfig().getMailFrom())),
                400, ApiErrors.VALIDATION_FAILED);
        Assert.assertEquals(existing.getOrgId(), home, "...and so does echoing it back");

        assertOk(resource.update(tripId, CSRF_OK, moveWithFrom(away, "Away <no-reply@unitetrip.com>")));
        Assert.assertEquals(existing.getOrgId(), away, "replaced in the same body: the move goes through");
        Assert.assertEquals(existing.getPaymentConfig().getMailFrom(), "Away <no-reply@unitetrip.com>");
        assertOk(resource.update(tripId, CSRF_OK, orgBody(home)));
        Assert.assertEquals(existing.getOrgId(), home, "a shared-domain From moves with the trip");
        existing.getPaymentConfig().setMailFrom("Home <no-reply@centerforpeacewest.com>");
        assertOk(resource.update(tripId, CSRF_OK, moveWithFrom(away, "")));
        Assert.assertEquals(existing.getOrgId(), away, "cleared in the same body: the move goes through");
        Assert.assertNull(existing.getPaymentConfig().getMailFrom());
    }

    /**
     * An echoed From is not a payment write (docs/api-contract.md): a client that sends back the From it read,
     * stored when the rule was wider, keeps saving its other edits; a CHANGED From is still checked.
     */
    @Test
    public void anEchoedFromIsNotRecheckedButAChangedOneIs() throws IOException {
        final String orgId = orgAdministeredBy();
        final String tripId = UUID.randomUUID().toString();
        final Trip existing = trip(tripId, ME);
        existing.setOrgId(orgId);
        existing.getPaymentConfig().setMailFrom("Old <no-reply@centerforpeacewest.com>");
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);
        signedInAsSiteAdmin(ME);

        final TripDto echo = new TripDto(null, "Renamed", null, null, false, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                new TripPaymentConfigDto(null, null, null, null, null, " Old <no-reply@centerforpeacewest.com> ",
                        null, null, null));
        assertOk(resource.update(tripId, CSRF_OK, echo));
        Assert.assertEquals(existing.getTitle(), "Renamed", "the title edit is not lost to the stored From");
        assertError(resource.update(tripId, CSRF_OK, paymentBody(new TripPaymentConfigDto(null, null, null, null,
                null, "New <no-reply@centerforpeacewest.com>", null, null, null))), 400, ApiErrors.VALIDATION_FAILED);
        Assert.assertEquals(existing.getPaymentConfig().getMailFrom(), "Old <no-reply@centerforpeacewest.com>");

        // A new trip has nothing stored: its From is always checked.
        Mockito.when(trips.createTrip()).thenReturn(trip("new-from-trip", ME));
        final TripDto create = new TripDto(null, "Rome 2027", null, null, false, null, null, null, null, orgId, null,
                null, null, null, null, null, null, null, null, null, null, null,
                new TripPaymentConfigDto(null, null, null, null, null, "x@centerforpeacewest.com", null, null, null));
        assertError(resource.create(CSRF_OK, create), 400, ApiErrors.VALIDATION_FAILED);
    }

    private static TripDto moveWithFrom(final String orgId, final String mailFrom) {
        return new TripDto(null, null, null, null, false, null, null, null, null, orgId, null, null, null, null,
                null, null, null, null, null, null, null, null,
                new TripPaymentConfigDto(null, null, null, null, null, mailFrom, null, null, null));
    }

    /**
     * F7: the trip editor shows Payment Settings only to the organization's payments admins. The API took
     * them from any Editor Admin: one PUT made every payer pay the fee and copied every confirmation, with
     * the payer's name and amount, to an outside address.
     */
    @Test
    public void paymentSettingsNeedTheOrganizationsPaymentsGrantToWriteAndToRead() throws IOException {
        final String orgId = orgAdministeredBy();
        final String tripId = UUID.randomUUID().toString();
        final Person.Id manager = holderOf(PrivilegeCommands.TRIP_MGR, tripId);
        final Trip existing = trip(tripId, ME);
        existing.setOrgId(orgId);
        existing.getPaymentConfig().setFeesPaidBy(FeesPaidBy.ORGANIZATION);
        existing.getPaymentConfig().setBcc("office@org.example");
        tripExists(existing);
        Mockito.when(trips.saveTrip(ArgumentMatchers.eq(existing), ArgumentMatchers.any())).thenReturn(true);
        final TripDto hijack = paymentBody(new TripPaymentConfigDto(null, "PAYER", null, null, null, null, null,
                "me@example.net", null));

        assertError(as(manager).update(tripId, CSRF_OK, hijack), 403, ApiErrors.FORBIDDEN);
        Assert.assertEquals(existing.getPaymentConfig().getFeesPaidBy(), FeesPaidBy.ORGANIZATION);
        Assert.assertEquals(existing.getPaymentConfig().getBcc(), "office@org.example");
        Mockito.verify(trips, Mockito.never()).saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any());
        Assert.assertNull(((TripDto) as(manager).get(tripId, null).getEntity()).paymentConfig(),
                "nor is it read back to a manager without the grant");

        // An unchanged value is not a payment write: a body echoing the stored settings, or an empty
        // paymentConfig, saves the rest of the trip as before.
        assertOk(as(manager).update(tripId, CSRF_OK, paymentBody(new TripPaymentConfigDto(null, "ORGANIZATION",
                null, null, null, null, null, "office@org.example", Map.of()))));
        assertOk(as(manager).update(tripId, CSRF_OK, paymentBody(new TripPaymentConfigDto(null, null, null, null,
                null, null, null, null, null))));

        // With paymentsAdmin on the trip's organization the same body goes through, and reads back.
        grant(PrivilegeCommands.PAYMENTS_ADMIN, orgId, manager);
        assertOk(as(manager).update(tripId, CSRF_OK, hijack));
        Assert.assertEquals(existing.getPaymentConfig().getFeesPaidBy(), FeesPaidBy.PAYER);
        final TripDto read = (TripDto) as(manager).get(tripId, null).getEntity();
        Assert.assertEquals(read.paymentConfig().bcc(), "me@example.net");
    }

    /** F7 on create: addTrip@org creates the trip, but its payment settings still take the payments grant. */
    @Test
    public void creatingATripDoesNotCarryPaymentSettingsWithoutThePaymentsGrant() throws IOException {
        final String orgId = orgAdministeredBy();
        final Person.Id creator = holderOf(PrivilegeCommands.ADD_TRIP, orgId);
        Mockito.when(trips.createTrip()).thenAnswer(call -> trip(UUID.randomUUID().toString(), ME));
        Mockito.when(trips.saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any())).thenReturn(true);
        final TripDto withBcc = new TripDto(null, "Rome 2027", null, null, false, null, null, null, null, orgId,
                null, null, null, null, null, null, null, null, null, null, null, null,
                new TripPaymentConfigDto(null, null, null, null, null, null, null, "me@example.net", null));
        final TripDto withNothingSet = new TripDto(null, "Rome 2027", null, null, false, null, null, null, null,
                orgId, null, null, null, null, null, null, null, null, null, null, null, null,
                new TripPaymentConfigDto(null, null, null, null, null, null, null, null, null));

        assertError(as(creator).create(CSRF_OK, withBcc), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(trips, Mockito.never()).saveTrip(ArgumentMatchers.any(), ArgumentMatchers.any());
        assertOk(as(creator).create(CSRF_OK, withNothingSet));

        grant(PrivilegeCommands.PAYMENTS_ADMIN, orgId, creator);
        assertOk(as(creator).create(CSRF_OK, withBcc));
    }

    /**
     * F22: acting "for yourself" is always allowed, so by itself it let any signed-in account join the
     * events of, and write notes into, a trip it is not on (each call re-saving the whole trip).
     */
    @Test
    public void aStrangerCannotJoinEventsOrWriteNotesOnATripTheyAreNotOn() {
        signedInAs(ME);
        tripExists(trip(TRIP_ID, OTHER));

        assertError(resource.setParticipation(TRIP_ID, CSRF_OK, null, Map.of("eventIds", List.of("evt-1"))),
                403, ApiErrors.FORBIDDEN);
        assertError(resource.saveNote(TRIP_ID, "evt-1", CSRF_OK, null, Map.of("note", "hello")),
                403, ApiErrors.FORBIDDEN);
        assertError(resource.saveNote(TRIP_ID, "no-such-event", CSRF_OK, null, Map.of("note", "hello")),
                403, ApiErrors.FORBIDDEN);
        Mockito.verify(trips, Mockito.never()).setEventParticipation(ArgumentMatchers.any(),
                ArgumentMatchers.anyList(), ArgumentMatchers.any());
        Mockito.verify(trips, Mockito.never()).saveEventNote(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyString());
    }

    /**
     * F23: the open listing is what the anonymous public sees, and the public never sees who is traveling.
     * A trip the caller cannot read keeps only the caller's own people in {@code people} (what the apps'
     * "am I on the roster" check reads); a trip they are on is unchanged.
     */
    @Test
    public void anOpenListingCarriesOnlyTheCallersOwnPeopleForATripTheyCannotRead() {
        final Person.Id kid = Person.Id.from("trips-kid");
        final Person me = new Person();
        me.setId(ME);
        me.setManagedUsers(List.of(kid));
        Mockito.when(people.getPerson(ME)).thenReturn(me);
        signedInAs(ME);
        Mockito.when(trips.getPublicTrips()).thenReturn(List.of(
                trip("open-strangers", OTHER, Person.Id.from("trips-third")),
                trip("open-kid", OTHER, kid),
                trip("open-mine", ME, OTHER)));
        // The kid's seat makes the second trip readable in production (managesSomeoneOn); the bean is a
        // mock here, so it stays unreadable and shows the narrowing keeping a managed person.

        final List<?> listed = (List<?>) resource.list("open", 50, null).getEntity();

        Assert.assertEquals(((TripDto) listed.get(0)).people(), List.of(), "strangers' ids are not listed");
        Assert.assertEquals(((TripDto) listed.get(1)).people(), List.of(kid.getValue()));
        Assert.assertEquals(((TripDto) listed.get(2)).people(), List.of(ME.getValue(), OTHER.getValue()),
                "a trip I am on still carries its roster");
    }

    @Test
    public void theProducedTypeIsTheTripsMediaType() {
        Assert.assertEquals(new TripsResource().versionedType(), ApiMediaTypes.TRIPS_V1);
    }


    /** Structured details are sent only when the event has some; a free-text event carries no map at all. */
    @Test
    public void eventDetailsAreOnTheWireOnlyWhenPresent() {
        signedInAs(ME);
        final Trip existing = trip(TRIP_ID, ME);
        existing.getTripEvents().get(0).setDetails(new java.util.LinkedHashMap<>(
                Map.of("from", "PDX", "to", "EWR", "flightNumber", "UA 1234")));
        final TripEvent plain = event("evt-2");
        existing.getTripEvents().add(plain);
        tripExists(existing);

        final Response response = resource.events(TRIP_ID, null);
        assertOk(response);
        @SuppressWarnings("unchecked")
        final List<TripEventDto> events = (List<TripEventDto>) response.getEntity();
        Assert.assertEquals(events.get(0).details().get("flightNumber"), "UA 1234");
        Assert.assertNull(events.get(1).details(), "no details means no map, not an empty one");
    }

    @Test
    public void rosterNamesEveryoneWithTheirRolesAndIsMembersOnly() {
        signedInAs(ME);
        final Trip existing = trip(TRIP_ID, ME);
        existing.setDirectorIds(List.of(OTHER));
        existing.setFacilitatorIds(List.of(ME));
        existing.setLocalGuide("Ivana");
        tripExists(existing);
        final PersonCommands people = bean(PersonCommands.class);
        final Person me = new Person();
        me.setId(ME);
        me.setFirst("Ken");
        me.setLast("Paulsen");
        me.setNickname("Kenny");
        final Person other = new Person();
        other.setId(OTHER);
        other.setFirst("Fr. Legacy");
        other.setLast("Director");
        Mockito.when(people.getPerson(ME)).thenReturn(me);
        Mockito.when(people.getPerson(OTHER)).thenReturn(other);
        final org.paulsens.trip.action.ProfilePhotos photos = bindMock(org.paulsens.trip.action.ProfilePhotos.class);
        Mockito.when(photos.hasPhoto(OTHER.getValue())).thenReturn(true);
        Mockito.when(photos.getUrl(OTHER.getValue())).thenReturn("/profile-photos/profilePics/x/1-9.jpg");
        Mockito.when(request.getScheme()).thenReturn("http");
        Mockito.when(request.getServerName()).thenReturn("localhost");
        Mockito.when(request.getServerPort()).thenReturn(8080);

        final Response response = resource.roster(TRIP_ID);
        assertOk(response);
        final org.paulsens.trip.api.dto.TripRosterDto roster =
                (org.paulsens.trip.api.dto.TripRosterDto) response.getEntity();
        Assert.assertEquals(roster.people().size(), 2, "the director who is not on the roster is still listed");
        final org.paulsens.trip.api.dto.TripRosterDto.RosterEntryDto first = roster.people().get(0);
        Assert.assertEquals(first.id(), ME.getValue());
        Assert.assertEquals(first.preferredName(), "Kenny");
        Assert.assertEquals(first.roles(), List.of("MEMBER", "FACILITATOR"));
        Assert.assertNull(first.photoUrl(), "no photo, no URL");
        final org.paulsens.trip.api.dto.TripRosterDto.RosterEntryDto director = roster.people().get(1);
        Assert.assertEquals(director.roles(), List.of("DIRECTOR"));
        Assert.assertEquals(director.photoUrl(), "http://localhost:8080/profile-photos/profilePics/x/1-9.jpg",
                "a context-relative photo path leaves the API absolute");
        Assert.assertEquals(roster.directorIds(), List.of(OTHER.getValue()));
        Assert.assertEquals(roster.localGuide(), "Ivana");

        signedInAs(OTHER);
        final TripsResource asStranger = resource(new TripsResource());
        final Trip closed = trip("trip-2", ME);
        tripExists(closed);
        assertError(asStranger.roster("trip-2"), 403, ApiErrors.FORBIDDEN);
        assertError(asStranger.roster("no-such-trip"), 404, ApiErrors.NOT_FOUND);
    }

    /** A roster id whose person no longer exists is skipped, never an NPE or a blank stranger. */
    @Test
    public void rosterSkipsAnIdWithNoPersonBehindIt() {
        signedInAs(ME);
        final Trip existing = trip(TRIP_ID, ME, OTHER);
        tripExists(existing);
        final PersonCommands people = bean(PersonCommands.class);
        final Person me = new Person();
        me.setId(ME);
        me.setFirst("Ken");
        Mockito.when(people.getPerson(ME)).thenReturn(me);
        Mockito.when(people.getPerson(OTHER)).thenReturn(new Person());
        bindMock(org.paulsens.trip.action.ProfilePhotos.class);

        final Response response = resource.roster(TRIP_ID);
        assertOk(response);
        Assert.assertEquals(((org.paulsens.trip.api.dto.TripRosterDto) response.getEntity()).people().size(), 1);
    }

    /** ME and OTHER on one trip; OTHER shares an email and is on {@link #othersEvent}. */
    private Trip sharingTrip(final boolean sharing, final Person other) {
        final Trip existing = trip(TRIP_ID, ME, OTHER);
        existing.getTripEvents().add(othersEvent("evt-2"));
        existing.setItinerarySharing(sharing);
        tripExists(existing);
        final Person me = new Person();
        me.setId(ME);
        other.setId(OTHER);
        other.setEmail("other@example.com");
        Mockito.when(people.getPerson(ME)).thenReturn(me);
        Mockito.when(people.getPerson(OTHER)).thenReturn(other);
        bindMock(org.paulsens.trip.action.ProfilePhotos.class);
        return existing;
    }

    private static org.paulsens.trip.api.dto.TripRosterDto.RosterEntryDto entry(final Response response,
            final Person.Id id) {
        return ((org.paulsens.trip.api.dto.TripRosterDto) response.getEntity()).people().stream()
                .filter(e -> e.id().equals(id.getValue()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    public void rosterTellsAFellowTravelerWhatTheyMaySeeOfEachPerson() {
        signedInAs(ME);
        sharingTrip(true, new Person());
        final Response response = resource.roster(TRIP_ID);
        assertOk(response);
        Assert.assertEquals(entry(response, ME).itinerary(), "FULL", "your own itinerary is always the full one");
        final var other = entry(response, OTHER);
        Assert.assertEquals(other.itinerary(), "SHARED");
        Assert.assertEquals(other.contact().email(), "other@example.com", "a shared email reaches a co-traveler");
        Assert.assertNull(other.contact().cell(), "a field with no value is absent, not blank");
        Assert.assertNull(other.contact().masked(), "nothing is masked for a fellow traveler");
    }

    @Test
    public void rosterOffersNothingForAPersonWhoSharesNothing() {
        signedInAs(ME);
        final Person other = new Person();
        other.getPrivacy().setEmail(org.paulsens.trip.model.PrivacySettings.Visibility.PRIVATE);
        other.getPrivacy().setItineraryShared(false);
        sharingTrip(true, other);
        final var entry = entry(resource.roster(TRIP_ID), OTHER);
        Assert.assertNull(entry.itinerary(), "an opted-out person offers no itinerary");
        Assert.assertNull(entry.contact(), "no shared contact field means no contact block at all");
    }

    @Test
    public void rosterOffersNoItineraryOnATripThatDoesNotShareThem() {
        signedInAs(ME);
        sharingTrip(false, new Person());
        Assert.assertNull(entry(resource.roster(TRIP_ID), OTHER).itinerary());
    }

    @Test
    public void anAdminSeesAPrivateFieldMaskedAndTheFullItinerary() {
        signedInAsSiteAdmin(ME);
        final Person other = new Person();
        other.getPrivacy().setEmail(org.paulsens.trip.model.PrivacySettings.Visibility.PRIVATE);
        other.getPrivacy().setItineraryShared(false);
        sharingTrip(false, other);
        final var entry = entry(resource.roster(TRIP_ID), OTHER);
        Assert.assertEquals(entry.itinerary(), "FULL", "a site admin's view ignores both switches");
        Assert.assertEquals(entry.contact().email(), "other@example.com");
        Assert.assertEquals(entry.contact().masked(), List.of("email"), "the client hides it behind a tap");
    }

    @Test
    public void aSharedItineraryCarriesTheTripsEventsAndNoPersonalField() {
        signedInAs(ME);
        sharingTrip(true, new Person());
        final Response response = resource.sharedItinerary(TRIP_ID, OTHER.getValue());
        assertOk(response);
        final var dto = (org.paulsens.trip.api.dto.SharedItineraryDto) response.getEntity();
        Assert.assertEquals(dto.personId(), OTHER.getValue());
        Assert.assertEquals(dto.events().stream().map(TripEventDto::id).toList(), List.of("evt-2"),
                "only the events the person is on");
        Assert.assertNull(dto.events().get(0).privNote(), "OTHER's private note never leaves");
        Assert.assertNull(dto.events().get(0).participating(), "no viewer-relative flag");
    }

    @Test
    public void aSharedItineraryIsRefusedWhenNotShared() {
        signedInAs(ME);
        final Person other = new Person();
        other.getPrivacy().setItineraryShared(false);
        sharingTrip(true, other);
        assertError(resource.sharedItinerary(TRIP_ID, OTHER.getValue()), 403, ApiErrors.ITINERARY_NOT_SHARED);
        assertError(resource.sharedItinerary("no-such-trip", OTHER.getValue()), 404, ApiErrors.NOT_FOUND);
        Mockito.when(people.getPerson(Person.Id.from("nobody"))).thenReturn(new Person());
        assertError(resource.sharedItinerary(TRIP_ID, "nobody"), 404, ApiErrors.NOT_FOUND);
    }

    @Test
    public void aTripCreatedOverRestDefaultsItsSharingFromItsKind() {
        final Trip trip = Trip.builder().build();
        TripCommands.stampCreationDefaults(trip);
        Assert.assertTrue(trip.getItinerarySharing(), "a new trip shares itineraries");
        final Trip conference = Trip.builder().kind(org.paulsens.trip.model.TripKind.CONFERENCE).build();
        TripCommands.stampCreationDefaults(conference);
        Assert.assertFalse(conference.getItinerarySharing(), "a new conference does not");
        Assert.assertFalse(Trip.builder().build().getItinerarySharing(), "an existing (unset) trip reads as off");
    }

    // ------------------------------------------------------------------ pictures and place (issue 154)

    private TripPictureCommands picturesAtLocalhost() {
        Mockito.when(request.getScheme()).thenReturn("http");
        Mockito.when(request.getServerName()).thenReturn("localhost");
        Mockito.when(request.getServerPort()).thenReturn(8080);
        Mockito.when(request.getContextPath()).thenReturn("");
        final TripPictureCommands pictures = bindMock(TripPictureCommands.class);
        Mockito.when(pictures.url(ArgumentMatchers.any(TripPicture.class), ArgumentMatchers.anyInt()))
                .thenAnswer(call -> "/trip-pictures/" + call.<TripPicture>getArgument(0).keyAt(call.getArgument(1)));
        Mockito.when(pictures.url(ArgumentMatchers.any(TripPicture.class)))
                .thenAnswer(call -> "/trip-pictures/" + call.<TripPicture>getArgument(0).keyAt(1920));
        return pictures;
    }

    @Test
    public void tripsCarryTheirPicturesWithAbsoluteUrlsAndTheirPlace() {
        signedInAs(ME);
        picturesAtLocalhost();
        final Trip trip = trip(TRIP_ID, ME);
        trip.getPictures().add(new TripPicture("tripPictures/trip-1/5", "Cover", 1920, 1280, "#112233"));
        trip.getPictures().add(new TripPicture("tripPictures/trip-1/6", null, 960, 640, "#445566"));
        trip.setDestination("Medjugorje, Bosnia and Herzegovina");
        trip.setCountry("BA");
        trip.setTags(List.of("retreat"));
        tripExists(trip);
        Mockito.when(trips.getTripsForUser(ME)).thenReturn(List.of(trip));

        final TripDto one = (TripDto) resource.get(TRIP_ID, null).getEntity();
        Assert.assertEquals(one.pictures().size(), 2, "Cover first, then the gallery");
        final TripPictureDto cover = one.pictures().getFirst();
        Assert.assertEquals(cover.key(), "tripPictures/trip-1/5");
        Assert.assertEquals(cover.url(), "http://localhost:8080/trip-pictures/tripPictures/trip-1/5-1920.jpg");
        Assert.assertEquals(cover.url480(), "http://localhost:8080/trip-pictures/tripPictures/trip-1/5-480.jpg");
        Assert.assertEquals(cover.url960(), "http://localhost:8080/trip-pictures/tripPictures/trip-1/5-960.jpg");
        Assert.assertEquals(cover.alt(), "Cover");
        Assert.assertEquals(cover.width(), 1920);
        Assert.assertEquals(cover.height(), 1280);
        Assert.assertEquals(cover.placeholder(), "#112233");
        Assert.assertEquals(one.destination(), "Medjugorje, Bosnia and Herzegovina");
        Assert.assertEquals(one.country(), "BA");
        Assert.assertEquals(one.tags(), List.of("retreat"));
        Assert.assertEquals(one.redactedFor(false, false).pictures(), one.pictures(), "Public by URL: kept");
        Assert.assertEquals(one.withPeople(List.of()).tags(), one.tags());

        final TripDto row = (TripDto) ((List<?>) resource.list("mine", 50, null).getEntity()).getFirst();
        Assert.assertEquals(row.pictures(), one.pictures(), "A listing carries them too");
        Assert.assertEquals(row.withoutEvents().country(), "BA");

        trip.getPictures().clear();
        final TripDto none = (TripDto) resource(new TripsResource()).get(TRIP_ID, null).getEntity();
        Assert.assertEquals(none.pictures(), List.of(), "None is an empty list, not an absent field");
    }

    private static java.io.InputStream bodyOf(final byte[] bytes) {
        return new java.io.ByteArrayInputStream(bytes);
    }

    /** A request body that fails the test if anything reads it. */
    private static java.io.InputStream unreadBody() {
        return new java.io.InputStream() {
            @Override
            public int read() {
                throw new AssertionError("The upload body was read before the caller was authorized");
            }
        };
    }

    private static TripPictureCommands.PictureResult result(final TripPictureCommands.PictureResult.Status status) {
        return new TripPictureCommands.PictureResult(status, status.name(), null, null);
    }

    @Test
    public void addPictureWrapsTheBeanAndMapsItsRefusals() {
        signedInAs(ME);
        final TripPictureCommands pictures = picturesAtLocalhost();
        final byte[] bytes = org.paulsens.trip.media.PhotoFixtures.jpeg(60, 40);
        assertError(resource.addPicture(TRIP_ID, null, null, null, null, null, null, null, unreadBody()), 403,
                ApiErrors.CSRF);

        // An unknown trip and a caller who may not edit it are answered before a byte of the body is read.
        assertError(resource.addPicture("no-such-trip", CSRF_OK, null, null, null, null, null, null, unreadBody()),
                404, ApiErrors.NOT_FOUND);
        final Trip trip = trip(TRIP_ID, ME);
        tripExists(trip);
        assertError(resource.addPicture(TRIP_ID, CSRF_OK, null, null, null, null, null, null, unreadBody()), 403,
                ApiErrors.FORBIDDEN);
        Mockito.verify(pictures, Mockito.never()).add(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.when(pictures.mayEdit(ArgumentMatchers.any(Caller.class), ArgumentMatchers.eq(trip)))
                .thenReturn(true);

        assertError(resource.addPicture(TRIP_ID, CSRF_OK, null, null, 1, 2, null, null, bodyOf(bytes)), 400,
                ApiErrors.BAD_REQUEST);
        assertError(resource.addPicture(TRIP_ID, CSRF_OK, 17L * 1024 * 1024, null, null, null, null, null,
                bodyOf(bytes)), 413, ApiErrors.PAYLOAD_TOO_LARGE);
        assertError(resource.addPicture(TRIP_ID, CSRF_OK, null, null, null, null, null, null, bodyOf(new byte[0])),
                400, ApiErrors.VALIDATION_FAILED);

        final TripPicture added = new TripPicture("tripPictures/trip-1/9", "Hill", 900, 600, "#000000");
        Mockito.when(pictures.add(ArgumentMatchers.any(), ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(bytes),
                ArgumentMatchers.eq(new org.paulsens.trip.media.PhotoProcessor.CropRect(1, 2, 30, 20)),
                ArgumentMatchers.eq("Hill")))
                .thenReturn(new TripPictureCommands.PictureResult(TripPictureCommands.PictureResult.Status.OK,
                        "Cover picture added.", added, "Low resolution"));
        final Response ok = resource.addPicture(TRIP_ID, CSRF_OK, (long) bytes.length, "Hill", 1, 2, 30, 20,
                bodyOf(bytes));
        assertOk(ok);
        final Map<?, ?> body = (Map<?, ?>) ok.getEntity();
        Assert.assertEquals(((TripPictureDto) body.get("picture")).url480(),
                "http://localhost:8080/trip-pictures/tripPictures/trip-1/9-480.jpg");
        Assert.assertEquals(body.get("warning"), "Low resolution");

        Mockito.when(pictures.add(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(result(TripPictureCommands.PictureResult.Status.NOT_FOUND))
                .thenReturn(result(TripPictureCommands.PictureResult.Status.FORBIDDEN))
                .thenReturn(result(TripPictureCommands.PictureResult.Status.LIMIT))
                .thenReturn(result(TripPictureCommands.PictureResult.Status.INVALID))
                .thenReturn(result(TripPictureCommands.PictureResult.Status.FAILED));
        assertError(resource.addPicture(TRIP_ID, CSRF_OK, null, null, null, null, null, null, bodyOf(bytes)), 404,
                ApiErrors.NOT_FOUND);
        assertError(resource.addPicture(TRIP_ID, CSRF_OK, null, null, null, null, null, null, bodyOf(bytes)), 403,
                ApiErrors.FORBIDDEN);
        assertError(resource.addPicture(TRIP_ID, CSRF_OK, null, null, null, null, null, null, bodyOf(bytes)), 409,
                ApiErrors.CONFLICT);
        assertError(resource.addPicture(TRIP_ID, CSRF_OK, null, null, null, null, null, null, bodyOf(bytes)), 400,
                ApiErrors.VALIDATION_FAILED);
        assertError(resource.addPicture(TRIP_ID, CSRF_OK, null, null, null, null, null, null, bodyOf(bytes)), 500,
                ApiErrors.STORE_FAILED);
    }

    @Test
    public void removeAndChangePictureWrapTheBean() {
        signedInAs(ME);
        final TripPictureCommands pictures = picturesAtLocalhost();
        final String key = "tripPictures/trip-1/9";
        final TripPicture pic = new TripPicture(key, null, 1920, 1280, "#000000");
        final TripPictureCommands.PictureResult ok = new TripPictureCommands.PictureResult(
                TripPictureCommands.PictureResult.Status.OK, "done", pic, null);

        assertError(resource.removePicture(TRIP_ID, key, null), 403, ApiErrors.CSRF);
        Mockito.when(pictures.remove(ArgumentMatchers.any(), ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(key)))
                .thenReturn(ok, result(TripPictureCommands.PictureResult.Status.NOT_FOUND));
        assertOk(resource.removePicture(TRIP_ID, key, CSRF_OK));
        assertError(resource.removePicture(TRIP_ID, key, CSRF_OK), 404, ApiErrors.NOT_FOUND);

        assertError(resource.changePicture(TRIP_ID, null, Map.of("key", key)), 403, ApiErrors.CSRF);
        assertError(resource.changePicture(TRIP_ID, CSRF_OK, null), 400, ApiErrors.VALIDATION_FAILED);
        assertError(resource.changePicture(TRIP_ID, CSRF_OK, Map.of("key", key)), 400, ApiErrors.VALIDATION_FAILED);
        assertError(resource.changePicture(TRIP_ID, CSRF_OK, Map.of("key", key, "position", "first")), 400,
                ApiErrors.VALIDATION_FAILED);

        final Trip trip = trip(TRIP_ID, ME);
        trip.getPictures().add(pic);
        tripExists(trip);
        Mockito.when(pictures.describe(ArgumentMatchers.any(), ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(key),
                ArgumentMatchers.any())).thenReturn(ok);
        Mockito.when(pictures.move(ArgumentMatchers.any(), ArgumentMatchers.eq(TRIP_ID), ArgumentMatchers.eq(key),
                ArgumentMatchers.eq(0))).thenReturn(ok);
        final java.util.Map<String, Object> both = new java.util.HashMap<>();
        both.put("key", key);
        both.put("alt", null);
        both.put("position", 0);
        final Response changed = resource.changePicture(TRIP_ID, CSRF_OK, both);
        assertOk(changed);
        Assert.assertEquals(((List<?>) changed.getEntity()).size(), 1, "Answers the pictures in their new order");
        Mockito.verify(pictures).describe(ArgumentMatchers.any(), ArgumentMatchers.eq(TRIP_ID),
                ArgumentMatchers.eq(key), ArgumentMatchers.isNull());
        assertOk(resource.changePicture(TRIP_ID, CSRF_OK, Map.of("key", key, "alt", "Text")));

        Mockito.when(pictures.describe(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq("Refused"))).thenReturn(result(TripPictureCommands.PictureResult.Status.FORBIDDEN));
        assertError(resource.changePicture(TRIP_ID, CSRF_OK, Map.of("key", key, "alt", "Refused", "position", 0)),
                403, ApiErrors.FORBIDDEN);
        Mockito.verify(pictures, Mockito.times(1)).move(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyInt());

        Mockito.when(trips.getTrip(TRIP_ID)).thenReturn(null);
        assertOk(resource.changePicture(TRIP_ID, CSRF_OK, Map.of("key", key, "position", 0)));
    }
}
