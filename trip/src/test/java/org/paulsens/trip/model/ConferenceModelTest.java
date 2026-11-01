package org.paulsens.trip.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import nl.jqno.equalsverifier.EqualsVerifier;
import nl.jqno.equalsverifier.Warning;
import org.paulsens.trip.dynamo.DAO;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/**
 * The conference foundations' model changes (docs/conference.md, docs/checkout.md, docs/checkin.md): the trip kind,
 * typed and priceable registration questions, the widened registration, the stored price breakdown, the FIXED_PRICE
 * concession and the agenda details on events. Every field is additive, so each type is also read from JSON stored
 * before the field existed.
 */
public class ConferenceModelTest {
    private static final ObjectMapper MAPPER = DAO.getInstance().getMapper();
    private static final Person.Id ADA = Person.Id.from("person-ada");
    private static final LocalDateTime AT = LocalDateTime.of(2027, 3, 1, 9, 0);

    // ------------------------------------------------------------------ TripKind and Trip.kind

    @Test
    public void aTripStoredBeforeKindsIsAPilgrimage() throws Exception {
        final Trip legacy = MAPPER.readValue("{\"id\":\"t1\",\"title\":\"Old\"}", Trip.class);
        assertEquals(legacy.getKind(), TripKind.PILGRIMAGE);
        assertFalse(legacy.isConference());
        assertEquals(Trip.builder().build().getKind(), TripKind.PILGRIMAGE);
    }

    @Test
    public void aConferenceRoundTrips() throws Exception {
        final Trip conference = Trip.builder().id("c1").kind(TripKind.CONFERENCE).build();
        assertTrue(conference.isConference());
        final Trip back = MAPPER.readValue(MAPPER.writeValueAsString(conference), Trip.class);
        assertEquals(back.getKind(), TripKind.CONFERENCE);
        assertTrue(back.isConference());
    }

    @Test
    public void kindCopyReadsNaturally() {
        assertEquals(TripKind.PILGRIMAGE.getRegisterLabel(), "Join Pilgrimage");
        assertEquals(TripKind.PILGRIMAGE.getPeopleNounTitle(), "Travelers",
                "a pilgrimage's button text must stay exactly what it was");
        assertEquals(TripKind.CONFERENCE.getRegisterLabel(), "Register");
        assertEquals(TripKind.CONFERENCE.getPeopleNoun(), "attendees");
        assertEquals(TripKind.CONFERENCE.getPeopleNounTitle(), "Attendees");
        assertEquals(TripKind.CONFERENCE.getDisplayValue(), "Conference");
    }

    // ------------------------------------------------------------------ OptionChoice

    @Test
    public void optionChoiceEquality() {
        EqualsVerifier.forClass(OptionChoice.class).suppress(Warning.NONFINAL_FIELDS).verify();
    }

    @Test
    public void optionChoiceDefaultsAreSafe() {
        final OptionChoice blank = new OptionChoice(null, null, -5L, -1, "  ", null, null);
        assertEquals(blank.getId().length(), 8, "a missing id is minted");
        assertEquals(blank.getLabel(), "");
        assertEquals(blank.getPriceCents(), 0L, "a negative price is free");
        assertNull(blank.getCapacity(), "a negative capacity is unlimited");
        assertNull(blank.getAttestationText(), "blank attestation requires nothing");
        assertFalse(blank.isAttestationRequired());
        assertTrue(blank.getDays().isEmpty());
        assertTrue(blank.isOffered(), "null enabled reads as offered");
        assertFalse(blank.isPriced());
        assertEquals(new OptionChoice("Shirt").getLabel(), "Shirt");
    }

