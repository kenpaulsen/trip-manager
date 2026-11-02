package org.paulsens.trip.checkout;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.paulsens.trip.dynamo.DAO;
import org.paulsens.trip.model.AgeBand;
import org.paulsens.trip.model.Concession;
import org.paulsens.trip.model.DiscountCode;
import org.paulsens.trip.model.PriceBreakdown;
import org.paulsens.trip.model.PriceBreakdown.Line;
import org.paulsens.trip.model.PriceBreakdown.LineKind;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.RegistrationOption.QuestionType;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.Test;

import static org.paulsens.trip.checkout.GoldenTrip.ADULT;
import static org.paulsens.trip.checkout.GoldenTrip.BEFORE;
import static org.paulsens.trip.checkout.GoldenTrip.CHILD_7;
import static org.paulsens.trip.checkout.GoldenTrip.INFANT_2;
import static org.paulsens.trip.checkout.GoldenTrip.answers;
import static org.paulsens.trip.checkout.GoldenTrip.trip;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Golden prices for the {@link GoldenTrip} conference: every rule of {@link CheckoutPricing} with exact totals and
 * exact line lists, two summaries pinned byte for byte (they land in Bill descriptions), and a JSON round trip.
 */
public class CheckoutPricingTest {
    private static final Line FULL = new Line("Full weekend", LineKind.BASE, 15000L, "choice:full");
    private static final Line SHIRT = new Line("T-shirt", LineKind.ADD_ON, 2000L, "choice:shirt");

    @Test
    public void anAdultPaysTheFullWeekend() {
        final PriceBreakdown price = CheckoutPricing.price(trip(), ADULT, answers("full", null), null, BEFORE);
        assertEquals(price.getTotalCents(), 15000L);
        assertEquals(price.getLines(), List.of(FULL));
        assertEquals(price.getRegistrationType(), "Full weekend");
        assertEquals(price.getComputedAt(), BEFORE);
        final PriceBreakdown.Inputs inputs = price.getInputs();
        assertEquals(inputs.getAgeAtStart(), Integer.valueOf(46));
        assertEquals(inputs.getTripStart(), LocalDate.of(2027, 3, 5));
        assertEquals(inputs.getChoiceIds(), List.of("full"));
        assertEquals(inputs.getTypeChoiceId(), "full");
        assertNull(inputs.getCodeText(), "a blank code is recorded as none");
        assertTrue(inputs.getAttestedChoiceIds().isEmpty(), "attestations are the caller's, not pricing's");
        assertEquals(price.summary(), "Full weekend $150.00 = $150.00");
    }

    @Test
    public void aChildOfSevenPaysAtMostFifty() {
        final PriceBreakdown price = CheckoutPricing.price(trip(), CHILD_7, answers("full", null), "", BEFORE);
        assertEquals(price.getTotalCents(), 5000L);
        assertEquals(price.getLines(), List.of(FULL,
                new Line("Child (4-10) at most $50.00", LineKind.AGE_BAND, -10000L, "band:4-10")));
        assertEquals(price.getInputs().getAgeAtStart(), Integer.valueOf(7));
        assertEquals(price.summary(), "Full weekend $150.00; Child (4-10) at most $50.00 -$100.00 = $50.00");
    }

    @Test
    public void anInfantIsFreeButTheShirtIsNot() {
        final PriceBreakdown price = CheckoutPricing.price(trip(), INFANT_2, answers("full", "shirt"), null, BEFORE);
        assertEquals(price.getTotalCents(), 2000L);
        assertEquals(price.getLines(), List.of(FULL,
                new Line("Infant (0-3) complimentary", LineKind.AGE_BAND, -15000L, "band:0-3"), SHIRT),
                "the band line follows the admission it changed; a band never touches an add-on");
        assertEquals(price.getInputs().getChoiceIds(), List.of("full", "shirt"));
    }

