package org.paulsens.trip.action;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.cache.Cached;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.dynamo.FakeData;
import org.paulsens.trip.model.ContentInstance;
import org.paulsens.trip.model.OptionChoice;
import org.paulsens.trip.model.Passport;
import org.paulsens.trip.model.Person;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.model.TripEvent;
import org.paulsens.trip.model.TripKind;
import org.paulsens.trip.security.TicketCodes;
import org.paulsens.trip.util.RandomData;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * The conference foundations in the beans (docs/conference.md, docs/checkout.md, docs/checkin.md): the
 * CONFIRMED-has-a-ticket invariant, required typed answers, the pick-any answer adapter, answers shown by label,
 * the conference profile checklist, the registration-question helpers, agenda fields on the event form, and
 * conferences kept out of the pilgrimage listings.
 */
public class ConferenceFoundationsTest {
    private DAO dao;

    @BeforeClass
    public void init() {
        FakeData.initFakeData();
        FakeData.addFakeData();
        dao = DAO.getInstance();
    }

    // ------------------------------------------------------------------ the ticket invariant

    @Test
    public void onlyAConfirmedRegistrationGetsATicketAndKeepsIt() {
        final Person.Id who = Person.Id.newInstance();
        final Registration pending = new Registration("t", who).withStatus(Registration.Status.PENDING);
        assertSame(RegistrationCommands.withTicket(pending), pending, "a pending registration has no ticket");
        assertNull(RegistrationCommands.withTicket(null));

        final Registration confirmed = RegistrationCommands.withTicket(
                pending.withStatus(Registration.Status.CONFIRMED));
        assertNotNull(TicketCodes.normalize(confirmed.getTicketCode()), "a confirmed registration gets a code");
        assertSame(RegistrationCommands.withTicket(confirmed), confirmed, "an existing ticket is never replaced");
    }

    @Test
    public void savingAConfirmedRowStoresATicketWhateverConfirmedIt() throws IOException {
        final Person person = savedPerson();
        final String tripId = "tk-" + RandomData.genAlpha(8);
        final Registration confirmed = new Registration(tripId, person.getId())
                .withStatus(Registration.Status.CONFIRMED);
        assertTrue(new RegistrationCommands().saveRegistration(confirmed));
        final Registration stored = dao.getRegistration(tripId, person.getId(), Cached.NO).orElseThrow();
        assertNotNull(stored.getTicketCode(), "the save path is the invariant's backstop");
    }

    // ------------------------------------------------------------------ required typed answers

    @Test
    public void aRequiredUnansweredQuestionRefusesTheTravelerByName() throws IOException {
        final Person me = savedPerson();
        final Trip trip = Trip.builder().id("req-" + RandomData.genAlpha(8)).title("Required Trip")
                .startDate(LocalDateTime.now().plusDays(30)).endDate(LocalDateTime.now().plusDays(32))
                .regOptions(List.of(admission(), new RegistrationOption(9, "Hidden but required", "", false,
                        RegistrationOption.QuestionType.TEXT, true, null)))
                .build();
        assertTrue(dao.saveTrip(trip));

        final Registration blank = new Registration(trip.getId(), me.getId());
        assertEquals(RegistrationCommands.missingAnswers(trip, blank), List.of("Admission"),
                "a hidden question is never required of the registrant");
        assertEquals(RegistrationCommands.missingAnswers(null, blank), List.of());
        assertEquals(RegistrationCommands.missingAnswers(trip, null), List.of("Admission"));

        final Map<String, Registration> regs = new HashMap<>();
        regs.put(me.getId().getValue(), blank);
        final Map<String, Object> selected = Map.of(me.getId().getValue(), Boolean.TRUE);
        final RegistrationCommands commands = new RegistrationCommands(() -> callerFor(me));
        assertEquals(commands.registerPartyOutcome(trip, selected, regs, null).refused(),
                Map.of(me.getId().getValue(), RegistrationCommands.PartyOutcome.ANSWERS_REQUIRED));

        blank.getOptions().put("1", "full");
        final RegistrationCommands.PartyOutcome filed = commands.registerPartyOutcome(trip, selected, regs, null);
        assertEquals(filed.registered().size(), 1, "answered, the traveler registers");
    }

