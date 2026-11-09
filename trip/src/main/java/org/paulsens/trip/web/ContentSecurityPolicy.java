package org.paulsens.trip.web;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * The site's Content-Security-Policy, built from what the pages actually load ({@code docs/security-headers.md}
 * records the reason for every source and the path to an enforcing policy).
 *
 * <p>One policy for every page rather than one per page: the page-specific sources (Stripe's frames, the
 * microsite's YouTube and Google Maps embeds) are harmless on the pages that never load them, and a single
 * string is something an operator can read in one {@code curl -I}. The media CDN origin is the one deployment
 * fact here, read from the same {@code TRIP_MEDIA_BASE_URL} the pages and {@link PdfRedirectFilter} use, so the
 * public repository holds no account-specific host.
 */
public final class ContentSecurityPolicy {

    /** The name the {@code Reporting-Endpoints} header and the {@code report-to} directive share. */
    public static final String REPORT_GROUP = "csp-endpoint";

    private ContentSecurityPolicy() {
    }

    /**
     * The policy's directives, semicolon-separated, ready for the header.
     *
     * @param mediaOrigin the media CDN's origin ({@code https://host}), or null locally where there is none.
     * @param reportPath  the context path the browser POSTs violation reports to.
     */
    public static String build(final String mediaOrigin, final String reportPath) {
        final StringJoiner policy = new StringJoiner("; ");
        // Anything a directive below does not name falls back to same-origin.
        policy.add("default-src 'self'");
        // Neither of these exist in the markup; pinning them costs nothing and closes two injection shapes.
        policy.add("base-uri 'self'");
        // The CSP twin of X-Frame-Options SAMEORIGIN: two pages frame their own origin (the donate button, the
        // itinerary's printable transactions) and nothing frames us from elsewhere.
        policy.add("frame-ancestors 'self'");
        // The donate button is a plain form posting to PayPal; every other form posts to us. Chrome also checks
        // the REDIRECT after a form post against this list, so the PayPal checkout hand-off (a postback that
        // answers with a redirect to the approval URL) must name both PayPal hosts.
        policy.add("form-action 'self' https://www.paypal.com https://www.sandbox.paypal.com");
        // Mojarra and PrimeFaces render inline scripts and on* handlers, and both evaluate the scripts an ajax
        // partial response carries (unsafe-eval). Stripe.js (plus its performance sub-origins) and the
        // analytics tag are the only foreign scripts a page loads.
        policy.add("script-src 'self' 'unsafe-inline' 'unsafe-eval' https://js.stripe.com https://*.js.stripe.com"
                + " https://www.googletagmanager.com");
        // PrimeFaces writes style attributes everywhere (unsafe-inline). A trip page's own stylesheets are media
        // library rows the CDN serves in production.
        policy.add(withOrigin("style-src 'self' 'unsafe-inline'", mediaOrigin));
        // Content editors paste image URLs from anywhere (Quill's image-by-URL is the sanctioned shape), chat and
        // branding photos come from the CDN, PrimeFaces themes inline data: images, the photo viewer and the
        // crop dialog preview blobs. Any https image is therefore allowed; scripts and frames stay narrow.
        policy.add("img-src 'self' data: blob: https:");
        policy.add(withOrigin("font-src 'self' data:", mediaOrigin));
        // Ajax, the REST API and the long-poll are same-origin; Stripe.js talks to its API; the analytics tag
        // beacons to Google's collectors.
        policy.add(withOrigin("connect-src 'self' https://api.stripe.com https://*.google-analytics.com"
                + " https://*.analytics.google.com https://www.googletagmanager.com", mediaOrigin));
        // Stripe's Payment Element and its 3-D Secure challenge frames; the microsite pages' YouTube videos,
        // Google Maps and Google Forms embeds; our own two same-origin frames.
        policy.add("frame-src 'self' https://js.stripe.com https://*.js.stripe.com https://hooks.stripe.com"
                + " https://www.youtube.com https://www.youtube-nocookie.com https://www.google.com"
                + " https://maps.google.com https://docs.google.com https://calendar.google.com");
        // The media page previews a PDF inline with an object tag pointing at the CDN (locally, at a servlet).
        policy.add(withOrigin("object-src 'self'", mediaOrigin));
        policy.add(withOrigin("media-src 'self' blob:", mediaOrigin));
        // sw.js (Web Push) and /manifest.jsf.
        policy.add("worker-src 'self'");
        policy.add("manifest-src 'self'");
        // Both reporting mechanisms: report-uri is what every browser still honours, report-to is where Chrome
        // is going. They point at the same servlet (CspReportServlet).
        policy.add("report-uri " + reportPath);
        policy.add("report-to " + REPORT_GROUP);
        return policy.toString();
    }

    /** The {@code Reporting-Endpoints} header value that gives {@link #REPORT_GROUP} its URL. */
    public static String reportingEndpoints(final String reportPath) {
        return REPORT_GROUP + "=\"" + reportPath + "\"";
    }

    /**
     * The origin of a base URL ({@code scheme://host[:port]}), or null when there is none to take. The media base
     * URL is an origin already in every deployment, but a path on it would poison the whole directive, so this
     * strips one rather than trusting the configuration.
     */
    static String originOf(final String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        final String trimmed = baseUrl.trim();
        final int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd < 0) {
            return null;
        }
        final int pathStart = trimmed.indexOf('/', schemeEnd + 3);
        final String origin = pathStart < 0 ? trimmed : trimmed.substring(0, pathStart);
        return origin.length() > schemeEnd + 3 ? origin : null;
    }

    private static String withOrigin(final String directive, final String origin) {
        return origin == null ? directive : directive + " " + origin;
    }

    /** The directive names in the policy, in order; a test's view of the policy's shape. */
    static List<String> directiveNames(final String policy) {
        final List<String> names = new ArrayList<>();
        for (final String directive : policy.split("; ")) {
            names.add(directive.substring(0, directive.indexOf(' ')));
        }
        return names;
    }
}