    @Test
    public void optionChoiceEditorPropertiesRoundTrip() {
        final OptionChoice choice = new OptionChoice("Full weekend");
        choice.setPriceDollars(149.99);
        assertEquals(choice.getPriceCents(), 14999L);
        assertEquals(choice.getPriceDollars(), 149.99);
        choice.setPriceDollars(null);
        assertEquals(choice.getPriceCents(), 0L);
        choice.setPriceDollars(-3.0);
        assertEquals(choice.getPriceCents(), 0L);
        choice.setPriceCents(-1L);
        assertEquals(choice.getPriceCents(), 0L);
        choice.setPriceCents(15000L);
        assertEquals(choice.getDisplayLabel(), "Full weekend ($150.00)");
        choice.setDaysText(" 2027-03-05, ,2027-03-06 ");
        assertEquals(choice.getDays(), List.of("2027-03-05", "2027-03-06"));
        assertEquals(choice.getDaysText(), "2027-03-05, 2027-03-06");
        choice.setDaysText(null);
        assertTrue(choice.getDays().isEmpty());
        choice.setAttestationText(" I am a hotel guest ");
        assertEquals(choice.getAttestationText(), "I am a hotel guest");
        assertTrue(choice.isAttestationRequired());
        choice.setOffered(false);
        assertEquals(choice.getEnabled(), Boolean.FALSE);
        assertFalse(choice.isOffered());
        choice.setOffered(true);
        assertNull(choice.getEnabled(), "offered is stored as the default");
        assertEquals(new OptionChoice("Free").getDisplayLabel(), "Free", "an unpriced choice shows no price");
    }

    // ------------------------------------------------------------------ RegistrationOption (typed questions)

    @Test
    public void aQuestionStoredBeforeTypesIsOptionalFreeText() throws Exception {
        final RegistrationOption legacy = MAPPER.readValue(
                "{\"id\":3,\"shortDesc\":\"Diet\",\"longDesc\":\"\",\"show\":true}", RegistrationOption.class);
        assertEquals(legacy.getType(), RegistrationOption.QuestionType.TEXT);
        assertFalse(legacy.isRequiredFlag());
        assertTrue(legacy.isAgeBandsApplyFlag());
        assertTrue(legacy.getChoices().isEmpty());
        assertFalse(legacy.isChoiceType());
        assertFalse(legacy.isPriced());
        assertEquals(legacy, new RegistrationOption(3, "Diet", "", true));
    }

    @Test
    public void aTypedQuestionRoundTrips() throws Exception {
        final RegistrationOption admission = admission();
        final RegistrationOption back = MAPPER.readValue(MAPPER.writeValueAsString(admission),
                RegistrationOption.class);
        assertEquals(back, admission);
        assertEquals(back.getChoices().get(0).getPriceCents(), 15000L);
        assertEquals(RegistrationOption.QuestionType.MULTI_CHOICE.getDisplayValue(), "Pick any");
    }

    @Test
    public void choiceAnswersParseAndDisplayByLabel() {
        final RegistrationOption admission = admission();
        assertTrue(admission.isChoiceType());
        assertTrue(admission.isPriced());
        assertEquals(admission.choiceIdsOf(" full "), List.of("full"));
        assertEquals(admission.choiceIdsOf(""), List.of());
        assertEquals(admission.displayOf("full"), "Full weekend");
        assertEquals(admission.displayOf("gone"), "gone", "an unknown id shows as stored, never vanishes");
        assertNull(admission.displayOf(" "), "unanswered reads as null");
        assertTrue(admission.isAnswered("full"));
        assertFalse(admission.isAnswered(null));
        assertFalse(admission.isAnswered(""));
        assertNull(admission.choice(null));
        assertNull(admission.choice("nope"));

        final RegistrationOption extras = new RegistrationOption(2, "Extras", "", true,
                RegistrationOption.QuestionType.MULTI_CHOICE, null,
                List.of(new OptionChoice("shirt", "T-shirt", 2000L, null, null, null, null),
                        new OptionChoice("meal", "Dinner", 0L, null, null, null, Boolean.FALSE)), Boolean.FALSE);
        assertEquals(extras.choiceIdsOf("shirt, meal"), List.of("shirt", "meal"));
        assertEquals(extras.displayOf("shirt,meal"), "T-shirt, Dinner");
        assertEquals(extras.getOfferedChoices().size(), 1, "a withdrawn choice is never offered");
        assertFalse(extras.isAgeBandsApplyFlag());

        final RegistrationOption yesNo = new RegistrationOption(5, "Photos ok?", "", true,
                RegistrationOption.QuestionType.YES_NO, true, null, null);
        assertEquals(yesNo.displayOf(RegistrationOption.YES), "Yes");
        assertTrue(yesNo.isAnswered(RegistrationOption.NO));
        assertEquals(yesNo.choiceIdsOf("Yes"), List.of(), "only choice questions name choices");
        assertEquals(new RegistrationOption(6, "Text", "", true).displayOf("hi"), "hi");
    }

