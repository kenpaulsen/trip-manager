package org.paulsens.trip.checkout;

/**
 * One reason a checkout cannot proceed, as the validator and the capacity check report it. {@code code} is one of
 * the constants below (what a REST client switches on); {@code message} is a complete sentence naming the question,
 * choice or code concerned, because pages put it straight into a growl SUMMARY with no detail line to explain it.
 *
 * @param code      what kind of problem: one of the constants on this type
 * @param personId  the person whose registration has the problem; null for a party-wide one (capacity)
 * @param ref       what the problem is about: a question key, a choice id, a normalized code, or the trip id
 * @param message   the human sentence a page shows
 */
public record CheckoutProblem(String code, String personId, String ref, String message) {
    /** The registration window is not open, or the trip has already started. */
    public static final String CHECKOUT_CLOSED = "CHECKOUT_CLOSED";
    /** The trip, or one picked choice, has no room left for the party. */
    public static final String CAPACITY_FULL = "CAPACITY_FULL";
    /** An answer names a choice the question does not offer (unknown, or withdrawn by the admin). */
    public static final String CHOICE_REQUIRED = "CHOICE_REQUIRED";
    /** A picked choice requires a statement the person did not tick. */
    public static final String ATTESTATION_REQUIRED = "ATTESTATION_REQUIRED";
    /** The typed discount code cannot be used for this registration. */
    public static final String CODE_INVALID = "CODE_INVALID";
    /** A shown, required question is unanswered (the same rule as {@code RegistrationCommands.missingAnswers}). */
    public static final String ANSWERS_REQUIRED = "ANSWERS_REQUIRED";
}