    // ------------------------------------------------------------------ pay-first guards (docs/checkout.md)

    /** A checkout trip files nobody for free, and a registered person's party edit keeps what they paid for. */
    @Test
    public void aCheckoutTripRegistersOnlyThroughCheckoutAndKeepsPaidAnswers() throws IOException {
        final Person me = savedPerson();
        final RegistrationOption paid = new RegistrationOption(1, "Admission", "", true,
                RegistrationOption.QuestionType.CHOICE, true, List.of(
                        new OptionChoice("fri", "Friday", 6000L, null, null, null, null),
                        new OptionChoice("full", "Full weekend", 15000L, null, null, null, null)));
        final Trip trip = Trip.builder().id("co-" + RandomData.genAlpha(8)).title("Checkout Trip")
                .startDate(LocalDateTime.now().plusDays(30)).endDate(LocalDateTime.now().plusDays(32))
                .regOptions(List.of(paid, new RegistrationOption(2, "Diet", "", true))).build();
        trip.getCheckout().setOn(true);
        assertTrue(dao.saveTrip(trip));
        final RegistrationCommands commands = new RegistrationCommands(() -> callerFor(me));
        final String pid = me.getId().getValue();

        final Map<String, Registration> regs = new HashMap<>();
        regs.put(pid, new Registration(trip.getId(), me.getId(), null, null,
                new HashMap<>(Map.of("1", "full"))));
        assertEquals(commands.registerPartyOutcome(trip, Map.of(pid, Boolean.TRUE), regs, null).refused(),
                Map.of(pid, RegistrationCommands.PartyOutcome.CHECKOUT_REQUIRED), "pay first");

        assertTrue(dao.saveRegistration(new Registration(trip.getId(), me.getId(), null,
                Registration.Status.CONFIRMED, new HashMap<>(Map.of("1", "fri", "2", "none")))));
        regs.put(pid, new Registration(trip.getId(), me.getId(), null, Registration.Status.CONFIRMED,
                new HashMap<>(Map.of("1", "full", "2", "vegan"))));
        assertEquals(commands.registerPartyOutcome(trip, Map.of(pid, Boolean.TRUE), regs, null).updated().size(), 1);
        final Registration stored = dao.getRegistration(trip.getId(), me.getId(), Cached.NO).orElseThrow();
        assertEquals(stored.getOptions().get("1"), "fri", "Friday-only does not become the full weekend free");
        assertEquals(stored.getOptions().get("2"), "vegan");

        assertTrue(CheckoutCommands.isPaidQuestion(trip, paid));
        assertFalse(CheckoutCommands.isPaidQuestion(trip, trip.getRegOptions().get(1)), "unpriced");
        assertFalse(CheckoutCommands.isPaidQuestion(null, paid));
        assertEquals(CheckoutCommands.keepPaidAnswers(trip, Map.of(), Map.of("1", "full", "2", "x")),
                Map.of("2", "x"), "no paid answer stored: none may be posted");
        assertEquals(CheckoutCommands.keepPaidAnswers(null, Map.of(), Map.of("1", "full")), Map.of("1", "full"));
        trip.getCheckout().setOn(false);
        assertFalse(CheckoutCommands.isPaidQuestion(trip, paid), "a classic trip's answers stay editable");
    }

