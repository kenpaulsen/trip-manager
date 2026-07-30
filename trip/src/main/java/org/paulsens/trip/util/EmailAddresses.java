package org.paulsens.trip.util;

/**
 * Whether an address is usable as an email recipient.
 *
 * <p>Deliberately a lightweight syntax check, not an RFC 5322 parser. Its job is to keep unusable values out of
 * SES, and the case that matters in this data set is real: **some people are on a trip with no email address, and
 * the field holds something like {@code joe.smith}** — a name, not an address. Handing that to SES fails the whole
 * request, which in a mail merge means the recipients after it never get their mail.
 *
 * <p>Rules: non-null, non-blank, an {@code @} with at least one character before it, a {@code .} in the domain
 * with characters on both sides, and no spaces. These are the rules {@code MailCommands.validateEmail} has always
 * applied, plus one addition: a domain ending in {@code .} is now rejected ({@code a@b.}), which the old check
 * let through. They live here so the notification code can ask the same question without constructing a
 * {@code MailCommands}, which builds an SES client in a field.
 */
public final class EmailAddresses {

    private EmailAddresses() {
    }

    /**
     * The trimmed address, or {@code null} if it is not usable.
     *
     * @param candidate a bare address; callers holding a {@code "Name <addr>"} display form must extract the
     *                  address first, or the angle brackets and space will fail the check.
     */
    public static String normalize(final String candidate) {
        if (candidate == null) {
            return null;
        }
        final String email = candidate.trim();
        final int at = email.indexOf('@');
        if (at < 1) {
            return null;
        }
        // A dot must follow the '@' with at least one character between them, and at least one after it.
        final int dot = email.indexOf('.', at + 1);
        if (dot < (at + 2) || dot == email.length() - 1) {
            return null;
        }
        return (email.indexOf(' ') == -1) ? email : null;
    }

    /** Whether this address can be mailed. Notification preferences treat an invalid address as OFF. */
    public static boolean isValid(final String candidate) {
        return normalize(candidate) != null;
    }
}
