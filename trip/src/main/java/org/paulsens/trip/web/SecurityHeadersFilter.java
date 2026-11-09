package org.paulsens.trip.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;

/**
 * The HTTP security headers every response carries ({@code docs/security-headers.md}).
 *
 * <p>Set here, in the application, rather than at the load balancer or through Tomcat's
 * {@code HttpHeaderSecurityFilter}. The ALB listener is the resource CodeDeploy rewrites on every blue/green
 * deployment, so a CloudFormation change to it races the deployment controller (see the listener notes in the
 * infra stack); Tomcat's filter has no Content-Security-Policy; and a filter of our own is the only shape the
 * webtest suite and a local container exercise exactly as production does. Four headers, each with its knob in
 * the live web.xml:
 * <ul>
 * <li>{@code Strict-Transport-Security}, only on a request that arrived over TLS (behind the ALB that is what
 *     {@code RemoteIpValve} derives from {@code X-Forwarded-Proto}; the spec says browsers ignore it on plain
 *     http anyway). {@code hstsMaxAgeSeconds} starts small and is raised once the site has lived under it;
 *     {@code hstsIncludeSubDomains} stays off until every subdomain is known to serve HTTPS.</li>
 * <li>{@code X-Content-Type-Options: nosniff} on everything: every content type this server sends is
 *     explicit, and the two servlets that set it themselves keep doing so (setHeader is idempotent).</li>
 * <li>{@code X-Frame-Options} ({@code frameOptions}, SAMEORIGIN: two pages frame their own origin).</li>
 * <li>The Content-Security-Policy ({@link ContentSecurityPolicy}), as {@code Content-Security-Policy-Report-Only}
 *     until {@code cspMode} is flipped to {@code enforce}, with {@code Reporting-Endpoints} naming the report
 *     servlet. Not sent on JSF resource responses (scripts, styles, images, fonts): a policy governs the document
 *     that loads them, and the header would be a kilobyte on every one of the dozens of requests a page makes.</li>
 * </ul>
 * Headers are set before the chain runs, so they are on the response whether a servlet, an error page or a
 * redirect finishes it; the mapping also covers ERROR and ASYNC dispatches so a 404 page and a resumed long-poll
 * carry them too.
 */
public class SecurityHeadersFilter implements Filter {

    /** The media CDN base URL; its origin joins the policy. Shared with {@link PdfRedirectFilter}. */
    static final String MEDIA_BASE_URL_VAR = PdfRedirectFilter.BASE_URL_VAR;
    /** Mojarra's resource prefix; what follows it is never a document. */
    static final String RESOURCE_PREFIX = "/jakarta.faces.resource/";

    static final String DEFAULT_HSTS_MAX_AGE = "300";
    static final String DEFAULT_FRAME_OPTIONS = "SAMEORIGIN";
    static final String DEFAULT_CSP_MODE = "report-only";
    static final String DEFAULT_REPORT_PATH = "/csp-report";

    /** How the policy is sent. */
    enum CspMode {
        REPORT_ONLY("Content-Security-Policy-Report-Only"),
        ENFORCE("Content-Security-Policy"),
        OFF(null);

        private final String header;

        CspMode(final String header) {
            this.header = header;
        }

        String header() {
            return header;
        }

        static CspMode parse(final String value) {
            return switch (value == null ? DEFAULT_CSP_MODE : value.trim().toLowerCase(Locale.ROOT)) {
                case "enforce" -> ENFORCE;
                case "off" -> OFF;
                case "report-only", "" -> REPORT_ONLY;
                default -> throw new IllegalArgumentException(
                        "cspMode must be report-only, enforce or off, not '" + value + "'");
            };
        }
    }

    private String hsts;
    private String frameOptions;
    private CspMode cspMode;
    private String policy;
    private String reportingEndpoints;

    @Override
    public void init(final FilterConfig config) {
        configure(param(config, "hstsMaxAgeSeconds", DEFAULT_HSTS_MAX_AGE),
                Boolean.parseBoolean(param(config, "hstsIncludeSubDomains", "false")),
                param(config, "frameOptions", DEFAULT_FRAME_OPTIONS),
                CspMode.parse(param(config, "cspMode", DEFAULT_CSP_MODE)),
                param(config, "cspReportPath", DEFAULT_REPORT_PATH),
                System.getenv(MEDIA_BASE_URL_VAR));
    }

    /** The resolved configuration; the test seam, since init() reads an environment variable. */
    void configure(final String hstsMaxAgeSeconds, final boolean includeSubDomains, final String frameOptions,
            final CspMode cspMode, final String reportPath, final String mediaBaseUrl) {
        final long maxAge = Long.parseLong(hstsMaxAgeSeconds.trim());
        if (maxAge < 0) {
            throw new IllegalArgumentException("hstsMaxAgeSeconds must not be negative: " + maxAge);
        }
        this.hsts = "max-age=" + maxAge + (includeSubDomains ? "; includeSubDomains" : "");
        this.frameOptions = frameOptions.trim();
        this.cspMode = cspMode;
        this.policy = ContentSecurityPolicy.build(ContentSecurityPolicy.originOf(mediaBaseUrl), reportPath);
        this.reportingEndpoints = ContentSecurityPolicy.reportingEndpoints(reportPath);
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest req && response instanceof HttpServletResponse resp) {
            apply(req, resp);
        }
        chain.doFilter(request, response);
    }

    private void apply(final HttpServletRequest req, final HttpServletResponse resp) {
        if (req.isSecure()) {
            resp.setHeader("Strict-Transport-Security", hsts);
        }
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("X-Frame-Options", frameOptions);
        if (cspMode != CspMode.OFF && !isResource(req)) {
            resp.setHeader(cspMode.header(), policy);
            resp.setHeader("Reporting-Endpoints", reportingEndpoints);
        }
    }

    private static boolean isResource(final HttpServletRequest req) {
        final String path = req.getServletPath();
        return path != null && path.startsWith(RESOURCE_PREFIX);
    }

    private static String param(final FilterConfig config, final String name, final String fallback) {
        final String value = config == null ? null : config.getInitParameter(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** The policy string as sent; for tests and the operator's documentation. */
    String policy() {
        return policy;
    }
}