    /** A conference's approval email links the ticket; a pilgrimage's reads as it always did. */
    @Test
    public void theApprovalEmailLinksAConferencesTicket() throws IOException {
        final Person me = savedPerson();
        final Trip conference = Trip.builder().id(java.util.UUID.randomUUID().toString()).title("Conf")
                .kind(TripKind.CONFERENCE).build();
        final Registration approved = RegistrationCommands.withTicket(new Registration(conference.getId(),
                me.getId()).withStatus(Registration.Status.CONFIRMED));
        final RegistrationCommands reg = new RegistrationCommands();
        final String html = ((org.paulsens.trip.chat.MailTemplates.Raw) reg.approvedMailValues(conference, me,
                approved).get("ticketBlock")).html();
        assertTrue(html.contains("/trip/ticket.jsf?trip=" + conference.getId() + "&amp;t=" + approved.getTicketCode()),
                html);
        assertTrue(html.contains(TicketCodes.grouped(approved.getTicketCode())), html);

        final Trip pilgrimage = Trip.builder().id(java.util.UUID.randomUUID().toString()).title("Walk").build();
        assertEquals(((org.paulsens.trip.chat.MailTemplates.Raw) reg.approvedMailValues(pilgrimage, me, approved)
                .get("ticketBlock")).html(), "");
        assertEquals(((org.paulsens.trip.chat.MailTemplates.Raw) reg.approvedMailValues(conference, me)
                .get("ticketBlock")).html(), "", "no row, no ticket");
    }

    /** Every save path refuses an inconsistent pricing policy (the editor's dialog X skips its Done check). */
    @Test
    public void aTripWithAnInconsistentPricingPolicyIsNotSaved() {
        final Trip trip = Trip.builder().id("pp-" + RandomData.genAlpha(8)).title("Bad policy").build();
        trip.getCheckout().setOpensAt(LocalDateTime.now());
        trip.getCheckout().setClosesAt(LocalDateTime.now().minusDays(1));
        assertFalse(new TripCommands().saveTrip(trip));
        assertTrue(dao.getTrip(trip.getId(), Cached.NO).isEmpty(), "nothing was written");
    }

    @Test
    public void aPickAnyAnswerIsStoredCommaJoined() {
        final Map<String, String> answers = new HashMap<>();
        final RegistrationCommands reg = new RegistrationCommands();
        final RegistrationCommands.MultiAnswer multi = reg.multiAnswer(answers, "2");
        assertEquals(multi.getValues(), List.of());
        multi.setValues(List.of("shirt", "meal"));
        assertEquals(answers.get("2"), "shirt,meal", "a String, never a String[] in the map");
        assertEquals(reg.multiAnswer(answers, "2").getValues(), List.of("shirt", "meal"));
        multi.setValues(List.of());
        assertFalse(answers.containsKey("2"), "nothing picked removes the answer");
        multi.setValues(List.of("a"));
        multi.setValues(null);
        assertFalse(answers.containsKey("2"));
        answers.put("2", " a, ,b ");
        assertEquals(multi.getValues(), List.of("a", "b"));
        reg.multiAnswer(null, "2").setValues(List.of("x"));
        assertEquals(reg.multiAnswer(null, null).getValues(), List.of());
    }

    @Test
    public void answersAreShownByChoiceLabel() {
        final RegistrationCommands reg = new RegistrationCommands();
        final RegistrationOption admission = admission();
        final Person.Id who = Person.Id.newInstance();
        final Registration answered = new Registration("t", who, null, null, new HashMap<>(Map.of("1", "full")));
        assertEquals(reg.answerText(answered, admission), "Full weekend");
        assertNull(reg.answerText(new Registration("t", who), admission));
        assertNull(reg.answerText(null, admission));
        assertNull(reg.answerText(answered, null));
        assertEquals(reg.regAnswerText(List.of(answered), who.getValue(), admission), "Full weekend");
    }

    // ------------------------------------------------------------------ kind-dependent copy

