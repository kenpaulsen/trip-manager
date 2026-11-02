package org.paulsens.trip.checkout;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.paulsens.trip.model.AgeBand;
import org.paulsens.trip.model.CheckoutConfig;
import org.paulsens.trip.model.DiscountCode;
import org.paulsens.trip.model.OptionChoice;
import org.paulsens.trip.model.PriceBreakdown;
import org.paulsens.trip.model.PriceBreakdown.Inputs;
import org.paulsens.trip.model.PriceBreakdown.Line;
import org.paulsens.trip.model.PriceBreakdown.LineKind;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.RegistrationOption.QuestionType;
import org.paulsens.trip.model.Trip;
import org.paulsens.trip.util.Ages;

/**
 * The priced-checkout engine (docs/checkout.md): what ONE person's registration costs, as an explained
 * {@link PriceBreakdown}. Pure -- no DAO, no FacesContext, no clock of its own -- so the same answers always price the
 * same, and a golden test can pin every rule.
 *
 * <p>Only SHOWN, priced choice questions take part. The admission choice comes first (its label is the registration
 * type), then add-ons in question order. An age band adjusts the ADMISSION price only, never an add-on (a child's
 * T-shirt costs what a T-shirt costs; a children's meal plan is its own choice at its own price), and its line
 * follows the admission line it changed. A discount code is priced only when the validator would
 * accept it (apart from its use count, which only the caller can know): an invalid code is a problem to show, never
 * a silent zero discount.
 */
public final class CheckoutPricing {
    static final String NO_BIRTHDATE_NOTE = "No birthdate on file: priced as adult";
    static final String FLOOR_LABEL = "Not below zero";
    private static final String CHOICE_REF = "choice:";

    private CheckoutPricing() {
    }

    /** One picked, offered choice of a shown priced question; {@code admission} marks the type question's pick. */
    record Pick(RegistrationOption question, OptionChoice choice, boolean admission) {
    }

    /**
     * Prices one person. {@code birthdate} may be null (priced as an adult, with a note when the trip has bands);
     * {@code codeText} may be blank; {@code now} decides code expiry and becomes {@code computedAt}.
     */
    public static PriceBreakdown price(final Trip trip, final LocalDate birthdate, final Map<String, String> answers,
            final String codeText, final LocalDateTime now) {
        final CheckoutConfig policy = trip.getCheckout();
        final LocalDate tripStart = (trip.getStartDate() == null) ? null : trip.getStartDate().toLocalDate();
        final OptionalInt age = Ages.ageAt(birthdate, tripStart);
        final List<Pick> picks = picks(trip, answers);
        final List<Line> lines = new ArrayList<>();
        // Choice id -> its price after the age band: the discount code's eligible subtotal reads it.
        final Map<String, Long> netByChoice = new LinkedHashMap<>();
        final AgeBand band = age.isPresent() ? policy.bandFor(age.getAsInt()) : null;
        for (final Pick pick : picks) {
            netByChoice.put(pick.choice().getId(), priceOne(pick, band, lines));
        }
        if (age.isEmpty() && !policy.getAgeBands().isEmpty()) {
            lines.add(new Line(NO_BIRTHDATE_NOTE, LineKind.NOTE, 0L, null));
        }
        long total = netByChoice.values().stream().mapToLong(Long::longValue).sum();
        total += applyCode(policy.code(codeText), netByChoice, now.toLocalDate(), lines);
        total = floor(lines, total);
        final Pick admission = picks.stream().filter(Pick::admission).findFirst().orElse(null);
        final String normalizedCode = DiscountCode.normalize(codeText);
        final Inputs inputs = new Inputs(age.isPresent() ? age.getAsInt() : null, tripStart,
                List.copyOf(netByChoice.keySet()), (admission == null) ? null : admission.choice().getId(),
                normalizedCode.isEmpty() ? null : normalizedCode, List.of());
        return new PriceBreakdown(inputs, lines, total,
                (admission == null) ? null : admission.choice().getLabel(), now);
    }

    /**
     * The registration-type question: the first shown, required, pick-one question with a priced choice. Null when
     * the trip has none, in which case every priced pick is an add-on and the registration has no type.
     */
    static RegistrationOption typeQuestion(final Trip trip) {
        return trip.getRegOptions().stream().filter(CheckoutPricing::isTypeQuestion).findFirst().orElse(null);
    }