    @Test
    public void editorFlagsAndChoiceListEdits() {
        final RegistrationOption question = new RegistrationOption(4, "Size", "", true);
        question.setType(null);
        assertEquals(question.getType(), RegistrationOption.QuestionType.TEXT, "a cleared type is free text");
        question.setType(RegistrationOption.QuestionType.CHOICE);
        question.setRequiredFlag(true);
        assertEquals(question.getRequired(), Boolean.TRUE);
        question.setRequiredFlag(false);
        assertNull(question.getRequired());
        question.setAgeBandsApplyFlag(false);
        assertEquals(question.getAgeBandsApply(), Boolean.FALSE);
        question.setAgeBandsApplyFlag(true);
        assertNull(question.getAgeBandsApply());
        final OptionChoice added = question.addChoice();
        assertEquals(question.getChoices(), List.of(added));
        assertTrue(question.removeChoice(added.getId()));
        assertFalse(question.removeChoice("missing"));
        question.setChoices(null);
        assertTrue(question.getChoices().isEmpty());
        assertFalse(question.isAnswered(" "), "a blank pick is unanswered");
    }

    // ------------------------------------------------------------------ Registration (widened)

    @Test
    public void registrationEquality() {
        EqualsVerifier.forClass(Registration.class).verify();
    }

    @Test
    public void aRegistrationStoredBeforeCheckoutReadsWithNoTicket() throws Exception {
        final Registration legacy = MAPPER.readValue("{\"tripId\":\"t\",\"userId\":\"person-ada\","
                + "\"created\":\"2026-01-01T10:00:00\",\"status\":\"Confirmed\",\"options\":{\"1\":\"x\"}}",
                Registration.class);
        assertNull(legacy.getTicketCode());
        assertNull(legacy.getPaymentId());
        assertNull(legacy.getPriceBreakdown());
        assertTrue(legacy.getAttestations().isEmpty());
        assertFalse(legacy.isCheckedIn());
        final String json = MAPPER.writeValueAsString(legacy);
        assertFalse(json.contains("ticketCode") || json.contains("attestations") || json.contains("checkedIn"),
                "absent fields are not written: " + json);
    }

    @Test
    public void everyNewFieldRoundTripsAndSurvivesCopies() throws Exception {
        final Map<String, String> attest = new HashMap<>();
        attest.put("hotel", "I am staying at the conference hotel");
        final Registration full = new Registration("t", ADA, AT, Registration.Status.CONFIRMED,
                new HashMap<>(Map.of("1", "full")), "pay-1", "TICKET", "Full weekend", breakdown(), attest,
                AT.plusDays(60), ADA, AT.plusDays(59));
        assertTrue(full.isCheckedIn());
        final Registration back = MAPPER.readValue(MAPPER.writeValueAsString(full), Registration.class);
        assertEquals(back, full);

        final Registration edited = full.withOptions(new HashMap<>(Map.of("1", "friday")));
        assertEquals(edited.getTicketCode(), "TICKET", "withOptions must never drop the ticket");
        assertEquals(edited.getPaymentId(), "pay-1");
        assertSame(edited.getPriceBreakdown(), full.getPriceBreakdown());
        assertEquals(edited.withStatus(Registration.Status.PENDING).getCheckedInAt(), AT.plusDays(60));
    }

    // ------------------------------------------------------------------ PriceBreakdown

    @Test
    public void breakdownEquality() {
        EqualsVerifier.forClass(PriceBreakdown.class).verify();
        EqualsVerifier.forClass(PriceBreakdown.Line.class).verify();
        EqualsVerifier.forClass(PriceBreakdown.Inputs.class).verify();
    }

