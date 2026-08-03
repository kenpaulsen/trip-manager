package org.paulsens.trip.api;

import java.util.Arrays;
import java.util.Locale;

/**
 * Whether a client-supplied address is one this site is willing to send a person back to.
 *
 * <p>Used by the payment flow, where a native client chooses its own return and cancel addresses because it has
 * no server-rendered page to come back to. Unchecked, that is an open redirect with an unusually good disguise:
 * the payment really does begin on this site and really does go to PayPal, so a payer returned to somebody
 * else's page has every reason to think they are still where they started.
 *
 * <p>Its own class, rather than a private method on the resource, so the rule can be tested directly. A copy of
 * it in a test proves only that the copy is right.
 */
public final class RedirectAllowlist {

    private RedirectAllowlist() {
    }

    /**
     * Whether {@code url} sits under one of the comma-separated {@code allowedPrefixes}.
     *
     * <p>A plain {@code startsWith} is <b>not</b> sufficient and was the first version of this. Allowing
     * {@code https://example.com} that way also allows {@code https://example.com.evil.test}, because a
     * hostname can be extended to the right -- so an attacker registers the longer name and the check passes.
     * The prefix must therefore end at a URL boundary: end of string, or a {@code /}, {@code ?} or {@code #}.
     *
     * <p>A prefix that already ends in {@code /} carries its own boundary, which is what makes a custom app
     * scheme such as {@code trip://} expressible.
     */
    public static boolean allows(final String url, final String allowedPrefixes) {
        if (url == null || url.isBlank() || allowedPrefixes == null) {
            return false;
        }
        final String candidate = url.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(allowedPrefixes.split(","))
                .map(prefix -> prefix.trim().toLowerCase(Locale.ROOT))
                .filter(prefix -> !prefix.isEmpty())
                .anyMatch(prefix -> matches(candidate, prefix));
    }

    private static boolean matches(final String candidate, final String prefix) {
        if (!candidate.startsWith(prefix)) {
            return false;
        }
        if (prefix.endsWith("/") || candidate.length() == prefix.length()) {
            return true;
        }
        final char next = candidate.charAt(prefix.length());
        return next == '/' || next == '?' || next == '#';
    }
}
