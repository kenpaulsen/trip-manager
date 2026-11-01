package org.paulsens.trip.checkout;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.paulsens.trip.model.Trip;
import org.testng.annotations.Test;

import static org.paulsens.trip.checkout.GoldenTrip.BEFORE;
import static org.paulsens.trip.checkout.GoldenTrip.START;
import static org.paulsens.trip.checkout.GoldenTrip.answers;
import static org.paulsens.trip.checkout.GoldenTrip.trip;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Every problem {@link CheckoutValidator} reports, with its exact message (pages show it as a growl summary), and the
 * cases that are NOT problems.
 */
public class CheckoutValidatorTest {
    private static final String ME = "person-1";

    private static List<CheckoutProblem> problems(final Trip trip, final Map<String, String> answers,
            final String code) {
        return CheckoutValidator.problems(trip, ME, answers, code, Set.of(), BEFORE, 0);
    }

    private static CheckoutProblem problem(final String code, final String ref, final String message) {
        return new CheckoutProblem(code, ME, ref, message);
    }

    @Test
    public void aCompleteCheckoutHasNoProblems() {
        assertTrue(problems(trip(), answers("full", "shirt"), null).isEmpty());
        assertTrue(problems(trip(), answers("full", null), "  ").isEmpty(), "a blank code is fine");
        assertTrue(problems(trip(), answers("full", null), "speaker").isEmpty(), "codes match case-insensitively");
    }

    @Test
    public void beforeTheWindowOpensRegistrationHasNotOpened() {
        final Trip trip = trip();
        trip.getCheckout().setOpensAt(BEFORE.plusDays(1));
        assertEquals(problems(trip, Map.of(), "NOPE"), List.of(problem(CheckoutProblem.CHECKOUT_CLOSED,
                "trip-golden", "Registration for Spring Conference has not opened yet.")),
                "a closed window is reported alone");
    }

    @Test
    public void afterTheWindowClosesRegistrationIsClosed() {
        final Trip trip = trip();
        trip.getCheckout().setClosesAt(BEFORE);
        assertEquals(problems(trip, answers("full", null), null), List.of(problem(CheckoutProblem.CHECKOUT_CLOSED,
                "trip-golden", "Registration for Spring Conference is closed.")));
    }

    @Test
    public void onceTheTripStartsRegistrationIsClosed() {
        final Trip trip = trip();
        trip.setTitle(null);
        final List<CheckoutProblem> found = CheckoutValidator.problems(trip, ME, answers("full", null), null,
                Set.of(), START, 0);
        assertEquals(found, List.of(problem(CheckoutProblem.CHECKOUT_CLOSED, "trip-golden",
                "Registration for this trip closed when it started.")));
    }

    @Test
    public void aTripWithoutAStartDateStaysOpen() {
        final Trip trip = trip();
        trip.setStartDate(null);
        trip.setTitle(" ");
        assertTrue(problems(trip, answers("full", null), null).isEmpty());
        trip.getCheckout().setClosesAt(BEFORE);
        assertEquals(problems(trip, answers("full", null), null).get(0).message(),
                "Registration for this trip is closed.");
    }

    @Test
    public void aShownRequiredQuestionMustBeAnswered() {
        final CheckoutProblem missing = problem(CheckoutProblem.ANSWERS_REQUIRED, GoldenTrip.ADMISSION,
                "Please answer \"Admission\".");
        assertEquals(problems(trip(), answers(null, "shirt"), null), List.of(missing));
        assertEquals(problems(trip(), answers(" ", null), null), List.of(missing));
        assertEquals(problems(trip(), null, null), List.of(missing), "null answers read as none");
    }

    @Test
    public void aHiddenRequiredQuestionIsNeverRequired() {
        final Trip trip = trip();
        GoldenTrip.question(trip, GoldenTrip.ADMISSION).setShow(false);
        assertTrue(problems(trip, Map.of(), null).isEmpty());
        GoldenTrip.question(trip, GoldenTrip.ADMISSION).setShow(null);
        assertTrue(problems(trip, Map.of(), null).isEmpty(), "only an explicitly shown question counts");
    }

    @Test
    public void anUnknownChoiceMustBePickedAgain() {
        assertEquals(problems(trip(), answers("ghost", "shirt,phantom"), null), List.of(
                problem(CheckoutProblem.CHOICE_REQUIRED, "ghost",
                        "\"ghost\" is not one of the choices for \"Admission\"; please pick again."),
                problem(CheckoutProblem.CHOICE_REQUIRED, "phantom",
                        "\"phantom\" is not one of the choices for \"Extras\"; please pick again.")));
    }

