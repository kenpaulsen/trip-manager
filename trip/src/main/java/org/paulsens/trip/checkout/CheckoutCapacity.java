package org.paulsens.trip.checkout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.paulsens.trip.i18n.Text;
import org.paulsens.trip.model.DiscountCode;
import org.paulsens.trip.model.OptionChoice;
import org.paulsens.trip.model.PriceBreakdown;
import org.paulsens.trip.model.Registration;
import org.paulsens.trip.model.RegistrationOption;
import org.paulsens.trip.model.Trip;

/**
 * Whether a party fits (docs/checkout.md): the trip's registration limit and each choice's capacity, COUNTED from the
 * trip's registrations rather than stored on the trip, so checkout traffic never writes the trip row. A row holds a
 * place while it is CONFIRMED or PENDING. Pure: the caller reads the registrations and passes them in.
 */
public final class CheckoutCapacity {
    private CheckoutCapacity() {
    }

    /**
     * The capacity problems of registering a party, each with a null person (they belong to the party as a whole).
     *
     * @param existing     the trip's registrations, excluding the party's own (a re-registration is the caller's to
     *                     leave out, or it counts twice)
     * @param partyAnswers one answer map per person being registered
     */
    public static List<CheckoutProblem> problems(final Trip trip, final List<Registration> existing,
            final List<Map<String, String>> partyAnswers) {
        final List<CheckoutProblem> problems = new ArrayList<>();
        final List<Map<String, String>> party = (partyAnswers == null) ? List.of() : partyAnswers;
        if (party.isEmpty()) {
            return problems;
        }
        final List<Registration> holding = (existing == null) ? List.of()
                : existing.stream().filter(CheckoutCapacity::holdsPlace).toList();
        final Integer limit = trip.getRegLimit();
        if (limit != null && holding.size() + party.size() > limit) {
            problems.add(new CheckoutProblem(CheckoutProblem.CAPACITY_FULL, null, trip.getId(),
                    tripFull(trip.getTitle(), limit - holding.size(), party.size())));
        }
        for (final RegistrationOption question : trip.getRegOptions()) {
            for (final OptionChoice choice : question.getOfferedChoices()) {
                if (choice.getCapacity() != null) {
                    checkChoice(question, choice, holding, party, problems);
                }
            }
        }
        return problems;
    }

    /** How many place-holding registrations were priced with {@code code} (compared normalized); 0 for a blank one. */
    public static int codeUses(final List<Registration> existing, final String code) {
        final String wanted = DiscountCode.normalize(code);
        if (wanted.isEmpty() || existing == null) {
            return 0;
        }
        return (int) existing.stream()
                .filter(CheckoutCapacity::holdsPlace)
                .filter(reg -> wanted.equals(codeOf(reg)))
                .count();
    }

    private static boolean holdsPlace(final Registration reg) {
        return reg.getStatus() == Registration.Status.CONFIRMED || reg.getStatus() == Registration.Status.PENDING;
    }

    private static String codeOf(final Registration reg) {
        final PriceBreakdown breakdown = reg.getPriceBreakdown();
        return (breakdown == null || breakdown.getInputs() == null) ? null : breakdown.getInputs().getCodeText();
    }

    private static void checkChoice(final RegistrationOption question, final OptionChoice choice,
            final List<Registration> holding, final List<Map<String, String>> party,
            final List<CheckoutProblem> problems) {
        final long wanted = party.stream()
                .filter(answers -> picks(question, choice, answers))
                .count();
        if (wanted == 0) {
            return;
        }
        final long taken = holding.stream()
                .filter(reg -> picks(question, choice, reg.getOptions()))
                .count();
        if (taken + wanted > choice.getCapacity()) {
            problems.add(new CheckoutProblem(CheckoutProblem.CAPACITY_FULL, null, choice.getId(),
                    choiceFull(choice.getLabel(), question.getShortDesc(), choice.getCapacity() - taken, wanted)));
        }
    }

    private static boolean picks(final RegistrationOption question, final OptionChoice choice,
            final Map<String, String> answers) {
        return answers != null && question.choiceIdsOf(answers.get(question.getKey())).contains(choice.getId());
    }

    /** "X is full." or "X has only 2 places left, but your party has 3 people."; an untitled trip is "This trip". */
    private static String tripFull(final String title, final long left, final long needed) {
        if (title == null || title.isBlank()) {
            return left <= 0 ? Text.get("server.checkout.thisTripFull")
                    : Text.get("server.checkout.thisTripPlacesShort", left, needed);
        }
        return left <= 0 ? Text.get("server.checkout.tripFull", title)
                : Text.get("server.checkout.tripPlacesShort", title, left, needed);
    }

    /** The same for one choice of a question: "... but your party picked it for 3 people." */
    private static String choiceFull(final String label, final String question, final long left, final long needed) {
        return left <= 0 ? Text.get("server.checkout.choiceFull", label, question)
                : Text.get("server.checkout.choicePlacesShort", label, question, left, needed);
    }
}