    /** The band rule names the ADMISSION only: a second priced pick-one question (a dinner) is never adjusted. */
    @Test
    public void aBandNeverAdjustsASecondPricedQuestion() {
        final Trip trip = trip();
        final RegistrationOption dinner = new RegistrationOption(4, "Dinner", "", true, QuestionType.CHOICE, false,
                List.of(GoldenTrip.choice("gala", "Gala dinner", 3000L)));
        trip.setRegOptions(List.of(trip.getRegOptions().get(0), trip.getRegOptions().get(1), dinner));
        final Map<String, String> answers = new java.util.HashMap<>(answers("full", "shirt"));
        answers.put("4", "gala");
        final PriceBreakdown price = CheckoutPricing.price(trip, INFANT_2, answers, null, BEFORE);
        assertEquals(price.getTotalCents(), 5000L, "free admission; the shirt and the dinner cost what they cost");
        assertEquals(price.getLines(), List.of(FULL,
                new Line("Infant (0-3) complimentary", LineKind.AGE_BAND, -15000L, "band:0-3"), SHIRT,
                new Line("Gala dinner", LineKind.ADD_ON, 3000L, "choice:gala")));
        assertEquals(CheckoutPricing.isAdmissionQuestion(trip, trip.getRegOptions().get(0)), true);
        assertEquals(CheckoutPricing.isAdmissionQuestion(trip, dinner), false);
        assertEquals(CheckoutPricing.isAdmissionQuestion(null, dinner), false);
        assertEquals(CheckoutPricing.isAdmissionQuestion(trip, null), false);
    }

    @Test
    public void aSpeakerPaysOnlyForTheShirt() {
        final PriceBreakdown price = CheckoutPricing.price(trip(), ADULT, answers("full", "shirt"), " speaker ",
                BEFORE);
        assertEquals(price.getTotalCents(), 2000L);
        assertEquals(price.getLines(), List.of(FULL, SHIRT,
                new Line("code SPEAKER", LineKind.DISCOUNT_CODE, -15000L, "code:SPEAKER")));
        assertEquals(price.getInputs().getCodeText(), "SPEAKER", "the code is recorded normalized");
        assertEquals(price.summary(), "Full weekend $150.00; T-shirt $20.00; code SPEAKER -$150.00 = $20.00");
    }

    @Test
    public void earlyTenTakesTenPercentOfEverythingBeforeItExpires() {
        final PriceBreakdown price = CheckoutPricing.price(trip(), ADULT, answers("full", "shirt"), "early10",
                LocalDateTime.of(2027, 1, 15, 8, 0));
        assertEquals(price.getTotalCents(), 15300L);
        assertEquals(price.getLines(), List.of(FULL, SHIRT,
                new Line("code EARLY10", LineKind.DISCOUNT_CODE, -1700L, "code:EARLY10")));
    }

    @Test
    public void earlyTenIsUsableOnItsLastDay() {
        final PriceBreakdown price = CheckoutPricing.price(trip(), ADULT, answers("full", null), "EARLY10",
                LocalDateTime.of(2027, 1, 31, 23, 59));
        assertEquals(price.getTotalCents(), 13500L);
    }

    @Test
    public void anExpiredCodeIsAProblemAndIsNeverPriced() {
        final LocalDateTime after = LocalDateTime.of(2027, 2, 1, 8, 0);
        final Trip trip = trip();
        final Map<String, String> answers = answers("full", "shirt");
        assertEquals(CheckoutValidator.problems(trip, "p1", answers, "EARLY10", Set.of(), after, 0), List.of(
                new CheckoutProblem(CheckoutProblem.CODE_INVALID, "p1", "EARLY10",
                        "The code EARLY10 expired on January 31, 2027.")));
        final PriceBreakdown price = CheckoutPricing.price(trip, ADULT, answers, "EARLY10", after);
        assertEquals(price.getTotalCents(), 17000L);
        assertEquals(price.getLines(), List.of(FULL, SHIRT));
    }

    @Test
    public void speakerOnFridayOnlyIsAProblemAndIsNeverPriced() {
        final Trip trip = trip();
        final Map<String, String> answers = answers("fri", null);
        assertEquals(CheckoutValidator.problems(trip, "p1", answers, "SPEAKER", Set.of(), BEFORE, 0), List.of(
                new CheckoutProblem(CheckoutProblem.CODE_INVALID, "p1", "SPEAKER",
                        "The code SPEAKER does not apply to any of your choices.")));
        final PriceBreakdown price = CheckoutPricing.price(trip, ADULT, answers, "SPEAKER", BEFORE);
        assertEquals(price.getTotalCents(), 6000L);
        assertEquals(price.getLines(), List.of(new Line("Fri only", LineKind.BASE, 6000L, "choice:fri")));
        assertEquals(price.getRegistrationType(), "Fri only");
    }

    @Test
    public void noBirthdateIsPricedAsAnAdultWithANote() {
        final PriceBreakdown price = CheckoutPricing.price(trip(), null, answers("full", null), null, BEFORE);
        assertEquals(price.getTotalCents(), 15000L);
        assertEquals(price.getLines(), List.of(FULL,
                new Line("No birthdate on file: priced as adult", LineKind.NOTE, 0L, null)));
        assertNull(price.getInputs().getAgeAtStart());
        assertEquals(price.summary(), "Full weekend $150.00; No birthdate on file: priced as adult = $150.00");
    }

