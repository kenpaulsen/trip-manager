package org.paulsens.trip.security;

/**
 * The one rule for what a NEW password must look like, shared by every path that sets one: the create-account
 * page and its REST twin, the change-password page, and the set-a-password offer after a code login.
 *
 * <p>The rule is deliberately modest -- length plus one letter and one digit -- because the real defences are
 * elsewhere (BCrypt with a pepper, the wrong-password throttle, emailed-code sign-in). Composition rules stricter
 * than this mostly produce passwords written on paper. It applies only when a password is SET: existing
 * credentials keep working, so nobody is locked out by a rule that did not exist when they chose theirs.
 */
public final class PasswordPolicy {

    /** Minimum length, counted in characters as typed. */
    public static final int MIN_LENGTH = 8;

    /** The rule in one sentence, the message every rejection carries. */
    public static final String RULE = "Passwords need at least " + MIN_LENGTH
            + " characters, including at least one letter and one number.";

    private PasswordPolicy() {
    }

    /**
     * Why this password is not acceptable, or {@code null} when it is. The message is written for the person
     * typing it, so callers can show it as-is.
     */
    public static String problem(final String password) {
        if (password == null || password.isBlank()) {
            return "A password is required.";
        }
        return accepts(password) ? null : RULE;
    }

    /** Whether the password meets the rule: {@link #MIN_LENGTH}+ characters with a letter and a digit. */
    public static boolean accepts(final String password) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_LENGTH) {
            return false;
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < password.length(); i++) {
            final char c = password.charAt(i);
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
        }
        return letter && digit;
    }
}
