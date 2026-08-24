package org.paulsens.trip.api;

import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.paulsens.trip.action.AuditCommands;
import org.paulsens.trip.action.PersonCommands;
import org.paulsens.trip.action.PrivilegeCommands;
import org.paulsens.trip.action.RegistrationCommands;
import org.paulsens.trip.action.TripCommands;
import org.paulsens.trip.api.dto.RegistrationDto;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.Trip;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * {@link RegistrationsResource}.
 *
 * <p>Two behaviours here are load-bearing. A traveller may edit their answers but never their STATUS -- that
 * approval belongs to a human on staff. And the wire status parser must accept what a GET produced, or the most
 * ordinary client behaviour -- GET, edit one field, PUT it back -- silently wipes the status.
 */
public class RegistrationsResourceTest extends ResourceTestSupport {

    private static final String TRIP_ID = "trip-reg";
    private static final Person.Id ME = Person.Id.from("reg-me");
    private static final Person.Id OTHER = Person.Id.from("reg-other");

    private RegistrationCommands registrations;
    private AuditCommands audit;
    private TripCommands trips;
    private PersonCommands people;
    private RegistrationsResource resource;

    @BeforeMethod
    public void bindBeans() {
        registrations = bindMock(RegistrationCommands.class);
        audit = bindMock(AuditCommands.class);
        people = bindMock(PersonCommands.class);
        trips = bindMock(TripCommands.class);
        // The trip and every person exist by default; tests about the 404s stub a specific miss, shaped the way
        // the real get* beans miss -- a blank record with a minted id, never null.
        Mockito.when(trips.getTrip(TRIP_ID)).thenReturn(Trip.builder().id(TRIP_ID).build());
        Mockito.when(people.getPerson(ArgumentMatchers.any(Person.Id.class)))
                .thenAnswer(RegistrationsResourceTest::existingPerson);
        resource = resource(new RegistrationsResource());
    }

    /** Default stub: whoever is asked for exists, carrying exactly the id that was asked for. */
    private static Person existingPerson(final InvocationOnMock invocation) {
        final Person person = new Person();
        person.setId(invocation.getArgument(0));
        return person;
    }

    /** The id the real getTrip answers a miss with: freshly minted, never the one that was asked for. */
    private void tripIsMissing(final String tripId) {
        Mockito.when(trips.getTrip(tripId)).thenReturn(Trip.builder().build());
    }

    /** The real getPerson misses the same way: a blank Person whose constructor minted a fresh id. */
    private void personIsMissing(final Person.Id id) {
        Mockito.when(people.getPerson(id)).thenReturn(new Person());
    }

    private static Registration registration(final Person.Id who, final Registration.Status status) {
        return new Registration(TRIP_ID, who, null, status, Map.of("diet", "vegetarian"));
    }

    @Test
    public void theRosterIsStaffOnly() {
        signedInAs(ME);

        assertError(resource.list(TRIP_ID), 403, ApiErrors.FORBIDDEN);
        assertError(resource.pendingCount(TRIP_ID), 403, ApiErrors.FORBIDDEN);
        Mockito.verifyNoInteractions(registrations);
    }