    @Test
    public void aConferenceNeverAsksForAPassport() {
        final Person noTravelDocs = Person.builder().first("Ana").last("Lee").birthdate(LocalDate.of(1990, 1, 1))
                .passport(new Passport()).build();
        final FamilyCommands family = new FamilyCommands();
        final Trip conference = Trip.builder().kind(TripKind.CONFERENCE).build();
        assertEquals(family.missingProfileFields(noTravelDocs, conference), List.of("Emergency contact"));
        assertEquals(family.missingProfileFields(noTravelDocs),
                List.of("Passport number", "Emergency contact"), "travel still asks");
        assertEquals(family.missingProfileFields(noTravelDocs, Trip.builder().build()),
                family.missingProfileFields(noTravelDocs));
        assertEquals(family.missingProfileFields(null, conference), List.of());
    }

    // ------------------------------------------------------------------ the registration-question helpers

    @Test
    public void questionHelpersActOnTheQuestionTheKeyNames() {
        final TripCommands trips = new TripCommands();
        final Trip trip = Trip.builder().regOptions(List.of(admission())).build();
        final RegistrationOption found = trips.regOption(trip, "1");
        assertSame(found, trip.getRegOptions().get(0), "the draft's own instance, so edits land on it");
        assertNull(trips.regOption(trip, "2"));
        assertNull(trips.regOption(null, "1"));
        assertNull(trips.regOption(trip, null));

        trips.addRegChoice(trip, "1");
        assertEquals(found.getChoices().size(), 2);
        final String added = found.getChoices().get(1).getId();
        trips.removeRegChoice(trip, "1", added);
        assertEquals(found.getChoices().size(), 1);
        trips.addRegChoice(trip, "missing");
        trips.removeRegChoice(trip, "missing", added);
        trips.removeRegChoice(trip, "1", null);
        assertEquals(found.getChoices().size(), 1);

        assertEquals(trips.getTripKinds(), List.of(TripKind.PILGRIMAGE, TripKind.CONFERENCE));
        assertEquals(trips.getQuestionTypes().size(), RegistrationOption.QuestionType.values().length);
    }

    // ------------------------------------------------------------------ agenda fields on the event form

    @Test
    public void anEventKeepsItsAgendaFieldsAndStaysAGenericEvent() {
        final TripCommands trips = new TripCommands();
        final Trip trip = Trip.builder().title("Agenda").build();
        final TripEventForm form = trips.eventFormFor(trip, "EVENT", null);
        assertTrue(form.isAgendaSection());
        form.setTitle("Keynote");
        form.setStart(LocalDateTime.now().plusDays(20));
        form.setPresenter(" Sr. Demo ");
        form.setLocation("Main Hall");
        form.setTrack("  ");
        form.setAgendaHidden(true);
        assertTrue(trips.saveEventForm(trip, form));
        final TripEvent saved = trip.getTripEvents().get(0);
        assertEquals(saved.detail(TripEvent.Detail.PRESENTER), "Sr. Demo");
        assertEquals(saved.detail(TripEvent.Detail.LOCATION), "Main Hall");
        assertNull(saved.detail(TripEvent.Detail.TRACK), "a blank field is not stored");
        assertTrue(saved.isAgendaHidden());

        final TripEventForm reopened = trips.eventFormFor(trip, null, saved.getId());
        assertTrue(reopened.isGeneric(), "agenda fields never swap the dialog to a transport form");
        assertEquals(reopened.getPresenter(), "Sr. Demo");
        assertTrue(reopened.isAgendaHidden());

        reopened.setPresenter(null);
        reopened.setLocation(null);
        reopened.setAgendaHidden(false);
        assertTrue(trips.saveEventForm(trip, reopened));
        assertNull(trip.getTripEvent(saved.getId()).getDetails(), "a plain event stays detail-free");
        assertFalse(trips.eventFormFor(trip, "FLIGHT", null).isAgendaSection());
    }

    // ------------------------------------------------------------------ listings

