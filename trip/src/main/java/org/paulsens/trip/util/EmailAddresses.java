package org.paulsens.trip.util;

import java.util.regex.Pattern;

/**
 * Whether an address is usable as an email recipient.
 *
 * <p>Deliberately a lightweight syntax check, not an RFC 5322 parser. Its job is to keep unusable values out of
 * SES, and the case that matters in this data set is real: **some people are on a trip with no email address, and
 * the field holds something like {@code joe.smith}** — a name, not an address. Handing that to SES fails the whole
 * request, which in a mail merge means the recipients after it never get their mail.
 *
 * <p>Rules: non-null, non-blank, an {@code @} with at least one character before it, a {@code .} in the domain
 * with characters on both sides, and no space of any kind ({@link Util#isAnySpace}: a tab, a no-break space or a
 * zero-width one pasted in with the address). These are the rules {@code MailCommands.validateEmail} has always
 * applied, plus one addition: a domain ending in {@code .} is now rejected ({@code a@b.}), which the old check
 * let through. They live here so the notification code can ask the same question without constructing a
 * {@code MailCommands}, which builds an SES client in a field.
 *
 * <p>It also holds the one display-name quoting for a "Name &lt;addr&gt;" ({@link #phrase}, read back by
 * {@link #unphrase}), shared by {@code MailCommands.formatEmail} (every merge recipient) and
 * {@code MailAddressCommands.composeAddress} (every composed From).
 */
public final class EmailAddresses {

    private EmailAddresses() {
    }

    /**
     * The trimmed address, or {@code null} if it is not usable.
     *
     * <p><b>Outbound recipients only, never identity.</b> This deliberately preserves case (an address's local
     * part is case-sensitive on the wire), so two spellings of one mailbox compare unequal here. Everything
     * that treats an email as a KEY -- {@code Person.setEmail}, {@code CredentialsDAO}, the email index --
     * lowercases with {@code Locale.ROOT} instead; use those paths for identity.
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
        // Any space, the invisible ones a paste brings included (Util.isAnySpace): SES refuses them all.
        return Util.hasAnySpace(email) ? null : email;
    }

    /** Whether this address can be mailed. Notification preferences treat an invalid address as OFF. */
    public static boolean isValid(final String candidate) {
        return normalize(candidate) != null;
    }

    /**
     * The first piece of a comma-separated address list that is not ONE usable address, trimmed ({@code ""} for
     * an empty piece: a doubled or trailing comma, or nothing at all); null when every piece is. Stricter than
     * {@link #isValid} on purpose: that check lets {@code a@x.org;b@y.org} and {@code a@x@y.org} through as one
     * "address", which SES would take as a single malformed destination and refuse the whole send over.
     */
    public static String firstUnusableInList(final String list) {
        for (final String piece : list.split(",", -1)) {
            if (!isOneAddress(piece.trim())) {
                return piece.trim();
            }
        }
        return null;
    }

    private static boolean isOneAddress(final String piece) {
        return isValid(piece) && piece.indexOf('@') == piece.lastIndexOf('@') && piece.indexOf(';') == -1;
    }

    /** RFC 5322's specials (bar the angle brackets, which callers refuse or strip), legal only when quoted. */
    private static final Pattern SPECIALS = Pattern.compile("[()\\[\\]:;@\\\\,.\"]");

    /**
     * A display name as a valid RFC 5322 phrase: runs of whitespace (line breaks included, so no header can be
     * injected) collapsed to one space, then as is when it is plain words, else a quoted string with its
     * backslashes and quotes escaped. "bob@home", "Team: Bob" or "Smith, John" left bare is not a valid mailbox,
     * which strict parsing refuses or reads as two. Blank for a blank name. The caller keeps angle brackets out
     * first: {@code MailCommands.bareEmail} takes the first '&lt;' and {@code MailAddressCommands.addressOf} the
     * last, quoted or not.
     */
    public static String phrase(final String name) {
        final String trimmed = (name == null) ? "" : name.replaceAll("\\s+", " ").trim();
        return SPECIALS.matcher(trimmed).find()
                ? '"' + trimmed.replace("\\", "\\\\").replace("\"", "\\\"") + '"' : trimmed;
    }

    /** The name a {@link #phrase} stands for: a quoted string unquoted and unescaped, anything else trimmed. */
    public static String unphrase(final String phrase) {
        final String trimmed = (phrase == null) ? "" : phrase.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '"' || trimmed.charAt(trimmed.length() - 1) != '"') {
            return trimmed;
        }
        return trimmed.substring(1, trimmed.length() - 1).replaceAll("\\\\(.)", "$1");
    }
}
