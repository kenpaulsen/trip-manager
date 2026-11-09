package org.paulsens.trip.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.paulsens.trip.web.SecurityHeadersFilter.CspMode;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The security headers: HSTS only over TLS and shaped by its two knobs, nosniff and the frame option on every
 * response, the policy as report-only until told otherwise and never on a JSF resource, and the chain always
 * continued -- the filter decorates, it never answers.
 */
public class SecurityHeadersFilterTest {

    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeMethod
    public void setUp() {
        request = Mockito.mock(HttpServletRequest.class);
        response = Mockito.mock(HttpServletResponse.class);
        chain = Mockito.mock(FilterChain.class);
        Mockito.when(request.getServletPath()).thenReturn("/trip/index.jsf");
    }

    private static SecurityHeadersFilter defaults() {
        final SecurityHeadersFilter filter = new SecurityHeadersFilter();
        filter.configure(SecurityHeadersFilter.DEFAULT_HSTS_MAX_AGE, false, SecurityHeadersFilter.DEFAULT_FRAME_OPTIONS,
                CspMode.REPORT_ONLY, SecurityHeadersFilter.DEFAULT_REPORT_PATH, "https://media.example.org");
        return filter;
    }

    @Test
    public void aSecureRequestGetsEveryHeaderAndTheChainContinues() throws Exception {
        Mockito.when(request.isSecure()).thenReturn(true);

        defaults().doFilter(request, response, chain);

        Mockito.verify(response).setHeader("Strict-Transport-Security", "max-age=300");
        Mockito.verify(response).setHeader("X-Content-Type-Options", "nosniff");
        Mockito.verify(response).setHeader("X-Frame-Options", "SAMEORIGIN");
        Mockito.verify(response).setHeader(ArgumentMatchers.eq("Content-Security-Policy-Report-Only"),
                ArgumentMatchers.startsWith("default-src 'self'; "));
        Mockito.verify(response).setHeader("Reporting-Endpoints", "csp-endpoint=\"/csp-report\"");
        Mockito.verify(response, Mockito.never()).setHeader(ArgumentMatchers.eq("Content-Security-Policy"),
                ArgumentMatchers.anyString());
        Mockito.verify(chain).doFilter(request, response);
    }

    @Test
    public void hstsIsNotSentOverPlainHttp() throws Exception {
        Mockito.when(request.isSecure()).thenReturn(false);

        defaults().doFilter(request, response, chain);

        Mockito.verify(response, Mockito.never()).setHeader(ArgumentMatchers.eq("Strict-Transport-Security"),
                ArgumentMatchers.anyString());
        // The rest do not depend on the scheme.
        Mockito.verify(response).setHeader("X-Content-Type-Options", "nosniff");
        Mockito.verify(response).setHeader("X-Frame-Options", "SAMEORIGIN");
        Mockito.verify(chain).doFilter(request, response);
    }