    @Test
    public void noBirthdateNeedsNoNoteWhenTheTripHasNoBands() {
        final Trip trip = trip();
        trip.getCheckout().setAgeBands(new ArrayList<>());
        final PriceBreakdown price = CheckoutPricing.price(trip, null, answers("full", null), null, BEFORE);
        assertEquals(price.getLines(), List.of(FULL));
    }

    @Test
    public void anAmountOffLargerThanTheSubtotalNeverGoesNegative() {
        final Trip trip = trip();
        trip.getCheckout().getCodes().add(new DiscountCode("BIGOFF", null,
                GoldenTrip.concession(Concession.Kind.TOTAL, 0, 100000L), null, null, null, null));
        final PriceBreakdown price = CheckoutPricing.price(trip, ADULT, answers("full", "shirt"), "bigoff", BEFORE);
        assertEquals(price.getTotalCents(), 0L);
        assertEquals(price.getLines(), List.of(FULL, SHIRT,
                new Line("code BIGOFF", LineKind.DISCOUNT_CODE, -17000L, "code:BIGOFF")),
                "the concession clamps, so the floor never has to");
        assertEquals(price.summary(), "Full weekend $150.00; T-shirt $20.00; code BIGOFF -$170.00 = $0.00");
    }

    @Test
    public void theFloorBringsANegativeTotalToZero() {
        final List<Line> lines = new ArrayList<>();
        assertEquals(CheckoutPricing.floor(lines, 500L), 500L);
        assertEquals(CheckoutPricing.floor(lines, 0L), 0L);
        assertTrue(lines.isEmpty(), "a non-negative total is left alone");
        assertEquals(CheckoutPricing.floor(lines, -250L), 0L);
        assertEquals(lines, List.of(new Line("Not below zero", LineKind.FLOOR, 250L, "floor")));
    }

    @Test
    public void aCodeThatChangesNothingAddsNoLine() {
        final Trip trip = trip();
        trip.getCheckout().getCodes().add(new DiscountCode("FRIENDS", null,
                GoldenTrip.concession(Concession.Kind.PERCENT, 5000, 0L), null, List.of("full"), null, null));
        final PriceBreakdown price = CheckoutPricing.price(trip, INFANT_2, answers("full", null), "FRIENDS", BEFORE);
        assertEquals(price.getTotalCents(), 0L, "half of an already-free infant pass is still free");
        assertTrue(price.getLines().stream().noneMatch(line -> line.getKind() == LineKind.DISCOUNT_CODE));
        assertEquals(price.getInputs().getCodeText(), "FRIENDS");
    }

    @Test
    public void inactiveUnknownAndExhaustedCodesAreNeverPriced() {
        final Trip trip = trip();
        trip.getCheckout().code("EARLY10").setActive(false);
        trip.getCheckout().code("SPEAKER").setMaxUses(0);
        for (final String code : List.of("EARLY10", "SPEAKER", "NOPE")) {
            final PriceBreakdown price = CheckoutPricing.price(trip, ADULT, answers("full", null), code, BEFORE);
            assertEquals(price.getTotalCents(), 15000L, code);
        }
    }

    @Test
    public void theBandAppliesToTheDayPassToo() {
        final PriceBreakdown price = CheckoutPricing.price(trip(), CHILD_7, answers("sat", null), null, BEFORE);
        assertEquals(price.getTotalCents(), 5000L);
        assertEquals(price.getLines().get(1),
                new Line("Child (4-10) at most $50.00", LineKind.AGE_BAND, -1000L, "band:4-10"));
    }

    @Test
    public void anUnlabelledBandIsNamedByItsAges() {
        final Trip trip = trip();
        trip.getCheckout().getAgeBands().add(new AgeBand(" ", 65, null,
                GoldenTrip.concession(Concession.Kind.PERCENT, 1000, 0L)));
        final PriceBreakdown price = CheckoutPricing.price(trip, LocalDate.of(1950, 1, 1), answers("full", null),
                null, BEFORE);
        assertEquals(price.getTotalCents(), 13500L);
        assertEquals(price.getLines().get(1),
                new Line("Age 65+ 10% discount", LineKind.AGE_BAND, -1500L, "band:65+"));
    }

    @Test
    public void anAdultBandThatChangesNothingAddsNoLine() {
        final Trip trip = trip();
        trip.getCheckout().getAgeBands().add(new AgeBand("Adult", 11, null,
                GoldenTrip.concession(Concession.Kind.FIXED_PRICE, 0, 99900L)));
        final PriceBreakdown price = CheckoutPricing.price(trip, ADULT, answers("full", null), null, BEFORE);
        assertEquals(price.getLines(), List.of(FULL));
    }

