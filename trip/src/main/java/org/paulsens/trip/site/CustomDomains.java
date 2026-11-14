package org.paulsens.trip.site;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.paulsens.trip.model.Organization;

/**
 * The grammar of an organization's custom domain ({@code Organization.customDomain}) and of the shared sending
 * domain list ({@code KnownSettings.MAIL_SHARED_DOMAINS}): pure functions, so {@code OrgCommands} (the sole
 * writer), {@link SiteIndex} (the reader on every request) and the mail-domain rule all agree on one shape.
 *
 * <p>A custom domain is stored lowercase with no scheme, path, port, trailing dot or leading {@code www.}: the
 * site serves both the bare name and its {@code www.} form, so storing one spelling is what keeps two orgs from
 * claiming the two halves of one domain.
 */
public final class CustomDomains {

    /**
     * One DNS label: lowercase letters, digits, inner hyphens, 1-63 characters. A host name is checked label by
     * label rather than by one {@code (label\.)+tld} pattern: that nested repetition backtracks exponentially on
     * a crafted input (SpotBugs REDOS), and this runs on admin input.
     */
    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    /** The last label: alphabetic, so a bare IP address is never a host name here. */
    private static final Pattern TOP_LABEL = Pattern.compile("[a-z]{2,63}");
    private static final int MAX_LENGTH = 253;
    private static final String WWW = "www.";

    private CustomDomains() {
    }

    /**
     * The stored spelling of what an admin typed: trimmed, lowercased, with a scheme, a path, a port, a trailing
     * dot and a leading {@code www.} removed. Null or blank in, null out. The result is not yet VALID -- that is
     * {@link #problem}'s job.
     */
    public static String normalize(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String host = raw.trim().toLowerCase(Locale.ROOT);
        final int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        host = cutAt(cutAt(cutAt(host, '/'), '?'), ':');
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.startsWith(WWW)) {
            host = host.substring(WWW.length());
        }
        return host.isBlank() ? null : host;
    }

    /**
     * Why a normalized domain cannot be any organization's custom domain, or null when it can. Uniqueness across
     * organizations is the caller's check (it needs the org list).
     *
     * @param domain     the {@link #normalize normalized} domain
     * @param baseDomain the org-site base domain ({@code unitetrip.com}): it and every name under it are the
     *                   platform's, served by slugs
     * @param shared     the shared sending domains: shared sites and shared mail, never one tenant's
     */
    public static String problem(final String domain, final String baseDomain, final List<String> shared) {
        if (domain == null || domain.length() > MAX_LENGTH || !isHostName(domain)) {
            return "A custom domain is a host name such as example.org, without https:// or a path.";
        }
        if (domain.equals("localhost") || domain.endsWith(".localhost") || domain.endsWith(".local")) {
            return "A custom domain must be a public domain name.";
        }
        final String base = normalize(baseDomain);
        if (base != null && (domain.equals(base) || domain.endsWith("." + base))) {
            return "Names under " + base + " are subdomains: set the organization's subdomain instead.";
        }
        for (final String one : shared) {
            if (domain.equals(one) || domain.endsWith("." + one)) {
                return one + " is a shared domain, so no single organization may claim it.";
            }
        }
        return null;
    }

    /** Why a custom domain needs a slug first (shown when an org without a subdomain asks for one). */
    public static final String NEEDS_SLUG = "An organization needs a subdomain before it can have a custom domain: "
            + "the domain is a second address of that site.";

    /**
     * {@link #problem} plus uniqueness: why {@code org} may not claim {@code domain}, or null when it may.
     *
     * @param orgs every organization (a fresh read), to find another holder of the same domain
     */
    public static String claimProblem(final String domain, final Organization.Id org, final List<Organization> orgs,
            final String baseDomain, final List<String> shared) {
        final String problem = problem(domain, baseDomain, shared);
        if (problem != null) {
            return problem;
        }
        final boolean taken = orgs.stream().anyMatch(other -> !other.getId().equals(org)
                && domain.equals(normalize(other.getCustomDomain())));
        return taken ? "Another organization already uses " + domain + "." : null;
    }

    /** Two or more labels, each a DNS label, the last alphabetic. */
    private static boolean isHostName(final String domain) {
        final String[] labels = domain.split("\\.", -1);
        if (labels.length < 2 || !TOP_LABEL.matcher(labels[labels.length - 1]).matches()) {
            return false;
        }
        return Arrays.stream(labels).allMatch(label -> LABEL.matcher(label).matches());
    }

    /** The two host names a custom domain serves: the domain itself and its {@code www.} form. */
    public static List<String> hostsOf(final String domain) {
        return List.of(domain, WWW + domain);
    }

    /** A comma- or whitespace-separated domain list as normalized, de-duplicated names; never null. */
    public static List<String> parseList(final String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split("[,\\s]+"))
                .map(CustomDomains::bareDomain)
                .filter(domain -> domain != null)
                .distinct()
                .toList();
    }

    /** A list entry normalized WITHOUT the www. strip: a shared list names exactly the domains SES verified. */
    private static String bareDomain(final String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String cutAt(final String host, final char separator) {
        final int at = host.indexOf(separator);
        return at < 0 ? host : host.substring(0, at);
    }
}