    @Test
    public void theHstsKnobsShapeTheHeader() throws Exception {
        Mockito.when(request.isSecure()).thenReturn(true);
        final SecurityHeadersFilter filter = new SecurityHeadersFilter();
        filter.configure(" 31536000 ", true, "DENY", CspMode.REPORT_ONLY, "/csp-report", null);

        filter.doFilter(request, response, chain);

        Mockito.verify(response).setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        Mockito.verify(response).setHeader("X-Frame-Options", "DENY");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void aNegativeMaxAgeIsRefusedAtStartup() {
        new SecurityHeadersFilter().configure("-1", false, "DENY", CspMode.REPORT_ONLY, "/csp-report", null);
    }

    @Test
    public void enforceModeSendsTheRealHeader() throws Exception {
        final SecurityHeadersFilter filter = new SecurityHeadersFilter();
        filter.configure("300", false, "SAMEORIGIN", CspMode.ENFORCE, "/csp-report", null);

        filter.doFilter(request, response, chain);

        Mockito.verify(response).setHeader(ArgumentMatchers.eq("Content-Security-Policy"),
                ArgumentMatchers.eq(filter.policy()));
        Mockito.verify(response, Mockito.never()).setHeader(
                ArgumentMatchers.eq("Content-Security-Policy-Report-Only"), ArgumentMatchers.anyString());
    }

    @Test
    public void offModeSendsNoPolicyButKeepsTheOtherHeaders() throws Exception {
        final SecurityHeadersFilter filter = new SecurityHeadersFilter();
        filter.configure("300", false, "SAMEORIGIN", CspMode.OFF, "/csp-report", null);

        filter.doFilter(request, response, chain);

        Mockito.verify(response, Mockito.never()).setHeader(ArgumentMatchers.contains("Content-Security-Policy"),
                ArgumentMatchers.anyString());
        Mockito.verify(response, Mockito.never()).setHeader(ArgumentMatchers.eq("Reporting-Endpoints"),
                ArgumentMatchers.anyString());
        Mockito.verify(response).setHeader("X-Content-Type-Options", "nosniff");
    }

    @Test
    public void aJsfResourceGetsNoPolicy() throws Exception {
        Mockito.when(request.getServletPath()).thenReturn("/jakarta.faces.resource/trip.css.jsf");

        defaults().doFilter(request, response, chain);

        Mockito.verify(response, Mockito.never()).setHeader(ArgumentMatchers.contains("Content-Security-Policy"),
                ArgumentMatchers.anyString());
        // nosniff is exactly what a stylesheet or script response needs.
        Mockito.verify(response).setHeader("X-Content-Type-Options", "nosniff");
        Mockito.verify(response).setHeader("X-Frame-Options", "SAMEORIGIN");
    }

    @Test
    public void aNullServletPathIsADocument() throws Exception {
        Mockito.when(request.getServletPath()).thenReturn(null);

        defaults().doFilter(request, response, chain);

        Mockito.verify(response).setHeader(ArgumentMatchers.eq("Content-Security-Policy-Report-Only"),
                ArgumentMatchers.anyString());
    }

    @Test
    public void theMediaOriginJoinsThePolicy() {
        final String policy = defaults().policy();
        Assert.assertTrue(policy.contains("style-src 'self' 'unsafe-inline' https://media.example.org;"), policy);
        Assert.assertTrue(policy.contains("object-src 'self' https://media.example.org;"), policy);
    }

    @Test
    public void aNonHttpExchangePassesStraightThrough() throws Exception {
        final ServletRequest plain = Mockito.mock(ServletRequest.class);
        final ServletResponse plainResponse = Mockito.mock(ServletResponse.class);

        defaults().doFilter(plain, plainResponse, chain);

        Mockito.verify(chain).doFilter(plain, plainResponse);
        Mockito.verifyNoInteractions(plainResponse);
    }

    @Test
    public void initReadsTheKnobsAndFallsBackToTheDefaults() throws Exception {
        final FilterConfig config = Mockito.mock(FilterConfig.class);
        Mockito.when(config.getInitParameter("hstsMaxAgeSeconds")).thenReturn("600");
        Mockito.when(config.getInitParameter("frameOptions")).thenReturn("  ");
        Mockito.when(config.getInitParameter("cspMode")).thenReturn("enforce");
        Mockito.when(request.isSecure()).thenReturn(true);
        final SecurityHeadersFilter filter = new SecurityHeadersFilter();

        filter.init(config);
        filter.doFilter(request, response, chain);

        Mockito.verify(response).setHeader("Strict-Transport-Security", "max-age=600");
        Mockito.verify(response).setHeader("X-Frame-Options", "SAMEORIGIN");
        Mockito.verify(response).setHeader(ArgumentMatchers.eq("Content-Security-Policy"),
                ArgumentMatchers.endsWith("report-uri /csp-report; report-to csp-endpoint"));
    }

    @Test
    public void initWithoutAConfigUsesEveryDefault() throws Exception {
        final SecurityHeadersFilter filter = new SecurityHeadersFilter();
        filter.init(null);

        filter.doFilter(request, response, chain);

        Mockito.verify(response).setHeader(ArgumentMatchers.eq("Content-Security-Policy-Report-Only"),
                ArgumentMatchers.anyString());
    }

    @Test
    public void cspModeParsesItsThreeSpellingsAndRefusesTheRest() {
        Assert.assertEquals(CspMode.parse(null), CspMode.REPORT_ONLY);
        Assert.assertEquals(CspMode.parse(""), CspMode.REPORT_ONLY);
        Assert.assertEquals(CspMode.parse(" Report-Only "), CspMode.REPORT_ONLY);
        Assert.assertEquals(CspMode.parse("ENFORCE"), CspMode.ENFORCE);
        Assert.assertEquals(CspMode.parse("off"), CspMode.OFF);
        Assert.assertNull(CspMode.OFF.header());
        Assert.assertThrows(IllegalArgumentException.class, () -> CspMode.parse("yes please"));
    }
}
