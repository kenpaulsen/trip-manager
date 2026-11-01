package org.paulsens.trip.checkout;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.model.AgeBand;
import org.paulsens.trip.model.CheckoutConfig;
import org.paulsens.trip.model.Concession;
import org.paulsens.trip.model.DiscountCode;
import org.paulsens.trip.model.OptionChoice;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.RegistrationOption.QuestionType;
import org.paulsens.trip.model.Trip;

/**
 * The golden conference every checkout test prices (docs/checkout.md): starts 2027-03-05; a required "Admission"
 * pick-one (Full weekend $150 / Fri only $60 / Sat only $60); an "Extras" pick-any that age bands do not touch
 * (T-shirt $20); bands Infant 0-3 free and Child 4-10 at most $50; codes SPEAKER ($0 for the full weekend, 5 uses)
 * and EARLY10 (10% off until 2027-01-31). Built fresh per call so a test may change its copy.
 */
final class GoldenTrip {
    static final LocalDateTime START = LocalDateTime.of(2027, 3, 5, 9, 0);
    static final LocalDateTime BEFORE = LocalDateTime.of(2027, 1, 15, 12, 0);
    static final String ADMISSION = "1";
    static final String EXTRAS = "2";
    static final String NOTES = "3";
    static final LocalDate ADULT = LocalDate.of(1980, 6, 1);
    static final LocalDate CHILD_7 = LocalDate.of(2019, 6, 1);
    static final LocalDate INFANT_2 = LocalDate.of(2024, 6, 1);

    private GoldenTrip() {
    }

    static Trip trip() {
        final RegistrationOption admission = new RegistrationOption(1, "Admission", "Which days?", true,
                QuestionType.CHOICE, true, List.of(
                        choice("full", "Full weekend", 15000L),
                        choice("fri", "Fri only", 6000L),
                        choice("sat", "Sat only", 6000L)), null);
        final RegistrationOption extras = new RegistrationOption(2, "Extras", "Anything else?", true,
                QuestionType.MULTI_CHOICE, false, List.of(choice("shirt", "T-shirt", 2000L)), false);
        final RegistrationOption notes = new RegistrationOption(3, "Dietary needs", "", true);
        final CheckoutConfig checkout = new CheckoutConfig(true, null, null, null, List.of(
                new AgeBand("Infant (0-3)", 0, 3, concession(Concession.Kind.COMPLIMENTARY, 0, 0L)),
                new AgeBand("Child (4-10)", 4, 10, concession(Concession.Kind.FIXED_PRICE, 0, 5000L))), List.of(
                new DiscountCode("speaker", "Speakers", concession(Concession.Kind.FIXED_PRICE, 0, 0L), null,
                        List.of("full"), null, 5),
                new DiscountCode("EARLY10", null, concession(Concession.Kind.PERCENT, 1000, 0L),
                        LocalDate.of(2027, 1, 31), null, null, null)));
        return Trip.builder().id("trip-golden").title("Spring Conference").startDate(START)
                .endDate(START.plusDays(2)).regOptions(List.of(admission, extras, notes)).checkout(checkout).build();
    }

    static OptionChoice choice(final String id, final String label, final long cents) {
        return new OptionChoice(id, label, cents, null, null, null, null);
    }

    static Concession concession(final Concession.Kind kind, final int bps, final long cents) {
        return new Concession(kind, bps, cents, null, null, null);
    }

    /** Answers picking {@code admission} and, when given, the comma-joined {@code extras}. */
    static Map<String, String> answers(final String admission, final String extras) {
        final Map<String, String> answers = new HashMap<>();
        if (admission != null) {
            answers.put(ADMISSION, admission);
        }
        if (extras != null) {
            answers.put(EXTRAS, extras);
        }
        return answers;
    }

    static RegistrationOption question(final Trip trip, final String key) {
        return trip.getRegOptions().stream().filter(opt -> key.equals(opt.getKey())).findFirst().orElseThrow();
    }
}
