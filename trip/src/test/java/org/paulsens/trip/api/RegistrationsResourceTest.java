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

    /** A PUT body carrying only a status and/or answers, the two fields a client writes. */
    private static RegistrationDto body(final String status, final Map<String, String> options) {
        return new RegistrationDto(null, null, null, status, options, null, null, null, null, null, null, null);
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
                body("CONFIRMED", null)), 403, ApiErrors.FORBIDDEN);
        Mockito.verify(registrations, Mockito.never()).saveRegistration(ArgumentMatchers.any());

        // Answers alone: accepted, and the stored status survives.
        assertOk(resource.save(TRIP_ID, ME.getValue(), CSRF_OK,
                body(null, Map.of("diet", "vegan"))));
        final ArgumentCaptor<Registration> saved = ArgumentCaptor.forClass(Registration.class);
        Mockito.verify(registrations).saveRegistration(saved.capture());
        Assert.assertEquals(saved.getValue().getStatus(), Registration.Status.PENDING);
        Assert.assertEquals(saved.getValue().getOptions(), Map.of("diet", "vegan"));
    }

    /**
     * An answer edit over the API rebuilds the registration; it must carry the ticket, payment and check-in fields
     * along (a constructor rebuild silently erased them, docs/checkout.md).
     */
    @Test
    public void anAnswerEditKeepsTheTicketAndCheckIn() {
        signedInAs(ME);
        final java.time.LocalDateTime checkedIn = java.time.LocalDateTime.of(2027, 3, 5, 8, 30);
        final Registration existing = registration(ME, Registration.Status.CONFIRMED)
                .withTicketCode("TICKETCODE").withPaymentId("pay-1").withCheckedInAt(checkedIn);
        Mockito.when(registrations.getRegistration(TRIP_ID, ME)).thenReturn(existing);
        Mockito.when(registrations.saveRegistration(ArgumentMatchers.any())).thenReturn(true);

        assertOk(resource.save(TRIP_ID, ME.getValue(), CSRF_OK,
                body(null, Map.of("diet", "vegan"))));
        final ArgumentCaptor<Registration> saved = ArgumentCaptor.forClass(Registration.class);
        Mockito.verify(registrations).saveRegistration(saved.capture());
        Assert.assertEquals(saved.getValue().getOptions(), Map.of("diet", "vegan"));
        Assert.assertEquals(saved.getValue().getTicketCode(), "TICKETCODE");
        Assert.assertEquals(saved.getValue().getPaymentId(), "pay-1");
        Assert.assertEquals(saved.getValue().getCheckedInAt(), checkedIn);
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
                body("CONFIRMED", null)));
        assertOk(resource.save(TRIP_ID, ME.getValue(), CSRF_OK,
                body("Confirmed", null)));

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
                body(null, Map.of("diet", "vegan")));
        Mockito.verifyNoInteractions(audit);

        resource.save(TRIP_ID, ME.getValue(), CSRF_OK,
                body("CONFIRMED", null));
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
                body("NOT_REGISTERED", null));

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
                body("CONFIRMED", null)), 404, ApiErrors.NOT_FOUND);
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
                body("CONFIRMED", null)), 404, ApiErrors.NOT_FOUND);
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


    // --- party registration ---

    private static final Person.Id THIRD = Person.Id.from("reg-third");

    /** The party endpoint's bean is caller-bound (built with this::caller), so it is substituted by seam. */
    private RegistrationsResource partyResource() {
        return resource(new RegistrationsResource() {
            @Override
            protected RegistrationCommands callerBoundRegistrations() {
                return registrations;
            }
        });
    }

    private static org.paulsens.trip.api.dto.RegisterPartyRequest.TravelerRequest traveler(final Person.Id id,
            final Map<String, String> options, final Boolean digest) {
        return new org.paulsens.trip.api.dto.RegisterPartyRequest.TravelerRequest(id.getValue(), options, digest);
    }

    private static org.paulsens.trip.api.dto.RegisterPartyRequest party(
            final org.paulsens.trip.api.dto.RegisterPartyRequest.TravelerRequest... travelers) {
        return new org.paulsens.trip.api.dto.RegisterPartyRequest(List.of(travelers));
    }

    /** Someone already on the roster is filed Confirmed by the seam, and the echo must say so too. */
    @Test
    public void aRosterMembersFiledRowIsEchoedConfirmed() {
        signedInAs(ME);
        final Trip trip = Trip.builder().id(TRIP_ID).title("Party").build();
        trip.getPeople().add(ME);
        Mockito.when(trips.getTrip(TRIP_ID)).thenReturn(trip);
        Mockito.when(registrations.getRegistration(TRIP_ID, ME)).thenReturn(new Registration(TRIP_ID, ME));
        final Person me = new Person();
        me.setId(ME);
        Mockito.when(registrations.registerPartyOutcome(ArgumentMatchers.eq(trip), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new RegistrationCommands.PartyOutcome(List.of(me), List.of(), Map.of()));

        final Response response = partyResource().registerParty(TRIP_ID, CSRF_OK, party(traveler(ME, null, null)));
        assertOk(response);
        Assert.assertEquals(((org.paulsens.trip.api.dto.RegisterPartyResponse) response.getEntity())
                .registered().get(0).status(), "CONFIRMED", "a roster member's filed row is echoed as Confirmed");
    }

    @Test
    public void partyFilesTheRowsThroughTheBeanAndReportsPerTraveler() {
        signedInAs(ME);
        final Trip trip = Trip.builder().id(TRIP_ID).title("Party").chatEnabled(true)
                .regOptions(List.of(new org.paulsens.trip.model.RegistrationOption(1, "Diet", "Needs?", true)))
                .build();
        Mockito.when(trips.getTrip(TRIP_ID)).thenReturn(trip);
        Mockito.when(registrations.getRegistration(TRIP_ID, ME)).thenReturn(new Registration(TRIP_ID, ME));
        Mockito.when(registrations.getRegistration(TRIP_ID, OTHER)).thenReturn(registration(OTHER,
                Registration.Status.PENDING));
        Mockito.when(registrations.getRegistration(TRIP_ID, THIRD)).thenReturn(new Registration(TRIP_ID, THIRD));
        final Person me = new Person();
        me.setId(ME);
        final Person other = new Person();
        other.setId(OTHER);
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Registration>> regsCaptor = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> digestCaptor = ArgumentCaptor.forClass(Map.class);
        final Map<String, String> refused = new java.util.LinkedHashMap<>();
        refused.put(THIRD.getValue(), RegistrationCommands.PartyOutcome.CANNOT_JOIN);
        Mockito.when(registrations.registerPartyOutcome(ArgumentMatchers.eq(trip), ArgumentMatchers.any(),
                regsCaptor.capture(), digestCaptor.capture(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new RegistrationCommands.PartyOutcome(List.of(me), List.of(other), refused));

        final Response response = partyResource().registerParty(TRIP_ID, CSRF_OK, party(
                traveler(ME, Map.of("1", "vegan", "_party", "forged", "9", "not a question"), false),
                traveler(OTHER, null, null),
                traveler(THIRD, Map.of(), true)));

        assertOk(response);
        final org.paulsens.trip.api.dto.RegisterPartyResponse body =
                (org.paulsens.trip.api.dto.RegisterPartyResponse) response.getEntity();
        Assert.assertEquals(body.registered().size(), 1);
        Assert.assertEquals(body.registered().get(0).status(), "PENDING", "the filed row is echoed as Pending");
        Assert.assertEquals(body.registered().get(0).options().get("1"), "vegan");
        Assert.assertFalse(body.registered().get(0).options().containsKey("_party"),
                "only the trip's own question keys are taken from the client");
        Assert.assertFalse(body.registered().get(0).options().containsKey("9"));
        Assert.assertEquals(body.updated().size(), 1);
        Assert.assertEquals(body.updated().get(0).userId(), OTHER.getValue());
        Assert.assertEquals(body.refused(), refused);

        final Map<String, Registration> regs = regsCaptor.getValue();
        Assert.assertEquals(regs.keySet(), java.util.Set.of(ME.getValue(), OTHER.getValue(), THIRD.getValue()));
        Assert.assertEquals(regs.get(OTHER.getValue()).getOptions().get("diet"), "vegetarian",
                "no options posted leaves the stored answers alone");
        final Map<String, Object> digests = digestCaptor.getValue();
        Assert.assertEquals(digests.get(ME.getValue()), Boolean.FALSE, "an explicit digest answer is kept");
        Assert.assertEquals(digests.get(OTHER.getValue()), Boolean.TRUE, "absent means the form default: on");
        Mockito.verify(audit).registered(me, trip, resource.actor());
        Mockito.verify(audit, Mockito.never()).registered(ArgumentMatchers.eq(other), ArgumentMatchers.any(),
                ArgumentMatchers.any());
        Mockito.verify(registrations).sendRegistrationMail(trip, List.of(me));
    }

    @Test
    public void partyRefusesMalformedRequestsBeforeTouchingTheBean() {
        signedInAs(ME);
        final RegistrationsResource party = partyResource();
        assertError(party.registerParty(TRIP_ID, null, party(traveler(ME, null, null))), 403, ApiErrors.CSRF);
        assertError(party.registerParty(TRIP_ID, CSRF_OK, null), 400, ApiErrors.VALIDATION_FAILED);
        assertError(party.registerParty(TRIP_ID, CSRF_OK, party()), 400, ApiErrors.VALIDATION_FAILED);
        assertError(party.registerParty(TRIP_ID, CSRF_OK, party(
                new org.paulsens.trip.api.dto.RegisterPartyRequest.TravelerRequest(" ", null, null))),
                400, ApiErrors.VALIDATION_FAILED);
        Mockito.when(registrations.getRegistration(TRIP_ID, ME)).thenReturn(new Registration(TRIP_ID, ME));
        assertError(party.registerParty(TRIP_ID, CSRF_OK, party(traveler(ME, null, null), traveler(ME, null, null))),
                400, ApiErrors.VALIDATION_FAILED);
        tripIsMissing("gone");
        assertThrown(() -> party.registerParty("gone", CSRF_OK, party(traveler(ME, null, null))), 404,
                ApiErrors.NOT_FOUND);
        personIsMissing(THIRD);
        assertThrown(() -> party.registerParty(TRIP_ID, CSRF_OK, party(traveler(THIRD, null, null))), 404,
                ApiErrors.NOT_FOUND);
        Mockito.verify(registrations, Mockito.never()).registerPartyOutcome(ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
    }

    // ------------------------------------------------------------------ pricing (docs/checkout.md)

    /** The policy is readable by any signed-in caller; the discount codes only by the trip's staff. */
    @Test
    public void pricingHidesTheCodesFromEveryoneButStaff() {
        signedInAs(ME);
        final Trip trip = Trip.builder().id(TRIP_ID).build();
        trip.setCheckout(new org.paulsens.trip.model.CheckoutConfig(Boolean.TRUE, null, null, null, List.of(),
                List.of(new org.paulsens.trip.model.DiscountCode("SPEAKER", null, null, null, null, null, null))));
        Mockito.when(trips.getTrip(TRIP_ID)).thenReturn(trip);
        final org.paulsens.trip.model.CheckoutConfig seen =
                (org.paulsens.trip.model.CheckoutConfig) resource.pricing(TRIP_ID).getEntity();
        Assert.assertTrue(seen.isOn());
        Assert.assertTrue(seen.getCodes().isEmpty(), "a code is a secret its holders were given");

        signedInAsSiteAdmin(ME);
        resource = resource(new RegistrationsResource());   // privileges are memoized per resource instance
        final org.paulsens.trip.model.CheckoutConfig full =
                (org.paulsens.trip.model.CheckoutConfig) resource.pricing(TRIP_ID).getEntity();
        Assert.assertEquals(full.getCodes().size(), 1);
    }

    @Test
    public void savingPricingIsForManagersAndRefusesAPolicyWithProblems() {
        final org.paulsens.trip.model.CheckoutConfig policy = new org.paulsens.trip.model.CheckoutConfig(
                Boolean.TRUE, null, null, null, List.of(), List.of());
        signedInAs(ME);
        assertError(resource.savePricing(TRIP_ID, CSRF_OK, policy), 403, ApiErrors.FORBIDDEN);
        assertError(resource.savePricing(TRIP_ID, null, policy), 403, ApiErrors.CSRF);

        signedInAsSiteAdmin(ME);
        resource = resource(new RegistrationsResource());   // privileges are memoized per resource instance
        assertError(resource.savePricing(TRIP_ID, CSRF_OK, null), 400, ApiErrors.VALIDATION_FAILED);
        final org.paulsens.trip.model.CheckoutConfig twice = new org.paulsens.trip.model.CheckoutConfig(
                Boolean.TRUE, null, null, null, List.of(), List.of(
                        new org.paulsens.trip.model.DiscountCode("A", null, null, null, null, null, null),
                        new org.paulsens.trip.model.DiscountCode("a", null, null, null, null, null, null)));
        assertError(resource.savePricing(TRIP_ID, CSRF_OK, twice), 400, ApiErrors.VALIDATION_FAILED);

        final Trip trip = Trip.builder().id(TRIP_ID).build();
        Mockito.when(trips.getTrip(TRIP_ID)).thenReturn(trip);
        Mockito.when(trips.saveTrip(trip)).thenReturn(true);
        assertOk(resource.savePricing(TRIP_ID, CSRF_OK, policy));
        Assert.assertTrue(trip.getCheckout().isOn(), "saved onto the trip");
        Mockito.when(trips.saveTrip(trip)).thenReturn(false);
        assertError(resource.savePricing(TRIP_ID, CSRF_OK, policy), 500, ApiErrors.STORE_FAILED);
    }

    // ------------------------------------------------------------------ checkout over REST (docs/checkout.md)

    private static final String RETURN_URL = "https://trip.example.org/pay/return";

    private org.paulsens.trip.action.CheckoutCommands checkout;
    private org.paulsens.trip.action.WaiverCommands waivers;

    /** Both checkout beans are caller-bound (built with this::caller), so they are substituted by seam. */
    private RegistrationsResource checkoutResource() {
        checkout = Mockito.mock(org.paulsens.trip.action.CheckoutCommands.class);
        waivers = Mockito.mock(org.paulsens.trip.action.WaiverCommands.class);
        Mockito.when(checkout.isEnabled(ArgumentMatchers.any())).thenReturn(true);
        final org.paulsens.trip.action.ConfigCommands config = bindMock(org.paulsens.trip.action.ConfigCommands.class);
        Mockito.when(config.getString(org.paulsens.trip.config.KnownSettings.PAYMENT_RETURN_URL_PREFIXES))
                .thenReturn("https://trip.example.org/");
        return resource(new RegistrationsResource() {
            @Override
            protected org.paulsens.trip.action.CheckoutCommands callerBoundCheckout() {
                return checkout;
            }

            @Override
            protected org.paulsens.trip.action.WaiverCommands callerBoundWaivers() {
                return waivers;
            }
        });
    }

    private static org.paulsens.trip.api.dto.CheckoutRequest.Traveler buyer(final Person.Id id,
            final Map<String, String> options, final String code, final List<String> attested,
            final List<org.paulsens.trip.api.dto.RegisterPartyRequest.WaiverAnswer> waivers) {
        return new org.paulsens.trip.api.dto.CheckoutRequest.Traveler(id.getValue(), options, code, attested,
                Boolean.FALSE, waivers);
    }

    private static org.paulsens.trip.api.dto.CheckoutRequest checkoutOf(final String returnUrl,
            final org.paulsens.trip.api.dto.CheckoutRequest.Offline offline,
            final org.paulsens.trip.api.dto.CheckoutRequest.Traveler... travelers) {
        return new org.paulsens.trip.api.dto.CheckoutRequest(null, List.of(travelers), null, returnUrl, offline,
                null);
    }

    private static org.paulsens.trip.action.CheckoutCommands.Quote quoteOf(final long totalCents) {
        return new org.paulsens.trip.action.CheckoutCommands.Quote(List.of(), List.of(), totalCents, totalCents, 0L,
                totalCents, true, null, null);
    }

    @Test
    public void checkoutRefusesAMalformedPartyAnOffTripAndSomeoneElse() {
        signedInAs(ME);
        final RegistrationsResource first = checkoutResource();
        assertThrown(() -> first.quoteCheckout(TRIP_ID, null), 400, ApiErrors.VALIDATION_FAILED);
        assertThrown(() -> first.quoteCheckout(TRIP_ID, checkoutOf(null, null,
                new org.paulsens.trip.api.dto.CheckoutRequest.Traveler(" ", null, null, null, null, null))),
                400, ApiErrors.VALIDATION_FAILED);
        assertThrown(() -> first.quoteCheckout(TRIP_ID, checkoutOf(null, null, buyer(ME, null, null, null, null),
                buyer(ME, null, null, null, null))), 400, ApiErrors.VALIDATION_FAILED);
        assertThrown(() -> first.quoteCheckout(TRIP_ID, checkoutOf(null, null, buyer(OTHER, null, null, null, null))),
                403, ApiErrors.FORBIDDEN);
        Mockito.when(checkout.isEnabled(ArgumentMatchers.any())).thenReturn(false);
        assertThrown(() -> first.quoteCheckout(TRIP_ID, checkoutOf(null, null, buyer(ME, null, null, null, null))),
                409, ApiErrors.CONFLICT);
        Mockito.verify(checkout, Mockito.never()).quote(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());
    }

    /** The request becomes exactly the page's draft: answers (trip keys only), code, attestations, digest. */
    @Test
    public void aQuoteIsThePagesDraftPricedByTheSameBean() {
        signedInAs(ME);
        final Trip trip = Trip.builder().id(TRIP_ID).title("Conf")
                .regOptions(List.of(new org.paulsens.trip.model.RegistrationOption(1, "Admission", "Which?", true)))
                .build();
        Mockito.when(trips.getTrip(TRIP_ID)).thenReturn(trip);
        final RegistrationsResource party = checkoutResource();
        final String key = trip.getRegOptions().get(0).getKey();
        Mockito.when(checkout.quote(ArgumentMatchers.eq(trip), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(quoteOf(15000L));

        final Response response = party.quoteCheckout(TRIP_ID, checkoutOf(null, null,
                buyer(ME, Map.of(key, "full", "_registeredBy", "forged"), " speaker ", List.of("hotel"), null)));
        assertOk(response);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        Assert.assertEquals(body.get("totalCents"), 15000L);
        Assert.assertEquals(body.get("priceCents"), 15000L);
        Assert.assertEquals(body.get("balanceCents"), 0L, "no deposit: nothing left to pay");
        Assert.assertNull(body.get("depositCents"));
        Assert.assertEquals(body.get("ready"), Boolean.TRUE);

        final ArgumentCaptor<org.paulsens.trip.model.RegistrationDraft> draft =
                ArgumentCaptor.forClass(org.paulsens.trip.model.RegistrationDraft.class);
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<List<Person>> people = ArgumentCaptor.forClass(List.class);
        Mockito.verify(checkout).quote(ArgumentMatchers.eq(trip), draft.capture(), people.capture());
        final String me = ME.getValue();
        Assert.assertEquals(draft.getValue().getSel().get(me), Boolean.TRUE);
        Assert.assertEquals(draft.getValue().getRegs().get(me).getOptions(), Map.of(key, "full"),
                "only the trip's own question keys are taken");
        Assert.assertEquals(draft.getValue().getCodes().get(me), "speaker");
        Assert.assertEquals(draft.getValue().getAttest().get(
                org.paulsens.trip.model.RegistrationDraft.attestKey(me, "hotel")), Boolean.TRUE);
        Assert.assertEquals(draft.getValue().getDigest().get(me), Boolean.FALSE);
        Assert.assertEquals(people.getValue().get(0).getId(), ME);
    }

    @Test
    public void startingACheckoutChecksTheReturnUrlAndAnswersEveryProblem() {
        signedInAs(ME);
        final RegistrationsResource party = checkoutResource();
        final org.paulsens.trip.api.dto.CheckoutRequest.Traveler me = buyer(ME, null, null, null, List.of(
                new org.paulsens.trip.api.dto.RegisterPartyRequest.WaiverAnswer("w1", 2, true, true)));
        assertError(party.startCheckout(TRIP_ID, null, checkoutOf(RETURN_URL, null, me)), 403, ApiErrors.CSRF);
        assertError(party.startCheckout(TRIP_ID, CSRF_OK, checkoutOf(null, null, me)), 400, ApiErrors.BAD_REQUEST);
        assertError(party.startCheckout(TRIP_ID, CSRF_OK, checkoutOf("https://evil.example/x", null, me)), 400,
                ApiErrors.BAD_REQUEST);

        final org.paulsens.trip.checkout.CheckoutProblem full = new org.paulsens.trip.checkout.CheckoutProblem(
                org.paulsens.trip.checkout.CheckoutProblem.CAPACITY_FULL, null, TRIP_ID, "The event is full.");
        Mockito.when(checkout.problemsBeforeStart(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(List.of(full));
        final Response refused = party.startCheckout(TRIP_ID, CSRF_OK, checkoutOf(RETURN_URL, null, me));
        assertError(refused, 400, ApiErrors.VALIDATION_FAILED);
        Assert.assertEquals(((Map<?, ?>) refused.getEntity()).get("problems"), List.of(full));
        Mockito.verify(waivers).applyAgreements(ArgumentMatchers.any(), ArgumentMatchers.eq(ME),
                ArgumentMatchers.eq(List.of(new org.paulsens.trip.action.WaiverCommands.Agreement("w1", 2, true,
                        true))), ArgumentMatchers.any());
        Mockito.verify(checkout, Mockito.never()).start(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.anyBoolean(), ArgumentMatchers.any());
    }

    @Test
    public void aStartedCheckoutAnswersHowToPayAndAFreePartyItsCompletion() {
        signedInAs(ME);
        final RegistrationsResource party = checkoutResource();
        Mockito.when(checkout.problemsBeforeStart(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(List.of());
        final org.paulsens.trip.action.PaymentCommands.Start start =
                Mockito.mock(org.paulsens.trip.action.PaymentCommands.Start.class);
        Mockito.when(start.getPaymentId()).thenReturn("pay-1");
        Mockito.when(start.getClientSecret()).thenReturn("secret");
        Mockito.when(checkout.start(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.isNull(), ArgumentMatchers.eq(false), ArgumentMatchers.eq(RETURN_URL)))
                .thenReturn(new org.paulsens.trip.action.CheckoutCommands.Started(start, null));
        final org.paulsens.trip.api.dto.CheckoutRequest body = checkoutOf(RETURN_URL, null,
                buyer(ME, null, null, null, null));
        final Response started = party.startCheckout(TRIP_ID, CSRF_OK, body);
        assertOk(started);
        Assert.assertEquals(((Map<?, ?>) started.getEntity()).get("paymentId"), "pay-1");
        Assert.assertEquals(((Map<?, ?>) started.getEntity()).get("clientSecret"), "secret");

        final org.paulsens.trip.action.PaymentCommands.Completion done =
                Mockito.mock(org.paulsens.trip.action.PaymentCommands.Completion.class);
        Mockito.when(done.getStatus()).thenReturn("recorded");
        Mockito.when(checkout.start(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyBoolean(), ArgumentMatchers.any()))
                .thenReturn(new org.paulsens.trip.action.CheckoutCommands.Started(null, done));
        Assert.assertEquals(((Map<?, ?>) party.startCheckout(TRIP_ID, CSRF_OK, body).getEntity()).get("status"),
                "recorded", "a free party is registered at once");

        Mockito.when(checkout.start(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.anyBoolean(), ArgumentMatchers.any())).thenReturn(null);
        assertError(party.startCheckout(TRIP_ID, CSRF_OK, body), 400, ApiErrors.BAD_REQUEST);
    }

    @Test
    public void anOfflinePaymentIsForRegistrationAdminsAndAnswersItsCompletion() {
        signedInAs(ME);
        // The traveler is on the trip: an admin's reach stops at the trip's own people.
        Mockito.when(trips.getTrip(TRIP_ID)).thenReturn(Trip.builder().id(TRIP_ID).people(List.of(OTHER)).build());
        final RegistrationsResource party = checkoutResource();
        final org.paulsens.trip.api.dto.CheckoutRequest.Offline check =
                new org.paulsens.trip.api.dto.CheckoutRequest.Offline("CHECK", "1042", 15000L, null, null);
        final org.paulsens.trip.api.dto.CheckoutRequest body = new org.paulsens.trip.api.dto.CheckoutRequest(
                OTHER.getValue(), List.of(buyer(OTHER, null, null, null, null)), null, null, check, null);
        assertError(party.recordOfflineCheckout(TRIP_ID, null, body), 403, ApiErrors.CSRF);
        assertError(party.recordOfflineCheckout(TRIP_ID, CSRF_OK, body), 403, ApiErrors.FORBIDDEN);

        Mockito.when(checkout.canAdminister(ArgumentMatchers.any())).thenReturn(true);
        assertError(party.recordOfflineCheckout(TRIP_ID, CSRF_OK, new org.paulsens.trip.api.dto.CheckoutRequest(
                null, body.travelers(), null, null, null, null)), 400, ApiErrors.VALIDATION_FAILED);
        Mockito.when(checkout.problemsBeforeStart(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any())).thenReturn(List.of());
        assertError(party.recordOfflineCheckout(TRIP_ID, CSRF_OK, body), 400, ApiErrors.VALIDATION_FAILED);

        final org.paulsens.trip.action.PaymentCommands.Completion done =
                Mockito.mock(org.paulsens.trip.action.PaymentCommands.Completion.class);
        Mockito.when(done.getStatus()).thenReturn("recorded");
        Mockito.when(checkout.recordOffline(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq(OTHER), ArgumentMatchers.eq("CHECK"), ArgumentMatchers.eq("1042"),
                ArgumentMatchers.eq(150.0), ArgumentMatchers.isNull(), ArgumentMatchers.eq(false))).thenReturn(done);
        final Response recorded = party.recordOfflineCheckout(TRIP_ID, CSRF_OK, body);
        assertOk(recorded);
        Assert.assertEquals(((Map<?, ?>) recorded.getEntity()).get("status"), "recorded",
                "an admin registers someone else, paid by check");
    }

    /** A traveler's PUT never changes what they paid for; staff may. */
    @Test
    public void aTravelersPutKeepsTheirPaidAnswer() {
        final org.paulsens.trip.model.RegistrationOption admission =
                new org.paulsens.trip.model.RegistrationOption(1, "Admission", "Which?", true);
        admission.setType(org.paulsens.trip.model.RegistrationOption.QuestionType.CHOICE);
        admission.setChoices(List.of(new org.paulsens.trip.model.OptionChoice("fri", "Friday", 6000L, null, null,
                null, null), new org.paulsens.trip.model.OptionChoice("full", "Full", 15000L, null, null, null,
                null)));
        final Trip trip = Trip.builder().id(TRIP_ID).regOptions(List.of(admission)).build();
        trip.setCheckout(new org.paulsens.trip.model.CheckoutConfig(Boolean.TRUE, null, null, null, List.of(),
                List.of()));
        Mockito.when(trips.getTrip(TRIP_ID)).thenReturn(trip);
        final String key = admission.getKey();
        Mockito.when(registrations.getRegistration(TRIP_ID, ME)).thenReturn(new Registration(TRIP_ID, ME, null,
                Registration.Status.CONFIRMED, Map.of(key, "fri", "diet", "none")));
        Mockito.when(registrations.saveRegistration(ArgumentMatchers.any())).thenReturn(true);
        signedInAs(ME);
        final RegistrationDto posted = body(null, Map.of(key, "full", "diet", "vegan"));
        final RegistrationDto saved = (RegistrationDto) resource.save(TRIP_ID, ME.getValue(), CSRF_OK, posted)
                .getEntity();
        Assert.assertEquals(saved.options().get(key), "fri", "Friday-only does not become the full weekend free");
        Assert.assertEquals(saved.options().get("diet"), "vegan", "unpriced answers still edit");

        signedInAsSiteAdmin(ME);
        final RegistrationDto staffSaved = (RegistrationDto) resource(new RegistrationsResource())
                .save(TRIP_ID, ME.getValue(), CSRF_OK, posted).getEntity();
        Assert.assertEquals(staffSaved.options().get(key), "full", "staff change a paid choice");
    }
    // ------------------------------------------------------------------ audit 2026-11-11, F21

    private static Person.Id holderOf(final String base, final String tripId) {
        final Person.Id who = Person.Id.newInstance();
        final PrivilegeCommands priv = new PrivilegeCommands();
        Assert.assertTrue(priv.savePrivilege(priv.getOrCreate(base, tripId, base).withNewPerson(who), null));
        return who;
    }

    private RegistrationsResource as(final Person.Id who) {
        signedInAs(who);
        return resource(new RegistrationsResource());
    }

    /**
     * {@code isTripStaff} was the READ gate and gated the writes too, so the read-only Viewer and the Editor
     * Admin approved registrations, switched an answer somebody had paid for, assigned rooms and (the Viewer)
     * read every discount code. Administering registrations is the registration admins' (and site admins'),
     * the pair the Registrations page admits; the other trip roles still read.
     */
    @Test
    public void onlyARegistrationAdminAdministersRegistrations() {
        final String tripId = java.util.UUID.randomUUID().toString();
        final org.paulsens.trip.model.RegistrationOption admission =
                new org.paulsens.trip.model.RegistrationOption(1, "Admission", "Which?", true);
        admission.setType(org.paulsens.trip.model.RegistrationOption.QuestionType.CHOICE);
        admission.setChoices(List.of(new org.paulsens.trip.model.OptionChoice("fri", "Friday", 6000L, null, null,
                null, null), new org.paulsens.trip.model.OptionChoice("full", "Full", 15000L, null, null, null,
                null)));
        final Trip trip = Trip.builder().id(tripId).regOptions(List.of(admission)).people(List.of(OTHER))
                .build();
        trip.setCheckout(new org.paulsens.trip.model.CheckoutConfig(Boolean.TRUE, null, null, null, List.of(),
                List.of(new org.paulsens.trip.model.DiscountCode("SPEAKER", null, null, null, null, null, null))));
        Mockito.when(trips.getTrip(tripId)).thenReturn(trip);
        final String key = admission.getKey();
        Mockito.when(registrations.getRegistration(tripId, OTHER)).thenReturn(new Registration(tripId, OTHER, null,
                Registration.Status.PENDING, Map.of(key, "fri")));
        Mockito.when(registrations.getRegistrations(tripId)).thenReturn(List.of());
        Mockito.when(registrations.saveRegistration(ArgumentMatchers.any())).thenReturn(true);
        Mockito.when(registrations.saveRoom(tripId, OTHER, "101")).thenReturn(true);
        final Person.Id viewer = holderOf(PrivilegeCommands.TRIP_VIEW, tripId);
        final Person.Id editor = holderOf(PrivilegeCommands.TRIP_MGR, tripId);
        final Person.Id registrar = holderOf(PrivilegeCommands.REGISTRATION_ADMIN, tripId);

        for (final Person.Id reader : List.of(viewer, editor)) {
            assertOk(as(reader).list(tripId));      // reading the roster is still theirs
            assertError(as(reader).save(tripId, OTHER.getValue(), CSRF_OK, body("CONFIRMED", null)),
                    403, ApiErrors.FORBIDDEN);
            assertError(as(reader).save(tripId, OTHER.getValue(), CSRF_OK, body(null, Map.of(key, "full"))),
                    403, ApiErrors.FORBIDDEN);
            assertError(as(reader).saveRoom(tripId, OTHER.getValue(), CSRF_OK, Map.of("room", "101")),
                    403, ApiErrors.FORBIDDEN);
        }
        Mockito.verify(registrations, Mockito.never()).saveRegistration(ArgumentMatchers.any());
        Mockito.verify(registrations, Mockito.never()).saveRoom(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any());

        // Discount codes: the pricing manager and the registration admin, not the Viewer.
        Assert.assertTrue(((org.paulsens.trip.model.CheckoutConfig) as(viewer).pricing(tripId).getEntity())
                .getCodes().isEmpty(), "a Viewer is handed no codes");
        Assert.assertEquals(((org.paulsens.trip.model.CheckoutConfig) as(editor).pricing(tripId).getEntity())
                .getCodes().size(), 1);
        Assert.assertEquals(((org.paulsens.trip.model.CheckoutConfig) as(registrar).pricing(tripId).getEntity())
                .getCodes().size(), 1);

        // The registration admin approves, changes a paid answer and rooms: the Registrations page's powers.
        final RegistrationDto approved = (RegistrationDto) as(registrar).save(tripId, OTHER.getValue(), CSRF_OK,
                body("CONFIRMED", Map.of(key, "full"))).getEntity();
        Assert.assertEquals(approved.status(), "CONFIRMED");
        Assert.assertEquals(approved.options().get(key), "full");
        assertOk(as(registrar).saveRoom(tripId, OTHER.getValue(), CSRF_OK, Map.of("room", "101")));
    }

    /**
     * A registration is what makes someone "on the trip", and a trip's staff read the profiles and ledgers of
     * the people on it. So staff may file one only for the trip's own people: someone already on it, or one of
     * its organization's members. Anyone else took a single request to turn into a traveler.
     */
    @Test
    public void aRegistrationAdminCannotRegisterSomeoneOutsideTheTripsPeople() {
        final String tripId = java.util.UUID.randomUUID().toString();
        Mockito.when(trips.getTrip(tripId)).thenReturn(Trip.builder().id(tripId).build());
        Mockito.when(registrations.getRegistration(tripId, OTHER)).thenReturn(new Registration(tripId, OTHER));
        Mockito.when(registrations.saveRegistration(ArgumentMatchers.any())).thenReturn(true);
        final Person.Id registrar = holderOf(PrivilegeCommands.REGISTRATION_ADMIN, tripId);

        assertError(as(registrar).save(tripId, OTHER.getValue(), CSRF_OK, body("PENDING", null)),
                403, ApiErrors.FORBIDDEN);
        Mockito.verify(registrations, Mockito.never()).saveRegistration(ArgumentMatchers.any());

        // The same stranger in a checkout party: the admin's exemption from "someone you act for" ends there too.
        Mockito.when(checkout.canAdminister(ArgumentMatchers.any())).thenReturn(true);
        signedInAs(registrar);
        final org.paulsens.trip.api.dto.CheckoutRequest party = new org.paulsens.trip.api.dto.CheckoutRequest(
                null, List.of(buyer(OTHER, null, null, null, null)), null, null, null, null);
        try {
            checkoutResource().quoteCheckout(tripId, party);
            Assert.fail("a stranger must not be priced (the price reveals their age band) or registered");
        } catch (final jakarta.ws.rs.WebApplicationException refused) {
            Assert.assertEquals(refused.getResponse().getStatus(), 403);
        }

        // A site admin is not bounded, and the trip's own traveler is the registrar's to administer.
        signedInAsSiteAdmin(ME);
        assertOk(resource(new RegistrationsResource()).save(tripId, OTHER.getValue(), CSRF_OK,
                body("PENDING", null)));
        Mockito.when(trips.getTrip(tripId)).thenReturn(Trip.builder().id(tripId).people(List.of(OTHER)).build());
        assertOk(as(registrar).save(tripId, OTHER.getValue(), CSRF_OK, body("PENDING", null)));
    }
}