    /** Whether {@code question} is the trip's registration-type question: the one the age bands adjust. */
    public static boolean isAdmissionQuestion(final Trip trip, final RegistrationOption question) {
        return trip != null && question != null && question == typeQuestion(trip);
    }

    /**
     * Every picked choice the engine prices, admission first: known, offered choices of shown priced questions,
     * each once. An unknown or withdrawn pick is skipped here because the validator refuses it.
     */
    static List<Pick> picks(final Trip trip, final Map<String, String> answers) {
        final RegistrationOption typeQuestion = typeQuestion(trip);
        final List<Pick> admission = new ArrayList<>();
        final List<Pick> addOns = new ArrayList<>();
        for (final RegistrationOption question : trip.getRegOptions()) {
            if (!Boolean.TRUE.equals(question.getShow()) || !question.isPriced()) {
                continue;
            }
            final String answer = (answers == null) ? null : answers.get(question.getKey());
            for (final String choiceId : new LinkedHashSet<>(question.choiceIdsOf(answer))) {
                final OptionChoice choice = question.choice(choiceId);
                if (choice == null || !choice.isOffered()) {
                    continue;
                }
                if (question == typeQuestion) {
                    admission.add(new Pick(question, choice, true));
                } else {
                    addOns.add(new Pick(question, choice, false));
                }
            }
        }
        admission.addAll(addOns);
        return admission;
    }

    /**
     * Brings a negative total to zero with a FLOOR line. Every rule today clamps on its own (a concession never
     * takes more than the amount it is applied to), so this is the backstop for a future rule that does not --
     * a registration must never price as a credit.
     */
    static long floor(final List<Line> lines, final long total) {
        if (total >= 0) {
            return total;
        }
        lines.add(new Line(FLOOR_LABEL, LineKind.FLOOR, -total, "floor"));
        return 0L;
    }

    private static boolean isTypeQuestion(final RegistrationOption question) {
        return Boolean.TRUE.equals(question.getShow()) && question.isRequiredFlag()
                && question.getType() == QuestionType.CHOICE && question.isPriced();
    }

    /** Adds one pick's price line (and, for the admission pick, its band line); answers the price after the band. */
    private static long priceOne(final Pick pick, final AgeBand band, final List<Line> lines) {
        final OptionChoice choice = pick.choice();
        final long price = choice.getPriceCents();
        // The admission line is kept even at $0 (a free clergy pass): it names what the person registered as.
        if (pick.admission() || choice.isPriced()) {
            lines.add(new Line(choice.getLabel(), pick.admission() ? LineKind.BASE : LineKind.ADD_ON, price,
                    CHOICE_REF + choice.getId()));
        }
        if (band == null || !pick.admission()) {
            return price;
        }
        final long net = band.getConcession().netCents(price, 0);
        if (net != price) {
            lines.add(new Line(bandLabel(band) + " " + band.getConcession().label(), LineKind.AGE_BAND,
                    net - price, "band:" + band.getRangeText()));
        }
        return net;
    }

    private static String bandLabel(final AgeBand band) {
        return band.getLabel().isBlank() ? "Age " + band.getRangeText() : band.getLabel();
    }

    /** Adds the discount code's line when the code is usable and changes the price; answers the (signed) change. */
    private static long applyCode(final DiscountCode code, final Map<String, Long> netByChoice, final LocalDate today,
            final List<Line> lines) {
        // Uses so far is the caller's to count; the validator refuses an exhausted code before anything is charged.
        if (code == null
                || CheckoutValidator.codeRefusal(code, code.getCode(), netByChoice.keySet(), today, 0) != null) {
            return 0L;
        }
        final long subtotal = netByChoice.entrySet().stream()
                .filter(entry -> code.appliesTo(entry.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
        final long delta = code.getConcession().netCents(subtotal, 0) - subtotal;
        if (delta != 0) {
            lines.add(new Line("code " + code.getCode(), LineKind.DISCOUNT_CODE, delta, "code:" + code.getCode()));
        }
        return delta;
    }
}
