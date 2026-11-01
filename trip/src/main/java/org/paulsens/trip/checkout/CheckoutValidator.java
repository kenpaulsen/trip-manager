package org.paulsens.trip.checkout;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.paulsens.trip.model.CheckoutConfig;
import org.paulsens.trip.model.DiscountCode;
import org.paulsens.trip.model.OptionChoice;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;

/**
 * Whether ONE person's checkout may proceed (docs/checkout.md): the window, required answers, the picked choices,
 * their attestations and the discount code. Pure, like {@link CheckoutPricing}; capacity, which needs the trip's
 * other registrations, is {@link CheckoutCapacity}'s. An empty list means the person may be priced and charged.
 */
public final class CheckoutValidator {
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    private CheckoutValidator() {
    }

    /**
     * Every problem with this person's checkout, in form order. A closed window is reported alone: nothing else
     * the person could fix would let them register.
     *
     * @param codeUsesSoFar how many registrations already carry the code ({@link CheckoutCapacity#codeUses}),
     *                      counting earlier members of the same party
     */
    public static List<CheckoutProblem> problems(final Trip trip, final String personId,
            final Map<String, String> answers, final String codeText, final Set<String> attestedChoiceIds,
            final LocalDateTime now, final int codeUsesSoFar) {
        final String closed = closedMessage(trip, now);
        if (closed != null) {
            return List.of(new CheckoutProblem(CheckoutProblem.CHECKOUT_CLOSED, personId, trip.getId(), closed));
        }
        final Map<String, String> given = (answers == null) ? Map.of() : answers;
        final Set<String> attested = (attestedChoiceIds == null) ? Set.of() : attestedChoiceIds;
        final List<CheckoutProblem> problems = new ArrayList<>();
        for (final RegistrationOption question : trip.getRegOptions()) {
            if (Boolean.TRUE.equals(question.getShow())) {
                checkQuestion(question, given.get(question.getKey()), personId, attested, problems);
            }
        }
        final String normalized = DiscountCode.normalize(codeText);
        if (!normalized.isEmpty()) {
            final List<String> picked = CheckoutPricing.picks(trip, given).stream()
                    .map(pick -> pick.choice().getId())
                    .toList();
            final String refusal = codeRefusal(trip.getCheckout().code(normalized), normalized, picked,
                    now.toLocalDate(), codeUsesSoFar);
            if (refusal != null) {
                problems.add(new CheckoutProblem(CheckoutProblem.CODE_INVALID, personId, normalized, refusal));
            }
        }
        return problems;
    }

    /**
     * Why {@code code} cannot be used, as the sentence a page shows, or null when it can. Shared with the pricing
     * engine, which passes zero uses: only the caller can count them.
     *
     * @param typed     the normalized code the person typed (named in the message when the code is unknown)
     * @param pickedIds the choices being priced, which the code must apply to at least one of
     */
    static String codeRefusal(final DiscountCode code, final String typed, final Collection<String> pickedIds,
            final LocalDate today, final int usesSoFar) {
        if (code == null) {
            return "The code " + typed + " is not a discount code for this registration.";
        }
        if (!code.isActive()) {
            return "The code " + code.getCode() + " is no longer active.";
        }
        if (code.isExpiredOn(today)) {
            return "The code " + code.getCode() + " expired on " + EXPIRY.format(code.getExpiresOn()) + ".";
        }
        if (code.getMaxUses() != null && usesSoFar >= code.getMaxUses()) {
            return "The code " + code.getCode() + " has already been used as many times as it allows.";
        }
        if (pickedIds.stream().noneMatch(code::appliesTo)) {
            return "The code " + code.getCode() + " does not apply to any of your choices.";
        }
        return null;
    }

    /** Why registration is closed at {@code now}, or null while it is open. */
    private static String closedMessage(final Trip trip, final LocalDateTime now) {
        final String name = (trip.getTitle() == null || trip.getTitle().isBlank()) ? "this trip" : trip.getTitle();
        final CheckoutConfig policy = trip.getCheckout();
        if (trip.getStartDate() != null && !now.isBefore(trip.getStartDate())) {
            return "Registration for " + name + " closed when it started.";
        }
        if (policy.getOpensAt() != null && now.isBefore(policy.getOpensAt())) {
            return "Registration for " + name + " has not opened yet.";
        }
        if (!policy.isOpenAt(now)) {
            return "Registration for " + name + " is closed.";
        }
        return null;
    }

    /** The question's required-answer, known-choice and attestation problems (each choice id once). */
    private static void checkQuestion(final RegistrationOption question, final String answer, final String personId,
            final Set<String> attested, final List<CheckoutProblem> problems) {
        // The same rule as RegistrationCommands.missingAnswers (shown + required + unanswered), which this pure
        // package cannot call: it lives on a CDI bean.
        if (question.isRequiredFlag() && !question.isAnswered(answer)) {
            problems.add(new CheckoutProblem(CheckoutProblem.ANSWERS_REQUIRED, personId, question.getKey(),
                    "Please answer \"" + question.getShortDesc() + "\"."));
            return;
        }
        for (final String choiceId : new LinkedHashSet<>(question.choiceIdsOf(answer))) {
            final OptionChoice choice = question.choice(choiceId);
            if (choice == null) {
                problems.add(new CheckoutProblem(CheckoutProblem.CHOICE_REQUIRED, personId, choiceId,
                        "\"" + choiceId + "\" is not one of the choices for \"" + question.getShortDesc()
                                + "\"; please pick again."));
            } else if (!choice.isOffered()) {
                problems.add(new CheckoutProblem(CheckoutProblem.CHOICE_REQUIRED, personId, choiceId,
                        "\"" + choice.getLabel() + "\" is no longer offered for \"" + question.getShortDesc()
                                + "\"; please pick another choice."));
            } else if (choice.isAttestationRequired() && !attested.contains(choiceId)) {
                problems.add(new CheckoutProblem(CheckoutProblem.ATTESTATION_REQUIRED, personId, choiceId,
                        "To choose \"" + choice.getLabel() + "\", please tick \"" + choice.getAttestationText()
                                + "\"."));
            }
        }
    }
}