    @Test
    public void theSummaryWordingIsPinned() throws Exception {
        assertEquals(breakdown().summary(),
                "Full weekend $150.00; Child (4-10) at most $50.00 -$100.00; code SPEAKER -$50.00; "
                        + "No birthdate on file: priced as adult = $0.00");
        final PriceBreakdown empty = new PriceBreakdown(null, null, 0L, null, null);
        assertEquals(empty.summary(), "No charge = $0.00");
        final PriceBreakdown back = MAPPER.readValue(MAPPER.writeValueAsString(breakdown()), PriceBreakdown.class);
        assertEquals(back, breakdown());
        assertTrue(back.getInputs().isBirthdatePresent());
        assertFalse(new PriceBreakdown.Inputs(null, null, null, null, null, null).isBirthdatePresent());
        final PriceBreakdown.Line defaults = new PriceBreakdown.Line(null, null, 5L, null);
        assertEquals(defaults.getLabel(), "");
        assertEquals(defaults.getKind(), PriceBreakdown.LineKind.NOTE);
        assertEquals(new PriceBreakdown.Line("Surcharge", PriceBreakdown.LineKind.FLOOR, 250L, null).describe(),
                "Surcharge +$2.50");
    }

    // ------------------------------------------------------------------ Concession.FIXED_PRICE

    @Test
    public void fixedPriceCapsTheGrossAndNeverRaisesIt() {
        final Concession cap = new Concession(Concession.Kind.FIXED_PRICE, 0, 5000L, null, null, null);
        assertEquals(cap.netCents(15000L, 0), 5000L);
        assertEquals(cap.netCents(3000L, 0), 3000L, "a cap above the price changes nothing");
        assertEquals(cap.label(), "at most $50.00");
        final Concession free = new Concession(Concession.Kind.FIXED_PRICE, 0, 0L, null, null, null);
        assertEquals(free.netCents(15000L, 0), 0L, "an exact price of zero is free");
    }

    // ------------------------------------------------------------------ TripEvent agenda details

    @Test
    public void agendaDetailsNeverMakeAnEventLookLikeTransport() {
        final TripEvent session = new TripEvent("e1", TripEvent.Type.EVENT, "Keynote", "", AT, null, null, null);
        assertFalse(session.hasDetails());
        assertFalse(session.isAgendaHidden());
        final Map<String, String> details = new HashMap<>();
        details.put(TripEvent.Detail.PRESENTER.key(), "Sr. Demo");
        details.put(TripEvent.Detail.AGENDA_HIDDEN.key(), "true");
        session.setDetails(details);
        assertFalse(session.hasDetails(), "a presenter is not a flight's airports");
        assertTrue(session.isAgendaHidden());
        assertEquals(session.detail(TripEvent.Detail.PRESENTER), "Sr. Demo");
        details.put(TripEvent.Detail.FROM.key(), "SEA");
        assertTrue(session.hasDetails(), "a transport key still counts");
        assertTrue(TripEvent.Detail.CARRIER.isTransport());
        assertFalse(TripEvent.Detail.TRACK.isTransport());
    }

    // ------------------------------------------------------------------ helpers

    private static RegistrationOption admission() {
        return new RegistrationOption(1, "Admission", "Which days?", true, RegistrationOption.QuestionType.CHOICE,
                true, List.of(new OptionChoice("full", "Full weekend", 15000L, 100, null,
                        List.of("2027-03-05", "2027-03-06"), null)), null);
    }

    private static PriceBreakdown breakdown() {
        return new PriceBreakdown(
                new PriceBreakdown.Inputs(7, LocalDate.of(2027, 3, 5), List.of("full"), "full", "SPEAKER", List.of()),
                List.of(new PriceBreakdown.Line("Full weekend", PriceBreakdown.LineKind.BASE, 15000L, "choice:full"),
                        new PriceBreakdown.Line("Child (4-10) at most $50.00", PriceBreakdown.LineKind.AGE_BAND,
                                -10000L, "band:child"),
                        new PriceBreakdown.Line("code SPEAKER", PriceBreakdown.LineKind.DISCOUNT_CODE, -5000L,
                                "code:SPEAKER"),
                        new PriceBreakdown.Line("No birthdate on file: priced as adult",
                                PriceBreakdown.LineKind.NOTE, 0L, null)),
                0L, "Full weekend", AT);
    }
}