    @Test
    public void conferencesStayOutOfThePilgrimageListings() {
        // A fixed public listing through a spy: the real one reads the trip date index, which other classes in
        // the shared suite clear and rebuild, so a trip saved here is not reliably listed by the time it is read.
        final Trip conference = Trip.builder().title("Listing Conference").kind(TripKind.CONFERENCE)
                .orgId(FakeData.CFPW_ORG_ID).provider("CFPW").openToPublic(true)
                .language(org.paulsens.trip.model.Language.English)
                .startDate(LocalDateTime.now().plusDays(10)).endDate(LocalDateTime.now().plusDays(12)).build();
        final Trip pilgrimage = Trip.builder().title("Listing Pilgrimage").orgId(FakeData.CFPW_ORG_ID)
                .provider("CFPW").openToPublic(true).language(org.paulsens.trip.model.Language.English)
                .startDate(LocalDateTime.now().plusDays(11)).endDate(LocalDateTime.now().plusDays(20)).build();
        final TripCommands trips = org.mockito.Mockito.spy(new TripCommands());
        org.mockito.Mockito.doReturn(List.of(conference, pilgrimage)).when(trips).getPublicTrips();

        assertEquals(trips.getPublicTrips("English"), List.of(pilgrimage), "the sidebar's language list");
        assertEquals(trips.getPublicTripLanguages(), List.of(org.paulsens.trip.model.Language.English));
        assertFalse(trips.getCountdownTrips(400).contains(conference), "no countdown card for a conference");
        final ContentInstance listing = new ContentInstance("cf-listing", "page:x", "Trips", "pilgrimages", 1,
                new HashMap<>(Map.of("language", "English")), null, 0, 1, null, null);
        assertFalse(new TripCommands().getPublicTripsFor(listing).stream().anyMatch(Trip::isConference),
                "the Trip Listings section never lists a conference");
    }

    @Test
    public void theConferenceSeedIsWhatTheDocsDescribe() {
        final Trip conference = dao.getTrip(FakeData.CONF_TRIP_ID, Cached.NO).orElseThrow();
        assertTrue(conference.isConference());
        final RegistrationOption admission = conference.getRegOptions().get(0);
        assertTrue(admission.isPriced());
        assertTrue(admission.choice(FakeData.CONF_HOTEL_WEEKEND).isAttestationRequired());
        assertTrue(conference.getTripEvents().stream().anyMatch(TripEvent::isAgendaHidden));
        assertEquals(conference.getPeople().size(), 1, "Trinity, confirmed");
        final List<Registration> regs = dao.getRegistrations(FakeData.CONF_TRIP_ID, Cached.NO);
        assertEquals(regs.size(), 2);
        assertTrue(regs.stream().filter(r -> r.getStatus() == Registration.Status.CONFIRMED)
                .allMatch(r -> r.getTicketCode() != null));
    }

    // ------------------------------------------------------------------ helpers

    private static RegistrationOption admission() {
        return new RegistrationOption(1, "Admission", "", true, RegistrationOption.QuestionType.CHOICE, true,
                List.of(new OptionChoice("full", "Full weekend", 15000L, null, null, null, null)));
    }

    private Person savedPerson() throws IOException {
        final Person person = Person.builder().first("Conf" + RandomData.genAlpha(4)).last(RandomData.genAlpha(6))
                .email("conf." + RandomData.genAlpha(8) + "@example.com").build();
        assertTrue(dao.savePerson(person));
        return person;
    }

    private static Caller callerFor(final Person person) {
        final PrivilegeCommands none = org.mockito.Mockito.mock(PrivilegeCommands.class);
        org.mockito.Mockito.when(none.check(org.mockito.Mockito.any(), org.mockito.Mockito.any(),
                org.mockito.Mockito.any())).thenReturn(false);
        return new Caller(person.getId(), false,
                new org.paulsens.trip.audit.AuditActor(person.getEmail(), person.getId().getValue()), none);
    }
}