    @Test
    public void aWithdrawnChoiceMustBeReplaced() {
        final Trip trip = trip();
        GoldenTrip.question(trip, GoldenTrip.ADMISSION).choice("sat").setOffered(false);
        assertEquals(problems(trip, answers("sat", null), null), List.of(
                problem(CheckoutProblem.CHOICE_REQUIRED, "sat",
                        "\"Sat only\" is no longer offered for \"Admission\"; please pick another choice.")));
    }

    @Test
    public void anAttestationMustBeTicked() {
        final Trip trip = trip();
        GoldenTrip.question(trip, GoldenTrip.EXTRAS).choice("shirt").setAttestationText("I will collect it on site");
        final Map<String, String> answers = answers("full", "shirt,shirt");
        assertEquals(problems(trip, answers, null), List.of(problem(CheckoutProblem.ATTESTATION_REQUIRED, "shirt",
                "To choose \"T-shirt\", please tick \"I will collect it on site\".")), "each choice is checked once");
        assertTrue(CheckoutValidator.problems(trip, ME, answers, null, Set.of("shirt"), BEFORE, 0).isEmpty());
        assertEquals(CheckoutValidator.problems(trip, ME, answers, null, null, BEFORE, 0).size(), 1,
                "null attestations read as none ticked");
    }

    @Test
    public void anUnknownCodeIsInvalid() {
        assertEquals(problems(trip(), answers("full", null), " nope "), List.of(problem(CheckoutProblem.CODE_INVALID,
                "NOPE", "The code NOPE is not a discount code for this registration.")));
    }

    @Test
    public void anInactiveCodeIsInvalid() {
        final Trip trip = trip();
        trip.getCheckout().code("SPEAKER").setActive(false);
        assertEquals(problems(trip, answers("full", null), "SPEAKER"), List.of(problem(CheckoutProblem.CODE_INVALID,
                "SPEAKER", "The code SPEAKER is no longer active.")));
    }

    @Test
    public void anExpiredCodeIsInvalidTheDayAfterItsLastDay() {
        final Trip trip = trip();
        final Map<String, String> answers = answers("full", null);
        assertTrue(CheckoutValidator.problems(trip, ME, answers, "EARLY10", Set.of(),
                LocalDateTime.of(2027, 1, 31, 23, 59), 0).isEmpty());
        assertEquals(CheckoutValidator.problems(trip, ME, answers, "EARLY10", Set.of(),
                LocalDateTime.of(2027, 2, 1, 0, 0), 0), List.of(problem(CheckoutProblem.CODE_INVALID, "EARLY10",
                "The code EARLY10 expired on January 31, 2027.")));
    }

    @Test
    public void aCodeAtItsUseLimitIsInvalid() {
        final Trip trip = trip();
        final Map<String, String> answers = answers("full", null);
        assertTrue(CheckoutValidator.problems(trip, ME, answers, "SPEAKER", Set.of(), BEFORE, 4).isEmpty());
        assertEquals(CheckoutValidator.problems(trip, ME, answers, "SPEAKER", Set.of(), BEFORE, 5), List.of(
                problem(CheckoutProblem.CODE_INVALID, "SPEAKER",
                        "The code SPEAKER has already been used as many times as it allows.")));
        assertTrue(CheckoutValidator.problems(trip, ME, answers, "EARLY10", Set.of(), BEFORE, 1000).isEmpty(),
                "a code without a limit is never used up");
    }

    @Test
    public void aCodeForNoneOfThePicksIsInvalid() {
        assertEquals(problems(trip(), answers("sat", "shirt"), "SPEAKER"), List.of(problem(
                CheckoutProblem.CODE_INVALID, "SPEAKER", "The code SPEAKER does not apply to any of your choices.")));
        assertEquals(problems(trip(), Map.of(), "EARLY10").get(1).code(), CheckoutProblem.CODE_INVALID,
                "a code that applies to everything still needs something picked");
    }

    @Test
    public void problemsComeInFormOrderWithTheCodeLast() {
        final Trip trip = trip();
        GoldenTrip.question(trip, GoldenTrip.EXTRAS).choice("shirt").setAttestationText("Yes");
        final List<CheckoutProblem> found = problems(trip, answers(null, "shirt"), "NOPE");
        assertEquals(found.stream().map(CheckoutProblem::code).toList(), List.of(CheckoutProblem.ANSWERS_REQUIRED,
                CheckoutProblem.ATTESTATION_REQUIRED, CheckoutProblem.CODE_INVALID));
        assertTrue(found.stream().allMatch(each -> ME.equals(each.personId())));
    }
}
