package org.paulsens.trip.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Predicate;

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

    /**
     * Whether {@code url} is an {@code https} address on a host {@code isOrgHost} recognises as an
     * organization's own site -- the per-tenant complement to the configured prefixes. Same boundary rule
     * as {@link #allows}: the URL must sit under {@code https://{host}}, so a host is matched whole, never
     * as a prefix of a longer name. Plain http is refused: an org site is only ever served over TLS, and a
     * cleartext return address would be a downgrade an attacker on the path could exploit. The resolver is
     * a parameter so the rule is testable without the live site index.
     */
    public static boolean allowsOrgSite(final String url, final Predicate<String> isOrgHost) {
        final String host = httpsHostOf(url);
        return host != null && isOrgHost.test(host) && allows(url, "https://" + host);
    }

    /** The host of an https URL, or null for anything else (other schemes, no host, unparseable). */
    private static String httpsHostOf(final String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            final URI uri = new URI(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return null;
            }
            // Hostnames are case-insensitive; the site index keys lower-case slugs.
            return uri.getHost().toLowerCase(Locale.ROOT);
        } catch (final URISyntaxException ex) {
            return null;
        }
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