    /** registrationAdmin@trip is trip staff: the page's tab privilege and the API agree (2026-08-24). */
    @Test
    public void aRegistrationAdminIsTripStaff() {
        // A UUID trip id: trip-scoped privilege rows anchor their scope parse on a canonical-UUID suffix.
        final String tripId = java.util.UUID.randomUUID().toString();
        Mockito.when(trips.getTrip(tripId)).thenReturn(Trip.builder().id(tripId).build());
        Mockito.when(registrations.getRegistrations(tripId))
                .thenReturn(List.of(new Registration(tripId, OTHER, null,
                        Registration.Status.PENDING, Map.of())));
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.createPrivilege(
                "registrationAdmin", "Works the registrations page", tripId, List.of(ME)), null));

        signedInAs(ME);
        assertOk(resource.list(tripId));
    }

    /** The roster strips answers: a list endpoint is where a hundred people's medical notes leak at once. */
    @Test
    public void theRosterCarriesNoAnswers() {
        signedInAsSiteAdmin(ME);
        Mockito.when(registrations.getRegistrations(TRIP_ID))
                .thenReturn(List.of(registration(OTHER, Registration.Status.CONFIRMED)));

        final Response response = resource.list(TRIP_ID);

        assertOk(response);
        final RegistrationDto dto = (RegistrationDto) ((List<?>) response.getEntity()).get(0);
        Assert.assertNull(dto.options(), "Roster entries must not carry free-text answers");
        Assert.assertEquals(dto.status(), Registration.Status.CONFIRMED.name());
    }

    @Test
    public void pendingCountAnswersTheNumber() {
        signedInAsSiteAdmin(ME);
        Mockito.when(registrations.getNumPending(TRIP_ID)).thenReturn(4);

        final Response response = resource.pendingCount(TRIP_ID);

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("pending"), 4);
    }

    @Test
    public void aTravellerReadsTheirOwnRegistrationAnswersIncluded() {
        signedInAs(ME);
        Mockito.when(registrations.getRegistration(TRIP_ID, ME))
                .thenReturn(registration(ME, Registration.Status.PENDING));

        final Response response = resource.get(TRIP_ID, ME.getValue());

        assertOk(response);
        final RegistrationDto dto = (RegistrationDto) response.getEntity();
        Assert.assertEquals(dto.options(), Map.of("diet", "vegetarian"));
    }

    /** "Not registered" is a state a client needs, not a missing resource: 200, never 404. */
    @Test
    public void anUnregisteredPersonIsAStateNotA404() {
        signedInAs(ME);
        Mockito.when(registrations.getRegistration(TRIP_ID, ME)).thenReturn(new Registration(TRIP_ID, ME));

        final Response response = resource.get(TRIP_ID, ME.getValue());

        assertOk(response);
        Assert.assertEquals(((RegistrationDto) response.getEntity()).status(),
                Registration.Status.NOT_REGISTERED.name());
    }

    @Test
    public void aTravellerCannotReadSomebodyElsesRegistration() {
        signedInAs(ME);

        assertError(resource.get(TRIP_ID, OTHER.getValue()), 403, ApiErrors.FORBIDDEN);
        assertError(resource.room(TRIP_ID, OTHER.getValue()), 403, ApiErrors.FORBIDDEN);
    }

    @Test
    public void savingNeedsTheCsrfHeader() {
        signedInAs(ME);

        assertError(resource.save(TRIP_ID, ME.getValue(), null, null), 403, ApiErrors.CSRF);
    }

    /** The rule the workflow exists for: nobody approves their own registration. */
    @Test
    public void aTravellerMayEditAnswersButNeverStatus() {
        signedInAs(ME);
        final Registration existing = registration(ME, Registration.Status.PENDING);
        Mockito.when(registrations.getRegistration(TRIP_ID, ME)).thenReturn(existing);
        Mockito.when(registrations.saveRegistration(ArgumentMatchers.any())).thenReturn(true);

        // Status in the body, no staff role: refused outright.
        assertError(resource.save(TRIP_ID, ME.getValue(), CSRF_OK,
                new RegistrationDto(null, null, null, "CONFIRMED", null)), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(registrations, Mockito.never()).saveRegistration(ArgumentMatchers.any());

        // Answers alone: accepted, and the stored status survives.
        assertOk(resource.save(TRIP_ID, ME.getValue(), CSRF_OK,
                new RegistrationDto(null, null, null, null, Map.of("diet", "vegan"))));
        final ArgumentCaptor<Registration> saved = ArgumentCaptor.forClass(Registration.class);
        Mockito.verify(registrations).saveRegistration(saved.capture());
        Assert.assertEquals(saved.getValue().getStatus(), Registration.Status.PENDING);
        Assert.assertEquals(saved.getValue().getOptions(), Map.of("diet", "vegan"));
    }

    /**
     * The GET->PUT round trip. The wire carries both the enum name and the display form; each must parse back,
     * or an unchanged PUT wipes the status it is echoing.
     */
    @Test
    public void statusParsingAcceptsBothTheEnumNameAndTheDisplayForm() {
        signedInAsSiteAdmin(ME);
        Mockito.when(registrations.getRegistration(TRIP_ID, ME))
                .thenReturn(registration(ME, Registration.Status.PENDING));
        Mockito.when(registrations.saveRegistration(ArgumentMatchers.any())).thenReturn(true);
        Mockito.when(bean(TripCommands.class).getTrip(TRIP_ID)).thenReturn(Trip.builder().id(TRIP_ID).build());

        assertOk(resource.save(TRIP_ID, ME.getValue(), CSRF_OK,
                new RegistrationDto(null, null, null, "CONFIRMED", null)));
        assertOk(resource.save(TRIP_ID, ME.getValue(), CSRF_OK,
                new RegistrationDto(null, null, null, "Confirmed", null)));

        final ArgumentCaptor<Registration> saved = ArgumentCaptor.forClass(Registration.class);
        Mockito.verify(registrations, Mockito.times(2)).saveRegistration(saved.capture());
        saved.getAllValues().forEach(reg ->
                Assert.assertEquals(reg.getStatus(), Registration.Status.CONFIRMED));
    }

    /** A status change is audited; an answers-only edit is not. */
    @Test
    public void onlyAStatusChangeIsAudited() {
        signedInAsSiteAdmin(ME);
        Mockito.when(registrations.getRegistration(TRIP_ID, ME))
                .thenReturn(registration(ME, Registration.Status.PENDING));
        Mockito.when(registrations.saveRegistration(ArgumentMatchers.any())).thenReturn(true);
        Mockito.when(bean(TripCommands.class).getTrip(TRIP_ID)).thenReturn(Trip.builder().id(TRIP_ID).build());

        resource.save(TRIP_ID, ME.getValue(), CSRF_OK,
                new RegistrationDto(null, null, null, null, Map.of("diet", "vegan")));
        Mockito.verifyNoInteractions(audit);

        resource.save(TRIP_ID, ME.getValue(), CSRF_OK,
                new RegistrationDto(null, null, null, "CONFIRMED", null));
        Mockito.verify(audit).registered(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void removingARegistrationAuditsTheRemoval() {
        signedInAsSiteAdmin(ME);
        Mockito.when(registrations.getRegistration(TRIP_ID, ME))
                .thenReturn(registration(ME, Registration.Status.CONFIRMED));
        Mockito.when(registrations.saveRegistration(ArgumentMatchers.any())).thenReturn(true);
        Mockito.when(bean(TripCommands.class).getTrip(TRIP_ID)).thenReturn(Trip.builder().id(TRIP_ID).build());

        resource.save(TRIP_ID, ME.getValue(), CSRF_OK,
                new RegistrationDto(null, null, null, "NOT_REGISTERED", null));

        Mockito.verify(audit).registrationRemoved(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
    }

    @Test
    public void aFailedSaveIsReportedAndNotAudited() {
        signedInAsSiteAdmin(ME);
        Mockito.when(registrations.getRegistration(TRIP_ID, ME))
                .thenReturn(registration(ME, Registration.Status.PENDING));
        Mockito.when(registrations.saveRegistration(ArgumentMatchers.any())).thenReturn(false);

        assertError(resource.save(TRIP_ID, ME.getValue(), CSRF_OK, null), 500, ApiErrors.STORE_FAILED);
        Mockito.verifyNoInteractions(audit);
    }

    @Test
    public void roomReadsAnswerTheAssignmentOrNull() {
        signedInAs(ME);
        Mockito.when(registrations.getRoomPDV(TRIP_ID, ME)).thenReturn(null);

        final Response response = resource.room(TRIP_ID, ME.getValue());

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertNull(body.get("room"));
    }

    @Test
    public void assigningARoomIsStaffOnly() {
        signedInAs(ME);

        assertError(resource.saveRoom(TRIP_ID, ME.getValue(), CSRF_OK, Map.of("room", "101")),
                403, ApiErrors.FORBIDDEN);
        Mockito.verifyNoInteractions(registrations);
    }

    @Test
    public void assigningARoomEchoesWhatWasSavedNotAReRead() {
        signedInAsSiteAdmin(ME);
        Mockito.when(registrations.saveRoom(TRIP_ID, ME, "101")).thenReturn(true);

        final Response response = resource.saveRoom(TRIP_ID, ME.getValue(), CSRF_OK, Map.of("room", "101"));

        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("room"), "101");
        // No re-read: the cache can still serve the pre-save value in production.
        Mockito.verify(registrations, Mockito.never()).getRoomPDV(ArgumentMatchers.anyString(),
                ArgumentMatchers.any());
    }

    @Test
    public void aFailedRoomSaveIsReported() {
        signedInAsSiteAdmin(ME);
        Mockito.when(registrations.saveRoom(ArgumentMatchers.anyString(), ArgumentMatchers.any(),
                ArgumentMatchers.anyString())).thenReturn(false);

        assertError(resource.saveRoom(TRIP_ID, ME.getValue(), CSRF_OK, null), 500, ApiErrors.STORE_FAILED);
    }

    /**
     * getRegistration invents a transient record keyed to whatever tripId was asked for, and getTrip answers a
     * miss with a blank trip whose builder mints a random id. Together they meant a nonexistent trip looked
     * registrable: a GET showed "not registered" for a trip that isn't real, and a PUT saved a junk registration
     * row under a key nobody ever created.
     */
    @Test
    public void aNonexistentTripIs404NotAnInventedRegistration() {
        signedInAs(ME);
        tripIsMissing("gone");

        assertThrown(() -> resource.get("gone", ME.getValue()), 404, ApiErrors.NOT_FOUND);
        Mockito.verifyNoInteractions(registrations);
    }

    @Test
    public void aPutToANonexistentTripSavesNoJunkRow() {
        signedInAsSiteAdmin(ME);
        tripIsMissing("gone");

        assertThrown(() -> resource.save("gone", ME.getValue(), CSRF_OK,
                new RegistrationDto(null, null, null, "CONFIRMED", null)), 404, ApiErrors.NOT_FOUND);
        Mockito.verify(registrations, Mockito.never()).saveRegistration(ArgumentMatchers.any());
        Mockito.verifyNoInteractions(audit);
    }

    /** getRoomPDV CREATES AND SAVES a blank record on a miss, so even the room READ must refuse first. */
    @Test
    public void aRoomReadOnANonexistentTripWritesNothing() {
        // Site admin, so the saveRoom half reaches the trip check rather than being refused as non-staff.
        signedInAsSiteAdmin(ME);
        tripIsMissing("gone");

        assertThrown(() -> resource.room("gone", ME.getValue()), 404, ApiErrors.NOT_FOUND);
        assertThrown(() -> resource.saveRoom("gone", ME.getValue(), CSRF_OK, Map.of("room", "101")),
                404, ApiErrors.NOT_FOUND);
        Mockito.verifyNoInteractions(registrations);
    }

    /**
     * The personId half of the same trap. The tripId guard alone still let trip staff write junk rows one key
     * over: getRoomPDV saves a blank person_data record on a miss (even on the GET), and a staff PUT with a
     * mistyped person id saved a registration under a person nobody ever created.
     */
    @Test
    public void aNonexistentPersonIs404AndWritesNothing() {
        signedInAsSiteAdmin(ME);
        final Person.Id nobody = Person.Id.from("reg-nobody");
        personIsMissing(nobody);

        assertThrown(() -> resource.get(TRIP_ID, nobody.getValue()), 404, ApiErrors.NOT_FOUND);
        assertThrown(() -> resource.save(TRIP_ID, nobody.getValue(), CSRF_OK,
                new RegistrationDto(null, null, null, "CONFIRMED", null)), 404, ApiErrors.NOT_FOUND);
        assertThrown(() -> resource.room(TRIP_ID, nobody.getValue()), 404, ApiErrors.NOT_FOUND);
        assertThrown(() -> resource.saveRoom(TRIP_ID, nobody.getValue(), CSRF_OK, Map.of("room", "101")),
                404, ApiErrors.NOT_FOUND);
        Mockito.verifyNoInteractions(registrations);
        Mockito.verifyNoInteractions(audit);
    }

    @Test
    public void theProducedTypeIsTheRegistrationsMediaType() {
        Assert.assertEquals(new RegistrationsResource().versionedType(), ApiMediaTypes.REGISTRATIONS_V1);
    }
}