    @Test
    public void nothingPickedIsNoCharge() {
        final PriceBreakdown price = CheckoutPricing.price(trip(), ADULT, Map.of(), null, BEFORE);
        assertEquals(price.getTotalCents(), 0L);
        assertTrue(price.getLines().isEmpty());
        assertNull(price.getRegistrationType());
        assertNull(price.getInputs().getTypeChoiceId());
        assertEquals(price.summary(), "No charge = $0.00");
        assertEquals(CheckoutPricing.price(trip(), ADULT, null, null, BEFORE).getTotalCents(), 0L,
                "null answers read as none");
    }

    @Test
    public void unknownWithdrawnHiddenAndRepeatedPicksAreNotPriced() {
        final Trip trip = trip();
        GoldenTrip.question(trip, GoldenTrip.ADMISSION).choice("sat").setOffered(false);
        assertEquals(CheckoutPricing.price(trip, ADULT, answers("sat", "shirt,shirt,ghost"), null, BEFORE)
                .getLines(), List.of(SHIRT), "a withdrawn admission and an unknown extra are the validator's");
        GoldenTrip.question(trip, GoldenTrip.EXTRAS).setShow(false);
        assertEquals(CheckoutPricing.price(trip, ADULT, answers("full", "shirt"), null, BEFORE).getLines(),
                List.of(FULL), "a hidden question takes no part");
    }

    @Test
    public void aFreeAdmissionStillNamesTheRegistrationType() {
        final Trip trip = trip();
        GoldenTrip.question(trip, GoldenTrip.ADMISSION).getChoices().add(GoldenTrip.choice("clergy", "Clergy", 0L));
        final PriceBreakdown price = CheckoutPricing.price(trip, ADULT, answers("clergy", "shirt"), null, BEFORE);
        assertEquals(price.getLines(), List.of(new Line("Clergy", LineKind.BASE, 0L, "choice:clergy"), SHIRT));
        assertEquals(price.getRegistrationType(), "Clergy");
        assertEquals(price.getTotalCents(), 2000L);
    }

    @Test
    public void withoutATypeQuestionEveryPickIsAnAddOn() {
        final Trip trip = trip();
        GoldenTrip.question(trip, GoldenTrip.ADMISSION).setRequiredFlag(false);
        final PriceBreakdown price = CheckoutPricing.price(trip, ADULT, answers("full", "shirt"), null, BEFORE);
        assertEquals(price.getLines(), List.of(
                new Line("Full weekend", LineKind.ADD_ON, 15000L, "choice:full"), SHIRT));
        assertNull(price.getRegistrationType());
        assertNull(price.getInputs().getTypeChoiceId());
        assertNull(CheckoutPricing.typeQuestion(trip));
    }

    @Test
    public void theAdmissionLineComesFirstWhateverTheQuestionOrder() {
        final Trip trip = trip();
        final List<RegistrationOption> reordered = new ArrayList<>(trip.getRegOptions());
        reordered.add(reordered.remove(0));
        trip.setRegOptions(reordered);
        final PriceBreakdown price = CheckoutPricing.price(trip, ADULT, answers("full", "shirt"), null, BEFORE);
        assertEquals(price.getLines(), List.of(FULL, SHIRT));
        assertEquals(CheckoutPricing.typeQuestion(trip).getType(), QuestionType.CHOICE);
    }

    @Test
    public void aTripWithoutAStartDateHasNoAge() {
        final Trip trip = trip();
        trip.setStartDate(null);
        final PriceBreakdown price = CheckoutPricing.price(trip, CHILD_7, answers("full", null), null, BEFORE);
        assertEquals(price.getTotalCents(), 15000L);
        assertNull(price.getInputs().getTripStart());
        assertNull(price.getInputs().getAgeAtStart());
    }

    @Test
    public void theBreakdownRoundTripsThroughTheStoreMapper() throws Exception {
        final ObjectMapper mapper = DAO.getInstance().getMapper();
        final PriceBreakdown price = CheckoutPricing.price(trip(), CHILD_7, answers("full", "shirt"), "SPEAKER",
                BEFORE);
        final PriceBreakdown back = mapper.readValue(mapper.writeValueAsString(price), PriceBreakdown.class);
        assertEquals(back, price);
        assertEquals(back.summary(), price.summary());
        assertEquals(back.getTotalCents(), 2000L);
    }
}
